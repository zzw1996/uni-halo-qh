package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pro.uhalo.uni.dto.PushStatsDTO;
import pro.uhalo.uni.scheme.PushRecord;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 推送看板统计。
 *
 * <p>订阅消息的失败大多不是「代码坏了」，而是配置或用户意愿，所以统计的重点是
 * <b>按微信错误码分组</b>：</p>
 * <ul>
 *   <li>{@code 43101} 用户没额度或拒收 → 正常现象（一次性订阅的额度用完了），
 *       占比高说明该引导用户多点几次「开启提醒」；</li>
 *   <li>{@code 43102} 模板未绑定服务类目 → 站长要去小程序后台改；</li>
 *   <li>{@code 47003} 参数不符 → 多半是标题/摘要超过 20 字（模板字段上限）；</li>
 *   <li>{@code 40037 / 41030} 模板 ID 或 page 路径配错。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PushStatsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 每类错误最多展示几个样本，避免看板被刷爆 */
    private static final int SAMPLES_PER_CODE = 3;

    private final ReactiveExtensionClient client;

    public Mono<PushStatsDTO> summary() {
        var today = LocalDate.now(ZONE).toString();
        return client.listAll(PushRecord.class, new ListOptions(), null)
            .collectList()
            .map(records -> build(records, today))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Push] 推送统计失败（返回空看板）：{}", ex.getMessage());
                return Mono.just(PushStatsDTO.builder()
                    .todaySent(0)
                    .todayFailed(0)
                    .totalRecords(0)
                    .failureReasons(List.of())
                    .todaySamples(List.of())
                    .build());
            });
    }

    private PushStatsDTO build(List<PushRecord> records, String today) {
        var todayRecords = records.stream()
            .filter(record -> isToday(record, today))
            .toList();
        var sent = todayRecords.stream().filter(record -> "SENT".equals(record.getStatus())).count();
        var failed = todayRecords.size() - sent;

        // 失败按错误码分组：这是「要不要去改配置」的判断依据
        var byCode = new LinkedHashMap<Integer, List<PushRecord>>();
        for (var record : records) {
            if ("SENT".equals(record.getStatus())) {
                continue;
            }
            var code = record.getErrorCode() == null ? 0 : record.getErrorCode();
            byCode.computeIfAbsent(code, key -> new ArrayList<>()).add(record);
        }
        var reasons = new ArrayList<PushStatsDTO.FailureReason>();
        byCode.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
            .forEach(entry -> reasons.add(PushStatsDTO.FailureReason.builder()
                .errorCode(entry.getKey())
                .count(entry.getValue().size())
                .meaning(meaningOf(entry.getKey()))
                .samples(entry.getValue().stream()
                    .limit(SAMPLES_PER_CODE)
                    .map(PushStatsService::describe)
                    .toList())
                .build()));

        return PushStatsDTO.builder()
            .todaySent((int) sent)
            .todayFailed((int) failed)
            .totalRecords(records.size())
            .newPostSent(todayRecords.stream()
                .filter(record -> "SENT".equals(record.getStatus()))
                .filter(record -> "newPost".equals(record.getSourceKind())).count())
            .aiDigestSent(todayRecords.stream()
                .filter(record -> "SENT".equals(record.getStatus()))
                .filter(record -> "aiDigest".equals(record.getSourceKind())).count())
            .failureReasons(List.copyOf(reasons))
            .todaySamples(todayRecords.stream()
                .sorted((a, b) -> nullToEmpty(b.getSentAt()).toString()
                    .compareTo(nullToEmpty(a.getSentAt()).toString()))
                .limit(10)
                .map(PushStatsService::describe)
                .toList())
            .build();
    }

    private static boolean isToday(PushRecord record, String today) {
        var sentAt = record.getSentAt();
        return sentAt != null && today.equals(sentAt.atZone(ZONE).toLocalDate().toString());
    }

    /** 错误码的业务含义（写死在服务端：站长不该去查微信文档才知道要做什么） */
    static String meaningOf(int errorCode) {
        return switch (errorCode) {
            case 0 -> "其它失败（无错误码）";
            case 43101 -> "用户没有额度或拒收（正常：一次性订阅用完了，可引导用户再点一次「开启提醒」）";
            case 43102 -> "模板未绑定服务类目（去小程序后台「订阅消息」检查模板）";
            case 40037 -> "模板 ID 无效（检查插件设置里填的模板 ID）";
            case 41030 -> "page 路径不存在（检查跳转页面配置）";
            case 47003 -> "参数不符合要求（多半是标题或摘要超过 20 字，或日期格式不对）";
            case 40001, 42001 -> "access_token 失效（会自动重试；持续出现请检查 appId/appSecret）";
            case 45009 -> "接口调用频率超限（把检查间隔调大一些）";
            default -> "其它微信错误（errmsg 见样本）";
        };
    }

    private static PushStatsDTO.RecordSample describe(PushRecord record) {
        return PushStatsDTO.RecordSample.builder()
            .openid(mask(record.getWxOpenid()))
            .kind(record.getSourceKind())
            .status(record.getStatus())
            .errorCode(record.getErrorCode())
            .message(record.getMessage())
            .page(record.getPage())
            .sentAt(record.getSentAt() == null ? "" : record.getSentAt()
                .atZone(ZONE).toString().replace('T', ' ').substring(0, 16))
            .build();
    }

    /** 看板上也做脱敏：openid 是用户标识，只留前几位 */
    private static String mask(String openid) {
        if (openid == null || openid.isBlank()) {
            return "(未知)";
        }
        var trimmed = openid.trim();
        return trimmed.length() <= 8 ? trimmed.charAt(0) + "***" : trimmed.substring(0, 6) + "***";
    }

    private static Instant nullToEmpty(Instant value) {
        return value == null ? Instant.EPOCH : value;
    }
}
