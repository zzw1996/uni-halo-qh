package pro.uhalo.uni.enums;

import lombok.Getter;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * AI 任务类型：一次日报与相关功能里，大模型被用来干的那几件「不同的事」。
 *
 * <p>分成任务的唯一理由：<b>它们对模型的要求与花钱方式完全相反</b>。
 * 评分一轮要跑 20~40 次、每次只回一小段 JSON，便宜模型足够；
 * 成稿一轮只跑 2~3 次、单次输出上千字且直接决定读者看到什么，必须留给强模型。
 * 按任务绑定通道，就能既省下评分那一大把调用的钱，又不牺牲成稿质量。</p>
 *
 * <p>{@link #key()} 是写进配置与下发给前端的稳定标识（改它等于让老配置失效）；
 * {@link #label()} 只用于界面与日志。</p>
 */
@Getter
public enum DigestAiTask {

    /** Step 2/5：给候选文章打分、选分类、提关键词（含分类缺口补抓时的重评） */
    SCORE("score", "评分"),
    /** Step 3/5：逐篇成稿（中译标题 + 概述 + 核心论点 + 推荐理由），单次输出最长 */
    WRITE("write", "成稿"),
    /** Step 4/5：日报开头的今日看点与背景铺垫（失败可降级跳过，所以单列） */
    OVERVIEW("overview", "看点背景"),
    /** 旧文章归类：把插件发布且没有分类的文章补上主题（人工点击触发） */
    CLASSIFY("classify", "旧文归类"),
    /** 付费「AI 定制内容」的每日简报导语与推荐理由 */
    CONTENT("content", "付费简报");

    private final String key;
    private final String label;

    DigestAiTask(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** 全部任务的标识，用于配置解析的白名单与前端渲染 */
    public static List<String> keys() {
        var result = new ArrayList<String>();
        for (var task : values()) {
            result.add(task.key);
        }
        return List.copyOf(result);
    }

    /** 按标识解析：认不出来返回空（配置里写了错别字不应该让整份清单作废） */
    public static Optional<DigestAiTask> fromKey(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Optional.empty();
        }
        var normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (var task : values()) {
            if (task.key.equals(normalized)) {
                return Optional.of(task);
            }
        }
        return Optional.empty();
    }

    /** 中文名：认不出来时原样回显，便于后台把「配错了什么」显示出来 */
    public static String labelOf(String key) {
        return fromKey(key).map(DigestAiTask::getLabel).orElse(key);
    }
}
