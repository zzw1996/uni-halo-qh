package pro.uhalo.uni.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestPreflightItemDTO;
import pro.uhalo.uni.dto.MpConfigDTO;
import pro.uhalo.uni.utils.MpAccessTokenProvider;
import pro.uhalo.uni.utils.MpConfigHelper;
import pro.uhalo.uni.utils.MpErrors;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.ArrayList;
import java.util.List;

/**
 * 公众号接入自检：把「填对了吗、IP 加白了吗、有哪些接口权限」一次查清楚。
 *
 * <p>逐项真实调用微信接口，结论直接给站长看（含服务器真实出口 IP）。</p>
 *
 * <p><b>深度自检</b>（{@code deep=true}）会额外在公众号草稿箱建一条测试草稿并
 * <b>立即删除</b>——这是唯一能确定「草稿箱接口对当前账号是否可用」的办法
 * （个人主体能否用草稿箱，官方没有明文保障）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MpSelfCheckService {

    private static final String PROBE_TITLE = "【自检】公众号推送自检草稿（会自动删除）";

    private final ReactiveSettingFetcher settingFetcher;
    private final MpAccessTokenProvider tokenProvider;
    private final MpApiClient apiClient;
    private final MpImageService imageService;

    /** 最近一次自检的结论（供状态接口展示：推草稿箱这条路通不通） */
    public record Verdict(boolean checked, boolean uploadImageOk, boolean coverMaterialOk,
                          String summary) {

        static Verdict unknown() {
            return new Verdict(false, true, true, "");
        }
    }

    private volatile Verdict lastVerdict = Verdict.unknown();

    public Verdict lastVerdict() {
        return lastVerdict;
    }

    public Mono<List<DigestPreflightItemDTO>> diagnose(boolean deep) {
        var items = new ArrayList<DigestPreflightItemDTO>();
        return loadConfig().flatMap(config -> {
            var configured = StringUtils.hasText(config.getAppId())
                && StringUtils.hasText(config.getAppSecret());
            items.add(item("公众号配置（AppID / AppSecret）", configured, configured
                ? "已填写：AppID=" + maskAppId(config.getAppId())
                    + "；作者=" + config.getAuthor()
                    + "；推送落点=" + MpConfigHelper.deliveryModeLabel(config.getDeliveryMode())
                : "未填写：请到公众号后台「设置与开发 → 开发接口管理」获取后填入插件设置「微信公众号」"
                    + "（注意与微信小程序的 AppID 不是同一个）"));
            if (!configured) {
                items.add(item("后续步骤", false, "配置填好后重新自检即可继续检查接口权限"));
                lastVerdict = new Verdict(true, false, false,
                    "尚未填写 AppID/AppSecret，无法判断可用能力");
                return Mono.just(items);
            }
            return tokenProvider.probe().flatMap(probe -> {
                items.add(tokenItem(probe));
                if (!probe.ok()) {
                    lastVerdict = new Verdict(true, false, false,
                        "access_token 获取失败：" + probe.error().suggestion());
                    return Mono.just(items);
                }
                return checkImagePermissions(items, deep);
            });
        });
    }

    private DigestPreflightItemDTO tokenItem(MpAccessTokenProvider.AccessTokenProbe probe) {
        if (probe.ok()) {
            return item("获取 access_token（stable_token）", true,
                "成功，token=" + probe.maskedToken() + "：AppID/AppSecret 与服务器出网都正常");
        }
        var error = probe.error();
        var detail = error.suggestion();
        if (StringUtils.hasText(error.ip())) {
            detail += "（服务器出口 IP：" + error.ip() + "，复制它加到白名单即可）";
        }
        return item("获取 access_token（stable_token）", false, detail);
    }

    private Mono<List<DigestPreflightItemDTO>> checkImagePermissions(
        List<DigestPreflightItemDTO> items, boolean deep) {
        return imageService.probeContentImageUpload().flatMap(contentImage -> {
            var contentImageUrl = contentImage.ok() ? contentImage.text("url") : "";
            items.add(contentImage.ok()
                ? item("正文图片上传（media/uploadimg）", true,
                    "可用：正文图会换成微信图床地址（本次探针图 " + contentImageUrl + "）")
                : item("正文图片上传（media/uploadimg）", false,
                    contentImage.error().suggestion()
                        + "。该项不可用时正文图保留站点地址：推送草稿箱可能不显示，"
                        + "复制到公众号编辑器时通常会自动转存"));
            return imageService.probePermanentImageUpload().flatMap(material -> {
                var materialOk = material.ok();
                items.add(materialOk
                    ? item("封面素材上传（material/add_material）", true,
                        "可用：封面会转成永久素材（草稿封面的 thumb_media_id 必须是永久素材）")
                    : item("封面素材上传（material/add_material）", false,
                        material.error().suggestion()
                            + "。微信规定**草稿封面的 thumb_media_id 必须是永久素材**，所以这一项"
                            + "不通时「推送草稿箱」也走不通；此时请用「复制正文」+「⬇ 封面」"
                            + "手动发布（那条路径不调用任何微信接口）。"
                            + (material.error().permissionDenied()
                            ? "（当前错误码属于权限类：这个号确实没有该接口权限）"
                            : "（当前错误码不是权限类，属可修复的接口/参数问题，请把上面这条反馈给维护者）")));
                if (!materialOk || !deep) {
                    items.add(item("草稿箱（draft/add）", materialOk,
                        materialOk
                            ? "未探测：点「深度自检」会创建一条<b>带图</b>的测试草稿并立即删除，"
                                + "以确认草稿箱接口对当前账号是否可用"
                            : "跳过：封面素材上传不可用时，草稿箱必然也推不进去"));
                    items.add(capabilityNote());
                    lastVerdict = new Verdict(true, contentImage.ok(), materialOk, materialOk
                        ? (deep ? "" : "未探测草稿箱（点「深度自检」可确认）")
                        : "封面素材上传不可用：草稿箱推送走不通，请用「复制正文」手动发布。原因："
                            + material.error().suggestion());
                    return Mono.just(items);
                }
                var mediaId = material.text("media_id");
                return draftProbe(mediaId, contentImageUrl).map(draft -> {
                    items.add(draft);
                    items.add(capabilityNote());
                    lastVerdict = new Verdict(true, contentImage.ok(), materialOk,
                        contentImage.ok()
                            ? "草稿箱链路可用：可以推草稿（含图床图片与永久素材封面）"
                            : "可以推草稿，但正文图片换不了图床（微信可能不显示）："
                                + "建议先解决 uploadimg 再推送");
                    return items;
                });
            });
        });
    }

    /** 深度自检：建一条带图的测试草稿（用到刚上传的图片与封面素材）→ 立刻删除 */
    private Mono<DigestPreflightItemDTO> draftProbe(String thumbMediaId, String contentImageUrl) {
        var content = "<p>这是插件自检生成的测试草稿，会自动删除。</p>"
            + (StringUtils.hasText(contentImageUrl)
            ? "<p><img src=\"" + contentImageUrl + "\" style=\"max-width:100%;\" /></p>" : "");
        var article = new MpApiClient.MpArticle(PROBE_TITLE, "小编",
            "这是插件自检生成的测试草稿，会自动删除。", content, "", thumbMediaId, false, false);
        return apiClient.addDraft(List.of(article)).flatMap(call -> {
            if (!call.ok()) {
                return Mono.just(item("草稿箱（draft/add）", false,
                    call.error().suggestion() + chat(call.error())));
            }
            var mediaId = call.text("media_id");
            log.info("[UniHalo][Mp] 深度自检：测试草稿已创建（media_id={}），正在删除", mediaId);
            return apiClient.deleteDraft(mediaId).map(deleted -> item("草稿箱（draft/add）", true,
                deleted.ok()
                    ? "可用：成功创建一条带图草稿（正文图片 + 封面素材），且测试草稿已自动删除；"
                        + "用「推送当天全部方向」即可把稿件送进草稿箱"
                    : "可用：可以创建草稿；但测试草稿删除失败（"
                        + deleted.error().suggestion() + "），请到公众号草稿箱手动删除一条「"
                        + PROBE_TITLE + "」"));
        });
    }

    /** 发布 / 群发能力说明：不主动探测（避免真的发出去），只说明前提与失败表现 */
    private DigestPreflightItemDTO capabilityNote() {
        return item("发布 / 群发能力（说明）", false,
            "官方自 2025-07 起仅企业主体已认证账号可调用发布（freepublish）与群发（mass/sendall）；"
                + "个人订阅号调用会报 48001（权限不足）。插件不主动探测这两项以免误发内容——"
                + "个人号请使用「复制正文」或「推送草稿箱」后手动发布/群发");
    }

    private Mono<MpConfigDTO> loadConfig() {
        return settingFetcher.get(MpConfigHelper.groupName())
            .map(MpConfigHelper::parse)
            .defaultIfEmpty(MpConfigHelper.defaults());
    }

    private static String chat(MpErrors.MpError error) {
        return StringUtils.hasText(error.errmsg()) ? "（" + error.errmsg() + "）" : "";
    }

    private static String maskAppId(String appId) {
        if (!StringUtils.hasText(appId) || appId.length() <= 8) {
            return "***";
        }
        return appId.substring(0, 6) + "***" + appId.substring(appId.length() - 2);
    }

    private static DigestPreflightItemDTO item(String name, boolean ok, String message) {
        return DigestPreflightItemDTO.builder().item(name).ok(ok).message(message).build();
    }
}
