package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import pro.uhalo.uni.dto.ConfigCheckDTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商业化配置的<b>关联检查</b>：把分散在不同分组里的耦合关系算成可读结论。
 *
 * <p>这些耦合在设置表单里完全看不出来，而且出问题的时间点往往是「用户付款之后」，
 * 不是站长保存配置的那一刻。典型三条：</p>
 * <ol>
 *   <li>商品类型选了会员卡、但「会员卡」功能没开 → 用户付款后发货失败转人工退款；</li>
 *   <li>商品里填的装扮 key 没在装扮商店登记 → 该商品不会下发（用户根本看不到），
 *       站长却以为已经上架；</li>
 *   <li>每日任务里的行为积分规则为 0 → 该行为不写积分流水，而任务进度正是按流水条数统计的，
 *       于是这个任务<b>永远不可能完成</b>，用户点了「去完成」回来还是 0/N，且没有任何报错。</li>
 * </ol>
 *
 * <p>纯函数、不依赖 Halo 运行时：输入是各组配置的 JSON 与 Halo 里实际存在的标签 slug，
 * 输出是检查结论。这样每条规则都能用单测钉住——关联检查最大的风险是「误报」，
 * 一个经常乱叫的检查等于没有检查。</p>
 */
public final class CommercialConfigChecks {

    public static final String GROUP_PAY = "payConfig";
    public static final String GROUP_MEMBER = "memberConfig";

    /** AI 定制内容（付费权益：用户自配偏好 → 每日简报） */
    public static final String GROUP_AI_CONTENT = "aiContentConfig";
    public static final String GROUP_CONTENT = "contentConfig";
    public static final String GROUP_TICKET = "ticketConfig";
    public static final String GROUP_POINTS = "pointsConfig";
    public static final String GROUP_DAILY_TASK = "dailyTaskConfig";
    public static final String GROUP_MALL = "mallConfig";
    public static final String GROUP_LEVEL = "levelConfig";

    /** 本页面接管的分组，顺序即页面展示顺序 */
    public static final List<String> MANAGED_GROUPS = List.of(
        GROUP_PAY, GROUP_AI_CONTENT, GROUP_MEMBER, GROUP_CONTENT, GROUP_TICKET,
        GROUP_POINTS, GROUP_DAILY_TASK, GROUP_MALL, GROUP_LEVEL);

    /** 支持的任务类型 → pointsConfig.rules 里对应的字段名 */
    private static final Map<String, String> TASK_ACTION_TO_RULE = Map.of(
        "browse", "browse",
        "comment", "comment",
        "upvote", "upvote",
        "vote", "vote",
        "sign_in", "signIn");

    private CommercialConfigChecks() {
    }

    /**
     * 执行全部关联检查。
     *
     * @param configs        分组名 → 该分组配置（缺失的按空对象处理）
     * @param existingTagSlugs Halo 中实际存在的标签 slug（小写），用于校验付费标记标签；
     *                        传 null 表示跳过该项校验（拿不到标签时不该乱报）
     */
    public static List<ConfigCheckDTO> check(Map<String, JsonNode> configs,
        Set<String> existingTagSlugs) {
        var checks = new ArrayList<ConfigCheckDTO>();
        var pay = configs.getOrDefault(GROUP_PAY, emptyObject());
        var member = configs.getOrDefault(GROUP_MEMBER, emptyObject());
        var content = configs.getOrDefault(GROUP_CONTENT, emptyObject());
        var ticket = configs.getOrDefault(GROUP_TICKET, emptyObject());
        var points = configs.getOrDefault(GROUP_POINTS, emptyObject());
        var dailyTask = configs.getOrDefault(GROUP_DAILY_TASK, emptyObject());
        var mall = configs.getOrDefault(GROUP_MALL, emptyObject());
        var level = configs.getOrDefault(GROUP_LEVEL, emptyObject());

        var goods = arrayOf(pay, "goods");
        var outfits = arrayOf(level, "outfits");
        var registeredOutfitKeys = new HashSet<String>();
        var duplicateOutfitKeys = new LinkedHashSet<String>();
        for (var outfit : outfits) {
            var key = text(outfit, "key");
            if (!key.isEmpty() && !registeredOutfitKeys.add(key)) {
                duplicateOutfitKeys.add(key);
            }
        }

        checkPay(pay, goods, member, content, outfits, registeredOutfitKeys, checks);
        checkMember(member, goods, checks);
        checkContent(content, goods, ticket, dailyTask, existingTagSlugs, checks);
        checkDailyTasks(dailyTask, points, ticket, checks);
        checkPointsEngine(points, dailyTask, checks);
        checkDuplicates(goods, mall, duplicateOutfitKeys, checks);
        checkTicketValue(content, ticket, goods, checks);
        return List.copyOf(checks);
    }

