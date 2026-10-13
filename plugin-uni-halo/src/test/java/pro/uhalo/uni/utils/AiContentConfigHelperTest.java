package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 定制内容配置解析与「买断 + 有效期」的算法。
 *
 * <p>两条线要守住：</p>
 * <ol>
 *   <li><b>默认关闭</b>：这是付费功能，误开会让站长在没配商品时露出一个买不了的入口；</li>
 *   <li><b>上限收敛</b>：这些上限同时是匿名接口的入参上限，配错等于把接口敞开。</li>
 * </ol>
 */
class AiContentConfigHelperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void defaultsShouldBeSafeToRun() {
        var config = AiContentConfigHelper.defaults();

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.isAiIntroEnabled()).isTrue();
        assertThat(config.getMaxCategories()).isEqualTo(5);
        // 实时素材默认 48 小时：简报的卖点是最新，素材来自现抓而不是已发布的日报
        assertThat(config.getLiveHours()).isEqualTo(48);
        // 生成次数：买 1 天给 2 次、永久卡按上限 100 次、每天最多 3 次
        assertThat(config.getCreditsPerDay()).isEqualTo(2);
        assertThat(config.getMaxCreditsPerGrant()).isEqualTo(100);
        assertThat(config.getDailyGenerationLimit()).isEqualTo(3);
        assertThat(config.getMaxKeywords()).isEqualTo(10);
        assertThat(config.getMaxItems()).isEqualTo(10);
        assertThat(config.getWindowDays()).isEqualTo(7);
        assertThat(config.getIntroWords()).isEqualTo(200);
        assertThat(config.getBenefitNote()).isEmpty();
    }

    @Test
    void parseShouldReadAndClampValues() throws Exception {
        var config = AiContentConfigHelper.parse(MAPPER.readTree("""
            {"enabled":true,"aiIntroEnabled":false,"maxCategories":0,"liveHours":1,
             "maxKeywords":-5,"maxItems":100,"windowDays":0,"introWords":9999,
             "benefitNote":"  每天一份  "}"""));

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.isAiIntroEnabled()).isFalse();
        // 维度上限至少 1（0 会让用户一个都选不了，等于功能不可用）
        assertThat(config.getMaxCategories()).isEqualTo(1);
        // 实时时间窗下限 6 小时：再小就经常抓不到内容
        assertThat(config.getLiveHours()).isEqualTo(6);
        // 关键词允许 0：站长可以用它整个关掉这一维度
        assertThat(config.getMaxKeywords()).isZero();
        assertThat(config.getMaxItems()).isEqualTo(30);
        assertThat(config.getWindowDays()).isEqualTo(1);
        assertThat(config.getIntroWords()).isEqualTo(800);
        assertThat(config.getBenefitNote()).isEqualTo("每天一份");
    }

    @Test
    void parseShouldFallBackOnMissingFields() throws Exception {
        var config = AiContentConfigHelper.parse(MAPPER.readTree("{\"enabled\":true}"));

        assertThat(config.getMaxCategories()).isEqualTo(5);
        assertThat(config.getWindowDays()).isEqualTo(7);
        assertThat(AiContentConfigHelper.parse(null).isEnabled()).isFalse();
    }

    /** 赠送次数 = 天数 × 每天次数，永久卡（days ≤ 0）按上限给——不能按 36500 天算 */
    @Test
    void creditsShouldFollowDaysAndCap() {
        var config = AiContentConfigHelper.defaults();

        assertThat(AiContentConfigHelper.creditsForDays(1, config)).isEqualTo(2);
        assertThat(AiContentConfigHelper.creditsForDays(30, config)).isEqualTo(60);
        assertThat(AiContentConfigHelper.creditsForDays(90, config)).isEqualTo(100);
        assertThat(AiContentConfigHelper.creditsForDays(0, config)).isEqualTo(100);
        assertThat(AiContentConfigHelper.creditsForDays(-1, config)).isEqualTo(100);
    }

    @Test
    void extendShouldAddDaysOnTopOfRemainingTime() {
        var current = Instant.now().plus(10, ChronoUnit.DAYS);

        var extended = AiContentConfigHelper.extend(current, 30);

        // 续费必须在「当前到期时间」上叠加：从当下重新计时会白吃用户 10 天
        assertThat(extended).isAfter(current.plus(29, ChronoUnit.DAYS));
        assertThat(extended).isBefore(current.plus(31, ChronoUnit.DAYS));
    }

    @Test
    void extendShouldStartFromNowWhenExpiredOrMissing() {
        var expired = Instant.now().minus(100, ChronoUnit.DAYS);

        var renewed = AiContentConfigHelper.extend(expired, 30);
        assertThat(ChronoUnit.DAYS.between(Instant.now(), renewed)).isEqualTo(30);

        var fresh = AiContentConfigHelper.extend(null, 30);
        assertThat(ChronoUnit.DAYS.between(Instant.now(), fresh)).isEqualTo(30);
    }

    @Test
    void extendWithZeroDaysShouldMeanPermanent() {
        var permanent = AiContentConfigHelper.extend(null, 0);

        assertThat(AiContentConfigHelper.isPermanent(permanent)).isTrue();
        assertThat(AiContentConfigHelper.isActive(permanent)).isTrue();
        assertThat(AiContentConfigHelper.formatExpireAt(permanent)).isEqualTo("永久");
        assertThat(AiContentConfigHelper.daysRemaining(permanent)).isZero();
    }

    @Test
    void extendOnPermanentShouldStayPermanent() {
        var permanent = AiContentConfigHelper.extend(null, 0);

        assertThat(AiContentConfigHelper.extend(permanent, 30))
            .isEqualTo(AiContentConfigHelper.PERMANENT_EXPIRE_AT);
    }

    @Test
    void activeAndRemainingShouldFollowMemberConvention() {
        assertThat(AiContentConfigHelper.isActive(null)).isFalse();
        assertThat(AiContentConfigHelper.isActive(Instant.now().minus(1, ChronoUnit.HOURS)))
            .isFalse();
        assertThat(AiContentConfigHelper.isActive(Instant.now().plus(1, ChronoUnit.HOURS)))
            .isTrue();

        // 不足一天按一天计，与会员卡口径一致
        assertThat(AiContentConfigHelper.daysRemaining(Instant.now().plus(2, ChronoUnit.HOURS)))
            .isEqualTo(1);
        assertThat(AiContentConfigHelper.daysRemaining(null)).isZero();
        assertThat(AiContentConfigHelper.formatExpireAt(null)).isNull();
    }
}
