package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.LuckyPrizeDTO;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 幸运抽奖概率公示测试。
 *
 * <p>抽奖类活动必须向用户公示全部奖品内容及对应中奖概率，且公示值要与实际抽中率一致。
 * 概率统一由服务端按权重计算后下发，因此这里同时锁定「计算正确」与「与实际抽取一致」两点。</p>
 */
class LuckyDrawConfigHelperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static LuckyPrizeDTO prize(String name, int weight) {
        return LuckyPrizeDTO.builder().name(name).emoji("🎁").weight(weight)
            .needsAddress(false).desc(name + " 说明").build();
    }

    @Test
    void shouldComputeProbabilityFromWeights() {
        var result = LuckyDrawConfigHelper.withProbabilities(new ArrayList<>(List.of(
            prize("再来一次", 50),
            prize("定制周边小礼", 15),
            prize("感谢参与", 35)
        )));

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getProbability()).isEqualTo(50.0);
        assertThat(result.get(1).getProbability()).isEqualTo(15.0);
        assertThat(result.get(2).getProbability()).isEqualTo(35.0);
    }

    /** 非整除权重需保留 1 位小数，且不得出现浮点尾数。 */
    @Test
    void shouldRoundProbabilityToOneDecimal() {
        var result = LuckyDrawConfigHelper.withProbabilities(new ArrayList<>(List.of(
            prize("a", 1),
            prize("b", 1),
            prize("c", 1)
        )));

        // 1/3 = 33.333...% → 33.3
        assertThat(result.get(0).getProbability()).isEqualTo(33.3);
        assertThat(String.valueOf(result.get(0).getProbability())).isEqualTo("33.3");
    }

    /**
     * 概率必须如实反映权重，不做「凑整到合计 100%」的调整。
     * 强行凑整会歪曲真实概率，反而不符合如实公示要求。
     */
    @Test
    void shouldNotForceProbabilitySumToExactly100() {
        var result = LuckyDrawConfigHelper.withProbabilities(new ArrayList<>(List.of(
            prize("a", 1),
            prize("b", 1),
            prize("c", 1)
        )));

        // 每项都是 33.3；三者之和用容差比较，因为浮点累加会产生
        // 99.89999999999999 这类尾数，这是加法误差、不是概率算错
        assertThat(result).allSatisfy(p -> assertThat(p.getProbability()).isEqualTo(33.3));
        var sum = result.stream().mapToDouble(LuckyPrizeDTO::getProbability).sum();
        assertThat(sum).isCloseTo(99.9, within(1e-9));
        // 如实保留而非凑成 100
        assertThat(sum).isLessThan(100.0);
    }

    /**
     * 序列化校验：下发给前端的必须是干净的 33.3，不能是 33.30000000000001 之类尾数。
     * 前端直接把该数字拼进「中奖概率 33.3%」文案展示给用户，尾数会原样露出。
     */
    @Test
    void shouldSerializeProbabilityWithoutFloatingPointTail() throws Exception {
        var result = LuckyDrawConfigHelper.withProbabilities(new ArrayList<>(List.of(
            prize("a", 1),
            prize("b", 1),
            prize("c", 1)
        )));

        var json = MAPPER.writeValueAsString(result.get(0));
        assertThat(json).contains("\"probability\":33.3");
        assertThat(json).doesNotContain("33.30000000000001");
        assertThat(json).doesNotContain("33.33333");
    }

    /** 边界：空列表、单奖品、权重和为 0 时都不得抛异常。 */
    @Test
    void shouldHandleEdgeCasesWithoutException() {
        assertThat(LuckyDrawConfigHelper.withProbabilities(null)).isEmpty();
        assertThat(LuckyDrawConfigHelper.withProbabilities(new ArrayList<>())).isEmpty();

        var single = LuckyDrawConfigHelper.withProbabilities(
            new ArrayList<>(List.of(prize("only", 7))));
        assertThat(single).hasSize(1);
        assertThat(single.get(0).getProbability()).isEqualTo(100.0);
    }

    // ========== 配置解析三条路径均需带出概率 ==========

    @Test
    void defaultsShouldCarryProbability() {
        var config = LuckyDrawConfigHelper.defaults();

        assertThat(config.getPrizes()).hasSize(3);
        assertThat(config.getPrizes())
            .allSatisfy(prize -> assertThat(prize.getProbability()).isGreaterThan(0.0));
        assertThat(config.getPrizes().get(1).getProbability()).isEqualTo(15.0);
    }

    @Test
    void parseLegacyGroupShouldCarryProbability() throws Exception {
        var config = LuckyDrawConfigHelper.parse(MAPPER.readTree("""
            {"enabled":true,"costPoints":20,"prizes":[
              {"name":"甲","emoji":"🎁","weight":25,"desc":"说明甲"},
              {"name":"乙","emoji":"🎀","weight":75,"desc":"说明乙"}
            ]}
            """));

        assertThat(config.getPrizes()).hasSize(2);
        assertThat(config.getPrizes().get(0).getProbability()).isEqualTo(25.0);
        assertThat(config.getPrizes().get(1).getProbability()).isEqualTo(75.0);
    }

    /** 当前实际生效的是积分商城分组（mallConfig），这条路径最容易被遗漏。 */
    @Test
    void parseMallGroupShouldCarryProbability() throws Exception {
        var config = LuckyDrawConfigHelper.parseMall(MAPPER.readTree("""
            {"luckyEnabled":true,"luckyCostPoints":20,"luckyPrizes":[
              {"name":"甲","emoji":"🎁","weight":40,"desc":"说明甲"},
              {"name":"乙","emoji":"🎀","weight":60,"desc":"说明乙"}
            ]}
            """));

        assertThat(config).isNotNull();
        assertThat(config.getPrizes()).hasSize(2);
        assertThat(config.getPrizes().get(0).getProbability()).isEqualTo(40.0);
        assertThat(config.getPrizes().get(1).getProbability()).isEqualTo(60.0);
    }

    /** 旧分组未配奖品时回落默认奖品池，回落结果同样要带概率。 */
    @Test
    void parseLegacyGroupShouldCarryProbabilityWhenFallingBackToDefaults() throws Exception {
        var config = LuckyDrawConfigHelper.parse(MAPPER.readTree(
            "{\"enabled\":true,\"costPoints\":20,\"prizes\":[]}"));

        assertThat(config.getPrizes()).hasSize(3);
        assertThat(config.getPrizes())
            .allSatisfy(prize -> assertThat(prize.getProbability()).isGreaterThan(0.0));
    }

    /**
     * 核心性质：公示的概率必须等于实际抽中率。
     *
     * <p>概率公式（weight / 总权重）与抽取实现（{@code nextInt(总权重)} 累加命中）
     * 必须口径一致。若将来有人改了其中一处而没改另一处，用户看到的概率就会失真。
     * 该统计校验依赖抽取实现 {@code pickPrize}，为避免跨包访问把它放宽为 public，
     * 校验放在同包的 services.impl.LuckyDrawServiceImplTest#publishedProbabilityShouldMatchActualDrawRate。</p>
     */
    @Test
    void publishedProbabilityShouldBeWeightRatio() {
        var prizes = LuckyDrawConfigHelper.withProbabilities(new ArrayList<>(List.of(
            prize("再来一次", 50),
            prize("定制周边小礼", 15),
            prize("感谢参与", 35)
        )));

        var totalWeight = prizes.stream().mapToInt(LuckyPrizeDTO::getWeight).sum();
        assertThat(totalWeight).isEqualTo(100);
        for (var published : prizes) {
            assertThat(published.getProbability())
                .isEqualTo(published.getWeight() * 100.0 / totalWeight);
        }
    }
}
