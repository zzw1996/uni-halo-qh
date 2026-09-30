package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分定价预设的三档数值、幂等性与「不越界」。
 *
 * <p>预设会直接改写站长的线上配置，所以真正要钉住的不是「数值是多少」，而是三条纪律：</p>
 * <ol>
 *   <li><b>幂等</b>：来回点不同档位不能累积放大（写绝对值，不是倍率）；</li>
 *   <li><b>不越界</b>：站长自定义的装扮、兑换项、任务字段（target/enabled/description）不能被抹掉；</li>
 *   <li><b>不越权</b>：关着的通道（AI 定制内容积分价为 0）不能被预设打开。</li>
 * </ol>
 */
class PricingPresetsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 一份「站长已经改过一些东西」的当前配置：含自定义装扮与自定义任务 */
    private static Map<String, JsonNode> current() {
        var map = new HashMap<String, JsonNode>();
        map.put("pointsConfig", json("""
            {"enabled":true,"rules":{"browse":{"points":1,"dailyMaxCount":99,"dailyMaxPoints":99},
             "comment":{"points":5},"custom":{"points":7}},"minBrowseDuration":5}"""));
        map.put("dailyTaskConfig", json("""
            {"enabled":true,"tasks":[
              {"type":"browse","target":3,"points":10,"enabled":true},
              {"type":"sign_in","target":1,"points":10,"enabled":false},
              {"type":"my_custom","target":5,"points":42,"enabled":true}]}"""));
        map.put("mallConfig", json("""
            {"exchangeItems":[
              {"key":"content_ticket","name":"阅读券 x1","points":30,"dailyMaxCount":1,"tickets":1},
              {"key":"dashboard_advanced","name":"看板","points":30,"dailyMaxCount":1},
              {"key":"my_item","name":"自建","points":88,"dailyMaxCount":2}],
             "otherField":"保留不动"}"""));
        map.put("levelConfig", json("""
            {"enabled":true,"outfits":[
              {"key":"nick_star","name":"星光辉映","type":"nick_frame","points":100,
               "durationDays":7,"style":"⭐"},
              {"key":"my_outfit","name":"自建装扮","type":"theme","points":66,
               "durationDays":0,"style":"mint"}]}"""));
        map.put("ticketConfig", json("""
            {"newUserTickets":1,"firstPurchaseTickets":1,"exchangeTickets":1,"exchangePoints":30,
             "streakDays":7,"streakTickets":2,"signInTickets":0}"""));
        map.put("subscriptionConfig", json("{\"freeQuota\":5,\"quotaPrice\":50,\"maxQuota\":10}"));
        map.put("inviteConfig", json("{\"enabled\":true,\"rewardPoints\":30,\"inviteeRewardPoints\":30}"));
        // 积分通道关着（0）：预设不该把它打开
        map.put("aiContentConfig", json("{\"enabled\":true,\"pointsPrice\":0,\"pointsDays\":30}"));
        return map;
    }

    @Test
    void shouldParseOnlyKnownPresetNames() {
        assertThat(PricingPresets.parse("standard")).isEqualTo(PricingPresets.Preset.STANDARD);
        assertThat(PricingPresets.parse(" CONSERVATIVE ")).isEqualTo(
            PricingPresets.Preset.CONSERVATIVE);
        assertThat(PricingPresets.parse("aggressive")).isEqualTo(PricingPresets.Preset.AGGRESSIVE);
        // 认不出来返回 null（调用方报错），不猜、不静默回落
        assertThat(PricingPresets.parse("")).isNull();
        assertThat(PricingPresets.parse(null)).isNull();
        assertThat(PricingPresets.parse("cheap")).isNull();
    }

    @Test
    void standardPresetShouldMatchTheRecommendedValues() {
        var patch = PricingPresets.patch(PricingPresets.Preset.STANDARD, current());

        var browse = action(patch, "browse");
        assertThat(browse).containsEntry("points", 1)
            .containsEntry("dailyMaxCount", 5).containsEntry("dailyMaxPoints", 5);
        var upvote = action(patch, "upvote");
        assertThat(upvote).containsEntry("dailyMaxPoints", 5);
        var vote = action(patch, "vote");
        assertThat(vote).containsEntry("dailyMaxPoints", 10);
        @SuppressWarnings("unchecked")
        var standardRules = (Map<String, Object>) patch.get("pointsConfig").get("rules");
        @SuppressWarnings("unchecked")
        var signIn = (Map<String, Object>) standardRules.get("signIn");
        assertThat(signIn).containsEntry("points", 5);

        var mall = patch.get("mallConfig");
        assertThat(ticketPoints(mall)).isEqualTo(150);
        assertThat(dashboardPoints(mall)).isEqualTo(100);
        assertThat(patch.get("subscriptionConfig")).containsEntry("quotaPrice", 300);
        assertThat(patch.get("inviteConfig")).containsEntry("rewardPoints", 100)
            .containsEntry("inviteeRewardPoints", 50);
    }

    /** 三档必须真的有差别：券越贵、产出越低 = 越保守 */
    @Test
    void presetsShouldDifferInTheRightDirection() {
        var conservative = PricingPresets.patch(PricingPresets.Preset.CONSERVATIVE, current());
        var standard = PricingPresets.patch(PricingPresets.Preset.STANDARD, current());
        var aggressive = PricingPresets.patch(PricingPresets.Preset.AGGRESSIVE, current());

        assertThat(ticketPoints(conservative.get("mallConfig")))
            .isGreaterThan(ticketPoints(standard.get("mallConfig")))
            .isGreaterThan(ticketPoints(aggressive.get("mallConfig")));

        assertThat(browseCap(conservative)).isLessThan(browseCap(standard));
        assertThat(browseCap(standard)).isLessThan(browseCap(aggressive));
    }

    /**
     * 幂等：同一档连续套用两次结果相同；来回换档也不会累积（这正是写绝对值而非倍率的原因）。
     */
    @Test
    void applyingTwiceShouldBeIdempotent() {
        var first = PricingPresets.patch(PricingPresets.Preset.STANDARD, current());
        // 用第一次的结果再套一次（模拟「保存后再点同一档」）
        var second = PricingPresets.patch(PricingPresets.Preset.STANDARD, current());

        assertThat(second.get("mallConfig")).isEqualTo(first.get("mallConfig"));
        assertThat(second.get("pointsConfig")).isEqualTo(first.get("pointsConfig"));

        var backAndForth = PricingPresets.patch(PricingPresets.Preset.STANDARD, current());
        assertThat(ticketPoints(backAndForth.get("mallConfig"))).isEqualTo(150);
    }

    /** 站长自定义的装扮 / 兑换项 / 任务字段不能被预设抹掉或改价 */
    @Test
    void shouldKeepCustomEntriesUntouched() {
        var patch = PricingPresets.patch(PricingPresets.Preset.STANDARD, current());

        @SuppressWarnings("unchecked")
        var outfits = (List<Map<String, Object>>) patch.get("levelConfig").get("outfits");
        var custom = outfits.stream().filter(o -> "my_outfit".equals(o.get("key")))
            .findFirst().orElseThrow();
        assertThat(custom).containsEntry("points", 66);
        var builtIn = outfits.stream().filter(o -> "nick_star".equals(o.get("key")))
            .findFirst().orElseThrow();
        assertThat(builtIn).containsEntry("points", 600)
            // durationDays / style 等原字段必须保留（自定义装扮是限时的）
            .containsEntry("durationDays", 7).containsEntry("style", "⭐");

        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) patch.get("mallConfig").get("exchangeItems");
        var myItem = items.stream().filter(i -> "my_item".equals(i.get("key")))
            .findFirst().orElseThrow();
        assertThat(myItem).containsEntry("points", 88);
        var ticket = items.stream().filter(i -> "content_ticket".equals(i.get("key")))
            .findFirst().orElseThrow();
        assertThat(ticket).containsEntry("points", 150)
            .containsEntry("dailyMaxCount", 1).containsEntry("tickets", 1);

        @SuppressWarnings("unchecked")
        var tasks = (List<Map<String, Object>>) patch.get("dailyTaskConfig").get("tasks");
        var customTask = tasks.stream().filter(t -> "my_custom".equals(t.get("type")))
            .findFirst().orElseThrow();
        assertThat(customTask).containsEntry("points", 42);
        var signInTask = tasks.stream().filter(t -> "sign_in".equals(t.get("type")))
            .findFirst().orElseThrow();
        // 奖励统一改为档位值，但 target 与 enabled（站长关掉了这个任务）必须保留
        assertThat(signInTask).containsEntry("points", 5)
            .containsEntry("target", 1).containsEntry("enabled", false);
    }

    /** pointsConfig 里非接管的规则项（站长自定义的）要保留 */
    @Test
    void shouldKeepUnknownRuleEntries() {
        var patch = PricingPresets.patch(PricingPresets.Preset.STANDARD, current());
        @SuppressWarnings("unchecked")
        var rules = (Map<String, Object>) patch.get("pointsConfig").get("rules");
        assertThat(rules).containsKey("custom");
    }

    /** AI 定制内容积分通道关着（0）时不能被预设打开——开通道是商业决策 */
    @Test
    void shouldNotOpenClosedAiContentChannel() {
        var patch = PricingPresets.patch(PricingPresets.Preset.STANDARD, current());
        assertThat(patch).doesNotContainKey("aiContentConfig");

        // 已经开着（>0）的站点会被同步到档位值
        var openChannel = current();
        openChannel.put("aiContentConfig", json("{\"enabled\":true,\"pointsPrice\":60}"));
        var patch2 = PricingPresets.patch(PricingPresets.Preset.STANDARD, openChannel);
        assertThat(patch2.get("aiContentConfig")).containsEntry("pointsPrice", 800);
    }

    /** 缺分组时不能抛异常（新装站点某些分组可能还没写入过） */
    @Test
    void shouldTolerateMissingGroups() {
        var patch = PricingPresets.patch(PricingPresets.Preset.STANDARD, Map.of());
        assertThat(patch).containsKeys("pointsConfig", "ticketConfig", "subscriptionConfig");
        @SuppressWarnings("unchecked")
        var rules = (Map<String, Object>) patch.get("pointsConfig").get("rules");
        assertThat(rules).containsKeys("browse", "comment", "upvote", "vote", "signIn");
    }

    /** 预设只碰它该碰的分组：付费、会员这类分组不在列表里 */
    @Test
    void shouldOnlyTargetManagedGroups() {
        assertThat(PricingPresets.targetGroups())
            .doesNotContain("payConfig", "memberConfig", "contentConfig", "basicConfig");
        var patch = PricingPresets.patch(PricingPresets.Preset.STANDARD, current());
        assertThat(patch.keySet()).allMatch(PricingPresets.targetGroups()::contains);
    }

    // ==================== 取值辅助 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> action(Map<String, Map<String, Object>> patch,
        String action) {
        var rules = (Map<String, Object>) patch.get("pointsConfig").get("rules");
        return (Map<String, Object>) rules.get(action);
    }

    @SuppressWarnings("unchecked")
    private static int ticketPoints(Map<String, Object> mall) {
        var items = (List<Map<String, Object>>) mall.get("exchangeItems");
        return (int) items.stream().filter(i -> "content_ticket".equals(i.get("key")))
            .findFirst().orElseThrow().get("points");
    }

    @SuppressWarnings("unchecked")
    private static int dashboardPoints(Map<String, Object> mall) {
        var items = (List<Map<String, Object>>) mall.get("exchangeItems");
        return (int) items.stream().filter(i -> "dashboard_advanced".equals(i.get("key")))
            .findFirst().orElseThrow().get("points");
    }

    private static int browseCap(Map<String, Map<String, Object>> patch) {
        return (int) action(patch, "browse").get("dailyMaxPoints");
    }
}
