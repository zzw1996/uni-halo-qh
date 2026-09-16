package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.dto.ContentTicketConfigDTO;

/**
 * 付费内容（阅读券）配置解析：来自设置分组 {@code contentConfig}。
 *
 * <p>设计取向：默认<b>关闭</b>。付费墙一旦误开，全站带标签文章都会变成需要解锁，
 * 影响面很大，因此必须由站长显式开启，不做「未配置即生效」的乐观假设。</p>
 */
public final class ContentTicketConfigHelper {

    public static final String GROUP_NAME = "contentConfig";

    /** 默认解锁消耗券张数 */
    public static final int DEFAULT_COST_TICKETS = 1;

    private ContentTicketConfigHelper() {
    }

    public static String groupName() {
        return GROUP_NAME;
    }

    /** 未配置时的兜底：功能关闭，任何文章都可自由阅读。 */
    public static ContentTicketConfigDTO disabled() {
        return ContentTicketConfigDTO.builder()
            .enabled(false)
            .paidTagSlug(Constants.DEFAULT_PAID_CONTENT_TAG_SLUG)
            .costTickets(DEFAULT_COST_TICKETS)
            .freeForOwner(true)
            .permanent(true)
            .freeForDigest(true)
            .build();
    }

    /**
     * 解析配置。
     *
     * <p>容错原则：字段缺失或非法时回落到安全默认值，而不是抛异常。
     * 因为本方法在内容拦截 Filter 的热路径上被调用，配置写错不应该
     * 让全站文章详情接口直接报错。</p>
     */
    public static ContentTicketConfigDTO parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return disabled();
        }
        var enabled = node.path("enabled").asBoolean(false);
        // 标签 slug 为空时回落到默认值，避免出现「空 slug 匹配不到任何标签」
        // 从而让付费功能静默失效、站长以为已生效的情况
        var slug = normalizeSlug(textValue(node, "paidTagSlug"));
        var cost = node.path("costTickets").asInt(DEFAULT_COST_TICKETS);
        // 消耗张数至少 1：配成 0 或负数会导致「免费解锁」，与付费意图矛盾
        var costTickets = cost > 0 ? cost : DEFAULT_COST_TICKETS;
        return ContentTicketConfigDTO.builder()
            .enabled(enabled)
            .paidTagSlug(slug)
            .costTickets(costTickets)
            // 博主豁免默认开启：站长本人看自己的付费文章不应被要求消耗券
            .freeForOwner(node.path("freeForOwner").asBoolean(true))
            .permanent(node.path("permanent").asBoolean(true))
            // 日报免券默认开启：日报是拉新入口，被付费墙拦住会直接把转化掐死
            .freeForDigest(node.path("freeForDigest").asBoolean(true))
            .build();
    }

    /**
     * 归一化标签 slug：去空白、转小写、限长。
     *
     * <p>Halo 标签 slug 本身区分大小写，但站长在表单里容易误加大小写或空格，
     * 因此这里统一小写后比较；解析标签时也按小写比较，保证两侧口径一致。</p>
     */
    private static String normalizeSlug(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Constants.DEFAULT_PAID_CONTENT_TAG_SLUG;
        }
        var trimmed = raw.trim().toLowerCase();
        if (trimmed.isEmpty()) {
            return Constants.DEFAULT_PAID_CONTENT_TAG_SLUG;
        }
        return trimmed.length() > 60 ? trimmed.substring(0, 60) : trimmed;
    }

    private static String textValue(JsonNode node, String field) {
        var value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
