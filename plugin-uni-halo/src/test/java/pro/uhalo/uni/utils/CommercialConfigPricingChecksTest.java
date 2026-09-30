package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.ConfigCheckDTO;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定价体检（积分产出 vs 权益定价）。
 *
 * <p>这一组检查的价值在于「把失衡翻译成一句人话 + 一个建议值」：站长看到的不是
 * 「你的积分价是 30」，而是「积分太便宜，用户不会用现金买券，建议提到 150」。
 * 因此测试要钉住三件事：命中条件、建议值算对、以及<b>不该报的时候不要报</b>
 * （误报会让站长直接无视整个体检面板）。</p>
 */
class CommercialConfigPricingChecksTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, JsonNode> configs(String... pairs) {
        var map = new HashMap<String, JsonNode>();
        for (var i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], read(pairs[i + 1]));
        }
        return map;
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static List<ConfigCheckDTO> check(Map<String, JsonNode> configs) {
        return CommercialConfigChecks.check(configs, null);
    }

    private static ConfigCheckDTO find(List<ConfigCheckDTO> checks, String fragment) {
        return checks.stream()
            .filter(item -> item.getTitle() != null && item.getTitle().contains(fragment))
            .findFirst().orElse(null);
    }

    /** 默认的「积分已开启」骨架：其余分组按需追加 */
    private static Map<String, JsonNode> withPoints(String pointsJsonOrEmpty) {
        var points = pointsJsonOrEmpty.isEmpty()
            ? "{\"enabled\":true}"
            : pointsJsonOrEmpty;
        return configs("pointsConfig", points);
    }

    // ==================== 隐含汇率 ====================

    @Test
    void shouldWarnWhenPointsTooCheapAgainstCashPrice() {
        var map = withPoints("{\"enabled\":true,\"rules\":{}}");
        map.put("payConfig", read("""
            {"goods":[{"type":"content_ticket","price":600,"value":5}]}"""));
        map.put("mallConfig", read("""
            {"exchangeItems":[{"key":"content_ticket","points":30,"dailyMaxCount":1}]}"""));

        var checks = check(map);
        var hit = find(checks, "积分太便宜");
        assertThat(hit).isNotNull();
        assertThat(hit.getLevel()).isEqualTo("warn");
        // 券的现金单价 ￥1.20 → 建议积分价 120
        assertThat(hit.getDetail()).contains("建议券的积分价提到 120 分");
    }

    @Test
    void shouldWarnWhenPointsTooExpensive() {
        var map = withPoints("{\"enabled\":true,\"rules\":{}}");
        map.put("payConfig", read("""
            {"goods":[{"type":"content_ticket","price":600,"value":5}]}"""));
        map.put("mallConfig", read("""
            {"exchangeItems":[{"key":"content_ticket","points":500,"dailyMaxCount":1}]}"""));

        var hit = find(check(map), "积分偏难获得");
        assertThat(hit).isNotNull();
        assertThat(hit.getLevel()).isEqualTo("info");
    }

    @Test
    void shouldStaySilentWhenRateIsHealthy() {
        var map = withPoints("{\"enabled\":true,\"rules\":{}}");
        map.put("payConfig", read("""
            {"goods":[{"type":"content_ticket","price":600,"value":5}]}"""));
        map.put("mallConfig", read("""
            {"exchangeItems":[{"key":"content_ticket","points":150,"dailyMaxCount":1}]}"""));

        // 150 分 ÷ ￥1.2 = 125 分/元，落在 80~150 的健康区间
        assertThat(find(check(map), "积分太便宜")).isNull();
        assertThat(find(check(map), "积分偏难获得")).isNull();
    }

    /** 积分系统没开：整套比例无从谈起，不该报（避免噪音） */
    @Test
    void shouldSkipPricingChecksWhenPointsDisabled() {
        var map = configs("pointsConfig", "{\"enabled\":false}");
        map.put("payConfig", read("""
            {"goods":[{"type":"content_ticket","price":600,"value":5}]}"""));
        map.put("mallConfig", read("""
            {"exchangeItems":[{"key":"content_ticket","points":10,"dailyMaxCount":1}]}"""));

        var checks = check(map);
        assertThat(find(checks, "积分太便宜")).isNull();
        assertThat(find(checks, "通胀")).isNull();
    }

    // ==================== 通胀 ====================

    @Test
    void shouldWarnAboutInflationWhenIncomeFarExceedsSpend() {
        // 产出约 30 分/天（浏览 10 + 点赞按半量 10 + 签到 10），而券只要 10 分/天即可换一张
        // ——这正是「积分太廉价」的典型配置：用户随手就能换，积分自然不值钱
        var map = withPoints("""
            {"enabled":true,"rules":{"browse":{"points":1,"dailyMaxCount":10,"dailyMaxPoints":10},
             "upvote":{"points":1,"dailyMaxCount":20,"dailyMaxPoints":20},
             "signIn":{"points":10}}}""");
        map.put("mallConfig", read("""
            {"exchangeItems":[{"key":"content_ticket","points":10,"dailyMaxCount":1}]}"""));

        var hit = find(check(map), "持续通胀");
        assertThat(hit).isNotNull();
        assertThat(hit.getLevel()).isEqualTo("warn");
        assertThat(hit.getDetail()).contains("估算日产出");
    }

    @Test
    void shouldWarnWhenNoExchangeItemsAtAll() {
        var map = withPoints("""
            {"enabled":true,"rules":{"browse":{"points":1,"dailyMaxCount":5,"dailyMaxPoints":5}}}""");
        map.put("mallConfig", read("{\"exchangeItems\":[]}"));

        var hit = find(check(map), "积分没有出口");
        assertThat(hit).isNotNull();
    }

    // ==================== 易刷项 ====================

    @Test
    void shouldWarnWhenFarmableActionsAreTooGenerous() {
        var map = withPoints("""
            {"enabled":true,"rules":{"browse":{"dailyMaxPoints":10},
             "upvote":{"dailyMaxPoints":40}}}""");

        var hit = find(check(map), "易被刷分");
        assertThat(hit).isNotNull();
        assertThat(hit.getDetail()).contains("50 分/天");
    }

    /** 按推荐值配好后不该再报警（点赞 5 + 浏览 5 = 10 < 20） */
    @Test
    void shouldStaySilentWhenFarmableActionsAreTight() {
        var map = withPoints("""
            {"enabled":true,"rules":{"browse":{"dailyMaxPoints":5},
             "upvote":{"dailyMaxPoints":5}}}""");

        assertThat(find(check(map), "易被刷分")).isNull();
    }

    // ==================== 产出估算 ====================

    /**
     * 产出估算的口径要能反映「默认配置下积分白拿」这件事：
     * 老默认（浏览10/评论25/点赞40/签到10 + 任务48）应显著高于新默认。
     */
    @Test
    void estimateShouldShowOldDefaultsAreTooGenerous() {
        var oldPoints = read("""
            {"enabled":true,"rules":{
              "browse":{"points":1,"dailyMaxCount":10,"dailyMaxPoints":10},
              "comment":{"points":5,"dailyMaxCount":5,"dailyMaxPoints":25},
              "upvote":{"points":2,"dailyMaxCount":20,"dailyMaxPoints":40},
              "signIn":{"points":10}}}""");
        var oldTasks = read("""
            {"tasks":[{"type":"sign_in","points":10},{"type":"browse","points":10},
             {"type":"comment","points":10},{"type":"upvote","points":8},{"type":"vote","points":10}]}""");
        var newPoints = read("""
            {"enabled":true,"rules":{
              "browse":{"points":1,"dailyMaxCount":5,"dailyMaxPoints":5},
              "comment":{"points":5,"dailyMaxCount":2,"dailyMaxPoints":10},
              "upvote":{"points":1,"dailyMaxCount":5,"dailyMaxPoints":5},
              "signIn":{"points":5}}}""");
        var newTasks = read("""
            {"tasks":[{"type":"sign_in","points":5},{"type":"browse","points":5},
             {"type":"comment","points":5},{"type":"upvote","points":5},{"type":"vote","points":5}]}""");

        var oldIncome = CommercialConfigChecks.estimateDailyIncome(oldPoints, oldTasks);
        var newIncome = CommercialConfigChecks.estimateDailyIncome(newPoints, newTasks);

        assertThat(oldIncome).isGreaterThan(50);
        assertThat(newIncome).isLessThan(oldIncome);
        // 新配置下中度活跃约 30 分/天上下，才是「打卡奖励」该有的量级
        assertThat(newIncome).isBetween(20, 45);
    }

    /** 日消耗按「积分 × 每日次数」累加，盲盒这类无上限项不在内 */
    @Test
    void estimateSpendShouldSumConfiguredItems() {
        var mall = read("""
            {"exchangeItems":[
              {"key":"content_ticket","points":150,"dailyMaxCount":1},
              {"key":"dashboard_advanced","points":100,"dailyMaxCount":1}]}""");

        assertThat(CommercialConfigChecks.estimateDailySpend(mall)).isEqualTo(250);
    }
}
