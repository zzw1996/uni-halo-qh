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
            .prizes(List.of(
                seed("再来一次", "🎁", 50, false, "未中奖，再接再厉"),
                seed("定制周边小礼", "🎀", 15, true, "随机周边小件（帆布包/钥匙扣等），中奖后填写收货信息"),
                seed("感谢参与", "🙏", 35, false, "未中奖，下次好运")
            ))
            .build();
    }

    private static LuckyPrizeDTO seed(String name, String emoji, int weight, boolean needsAddress,
        String desc) {
        return LuckyPrizeDTO.builder().name(name).emoji(emoji).weight(weight)
            .needsAddress(needsAddress).desc(desc).build();
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
            .prizes(List.copyOf(prizes))
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
            .prizes(List.copyOf(prizes.isEmpty() ? defaults().getPrizes() : prizes))
            .build();
    }
}