    // ==================== 虚拟支付 ====================

    private static void checkPay(JsonNode pay, List<JsonNode> goods, JsonNode member,
        JsonNode content, List<JsonNode> outfits, Set<String> registeredOutfitKeys,
        List<ConfigCheckDTO> checks) {
        var enabled = bool(pay, "enabled", false);
        if (!enabled) {
            // 未开启时只在「配了商品却没开」这种明显自相矛盾的情况下提示
            if (!goods.isEmpty()) {
                checks.add(ConfigCheckDTO.warn(GROUP_PAY, "权益直购未开启，但已配置了商品",
                    "已配置 " + goods.size() + " 个商品，但由于「开启权益直购」是关闭的，"
                        + "小程序端不会展示入口、接口也不下发商品。"));
            }
            return;
        }
        if (text(pay, "offerId").isEmpty()) {
            checks.add(ConfigCheckDTO.error(GROUP_PAY, "缺少 OfferID",
                "已开启权益直购但没填 OfferID，用户下单会直接失败。"
                    + "请在 MP 后台【虚拟支付 - 基本配置】复制 OfferID 填入。"));
        }
        if (text(pay, "appKey").isEmpty()) {
            checks.add(ConfigCheckDTO.error(GROUP_PAY, "缺少现网 AppKey",
                "已开启权益直购但没填 AppKey，服务端无法计算支付签名，用户下单会报 -15006。"
                    + "请在 MP 后台【虚拟支付 - 基本配置】复制现网 AppKey 填入。"));
        }
        if (goods.isEmpty()) {
            checks.add(ConfigCheckDTO.warn(GROUP_PAY, "没有配置任何商品",
                "已开启权益直购但商品列表为空，小程序端会显示一个买不到东西的页面。"));
        }

        var memberEnabled = bool(member, "enabled", false);
        for (var item : goods) {
            var type = text(item, "type");
            var name = displayName(item);
            switch (type) {
                case "outfit" -> requireRegisteredOutfit(item, name, outfits, registeredOutfitKeys,
                    checks);
                case "bundle" -> {
                    for (var sub : arrayOf(item, "items")) {
                        if ("outfit".equals(text(sub, "type"))) {
                            requireRegisteredOutfit(sub, name + " 里的装扮子项", outfits,
                                registeredOutfitKeys, checks);
                        }
                    }
                }
                case "membership" -> {
                    if (!memberEnabled) {
                        checks.add(ConfigCheckDTO.error(GROUP_PAY, "商品「" + name
                                + "」是会员卡，但「会员卡」功能没开启",
                            "用户付款后发货会被拒绝、订单转为「需人工退款」，需要你手工退款。"
                                + "请到「会员卡」分组开启该功能，或把这条商品下架。"));
                    }
                }
                case "content_ticket" -> {
                    if (!bool(content, "enabled", false)) {
                        checks.add(ConfigCheckDTO.warn(GROUP_PAY, "在卖阅读券，但付费内容没开启",
                            "商品「" + name + "」卖的是解锁付费文章用的阅读券，"
                                + "而「付费内容」是关闭的——用户买了券也没有文章可以解锁。"));
                    }
                }
                default -> {
                    // 未知道具类型由后端 parseConfig 拦下并不下发，另有日志，不在此重复报
                }
            }
        }
    }

