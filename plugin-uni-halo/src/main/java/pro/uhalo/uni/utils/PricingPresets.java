package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 积分经济的「一键套用」预设：保守 / 标准 / 激进。
 *
 * <p><b>为什么要有预设</b>：积分与现金的比例不是一个数，而是十几处配置的联动
 * （产出规则、每日任务、兑换价、装扮价、邀请奖励）。让站长逐个填等于让他自己算一遍
 * 经济系统；预设把「算好的整套值」一次写入，站长只需要选一个经营取向。</p>
 *
 * <p><b>三档的差别（都是同一套公式，只是斜率不同）</b>：</p>
 * <ul>
 *   <li><b>保守</b>：产出最低、价格最高。积分难攒（券要攒 9 天左右），现金优先——
 *       适合已经有付费用户、不想让积分侵蚀收入；</li>
 *   <li><b>标准</b>：推荐值。中度活跃约 4 天换一张券，积分有获得感但不廉价；</li>
 *   <li><b>激进</b>：产出最高、价格最低（约 2 天换一张券）。用积分换活跃度，
 *       适合早期缺日活、需要把用户拉回来的阶段。</li>
 * </ul>
 *
 * <p><b>幂等与安全</b>：只改「已知条目」的数值（按 key/type 精确匹配），
 * 站长自己加的装扮、兑换项、任务一律不动；重复套用同一档结果相同（写的是绝对值，
 * 不是倍率），因此可以放心来回切换比较。关着的通道不会被预设打开
 * （例如 AI 定制内容的积分通道为 0 时保持 0——开不开积分通道是商业决策，不是定价问题）。</p>
 */
public final class PricingPresets {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 三档预设（顺序即后台展示顺序） */
    public enum Preset {
        CONSERVATIVE, STANDARD, AGGRESSIVE
    }

    /** 预设作用的分组：与 {@link CommercialConfigChecks#MANAGED_GROUPS} 的交集 */
    private static final List<String> TARGET_GROUPS = List.of(
        "pointsConfig", "dailyTaskConfig", "mallConfig", "levelConfig",
        "ticketConfig", "subscriptionConfig", "inviteConfig", "aiContentConfig");

    private PricingPresets() {
    }

