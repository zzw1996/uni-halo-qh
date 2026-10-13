package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 今日头条号配图规划：正文配图位解析、按位生图提示词、卡片文案的取用顺序与补齐，以及图片类型识别。
 *
 * <p>守的是三条线：<b>位置来自正文</b>（模型在正文里标【配图N：描述】，图片与上下文对得上）、
 * <b>张数不会少</b>（每篇至少 3 张，模型没标就按正文补齐）、
 * 以及<b>提示词里必须带齐禁止项</b>（文字 / 真人 / 品牌——模型画不对字、真人商标有风险）。</p>
 */
class ToutiaoImageHelperTest {

    @Test
    void bodySlotsShouldReadMarkersInTheOrderTheyAppear() {
        var body = """
            开头讲一个具体的场景，写得比较长一些。

            【配图1：超市结账口有人刷脸支付的实拍画面】

            中间讲一个对比。

            【配图2：一部手机屏幕特写】
            收尾的一段。
            """;

        var slots = ToutiaoImageHelper.bodySlots(body);

        assertThat(slots).hasSize(2);
        assertThat(slots.get(0).order()).isEqualTo(1);
        assertThat(slots.get(0).description()).isEqualTo("超市结账口有人刷脸支付的实拍画面");
        assertThat(slots.get(0).contextBefore()).contains("开头讲一个具体的场景");
        assertThat(slots.get(1).description()).isEqualTo("一部手机屏幕特写");
        assertThat(slots.get(1).contextBefore()).contains("中间讲一个对比");
    }