    private static void requireRegisteredOutfit(JsonNode node, String where, List<JsonNode> outfits,
        Set<String> registeredOutfitKeys, List<ConfigCheckDTO> checks) {
        var style = text(node, "style");
        if (style.isEmpty()) {
            checks.add(ConfigCheckDTO.error(GROUP_PAY, where + "没填装扮 key",
                "装扮类商品必须填写「装扮商店」里已登记的 key，否则该商品不会下发到小程序"
                    + "（用户看不到，你也以为已经上架）。"));
            return;
        }
        if (!registeredOutfitKeys.contains(style)) {
            var available = registeredOutfitKeys.isEmpty() ? "（装扮商店目前是空的）"
                : "已登记的 key：" + String.join("、", registeredOutfitKeys);
            checks.add(ConfigCheckDTO.error(GROUP_PAY, where + "的装扮 key「" + style + "」不存在",
                "该 key 在「等级与称号装扮 → 装扮商店」里找不到，商品不会下发到小程序。"
                    + "请改成已登记的 key，或先去装扮商店新增同 key 的装扮。" + available));
        }
    }

    // ==================== 会员卡 ====================

    private static void checkMember(JsonNode member, List<JsonNode> goods,
        List<ConfigCheckDTO> checks) {
        if (!bool(member, "enabled", false)) {
            return;
        }
        if (!bool(member, "freeContent", true)) {
            checks.add(ConfigCheckDTO.warn(GROUP_MEMBER, "会员不含免券权益，也没有开通赠券",
                "「会员期内付费文章免券」已关闭、开通赠券也是 0，此时会员卡对用户几乎没有实际价值，"
                    + "除非你另有站外承诺的权益。"));
        }
        var hasMembershipGoods = goods.stream().anyMatch(item -> "membership".equals(text(item, "type"))
            || ("bundle".equals(text(item, "type")) && arrayOf(item, "items").stream()
                .anyMatch(sub -> "membership".equals(text(sub, "type")))));
        if (!hasMembershipGoods) {
            checks.add(ConfigCheckDTO.warn(GROUP_MEMBER, "会员卡已开启，但没有可售的会员商品",
                "「虚拟支付」里没有类型为「会员卡」的商品，用户无法开通。"
                    + "请在「权益与定价」里新增一条类型为会员卡的商品（道具数量填天数）。"));
        }
    }

    // ==================== 付费内容 ====================

    private static void checkContent(JsonNode content, List<JsonNode> goods, JsonNode ticket,
        JsonNode dailyTask, Set<String> existingTagSlugs, List<ConfigCheckDTO> checks) {
        if (!bool(content, "enabled", false)) {
            return;
        }
        var slug = text(content, "paidTagSlug");
        if (slug.isEmpty()) {
            checks.add(ConfigCheckDTO.error(GROUP_CONTENT, "付费标记标签 slug 为空",
                "没有标签 slug 就匹配不到任何付费文章，付费墙会静默失效（所有文章都能免费看）。"));
        } else if (existingTagSlugs != null && !existingTagSlugs.contains(slug.toLowerCase())) {
            var existingHint = existingTagSlugs.isEmpty()
                ? "" : "当前已有标签：" + String.join("、", existingTagSlugs);
            checks.add(ConfigCheckDTO.error(GROUP_CONTENT, "付费标记标签「" + slug + "」在 Halo 里不存在",
                "付费文章靠「文章带了这个标签」来识别，标签不存在时一篇都不会被拦截，"
                    + "付费墙静默失效。请到 Halo 后台【文章 - 标签】创建 slug 为「" + slug + "」的标签。"
                    + existingHint));
        }
        if (!bool(content, "permanent", true)) {
            checks.add(ConfigCheckDTO.warn(GROUP_CONTENT, "「解锁永久有效」被关闭",
                "该开关目前只是语义占位，关闭后既不支持限时解锁，也可能让用户以为解锁会过期。建议保持开启。"));
        }
        // 券的免费来源
        var hasFreeTickets = intVal(ticket, "newUserTickets", 0) > 0
            || intVal(ticket, "signInTickets", 0) > 0
            || (intVal(ticket, "streakDays", 0) > 0 && intVal(ticket, "streakTickets", 0) > 0)
            || intVal(ticket, "firstPurchaseTickets", 0) > 0
            || arrayOf(dailyTask, "tasks").stream()
                .anyMatch(task -> intVal(task, "tickets", 0) > 0);
        if (!hasFreeTickets) {
            checks.add(ConfigCheckDTO.info(GROUP_CONTENT, "没有任何免费赠券途径",
                "用户只能靠「现金购买」或「积分兑换」拿到阅读券。"
                    + "如果你希望用户先体验再付费，可以在「阅读券获取」或「每日任务」里配置赠券。"));
        }
    }

    // ==================== 每日任务 ====================

