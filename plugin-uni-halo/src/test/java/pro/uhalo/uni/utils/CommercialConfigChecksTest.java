package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.ConfigCheckDTO;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置关联检查的规则测试。
 *
 * <p>关联检查最大的风险是<b>误报</b>：一个动不动就报警的检查等于没有检查，
 * 站长看两次就会全部忽略，那时真正致命的那条也一起被忽略掉。
 * 因此这里既测「该报的报出来」，也测「不该报的不报」。</p>
 */
class CommercialConfigChecksTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, JsonNode> configs(String... groupJsonPairs) {
        var map = new HashMap<String, JsonNode>();
        for (var i = 0; i + 1 < groupJsonPairs.length; i += 2) {
            map.put(groupJsonPairs[i], read(groupJsonPairs[i + 1]));
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

    private static ConfigCheckDTO find(List<ConfigCheckDTO> checks, String titleFragment) {
        return checks.stream()
            .filter(item -> item.getTitle() != null && item.getTitle().contains(titleFragment))
            .findFirst()
            .orElse(null);
    }

    @Test
    void shouldReportMissingOfferIdAndAppKeyWhenPayEnabled() {
        var checks = check(configs("payConfig", """
            {"enabled":true,"offerId":"","appKey":"","goods":[]}
            """));

        assertThat(find(checks, "缺少 OfferID")).isNotNull();
        assertThat(find(checks, "缺少 OfferID").getLevel()).isEqualTo("error");
        assertThat(find(checks, "缺少现网 AppKey")).isNotNull();
        assertThat(find(checks, "没有配置任何商品")).isNotNull();
    }

    /** 未开启权益直购时不该报缺 OfferID：没开就不需要这些东西。 */
    @Test
    void shouldNotReportMissingCredentialsWhenPayDisabled() {
        var checks = check(configs("payConfig", """
            {"enabled":false,"offerId":"","appKey":"","goods":[]}
            """));

        assertThat(find(checks, "缺少 OfferID")).isNull();
        assertThat(find(checks, "缺少现网 AppKey")).isNull();
        assertThat(find(checks, "没有配置任何商品")).isNull();
    }

    /** 配了商品却没开开关：这是「配了半天没生效」的典型，必须提示。 */
    @Test
    void shouldWarnWhenGoodsConfiguredButFeatureDisabled() {
        var checks = check(configs("payConfig", """
            {"enabled":false,"goods":[{"goodsId":"g1","name":"阅读券","type":"content_ticket","price":600,"value":1}]}
            """));

        assertThat(find(checks, "权益直购未开启")).isNotNull();
    }

    /** 会员商品 + 会员卡未开启：会导致用户付款后被退款，必须是 error。 */
    @Test
    void shouldErrorWhenMembershipGoodsButMemberDisabled() {
        var checks = check(configs(
            "payConfig", """
                {"enabled":true,"offerId":"o","appKey":"k","goods":[
                  {"goodsId":"g1","name":"会员卡","type":"membership","price":1900,"value":30}]}
                """,
            "memberConfig", """
                {"enabled":false}
                """));

        var issue = find(checks, "是会员卡，但「会员卡」功能没开启");
        assertThat(issue).isNotNull();
        assertThat(issue.getLevel()).isEqualTo("error");
        assertThat(issue.getDetail()).contains("需人工退款");
    }

    /** 会员卡开启但没有可售商品：用户买不到，提示但不拦保存。 */
    @Test
    void shouldWarnWhenMemberEnabledButNoMembershipGoods() {
        var checks = check(configs(
            "payConfig", """
                {"enabled":true,"offerId":"o","appKey":"k","goods":[]}
                """,
            "memberConfig", """
                {"enabled":true,"freeContent":true,"bonusTickets":0}
                """));

        assertThat(find(checks, "没有可售的会员商品")).isNotNull();
    }

    /** 装扮 key 未登记：商品不会下发，用户根本看不到，必须 error。 */
    @Test
    void shouldErrorWhenOutfitGoodsStyleNotRegistered() {
        var checks = check(configs(
            "payConfig", """
                {"enabled":true,"offerId":"o","appKey":"k","goods":[
                  {"goodsId":"g1","name":"限定主题","type":"outfit","price":900,"style":"ghost_theme"}]}
                """,
            "levelConfig", """
                {"outfits":[{"key":"theme_dusk","name":"静谧蓝紫","type":"theme","points":150,"style":"primary"}]}
                """));

        var issue = find(checks, "的装扮 key「ghost_theme」不存在");
        assertThat(issue).isNotNull();
        assertThat(issue.getLevel()).isEqualTo("error");
        // 报错要给出可用选项，否则站长还得自己去翻装扮商店
        assertThat(issue.getDetail()).contains("theme_dusk");
    }

    @Test
    void shouldPassWhenOutfitGoodsStyleRegistered() {
        var checks = check(configs(
            "payConfig", """
                {"enabled":true,"offerId":"o","appKey":"k","goods":[
                  {"goodsId":"g1","name":"静谧蓝紫","type":"outfit","price":900,"style":"theme_dusk"}]}
                """,
            "levelConfig", """
                {"outfits":[{"key":"theme_dusk","name":"静谧蓝紫","type":"theme","points":150,"style":"primary"}]}
                """));

        assertThat(find(checks, "style")).isNull();
    }

    /** 套餐里的装扮子项同样要校验——它和单卖装扮走同一套判定。 */
    @Test
    void shouldCheckOutfitItemsInsideBundle() {
        var checks = check(configs(
            "payConfig", """
                {"enabled":true,"offerId":"o","appKey":"k","goods":[
                  {"goodsId":"g1","name":"超值礼包","type":"bundle","price":1990,"items":[
                    {"type":"content_ticket","value":3},
                    {"type":"outfit","value":1,"style":"not_exist"}]}]}
                """,
            "levelConfig", """
                {"outfits":[]}
                """));

        assertThat(find(checks, "超值礼包 里的装扮子项")).isNotNull();
    }

    /** 任务进度依赖积分流水，积分为 0 时任务永远完不成且无任何报错——必须 error。 */
    @Test
    void shouldErrorWhenDailyTaskActionHasZeroPoints() {
        var checks = check(configs(
            "dailyTaskConfig", """
                {"enabled":true,"tasks":[
                  {"type":"browse","target":3,"points":10,"tickets":0,"enabled":true}]}
                """,
            "pointsConfig", """
                {"enabled":true,"rules":{"browse":{"points":0}}}
                """));

        var issue = find(checks, "永远无法完成");
        assertThat(issue).isNotNull();
        assertThat(issue.getLevel()).isEqualTo("error");
        assertThat(issue.getDetail()).contains("0/3");
        assertThat(issue.getDetail()).contains("积分规则");
    }

    /** 签到是例外：积分为 0 但配了签到赠券时仍会写流水，因此不该报错。 */
    @Test
    void shouldNotReportSignInTaskWhenTicketRewardKeepsRecord() {
        var checks = check(configs(
            "dailyTaskConfig", """
                {"enabled":true,"tasks":[
                  {"type":"sign_in","target":1,"points":10,"tickets":0,"enabled":true}]}
                """,
            "pointsConfig", """
                {"enabled":true,"rules":{"signIn":{"points":0}}}
                """,
            "ticketConfig", """
                {"signInTickets":1}
                """));

        assertThat(find(checks, "永远无法完成")).isNull();
    }

    @Test
    void shouldReportSignInTaskWhenNoPointsAndNoTicketReward() {
        var checks = check(configs(
            "dailyTaskConfig", """
                {"enabled":true,"tasks":[
                  {"type":"sign_in","target":1,"points":10,"tickets":0,"enabled":true}]}
                """,
            "pointsConfig", """
                {"enabled":true,"rules":{"signIn":{"points":0}}}
                """,
            "ticketConfig", """
                {"signInTickets":0,"streakDays":0,"streakTickets":0}
                """));

        assertThat(find(checks, "永远无法完成")).isNotNull();
    }

    /** 未启用的任务不该被检查：站长停用它就是不想做这个任务。 */
    @Test
    void shouldIgnoreDisabledDailyTasks() {
        var checks = check(configs(
            "dailyTaskConfig", """
                {"enabled":true,"tasks":[
                  {"type":"browse","target":3,"points":10,"tickets":0,"enabled":false}]}
                """,
            "pointsConfig", """
                {"enabled":true,"rules":{"browse":{"points":0}}}
                """));

        assertThat(find(checks, "永远无法完成")).isNull();
    }

    /** 付费标签不存在：付费墙会静默失效，必须 error。 */
    @Test
    void shouldErrorWhenPaidTagSlugMissingInHalo() {
        var checks = CommercialConfigChecks.check(
            configs("contentConfig", """
                {"enabled":true,"paidTagSlug":"paid"}
                """),
            Set.of("news", "tech"));

        var issue = find(checks, "在 Halo 里不存在");
        assertThat(issue).isNotNull();
        assertThat(issue.getLevel()).isEqualTo("error");
        assertThat(issue.getDetail()).contains("news");
    }

    /** 标签 slug 命中（忽略大小写）时不该报错。 */
    @Test
    void shouldAcceptPaidTagSlugCaseInsensitively() {
        var checks = CommercialConfigChecks.check(
            configs("contentConfig", """
                {"enabled":true,"paidTagSlug":"PAID"}
                """),
            Set.of("paid"));

        assertThat(find(checks, "在 Halo 里不存在")).isNull();
    }

    /** 拿不到标签列表时跳过该项校验，而不是把「所有标签都不存在」误报出来。 */
    @Test
    void shouldSkipTagCheckWhenTagListUnavailable() {
        var checks = CommercialConfigChecks.check(
            configs("contentConfig", """
                {"enabled":true,"paidTagSlug":"paid"}
                """),
            null);

        assertThat(find(checks, "在 Halo 里不存在")).isNull();
    }

    /** 在卖券但付费内容没开：用户买了券没文章可解锁。 */
    @Test
    void shouldWarnWhenSellingTicketsButContentDisabled() {
        var checks = check(configs(
            "payConfig", """
                {"enabled":true,"offerId":"o","appKey":"k","goods":[
                  {"goodsId":"g1","name":"阅读券 x5","type":"content_ticket","price":600,"value":5}]}
                """,
            "contentConfig", """
                {"enabled":false}
                """));

        assertThat(find(checks, "在卖阅读券，但付费内容没开启")).isNotNull();
    }

    /** 券的价值折算：三个分组的数字必须能在一处看到。 */
    @Test
    void shouldSummariseTicketValueAcrossGroups() {
        var checks = check(configs(
            "contentConfig", """
                {"enabled":true,"costTickets":2,"paidTagSlug":"paid"}
                """,
            "ticketConfig", """
                {"exchangePoints":30}
                """,
            "payConfig", """
                {"enabled":true,"offerId":"o","appKey":"k","goods":[
                  {"goodsId":"g1","name":"阅读券 x5","type":"content_ticket","price":600,"value":5}]}
                """));

        var summary = find(checks, "券的价值折算");
        assertThat(summary).isNotNull();
        assertThat(summary.getLevel()).isEqualTo("info");
        // 解锁一篇 2 张券 × 30 积分 = 60 积分
        assertThat(summary.getDetail()).contains("需要 2 张券");
        assertThat(summary.getDetail()).contains("约 60 积分");
        // 券包 600 分 / 5 张 = 每张 1.20 元，一篇 2 张 = 2.40 元
        assertThat(summary.getDetail()).contains("1.20 元");
        assertThat(summary.getDetail()).contains("2.40 元");
    }

    /** 重复 key 会让其中一条配置永远不生效，必须 error。 */
    @Test
    void shouldReportDuplicateKeys() {
        var checks = check(configs(
            "payConfig", """
                {"enabled":true,"offerId":"o","appKey":"k","goods":[
                  {"goodsId":"dup","name":"A","type":"content_ticket","price":600,"value":1},
                  {"goodsId":"dup","name":"B","type":"content_ticket","price":900,"value":2}]}
                """,
            "levelConfig", """
                {"outfits":[
                  {"key":"same","name":"A","type":"theme","points":10,"style":"primary"},
                  {"key":"same","name":"B","type":"theme","points":20,"style":"blue"}]}
                """,
            "mallConfig", """
                {"exchangeItems":[
                  {"key":"k","name":"A","points":10,"dailyMaxCount":1},
                  {"key":"k","name":"B","points":20,"dailyMaxCount":1}]}
                """));

        assertThat(find(checks, "道具 ID 重复")).isNotNull();
        assertThat(find(checks, "装扮 key 重复")).isNotNull();
        assertThat(find(checks, "权益 key 重复")).isNotNull();
    }

    /** 什么都不配的空站点不该被报一堆问题。 */
    @Test
    void shouldStayQuietOnEmptyConfig() {
        var checks = check(new HashMap<>());

        assertThat(checks.stream().filter(item -> "error".equals(item.getLevel()))).isEmpty();
        assertThat(checks.stream().filter(item -> "warn".equals(item.getLevel()))).isEmpty();
    }

    /** 一份完全自洽的配置应当不产生任何需修复项。 */
    @Test
    void shouldProduceNoErrorsForCoherentConfig() {
        var checks = CommercialConfigChecks.check(configs(
            "payConfig", """
                {"enabled":true,"offerId":"o","appKey":"k","goods":[
                  {"goodsId":"g1","name":"阅读券 x5","type":"content_ticket","price":600,"value":5},
                  {"goodsId":"g2","name":"会员卡 30 天","type":"membership","price":1900,"value":30},
                  {"goodsId":"g3","name":"静谧蓝紫","type":"outfit","price":900,"style":"theme_dusk"}]}
                """,
            "memberConfig", """
                {"enabled":true,"freeContent":true,"bonusTickets":2}
                """,
            "contentConfig", """
                {"enabled":true,"costTickets":1,"paidTagSlug":"paid","permanent":true}
                """,
            "ticketConfig", """
                {"exchangePoints":30,"newUserTickets":1,"signInTickets":0}
                """,
            "dailyTaskConfig", """
                {"enabled":true,"tasks":[{"type":"browse","target":3,"points":10,"enabled":true}]}
                """,
            "pointsConfig", """
                {"enabled":true,"rules":{"browse":{"points":1}}}
                """,
            "levelConfig", """
                {"outfits":[{"key":"theme_dusk","name":"静谧蓝紫","type":"theme","points":150,"style":"primary"}]}
                """),
            Set.of("paid"));

        var errors = checks.stream().filter(item -> "error".equals(item.getLevel())).toList();
        assertThat(errors).isEmpty();
    }
}
