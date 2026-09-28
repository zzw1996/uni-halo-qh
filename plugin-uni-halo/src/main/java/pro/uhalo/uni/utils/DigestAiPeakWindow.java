package pro.uhalo.uni.utils;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 高峰/空闲时段判定（DeepSeek 的计费时段规则）。
 *
 * <p>官方原文：<b>北京时间（UTC+8）周一至周五（不含中国法定节假日）9:00-12:00、14:00-18:00
 * 为高峰时段；其余时段，包括周末及法定节假日全天均为空闲时段</b>，且空闲时段价格是高峰的一半。
 * 也就是说不区分时段的话，白天跑的日报会被高估一倍（9-12 点、14-18 点正是常见的执行时刻）。</p>
 *
 * <p><b>法定节假日无法自动获知</b>：插件不联网查日历，所以节假日会被当作高峰（略微高估）。
 * 这对「预算是多少」这个用途是安全的方向——宁可提示得贵一点，也不要让站长按半价做计划。</p>
 *
 * <p>纯函数：给定时刻就能判定，因此可以逐条单测（周末、午休、边界分钟、跨时区）。</p>
 */
public final class DigestAiPeakWindow {

    /** 官方时区：北京时间 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 上午高峰：9:00（含）- 12:00（不含） */
    public static final LocalTime MORNING_START = LocalTime.of(9, 0);
    public static final LocalTime MORNING_END = LocalTime.of(12, 0);

    /** 下午高峰：14:00（含）- 18:00（不含） */
    public static final LocalTime AFTERNOON_START = LocalTime.of(14, 0);
    public static final LocalTime AFTERNOON_END = LocalTime.of(18, 0);

    private DigestAiPeakWindow() {
    }

    /** 该时刻是否处于高峰时段 */
    public static boolean isPeak(Instant instant) {
        if (instant == null) {
            return true;
        }
        return isPeak(instant.atZone(ZONE));
    }

    public static boolean isPeak(ZonedDateTime time) {
        if (time == null) {
            return true;
        }
        var local = time.withZoneSameInstant(ZONE);
        var day = local.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        var clock = local.toLocalTime();
        return inWindow(clock, MORNING_START, MORNING_END)
            || inWindow(clock, AFTERNOON_START, AFTERNOON_END);
    }

    /** 左闭右开：12:00 整点已经是休息时间，不算高峰 */
    private static boolean inWindow(LocalTime clock, LocalTime start, LocalTime end) {
        return !clock.isBefore(start) && clock.isBefore(end);
    }

    /** 一句话描述规则：面板与帮助文案共用，避免两处口径不一致 */
    public static String describe() {
        return "高峰时段：北京时间周一至周五（不含法定节假日）9:00-12:00、14:00-18:00；"
            + "其余时间（含周末与节假日）为空闲时段，按高峰价的一半估算（节假日无法自动识别，按高峰计）";
    }
}
