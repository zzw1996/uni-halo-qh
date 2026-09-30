package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订阅额度配置：默认值、越界收敛、缺字段回落。
 *
 * <p>这条链路连着「能不能订阅」与「首页发几路请求」，配置读错比报错更危险——
 * 所以默认值本身也要钉住。</p>
 */
class SubscriptionConfigHelperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void defaultsShouldMatchTheOldHardcodedQuota() {
        var config = SubscriptionConfigHelper.defaults();

        // 默认必须等于改造前写死的 5，否则升级那一刻所有用户都会突然超出额度
        assertThat(config.getFreeQuota()).isEqualTo(5);
        assertThat(config.isEnabled()).isFalse();
        // 每份额度的积分价按「1 元 ≈ 100 积分」重新校准（原 50 分 = ￥0.5，太便宜）
        assertThat(config.getQuotaPrice()).isEqualTo(300);
        assertThat(config.getMaxQuota()).isEqualTo(10);
        assertThat(config.getMemberMaxQuota()).isEqualTo(30);
        assertThat(config.limitFor(0)).isEqualTo(5);
        assertThat(config.limitFor(10)).isEqualTo(15);
    }

    @Test
    void parseShouldClampOutOfRangeValues() throws Exception {
        var config = SubscriptionConfigHelper.parse(MAPPER.readTree("""
            {"enabled":true,"freeQuota":0,"quotaPrice":0,"maxQuota":-3,"memberMaxQuota":9999,
             "homeFeedPerCategory":1,"hotCategoryCount":0,"homeFeedCacheSeconds":-10}
            """));

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getFreeQuota()).isEqualTo(1);
        assertThat(config.getQuotaPrice()).isEqualTo(1);
        assertThat(config.getMaxQuota()).isZero();
        assertThat(config.getMemberMaxQuota()).isEqualTo(200);
        assertThat(config.getHomeFeedPerCategory()).isEqualTo(5);
        assertThat(config.getHotCategoryCount()).isEqualTo(1);
        assertThat(config.getHomeFeedCacheSeconds()).isZero();
    }

    @Test
    void parseShouldFallBackWhenGroupMissing() {
        assertThat(SubscriptionConfigHelper.parse(null).getFreeQuota()).isEqualTo(5);
        assertThat(SubscriptionConfigHelper.parse(null).getQuotaPrice()).isEqualTo(300);
    }

    @Test
    void memberLimitShouldNeverBeBelowFreeQuota() {
        // 站长把会员上限配得比免费额度还小：那买会员就成了「降级」，属于配置事故，
        // 按免费额度兜底（会员至少不亏）
        var config = pro.uhalo.uni.dto.SubscriptionConfigDTO.builder()
            .freeQuota(10)
            .memberMaxQuota(3)
            .build();

        assertThat(config.memberLimit()).isEqualTo(10);
        assertThat(config.limitFor(2)).isEqualTo(12);
    }
}
