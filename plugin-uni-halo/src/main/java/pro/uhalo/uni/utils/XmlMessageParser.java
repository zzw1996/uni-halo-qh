package pro.uhalo.uni.utils;

import java.util.regex.Pattern;

/**
 * 轻量 XML 字段提取器：仅用于解析微信推送的 <xml> 消息体，
 * 支持 <![CDATA[...]]> 与纯文本两种取值形式，字段名大小写不敏感。
 */
public final class XmlMessageParser {

    private static final Pattern TEXT_TAG_PATTERN =
        Pattern.compile("<([A-Za-z0-9_]+)>(?:(?:<!\\[CDATA\\[)?([^<|]*?)(?:\\]\\]>)?)</\\1>");

    private XmlMessageParser() {
    }

    /** 提取第一个匹配字段的值；不存在返回 null。 */
    public static String value(String xml, String tagName) {
        if (xml == null || tagName == null) {
            return null;
        }
        var matcher = TEXT_TAG_PATTERN.matcher(xml);
        while (matcher.find()) {
            if (matcher.group(1).equalsIgnoreCase(tagName)) {
                return matcher.group(2) == null ? null : matcher.group(2).trim();
            }
        }
        return null;
    }

    /** 提取第一个匹配字段的值；不存在或为空返回 fallback。 */
    public static String value(String xml, String tagName, String fallback) {
        var value = value(xml, tagName);
        return (value == null || value.isEmpty()) ? fallback : value;
    }

    /** 按多个候选字段名提取（兼容不同推送版本的字段命名）。 */
    public static String valueFirstOf(String xml, String... tagNames) {
        for (var tagName : tagNames) {
            var value = value(xml, tagName);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }
}
