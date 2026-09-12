package pro.uhalo.uni.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 已拥有装扮的限时结构解析器。
 *
 * <p>历史数据（v1.0.96 及之前）{@code outfitOwned} 是纯 key 数组（List&lt;String&gt;），
 * 视为永久拥有；本轮升级为对象数组：</p>
 *
 * <pre>
 * [ {"key":"avatar_gold","boughtAt":1735689600,"expireAt":null} ]
 * </pre>
 *
 * <p>读取兼容旧格式（纯字符串项按永久处理，expireAt=null）；
 * 写回时统一升级为对象数组（惰性迁移，不主动改写存量字段）。</p>
 */
public final class OwnedOutfitParser {

    public record OwnedOutfit(String key, long boughtAt, Long expireAt) {

        /** 是否仍在有效期内（expireAt==null 代表永久）。 */
        public boolean isStillValid() {
            if (expireAt == null) {
                return true;
            }
            return Instant.now().getEpochSecond() < expireAt;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<JsonNode>> NODE_LIST = new TypeReference<>() {
    };

    private OwnedOutfitParser() {
    }

    /**
     * 解析 outfitOwned：兼容旧格式（纯 key 字符串）与新格式（对象）。
     * 无法解析、或解析后过期但需保留记录的项，也会保留在列表中（过期不删，防误清）。
     * 调用方通过 {@link OwnedOutfit#isStillValid()} 判定有效性。
     */
    public static List<OwnedOutfit> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            var nodes = MAPPER.readValue(raw, NODE_LIST);
            if (nodes == null) {
                return List.of();
            }
            var result = new ArrayList<OwnedOutfit>(nodes.size());
            for (var node : nodes) {
                if (node.isTextual()) {
                    // 旧格式：纯 key，永久拥有
                    var text = node.asText().trim();
                    if (!text.isEmpty()) {
                        result.add(new OwnedOutfit(text, 0L, null));
                    }
                    continue;
                }
                if (!node.isObject()) {
                    continue;
                }
                var key = node.path("key").asText("").trim();
                if (key.isEmpty()) {
                    continue;
                }
                var boughtAt = node.path("boughtAt").asLong(0L);
                var expireAtNode = node.get("expireAt");
                Long expireAt = null;
                if (expireAtNode != null && !expireAtNode.isNull()) {
                    expireAt = expireAtNode.asLong();
                }
                result.add(new OwnedOutfit(key, boughtAt, expireAt));
            }
            return List.copyOf(result);
        } catch (Exception ex) {
            return List.of();
        }
    }

    /**
     * 序列化对象数组（新格式写入）。旧格式数据会在首次写回时经由 parse → 对象 升级。
     */
    public static String write(List<OwnedOutfit> owned) {
        try {
            var builder = new StringBuilder("[");
            for (var i = 0; i < owned.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                var item = owned.get(i);
                builder.append("{\"key\":\"")
                    .append(escape(item.key()))
                    .append("\",\"boughtAt\":")
                    .append(item.boughtAt());
                if (item.expireAt() == null) {
                    builder.append(",\"expireAt\":null");
                } else {
                    builder.append(",\"expireAt\":").append(item.expireAt());
                }
                builder.append('}');
            }
            builder.append(']');
            return builder.toString();
        } catch (Exception ex) {
            return "[]";
        }
    }

    /**
     * 判断 key 是否已拥有且仍在有效期内。
     */
    public static boolean isOwnedAndValid(List<OwnedOutfit> owned, String key) {
        return owned != null && owned.stream()
            .anyMatch(item -> key.equals(item.key()) && item.isStillValid());
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