    @Test
    void bodySlotsShouldTolerateMissingColonAndNumber() {
        var slots = ToutiaoImageHelper.bodySlots("正文一段。\n【配图 桌上的一杯咖啡】\n正文二段。");

        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).description()).isEqualTo("桌上的一杯咖啡");
    }

    @Test
    void bodySlotsShouldReturnEmptyForBodyWithoutMarkers() {
        assertThat(ToutiaoImageHelper.bodySlots("没有标记的正文。")).isEmpty();
        assertThat(ToutiaoImageHelper.bodySlots("")).isEmpty();
        assertThat(ToutiaoImageHelper.bodySlots(null)).isEmpty();
    }

    @Test
    void padSlotsShouldFillMissingSlotsFromBodyMaterial() {
        var body = """
            开头的一段。

            工具怎么选
            这里讲工具。

            上手路径
            这里讲路径。
            """;
        var slots = ToutiaoImageHelper.padSlots(
            ToutiaoImageHelper.bodySlots(body), body, 3, List.of("标题"), List.of("效率"), "远程办公");

        // 模型一个标记都没标 → 补齐到 3 张，描述来自小标题/首句/标签/关键词
        assertThat(slots).hasSize(3);
        assertThat(slots.get(0).description()).isEqualTo("工具怎么选");
        assertThat(slots.get(1).description()).isEqualTo("上手路径");
        assertThat(slots).extracting(ToutiaoImageHelper.BodySlot::contextBefore)
            .allSatisfy(context -> assertThat(context).isNotBlank());
    }

    @Test
    void padSlotsShouldKeepModelMarkersAndCapAtLimit() {
        var body = "一段。\n【配图1：第一张】\n二段。\n【配图2：第二张】\n三段。";
        var slots = ToutiaoImageHelper.padSlots(
            ToutiaoImageHelper.bodySlots(body), body, 3, List.of(), List.of(), "关键词");

        // 模型标的 2 个 + 补 1 个 = 3
        assertThat(slots).hasSize(3);
        assertThat(slots.get(0).description()).isEqualTo("第一张");
        assertThat(slots.get(1).description()).isEqualTo("第二张");
        assertThat(slots.get(2).description()).isNotBlank();

        // 模型标太多也不能超上限
        var many = "一。\n【配图1：a】\n二。\n【配图2：b】\n三。\n【配图3：c】\n四。\n【配图4：d】\n五。\n【配图5：e】\n六。\n【配图6：f】\n七。\n【配图7：g】";
        assertThat(ToutiaoImageHelper.padSlots(
            ToutiaoImageHelper.bodySlots(many), many, 3, List.of(), List.of(), "词"))
            .hasSize(ToutiaoImageHelper.MAX_IMAGES);
    }

    @Test
    void slotPromptShouldCarryDescriptionContextAndForbiddenItems() {
        var slot = new ToutiaoImageHelper.BodySlot(1, "超市结账口有人刷脸支付", "开头讲一次不排队的结账经历");
        var prompt = ToutiaoImageHelper.buildSlotPrompt("AI 改变生活", "情感故事", slot, "水彩插画");

        assertThat(prompt).contains("「AI 改变生活」").contains("情感故事");
        assertThat(prompt).contains("这张图前面一段在讲：开头讲一次不排队的结账经历");
        assertThat(prompt).contains("画面内容：超市结账口有人刷脸支付");
        assertThat(prompt).contains("水彩插画");
        assertThat(prompt).contains("不要出现任何文字、字母、数字、水印与 logo");
        assertThat(prompt).contains("不要出现可识别的真实人物、品牌与商标");
    }

    @Test
    void slotPromptShouldFallBackWhenDescriptionMissing() {
        var slot = new ToutiaoImageHelper.BodySlot(1, "", "上下文");
        var prompt = ToutiaoImageHelper.buildSlotPrompt("主题", "", slot, "");

        assertThat(prompt).contains("与上下文内容相符的实拍感画面");
        assertThat(prompt).contains(ToutiaoImageHelper.DEFAULT_STYLE_PROMPT);
    }

    @Test
    void stripMarkersShouldRemoveSlotLines() {
        var body = "一段。\n\n【配图1：某画面】\n\n二段。";

        assertThat(ToutiaoImageHelper.stripMarkers(body)).isEqualTo("一段。\n\n二段。");
    }

    @Test
    void cardPlanShouldStartWithTitleAndUseHeadingsAsPoints() {
        var body = """
            第一段引入，说清楚为什么值得关注。

            工具选择：先看这三个指标
            这里是第一层的内容，写得比较长一些。

            上手路径：从最小可用开始
            这里是第二层的内容。

            常见误区：别一上来就全家桶
            这里是第三层的内容。
            """;
        var plan = ToutiaoImageHelper.cardPlan(List.of("标题候选一", "标题候选二"), body,
            List.of("人工智能", "职场"), "深度解读", "AI 编程助手", 4);

        assertThat(plan).hasSize(4);
        // 第 1 张永远是标题（封面卡）
        assertThat(plan.get(0).title()).isEqualTo("标题候选一");
        assertThat(plan.get(0).categoryLabel()).isEqualTo("深度解读");
        // 后面的优先用小标题
        assertThat(plan.get(1).title()).isEqualTo("工具选择：先看这三个指标");
        assertThat(plan.get(2).title()).isEqualTo("上手路径：从最小可用开始");
        assertThat(plan.get(3).title()).isEqualTo("常见误区：别一上来就全家桶");
        // 配色 key 各不相同，画面才不会看着像同一张
        assertThat(plan.stream().map(ToutiaoImageHelper.CardSpec::categoryKey).distinct())
            .hasSize(4);
    }

    @Test
    void cardPlanShouldFallBackToFirstSentenceTagsThenKeyword() {
        // 没有小标题（只有连续叙述）时：正文首句 → 标签 → 关键词
        var body = "这是一句足够长的开头句子用来说明主题。后面还有内容但都是句子。";
        var plan = ToutiaoImageHelper.cardPlan(List.of("标题"), body, List.of("效率", "工具"),
            "资讯综述", "远程办公", 5);

        assertThat(plan).hasSize(5);
        assertThat(plan.get(1).title()).isEqualTo("这是一句足够长的开头句子用来说明主题。");
        assertThat(plan.get(2).title()).isEqualTo("效率 · 工具");
        assertThat(plan.get(3).title()).isEqualTo("远程办公");
        // 素材用尽：仍按张数补齐（同文案换配色），不会少于下限
        assertThat(plan.get(4).title()).isEqualTo("远程办公");
        assertThat(plan.get(4).categoryKey()).isNotEqualTo(plan.get(3).categoryKey());
    }

    @Test
    void cardPlanShouldNeverGoBelowThree() {
        var plan = ToutiaoImageHelper.cardPlan(List.of(), "", List.of(), "", "关键词", 1);

        assertThat(plan).hasSize(ToutiaoImageHelper.MIN_IMAGES);
        assertThat(plan.get(0).title()).isEqualTo("关键词");
    }

    @Test
    void cardPlanShouldClipLongTitles() {
        var longTitle = "标".repeat(60);
        var plan = ToutiaoImageHelper.cardPlan(List.of(longTitle), "", List.of(), "风格", "词", 3);

        assertThat(plan.get(0).title()).hasSizeLessThanOrEqualTo(36);
        assertThat(plan.get(0).title()).endsWith("…");
    }

    @Test
    void extractHeadingsShouldIgnoreSentencesAndListMarkers() {
        var body = """
            1、先把工具装好
            这是一句普通的正文，末尾有句号。
            第二步：配置参数
            短
            """;

        var headings = ToutiaoImageHelper.extractHeadings(body);

        assertThat(headings).containsExactly("先把工具装好", "第二步：配置参数");
    }

    @Test
    void mediaTypeOfShouldSniffCommonFormats() {
        assertThat(ToutiaoImageHelper.mediaTypeOf(pngBytes())).isEqualTo("image/png");
        assertThat(ToutiaoImageHelper.mediaTypeOf(jpegBytes())).isEqualTo("image/jpeg");
        assertThat(ToutiaoImageHelper.mediaTypeOf("RIFF____WEBPVP8 ".getBytes()))
            .isEqualTo("image/webp");
        // 认不出来时按 JPEG 处理（与附件上传器自己的兜底口径一致）
        assertThat(ToutiaoImageHelper.mediaTypeOf("unknown-bytes".getBytes()))
            .isEqualTo("image/jpeg");
        assertThat(ToutiaoImageHelper.mediaTypeOf(null)).isEqualTo("image/jpeg");
    }

    private static byte[] pngBytes() {
        var bytes = new byte[16];
        bytes[0] = (byte) 0x89;
        bytes[1] = 'P';
        bytes[2] = 'N';
        bytes[3] = 'G';
        return bytes;
    }

    private static byte[] jpegBytes() {
        var bytes = new byte[16];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }
}