    /**
     * 任务进度按「当日该行为的积分流水条数」统计，而积分规则为 0 时<b>不写流水</b>
     * （见 PointsServiceImpl#processEarn 的「该行为未配置积分」分支）。
     * 结果是任务永远停在 0/N，用户在页面上看不到任何错误。
     *
     * <p>签到是例外：签到即使积分为 0，只要配了签到赠券也会写流水（一个 0 分的签到流水），
     * 因此签到的判定要把赠券一并算进去。</p>
     */
    private static void checkDailyTasks(JsonNode dailyTask, JsonNode points, JsonNode ticket,
        List<ConfigCheckDTO> checks) {
        if (!bool(dailyTask, "enabled", true)) {
            return;
        }
        var rules = points.path("rules");
        for (var task : arrayOf(dailyTask, "tasks")) {
            if (!bool(task, "enabled", true)) {
                continue;
            }
            var type = text(task, "type");
            var target = intVal(task, "target", 0);
            if (target <= 0) {
                continue;
            }
            var ruleField = TASK_ACTION_TO_RULE.get(type);
            if (ruleField == null) {
                continue;
            }
            var actionPoints = rules.path(ruleField).path("points").asInt(0);
            if (actionPoints > 0) {
                continue;
            }
            if ("sign_in".equals(type) && signInStillWritesRecord(ticket)) {
                continue;
            }
            var label = taskLabel(type);
            var fixHint = "sign_in".equals(type)
                ? "请到「积分经济 → 积分规则」把签到积分设为大于 0，或在「阅读券获取」里配置签到赠券。"
                : "请到「积分经济 → 积分规则」把「" + label + "」的积分设为大于 0。";
            checks.add(ConfigCheckDTO.error(GROUP_DAILY_TASK, "每日任务「" + label + "」永远无法完成",
                "任务进度按「当日该行为的积分流水条数」统计，而该行为的积分规则是 0 分——"
                    + "不产生流水，进度就永远是 0/" + target + "，用户在任务页看不到任何报错。"
                    + fixHint));
        }
    }

    /**
     * 积分系统总开关与每日任务的关系。
     *
     * <p>任务进度按「当日积分流水条数」统计，而积分系统关闭时任何行为都不会写流水
     * （{@code PointsServiceImpl.ensureEnabled} 会直接拒绝），任务于是永远停在 0/N。</p>
     *
     * <p>用 warn 而不是 error：这是全局开关造成的状态，不是某条规则配错。
     * 确实不想用积分时，正确做法是把每日任务也一并关掉，而不是让页面一直报「需修复」——
     * 一个消不掉的报错会让人把整个检查面板都忽略掉。</p>
     */
    private static void checkPointsEngine(JsonNode points, JsonNode dailyTask,
        List<ConfigCheckDTO> checks) {
        if (bool(points, "enabled", false)) {
            return;
        }
        if (!bool(dailyTask, "enabled", true)) {
            return;
        }
        var activeTasks = arrayOf(dailyTask, "tasks").stream()
            .filter(task -> bool(task, "enabled", true) && intVal(task, "target", 0) > 0)
            .count();
        if (activeTasks == 0) {
            return;
        }
        checks.add(ConfigCheckDTO.warn(GROUP_POINTS, "积分系统未开启，每日任务不会累积进度",
            "任务进度按「当日积分流水条数」统计，积分系统关闭时任何行为都不写流水，"
                + "因此 " + activeTasks + " 个任务会一直停在 0/N。"
                + "如果确实不使用积分，建议把「每日任务」也关掉。"));
    }

    /** 签到在积分为 0 时仍会写流水的前提：配了签到赠券。 */
    private static boolean signInStillWritesRecord(JsonNode ticket) {
        return intVal(ticket, "signInTickets", 0) > 0
            || (intVal(ticket, "streakDays", 0) > 0 && intVal(ticket, "streakTickets", 0) > 0);
    }

    private static String taskLabel(String type) {
        return switch (type) {
            case "browse" -> "浏览文章";
            case "comment" -> "发表评论";
            case "upvote" -> "点赞";
            case "vote" -> "参与投票";
            case "sign_in" -> "每日签到";
            default -> type;
        };
    }

    // ==================== 重复 key ====================

