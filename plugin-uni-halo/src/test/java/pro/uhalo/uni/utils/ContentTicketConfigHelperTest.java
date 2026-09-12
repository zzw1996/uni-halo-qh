package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 付费内容配置解析测试。
 *
 * <p>该配置位于内容拦截 Filter 的热路径上，解析失败或默认值不当会直接影响全站可读性，
 * 因此重点锁定三类安全行为：<b>默认关闭</b>（未配置时绝不拦截）、
 * <b>slug 兜底</b>（空 slug 不能变成「匹配不到任何标签」从而静默失效）、
 * <b>券数兜底</b>（0 或负数不能变成免费解锁）。</p>
 */
class ContentTicketConfigHelperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== 默认关闭 ====================

    /** 未配置该分组时必须关闭：付费墙一旦误开，全站带标签文章都需解锁，影响面很大。 */
    @Test
    void shouldBeDisabledByDefault() {
        var config = ContentTicketConfigHelper.disabled();

        assertThat(config.getEnabled()).isFalse();
        assertThat(config.getPaidTagSlug()).isEqualTo("paid");
        assertThat(config.getCostTickets()).isEqualTo(1);
    }

    /** 传入 null（配置分组不存在）时按关闭处理，不得抛异常。 */
    @Test
    void shouldParseNullAsDisabled() {
        var config = ContentTicketConfigHelper.parse(null);

        assertThat(config.getEnabled()).isFalse();
        assertThat(config.getPaidTagSlug()).isEqualTo("paid");
    }

    /** 空 JSON 对象时按关闭处理：字段缺失不应让功能意外开启。 */
    @Test
    void shouldParseEmptyNodeAsDisabled() throws Exception {
        var config = ContentTicketConfigHelper.parse(MAPPER.readTree("{}"));

        assertThat(config.getEnabled()).isFalse();
        assertThat(config.getCostTickets()).isEqualTo(1);
        // 博主豁免默认开启：站长看自己的付费文章不应被要求消耗券
        assertThat(config.getFreeForOwner()).isTrue();
    }

    // ==================== 券数兜底 ====================

    /** costTickets 配成 0 会等于免费解锁，与付费意图矛盾，必须回落为 1。 */
    @Test
    void shouldFallbackCostTicketsWhenZeroOrNegative() throws Exception {
        assertThat(ContentTicketConfigHelper.parse(
            MAPPER.readTree("{\"enabled\":true,\"costTickets\":0}")).getCostTickets())
            .isEqualTo(1);
        assertThat(ContentTicketConfigHelper.parse(
            MAPPER.readTree("{\"enabled\":true,\"costTickets\":-5}")).getCostTickets())
            .isEqualTo(1);
    }

    /** 字段缺失时同样回落为默认 1，不能是 null（后续会拆箱 NPE）。 */
    @Test
    void shouldFallbackCostTicketsWhenMissing() throws Exception {
        var config = ContentTicketConfigHelper.parse(MAPPER.readTree("{\"enabled\":true}"));

        assertThat(config.getCostTickets()).isNotNull().isEqualTo(1);
    }

    @Test
    void shouldKeepPositiveCostTickets() throws Exception {
        assertThat(ContentTicketConfigHelper.parse(
            MAPPER.readTree("{\"enabled\":true,\"costTickets\":3}")).getCostTickets())
            .isEqualTo(3);
    }

    // ==================== slug 归一化 ====================

    /**
     * 空 slug 必须回落到默认值。
     *
     * <p>若保留空字符串，标签匹配会匹配不到任何标签 → 所有付费文章都不被拦截，
     * 站长以为已生效但实际在免费送内容。回落到默认 slug 至少能保住默认配置下的行为。</p>
     */
    @Test
    void shouldFallbackSlugWhenBlank() throws Exception {
        assertThat(ContentTicketConfigHelper.parse(
            MAPPER.readTree("{\"enabled\":true,\"paidTagSlug\":\"\"}")).getPaidTagSlug())
            .isEqualTo("paid");
        assertThat(ContentTicketConfigHelper.parse(
            MAPPER.readTree("{\"enabled\":true,\"paidTagSlug\":\"   \"}")).getPaidTagSlug())
            .isEqualTo("paid");
    }

    /** slug 统一转小写：与解析标签时的比较口径保持一致，避免大小写导致匹配不上。 */
    @Test
    void shouldNormalizeSlugToLowerCase() throws Exception {
        assertThat(ContentTicketConfigHelper.parse(
            MAPPER.readTree("{\"enabled\":true,\"paidTagSlug\":\"PAID\"}")).getPaidTagSlug())
            .isEqualTo("paid");
        assertThat(ContentTicketConfigHelper.parse(
            MAPPER.readTree("{\"enabled\":true,\"paidTagSlug\":\"  Vip-Only  \"}")).getPaidTagSlug())
            .isEqualTo("vip-only");
    }

    /** 自定义 slug 应原样保留（小写化后）。 */
    @Test
    void shouldKeepCustomSlug() throws Exception {
        assertThat(ContentTicketConfigHelper.parse(
            MAPPER.readTree("{\"enabled\":true,\"paidTagSlug\":\"members\"}")).getPaidTagSlug())
            .isEqualTo("members");
    }

    // ==================== 开关与豁免 ====================

    @Test
    void shouldRespectEnabledFlag() throws Exception {
        assertThat(ContentTicketConfigHelper.parse(
            MAPPER.readTree("{\"enabled\":true}")).getEnabled()).isTrue();
        assertThat(ContentTicketConfigHelper.parse(
            MAPPER.readTree("{\"enabled\":false}")).getEnabled()).isFalse();
    }

    /** freeForOwner 缺省时为 true（默认豁免博主），显式 false 时生效。 */
    @Test
    void shouldDefaultFreeForOwnerToTrue() throws Exception {
        assertThat(ContentTicketConfigHelper.parse(
            MAPPER.readTree("{\"enabled\":true}")).getFreeForOwner()).isTrue();
        assertThat(ContentTicketConfigHelper.parse(
            MAPPER.readTree("{\"enabled\":true,\"freeForOwner\":false}")).getFreeForOwner())
            .isFalse();
    }

    /** 类型异常（把 enabled 写成字符串）不得抛出，应按默认值兜底。 */
    @Test
    void shouldNotThrowOnTypeMismatch() throws Exception {
        var config = ContentTicketConfigHelper.parse(
            MAPPER.readTree("{\"enabled\":\"yes\",\"costTickets\":\"abc\"}"));

        assertThat(config.getEnabled()).isFalse();
        assertThat(config.getCostTickets()).isEqualTo(1);
    }
}
