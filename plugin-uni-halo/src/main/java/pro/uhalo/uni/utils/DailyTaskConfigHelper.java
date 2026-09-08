package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import pro.uhalo.uni.dto.DailyTaskConfigDTO;
import pro.uhalo.uni.dto.DailyTaskItemDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 每日任务配置解析：来自设置分组 dailyTaskConfig。
 * 任务进度按「当日该行为的积分流水条数」统计，因此若某行为积分规则为 0 分，
 * 该行为不产生流水，对应任务进度将统计不到。
 */
public final class DailyTaskConfigHelper {

    public static final String GROUP_NAME = "dailyTaskConfig";

    /** 支持的任务类型 */
    public static final Set<String> VALID_TYPES = Set.of("sign_in", "browse", "comment", "upvote", "vote");

    private DailyTaskConfigHelper() {
    }

    public static String groupName() {
        return GROUP_NAME;
    }

    public static DailyTaskConfigDTO defaults() {
        return DailyTaskConfigDTO.builder()
            .enabled(true)
            .tasks(List.of(
                seed("sign_in", 1, 10),
                seed("browse", 3, 10),
                seed("comment", 1, 10),
                seed("upvote", 3, 8),
                seed("vote", 1, 10)
            ))
            .build();
    }

    private static DailyTaskItemDTO seed(String type, int target, int points) {
        return DailyTaskItemDTO.builder()
            .type(type)
            .target(target)
            .points(points)
            .enabled(true)
            .build();
    }

    public static DailyTaskConfigDTO parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaults();
        }
        var tasks = new ArrayList<DailyTaskItemDTO>();
        var tasksNode = node.path("tasks");
        if (tasksNode.isArray()) {
            tasksNode.forEach(item -> {
                var parsed = parseItem(item);
                if (parsed != null) {
                    tasks.add(parsed);
                }
            });
        }
        if (tasks.isEmpty()) {
            return DailyTaskConfigDTO.builder()
                .enabled(node.path("enabled").asBoolean(true))
                .tasks(List.of())
                .build();
        }
        return DailyTaskConfigDTO.builder()
            .enabled(node.path("enabled").asBoolean(true))
            .tasks(List.copyOf(tasks))
            .build();
    }

    private static DailyTaskItemDTO parseItem(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        var type = node.path("type").asText("").trim().toLowerCase();
        var target = node.path("target").asInt(0);
        var points = node.path("points").asInt(0);
        if (!VALID_TYPES.contains(type) || target <= 0 || points <= 0) {
            return null;
        }
        return DailyTaskItemDTO.builder()
            .type(type)
            .target(target)
            .points(points)
            .enabled(node.path("enabled").asBoolean(true))
            .build();
    }
}
