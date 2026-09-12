package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已拥有装扮限时结构解析器测试。
 * 覆盖：旧格式兼容（纯 key）、新格式解析、到期判定、写入序列化。
 */
class OwnedOutfitParserTest {

    @Test
    void shouldParseLegacyPlainKeyArrayAsPermanent() {
        var parsed = OwnedOutfitParser.parse("[\"avatar_gold\",\"nick_star\"]");
        assertEquals(2, parsed.size());
        assertTrue(parsed.get(0).isStillValid(), "旧格式 key 应为永久有效");
        assertNull(parsed.get(0).expireAt(), "旧格式无过期时间");
        assertEquals("avatar_gold", parsed.get(0).key());
    }

    @Test
    void shouldParseObjectArrayWithExpireAt() {
        var now = java.time.Instant.now().getEpochSecond();
        var raw = "[{\"key\":\"theme_forest\",\"boughtAt\":" + (now - 100)
            + ",\"expireAt\":" + (now + 86400) + "}]";
        var parsed = OwnedOutfitParser.parse(raw);
        assertEquals(1, parsed.size());
        assertEquals("theme_forest", parsed.get(0).key());
        assertTrue(parsed.get(0).isStillValid(), "未过期应有效");
    }

    @Test
    void shouldTreatExpiredAsInvalid() {
        var now = java.time.Instant.now().getEpochSecond();
        var raw = "[{\"key\":\"theme_old\",\"boughtAt\":" + (now - 100000)
            + ",\"expireAt\":" + (now - 100) + "}]";
        var parsed = OwnedOutfitParser.parse(raw);
        assertFalse(parsed.get(0).isStillValid(), "已过期应无效");
    }

    @Test
    void shouldIgnoreMalformedEntries() {
        var parsed = OwnedOutfitParser.parse("[\"ok\", 123, {\"key\":\"\"}, {\"expireAt\":1}]");
        assertEquals(1, parsed.size(), "仅合法字符串 key 被识别");
        assertEquals("ok", parsed.get(0).key());
    }

    @Test
    void shouldFallbackEmptyOnCorruptJson() {
        assertEquals(List.of(), OwnedOutfitParser.parse("{not-json"));
        assertEquals(List.of(), OwnedOutfitParser.parse(null));
        assertEquals(List.of(), OwnedOutfitParser.parse(""));
    }

    @Test
    void writeRoundtripsThroughParse() {
        var now = java.time.Instant.now().getEpochSecond();
        var owned = List.of(
            new OwnedOutfitParser.OwnedOutfit("avatar_gold", now - 10, null),
            new OwnedOutfitParser.OwnedOutfit("theme_forest", now - 10, now + 86400)
        );
        var serialized = OwnedOutfitParser.write(owned);
        var parsed = OwnedOutfitParser.parse(serialized);
        assertEquals(2, parsed.size());
        assertEquals("avatar_gold", parsed.get(0).key());
        assertTrue(parsed.get(0).isStillValid());
        assertEquals("theme_forest", parsed.get(1).key());
        assertEquals(now + 86400, parsed.get(1).expireAt());
    }

    @Test
    void isOwnedAndValidRespectsExpiry() {
        var now = java.time.Instant.now().getEpochSecond();
        var owned = List.of(
            new OwnedOutfitParser.OwnedOutfit("perm", 0L, null),
            new OwnedOutfitParser.OwnedOutfit("expired", 0L, now - 1),
            new OwnedOutfitParser.OwnedOutfit("future", 0L, now + 60)
        );
        assertTrue(OwnedOutfitParser.isOwnedAndValid(owned, "perm"));
        assertFalse(OwnedOutfitParser.isOwnedAndValid(owned, "expired"));
        assertTrue(OwnedOutfitParser.isOwnedAndValid(owned, "future"));
        assertFalse(OwnedOutfitParser.isOwnedAndValid(owned, "missing"));
    }
}