    /** 解析预设名；认不出来返回 null（不猜，让调用方报错） */
    public static Preset parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "conservative" -> Preset.CONSERVATIVE;
            case "standard" -> Preset.STANDARD;
            case "aggressive" -> Preset.AGGRESSIVE;
            default -> null;
        };
    }

    public static String nameOf(Preset preset) {
        return preset == null ? "" : preset.name().toLowerCase(java.util.Locale.ROOT);
    }

    public static String labelOf(Preset preset) {
        return switch (preset) {
            case CONSERVATIVE -> "保守（现金优先）";
            case STANDARD -> "标准（推荐）";
            case AGGRESSIVE -> "激进（活跃优先）";
        };
    }

    public static String descriptionOf(Preset preset) {
        return switch (preset) {
            case CONSERVATIVE -> "产出最低、积分价最高：券约需攒 9 天。适合已有人付费、"
                + "不想让积分侵蚀现金收入。";
            case STANDARD -> "推荐档：券约需攒 4 天，积分有获得感但不廉价，"
                + "现金仍有「立刻到手」的优势。";
            case AGGRESSIVE -> "产出最高、积分价最低：券约需攒 2 天。用积分换日活与回访，"
                + "适合起步阶段，现金转化会弱一些。";
        };
    }

    /**
     * 生成要写入的补丁：分组 → 字段 → 值。
     *
     * <p>分组内的字段是「整块替换」的语义（服务层对分组内做字段级合并），
     * 所以对 {@code rules} / {@code tasks} / {@code exchangeItems} / {@code outfits}
     * 这类嵌套结构，这里会在<b>当前值</b>的基础上改，而不是另造一份——
     * 否则会把站长配置里的其它字段（如任务的 target、装扮的 durationDays）抹掉。</p>
     *
     * @param preset  预设档
     * @param current 当前分组配置（缺失的分组按空对象处理）
     */
    public static Map<String, Map<String, Object>> patch(Preset preset,
        Map<String, JsonNode> current) {
        var patch = new LinkedHashMap<String, Map<String, Object>>();
        var points = groupOf(current, "pointsConfig");
        patch.put("pointsConfig", Map.of("rules", rules(preset, points.path("rules"))));
        patch.put("dailyTaskConfig", Map.of("tasks",
            tasks(preset, groupOf(current, "dailyTaskConfig").path("tasks"))));
        var mall = mall(preset, groupOf(current, "mallConfig"));
        if (!mall.isEmpty()) {
            patch.put("mallConfig", mall);
        }
        var outfits = outfits(preset, groupOf(current, "levelConfig").path("outfits"));
        if (!outfits.isEmpty()) {
            patch.put("levelConfig", Map.of("outfits", outfits));
        }
        patch.put("ticketConfig", Map.of(
            "exchangePoints", ticketPrice(preset),
            "streakTickets", 1));
        patch.put("subscriptionConfig", Map.of("quotaPrice", quotaPrice(preset)));
        patch.put("inviteConfig", Map.of(
            "rewardPoints", inviteReward(preset),
            "inviteeRewardPoints", inviteeReward(preset)));
        // 积分通道关着（0）就不动：开不开是商业决策，预设只负责「开了之后该定多少」
        var aiPoints = groupOf(current, "aiContentConfig").path("pointsPrice").asInt(0);
        if (aiPoints > 0) {
            patch.put("aiContentConfig", Map.of("pointsPrice", aiPrice(preset)));
        }
        return patch;
    }

    /** 预设作用的分组清单（供接口返回「改了什么」） */
    public static List<String> targetGroups() {
        return TARGET_GROUPS;
    }

    // ==================== 各分组的取值 ====================

    /** 产出规则：{points, dailyMaxCount, dailyMaxPoints} 三元组按档位整体替换 */
    private static Map<String, Object> rules(Preset preset, JsonNode current) {
        var rules = new LinkedHashMap<String, Object>();
        rules.put("browse", action(browsePoints(preset), browseCount(preset), browseCap(preset)));
        rules.put("comment", action(commentPoints(preset), commentCount(preset),
            commentCap(preset)));
        rules.put("upvote", action(upvotePoints(preset), upvoteCount(preset), upvoteCap(preset)));
        rules.put("vote", action(votePoints(preset), voteCount(preset), voteCap(preset)));
        var signIn = new LinkedHashMap<String, Object>();
        signIn.put("points", signInPoints(preset));
        signIn.put("consecutiveBonus", List.of(
            Map.of("days", 3, "bonus", streakBonus3(preset)),
            Map.of("days", 7, "bonus", streakBonus7(preset))));
        rules.put("signIn", signIn);
        // 保留规则里其它未被本预设接管的字段（如站长自定义的项）
        var kept = new LinkedHashMap<String, Object>();
        current.fieldNames().forEachRemaining(name -> {
            if (!rules.containsKey(name)) {
                kept.put(name, toObject(current.get(name)));
            }
        });
        var merged = new LinkedHashMap<String, Object>();
        merged.putAll(kept);
        merged.putAll(rules);
        return merged;
    }

    private static Map<String, Object> action(int points, int count, int cap) {
        var node = new LinkedHashMap<String, Object>();
        node.put("points", points);
        node.put("dailyMaxCount", count);
        node.put("dailyMaxPoints", cap);
        return node;
    }

    /** 每日任务：只改「已知 type」的奖励，并保留 target / tickets / enabled 等原字段 */
    private static List<Object> tasks(Preset preset, JsonNode current) {
        var result = new ArrayList<Object>();
        for (var task : current) {
            var item = new LinkedHashMap<String, Object>();
            task.fieldNames().forEachRemaining(name ->
                item.put(name, toObject(task.get(name))));
            var type = task.path("type").asText("");
            if (List.of("sign_in", "browse", "comment", "upvote", "vote").contains(type)) {
                item.put("points", taskPoints(preset));
            }
            result.add(item);
        }
        return result;
    }

    /** 积分商城：已知兑换项按 key 改价；礼盒消耗同步调整。站长自定义的兑换项不动 */
    private static Map<String, Object> mall(Preset preset, JsonNode current) {
        var patch = new LinkedHashMap<String, Object>();
        var items = new ArrayList<Object>();
        for (var item : current.path("exchangeItems")) {
            var node = new LinkedHashMap<String, Object>();
            item.fieldNames().forEachRemaining(name ->
                node.put(name, toObject(item.get(name))));
            var key = item.path("key").asText("");
            if ("content_ticket".equals(key)) {
                node.put("points", ticketPrice(preset));
            } else if ("dashboard_advanced".equals(key)) {
                node.put("points", dashboardPrice(preset));
            }
            items.add(node);
        }
        if (!items.isEmpty()) {
            patch.put("exchangeItems", items);
        }
        return patch;
    }

    /** 装扮：只改 6 个内置 key 的积分价，保留 durationDays / style / minSpend */
    private static List<Object> outfits(Preset preset, JsonNode current) {
        var priceByKey = new LinkedHashMap<String, Integer>();
        priceByKey.put("nick_star", outfitNickStar(preset));
        priceByKey.put("theme_dusk", outfitThemeDusk(preset));
        priceByKey.put("theme_sunset", outfitThemeSunset(preset));
        priceByKey.put("avatar_gold", outfitAvatarGold(preset));
        priceByKey.put("nick_crown", outfitNickCrown(preset));
        priceByKey.put("avatar_rainbow", outfitAvatarRainbow(preset));
        var result = new ArrayList<Object>();
        for (var outfit : current) {
            var node = new LinkedHashMap<String, Object>();
            outfit.fieldNames().forEachRemaining(name ->
                node.put(name, toObject(outfit.get(name))));
            var price = priceByKey.get(outfit.path("key").asText(""));
            if (price != null) {
                node.put("points", price);
            }
            result.add(node);
        }
        return result;
    }

    // ==================== 三档的具体数值 ====================
    //
    // 口诀：保守 = 标准 × 1.5（价格）与 × 0.6（产出）；激进 = 标准 × 0.67 与 × 1.5。
    // 数值写死（不按倍率算）才能保证幂等：来回切换预设不会累积放大。

    private static int browsePoints(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 1;
            case STANDARD -> 1;
            case AGGRESSIVE -> 1;
        };
    }

    private static int browseCount(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 3;
            case STANDARD -> 5;
            case AGGRESSIVE -> 8;
        };
    }

    private static int browseCap(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 3;
            case STANDARD -> 5;
            case AGGRESSIVE -> 8;
        };
    }

    private static int commentPoints(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 5;
            case STANDARD -> 5;
            case AGGRESSIVE -> 5;
        };
    }

    private static int commentCount(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 2;
            case STANDARD -> 2;
            case AGGRESSIVE -> 3;
        };
    }

    private static int commentCap(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 10;
            case STANDARD -> 10;
            case AGGRESSIVE -> 15;
        };
    }

    private static int upvotePoints(Preset p) {
        return 1;
    }

    private static int upvoteCount(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 3;
            case STANDARD -> 5;
            case AGGRESSIVE -> 8;
        };
    }

    private static int upvoteCap(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 3;
            case STANDARD -> 5;
            case AGGRESSIVE -> 8;
        };
    }

    private static int votePoints(Preset p) {
        return 5;
    }

    private static int voteCount(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 1;
            case STANDARD -> 2;
            case AGGRESSIVE -> 3;
        };
    }

    private static int voteCap(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 5;
            case STANDARD -> 10;
            case AGGRESSIVE -> 15;
        };
    }

    private static int signInPoints(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 3;
            case STANDARD -> 5;
            case AGGRESSIVE -> 8;
        };
    }

    private static int streakBonus3(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 3;
            case STANDARD -> 5;
            case AGGRESSIVE -> 5;
        };
    }

    private static int streakBonus7(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 10;
            case STANDARD -> 15;
            case AGGRESSIVE -> 20;
        };
    }

    private static int taskPoints(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 3;
            case STANDARD -> 5;
            case AGGRESSIVE -> 8;
        };
    }

    private static int ticketPrice(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 225;
            case STANDARD -> 150;
            case AGGRESSIVE -> 100;
        };
    }

    private static int dashboardPrice(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 150;
            case STANDARD -> 100;
            case AGGRESSIVE -> 70;
        };
    }

    private static int quotaPrice(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 450;
            case STANDARD -> 300;
            case AGGRESSIVE -> 200;
        };
    }

    private static int aiPrice(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 1200;
            case STANDARD -> 800;
            case AGGRESSIVE -> 550;
        };
    }

    private static int outfitNickStar(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 900;
            case STANDARD -> 600;
            case AGGRESSIVE -> 400;
        };
    }

    private static int outfitThemeDusk(Preset p) {
        return outfitNickStar(p) * 3 / 2;
    }

    private static int outfitThemeSunset(Preset p) {
        return outfitNickStar(p) * 3 / 2;
    }

    private static int outfitAvatarGold(Preset p) {
        return outfitNickStar(p) * 2;
    }

    private static int outfitNickCrown(Preset p) {
        return outfitNickStar(p) * 3;
    }

    private static int outfitAvatarRainbow(Preset p) {
        return outfitNickStar(p) * 5;
    }


    private static int inviteReward(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 150;
            case STANDARD -> 100;
            case AGGRESSIVE -> 70;
        };
    }

    private static int inviteeReward(Preset p) {
        return switch (p) {
            case CONSERVATIVE -> 50;
            case STANDARD -> 50;
            case AGGRESSIVE -> 70;
        };
    }

    /** JsonNode → 普通 Java 对象（Map/List/标量），便于放进要写回的补丁里 */
    private static Object toObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return OBJECT_MAPPER.convertValue(node, Object.class);
    }

    private static JsonNode groupOf(Map<String, JsonNode> current, String group) {
        var node = current == null ? null : current.get(group);
        return node == null || !node.isObject()
            ? JsonNodeFactory.instance.objectNode() : node;
    }
}
