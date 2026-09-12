package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import pro.uhalo.uni.dto.LuckyConfigDTO;
import pro.uhalo.uni.dto.LuckyPrizeDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * 积分幸运抽奖配置解析：来自设置分组 luckyDrawConfig。
 * 奖品为活动赠品性质，中奖记录写入积分流水（积分明细可见），由站长人工发放。
 */
public final class LuckyDrawConfigHelper {

    public static final String GROUP_NAME = "luckyDrawConfig";

    private LuckyDrawConfigHelper() {
    }

    public static String groupName() {
        return GROUP_NAME;
    }

    public static LuckyConfigDTO defaults() {
        return LuckyConfigDTO.builder()
            .enabled(true)
            .costPoints(20)
            .prizes(withProbabilities(List.of(
                seed("再来一次", "🎁", 50, false, "未中奖，再接再厉"),
                seed("定制周边小礼", "🎀", 15, true, "随机周边小件（帆布包/钥匙扣等），中奖后填写收货信息"),
                seed("感谢参与", "🙏", 35, false, "未中奖，下次好运")
            )))
            .build();
    }

    private static LuckyPrizeDTO seed(String name, String emoji, int weight, boolean needsAddress,
        String desc) {
        return LuckyPrizeDTO.builder().name(name).emoji(emoji).weight(weight)
            .needsAddress(needsAddress).desc(desc).build();
    }

    /**
     * 为奖品列表统一计算中奖概率（百分比，保留 1 位小数）。
     *
     * <p>抽奖类活动必须向用户公示中奖概率，且概率要与实际抽取权重一致。
     * 服务端集中计算可保证「公示值 = 实际抽中率」，避免前端各自计算产生偏差。</p>
     *
     * <p>与 {@code LuckyDrawServiceImpl.pickPrize} 的抽取口径保持完全一致：
     * 该实现按 {@code RANDOM.nextInt(总权重)} 逐个累加权重命中，
     * 因此单个奖品的真实概率就是 {@code weight / 总权重}。</p>
     *
     * <p>不做「凑整到合计 100%」的调整——四舍五入后各概率之和可能是 99.9% 或 100.1%，
     * 强行凑整会歪曲真实概率，反而不符合如实公示的要求。</p>
     */
    public static List<LuckyPrizeDTO> withProbabilities(List<LuckyPrizeDTO> prizes) {
        if (prizes == null || prizes.isEmpty()) {
            return prizes == null ? List.of() : prizes;
        }
        var total = prizes.stream().mapToInt(LuckyPrizeDTO::getWeight).sum();
        for (var prize : prizes) {
            if (prize == null) {
                continue;
            }
            prize.setProbability(total > 0 ? roundToOneDecimal(prize.getWeight() * 100.0 / total) : 0.0);
        }
        return prizes;
    }

    /** 保留 1 位小数（四舍五入），避免下发 14.999999 之类的浮点尾数 */
    private static double roundToOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    public static LuckyConfigDTO parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaults();
        }
        var prizes = new ArrayList<LuckyPrizeDTO>();
        var prizesNode = node.path("prizes");
        if (prizesNode.isArray()) {
            prizesNode.forEach(item -> {
                var name = item.path("name").asText("").trim();
                var emoji = item.path("emoji").asText("").trim();
                var weight = item.path("weight").asInt(0);
                if (!name.isEmpty() && !emoji.isEmpty() && weight > 0) {
                    prizes.add(LuckyPrizeDTO.builder()
                        .name(name).emoji(emoji).weight(weight)
                        .needsAddress(item.path("needsAddress").asBoolean(false))
                        .desc(item.path("desc").asText(""))
                        .build());
                }
            });
        }
        if (prizes.isEmpty()) {
            prizes.addAll(defaults().getPrizes());
        }
        return LuckyConfigDTO.builder()
            .enabled(node.path("enabled").asBoolean(true))
            .costPoints(Math.max(1, node.path("costPoints").asInt(20)))
            .prizes(List.copyOf(withProbabilities(prizes)))
            .build();
    }

    /**
     * 解析「积分商城」分组的抽奖子配置（luckyEnabled/luckyCostPoints/luckyPrizes）。
     * 未配置抽奖字段时返回 null，由调用方回落旧分组。
     */
    public static LuckyConfigDTO parseMall(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        var enabledNode = node.get("luckyEnabled");
        var prizesNode = node.get("luckyPrizes");
        if (enabledNode == null || prizesNode == null || !prizesNode.isArray()) {
            return null;
        }
        var prizes = new ArrayList<LuckyPrizeDTO>();
        prizesNode.forEach(item -> {
            var name = item.path("name").asText("").trim();
            var emoji = item.path("emoji").asText("").trim();
            var weight = item.path("weight").asInt(0);
            if (!name.isEmpty() && !emoji.isEmpty() && weight > 0) {
                prizes.add(LuckyPrizeDTO.builder()
                    .name(name).emoji(emoji).weight(weight)
                    .needsAddress(item.path("needsAddress").asBoolean(false))
                    .desc(item.path("desc").asText(""))
                    .build());
            }
        });
        return LuckyConfigDTO.builder()
            .enabled(enabledNode.asBoolean(true))
            .costPoints(Math.max(1, node.path("luckyCostPoints").asInt(20)))
            .prizes(List.copyOf(withProbabilities(
                prizes.isEmpty() ? new ArrayList<>(defaults().getPrizes()) : prizes)))
            .build();
    }
}
