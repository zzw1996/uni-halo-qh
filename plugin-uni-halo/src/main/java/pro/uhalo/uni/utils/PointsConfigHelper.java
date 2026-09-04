package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.ConsecutiveBonusDTO;
import pro.uhalo.uni.dto.PointsActionRuleDTO;
import pro.uhalo.uni.dto.PointsConfigDTO;
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
