package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.ConsecutiveBonusDTO;
import pro.uhalo.uni.dto.DrawPrizeDTO;
import pro.uhalo.uni.dto.PointsActionRuleDTO;
import pro.uhalo.uni.dto.PointsConfigDTO;
import pro.uhalo.uni.dto.PointsExchangeItemRuleDTO;
import pro.uhalo.uni.dto.PointsExchangeRulesDTO;
import pro.uhalo.uni.dto.PointsDrawRuleDTO;
import pro.uhalo.uni.dto.PointsRulesDTO;
import pro.uhalo.uni.dto.PointsSignInRuleDTO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PointsConfigHelper {

    private static final String GROUP_NAME = "pointsConfig";

    private PointsConfigHelper() {
    }

    public static String groupName() {
        return GROUP_NAME;
    }

    public static PointsConfigDTO defaults() {
        return PointsConfigDTO.builder().build();
    }

    public static PointsConfigDTO parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaults();
        }
        var rulesNode = node.path("rules");
        return PointsConfigDTO.builder()
            .enabled(node.path("enabled").asBoolean(false))
            .showInProfile(node.path("showInProfile").asBoolean(true))
            .minBrowseDuration(Math.max(1, node.path("minBrowseDuration").asInt(5)))
            .rules(parseRules(rulesNode))
            .build();
    }

    private static PointsRulesDTO parseRules(JsonNode rulesNode) {
        var defaults = PointsRulesDTO.builder().build();
        if (rulesNode == null || rulesNode.isNull() || rulesNode.isMissingNode()) {
            return defaults;
        }
        return PointsRulesDTO.builder()
            .browse(parseActionRule(rulesNode.path("browse"), defaults.getBrowse()))
            .comment(parseActionRule(rulesNode.path("comment"), defaults.getComment()))
            .upvote(parseActionRule(rulesNode.path("upvote"), defaults.getUpvote()))
            .signIn(parseSignInRule(rulesNode.path("signIn"), defaults.getSignIn()))
            .newUserGift(parseActionRule(rulesNode.path("newUserGift"), defaults.getNewUserGift()))
            .draw(parseDrawRule(rulesNode.path("draw"), defaults.getDraw()))
            .exchange(parseExchangeRules(rulesNode.path("exchange"), defaults.getExchange()))
            .build();
    }

    private static PointsExchangeRulesDTO parseExchangeRules(JsonNode node, PointsExchangeRulesDTO fallback) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return fallback;
        }
        return PointsExchangeRulesDTO.builder()
            .publishTicket(parseExchangeItemRule(node.path("publishTicket"),
                fallback.getPublishTicket()))
            .dashboardAdvanced(parseExchangeItemRule(node.path("dashboardAdvanced"),
                fallback.getDashboardAdvanced()))
            .build();
    }

    private static PointsExchangeItemRuleDTO parseExchangeItemRule(JsonNode node,
        PointsExchangeItemRuleDTO fallback) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return fallback;
        }
        return PointsExchangeItemRuleDTO.builder()
            .points(Math.max(0, node.path("points").asInt(fallback.getPoints())))
            .dailyMaxCount(Math.max(0, node.path("dailyMaxCount").asInt(fallback.getDailyMaxCount())))
            .freeCount(Math.max(0, node.path("freeCount").asInt(fallback.getFreeCount())))
            .build();
    }

    private static PointsDrawRuleDTO parseDrawRule(JsonNode node, PointsDrawRuleDTO fallback) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return fallback;
        }
        var prizes = new ArrayList<DrawPrizeDTO>();
        var prizeNode = node.path("prizes");
        if (prizeNode.isArray()) {
            prizeNode.forEach(item -> {
                var points = item.path("points").asInt(0);
                var weight = item.path("weight").asInt(0);
                if (points > 0 && weight > 0) {
                    prizes.add(DrawPrizeDTO.builder().points(points).weight(weight).build());
                }
            });
        }
        if (prizes.isEmpty()) {
            prizes.addAll(fallback.getPrizes());
        }
        return PointsDrawRuleDTO.builder()
            .cost(Math.max(0, node.path("cost").asInt(fallback.getCost())))
            .prizes(List.copyOf(prizes))
            .build();
    }

    private static PointsActionRuleDTO parseActionRule(JsonNode node, PointsActionRuleDTO fallback) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return fallback;
        }
        return PointsActionRuleDTO.builder()
            .points(Math.max(0, node.path("points").asInt(fallback.getPoints())))
            .dailyMaxCount(Math.max(0, node.path("dailyMaxCount").asInt(fallback.getDailyMaxCount())))
            .dailyMaxPoints(Math.max(0, node.path("dailyMaxPoints").asInt(fallback.getDailyMaxPoints())))
            .build();
    }

    private static PointsSignInRuleDTO parseSignInRule(JsonNode node, PointsSignInRuleDTO fallback) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return fallback;
        }
        var bonuses = new ArrayList<ConsecutiveBonusDTO>();
        var bonusNode = node.path("consecutiveBonus");
        if (bonusNode.isArray()) {
            bonusNode.forEach(item -> {
                var days = item.path("days").asInt(0);
                var bonus = item.path("bonus").asInt(0);
                if (days > 0 && bonus > 0) {
                    bonuses.add(ConsecutiveBonusDTO.builder().days(days).bonus(bonus).build());
                }
            });
        }
        if (bonuses.isEmpty()) {
            bonuses.addAll(fallback.getConsecutiveBonus());
        } else {
            bonuses.sort(Comparator.comparingInt(ConsecutiveBonusDTO::getDays));
        }
        return PointsSignInRuleDTO.builder()
            .points(Math.max(0, node.path("points").asInt(fallback.getPoints())))
            .consecutiveBonus(bonuses)
            .build();
    }

    public static PointsActionRuleDTO ruleFor(PointsConfigDTO config, String action) {
        if (config == null || config.getRules() == null || !StringUtils.hasText(action)) {
            return PointsActionRuleDTO.builder().build();
        }
        return switch (action.trim().toLowerCase()) {
            case "browse" -> config.getRules().getBrowse();
            case "comment" -> config.getRules().getComment();
            case "upvote" -> config.getRules().getUpvote();
            default -> PointsActionRuleDTO.builder().build();
        };
    }
}