    private static void checkDuplicates(List<JsonNode> goods, JsonNode mall,
        Set<String> duplicateOutfitKeys, List<ConfigCheckDTO> checks) {
        reportDuplicates(goods.stream().map(item -> text(item, "goodsId")).toList(),
            GROUP_PAY, "道具 ID", checks);
        reportDuplicates(arrayOf(mall, "exchangeItems").stream()
            .map(item -> text(item, "key")).toList(),
            GROUP_MALL, "权益 key", checks);
        if (!duplicateOutfitKeys.isEmpty()) {
            checks.add(ConfigCheckDTO.error(GROUP_LEVEL, "装扮 key 重复："
                    + String.join("、", duplicateOutfitKeys),
                "装扮 key 必须唯一：系统用 key 反查装扮与判断用户是否已拥有，"
                    + "重复会让其中一条配置永远不生效、甚至发错装扮。"));
        }
    }

    private static void reportDuplicates(List<String> keys, String group, String what,
        List<ConfigCheckDTO> checks) {
        var seen = new HashSet<String>();
        var duplicates = new LinkedHashSet<String>();
        for (var key : keys) {
            if (!key.isEmpty() && !seen.add(key)) {
                duplicates.add(key);
            }
        }
        if (!duplicates.isEmpty()) {
            checks.add(ConfigCheckDTO.error(group, what + " 重复：" + String.join("、", duplicates),
                what + "必须唯一，重复的配置只会生效一条，另一条看起来配了实际上不起作用。"));
        }
    }

    // ==================== 券的价值（让三方关联一眼可见） ====================

    /**
     * 把「解锁一篇要几张券」「一张券多少积分」「券包一张多少钱」放在一起算给站长看。
     *
     * <p>这三个数字分属三个不同分组，共同决定「一张券值多少钱」。
     * 以前要跨三个分组来回翻才能判断券包卖贵了还是便宜了。</p>
     */
    private static void checkTicketValue(JsonNode content, JsonNode ticket, List<JsonNode> goods,
        List<ConfigCheckDTO> checks) {
        var costTickets = Math.max(1, intVal(content, "costTickets", 1));
        var exchangePoints = Math.max(1, intVal(ticket, "exchangePoints", 30));
        var parts = new ArrayList<String>();
        parts.add("解锁一篇付费文章需要 " + costTickets + " 张券");
        parts.add("每张券可用 " + exchangePoints + " 积分兑换，即每篇约 "
            + (costTickets * exchangePoints) + " 积分");

        var cashUnit = cheapestTicketUnitPriceCents(goods);
        if (cashUnit > 0) {
            parts.add(String.format("券包折合每张约 %.2f 元，即每篇约 %.2f 元",
                cashUnit / 100.0, costTickets * cashUnit / 100.0));
        }
        checks.add(ConfigCheckDTO.info(GROUP_CONTENT, "券的价值折算", String.join("；", parts) + "。"));
    }

    /** 券包里最便宜的单张现金价（分）；没有多张装时返回 0（不做无依据的对比）。 */
    private static long cheapestTicketUnitPriceCents(List<JsonNode> goods) {
        long cheapest = 0;
        for (var item : goods) {
            if (!"content_ticket".equals(text(item, "type"))) {
                continue;
            }
            var count = intVal(item, "value", 0);
            var price = item.path("price").asLong(0);
            if (count > 1 && price > 0) {
                var unit = price / count;
                if (cheapest == 0 || unit < cheapest) {
                    cheapest = unit;
                }
            }
        }
        return cheapest;
    }

    // ==================== JSON 读取工具 ====================

    private static JsonNode emptyObject() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    }

    /** 取数组字段；非数组（含缺失）一律返回空列表，避免各处判空。 */
    private static List<JsonNode> arrayOf(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isArray()) {
            return List.of();
        }
        var items = new ArrayList<JsonNode>();
        value.forEach(items::add);
        return List.copyOf(items);
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isTextual() ? value.asText().trim() : "";
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asBoolean(fallback);
    }

    private static int intVal(JsonNode node, String field, int fallback) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asInt(fallback);
    }

    /** 商品/子项的展示名：优先用名称，没有则用 goodsId。 */
    private static String displayName(JsonNode item) {
        var name = text(item, "name");
        if (!name.isEmpty()) {
            return name;
        }
        var goodsId = text(item, "goodsId");
        return goodsId.isEmpty() ? "未命名商品" : goodsId;
    }
}
