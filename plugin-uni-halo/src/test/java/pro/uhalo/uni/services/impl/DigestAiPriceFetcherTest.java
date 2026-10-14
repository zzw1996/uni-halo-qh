package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.DigestAiPriceDTO;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 官网价目表解析：这是「不用站长手填价格」的关键一步，所以拿真实的页面片段做夹具。
 *
 * <p>夹具抄自 {@code api-docs.deepseek.com/zh-cn/quick_start/pricing} 的表格结构，
 * 保留了它的两个坑：① 模型名带脚注 {@code <sup>(1)</sup>}；
 * ② 价格用 rowspan，「高峰时段」那一行没有价格标签（要靠上一行记住）。</p>
 */
class DigestAiPriceFetcherTest {

    /** 真实结构节选：模型名行 + 三组「空闲/高峰」价格行（rowspan 形态与线上一致） */
    private static final String FIXTURE = """
        <table style="text-align:center">
        <tr><td colspan="3" style="text-align:center">模型</td><td>deepseek-flash<sup>(1)</sup></td><td>deepseek-v4-pro</td></tr>
        <tr><td colspan="3" style="text-align:center">模型版本</td><td>DeepSeek-V4.1-Flash</td><td>DeepSeek-V4-Pro-0813</td></tr>
        <tr><td colspan="3">上下文长度</td><td colspan="2">1M</td></tr>
        <tr><td rowspan="6">价格<sup>(2)</sup></td><td rowspan="2">百万tokens输入<br>（缓存命中）</td><td>空闲时段</td><td>0.02元</td><td>0.15元</td></tr>
        <tr><td>高峰时段</td><td>0.04元</td><td>0.30元</td></tr>
        <tr><td rowspan="2">百万tokens输入<br>（缓存未命中）</td><td>空闲时段</td><td>1元</td><td>4.5元</td></tr>
        <tr><td>高峰时段</td><td>2元</td><td>9.0元</td></tr>
        <tr><td rowspan="2">百万tokens输出</td><td>空闲时段</td><td>4元</td><td>13.5元</td></tr>
        <tr><td>高峰时段</td><td>8元</td><td>27.0元</td></tr>
        <tr><td colspan="3">并发限制<sup>(3)</sup></td><td>2500</td><td>500</td></tr>
        </table>
        """;

    @Test
    void shouldParseBothModelsWithPeakPrices() {
        var prices = DigestAiPriceFetcher.parsePricingHtml(FIXTURE);

        assertThat(prices).hasSize(2);
        var flash = prices.get(0);
        // 模型名里的脚注标记必须被去掉，否则匹配串会变成 deepseek-flash(1)
        assertThat(flash.model()).isEqualTo("deepseek-flash");
        assertThat(flash.currency()).isEqualTo("CNY");
        // 取高峰价（主价），空闲价用折扣表达
        assertThat(flash.cacheHit()).isEqualByComparingTo(new BigDecimal("0.04"));
        assertThat(flash.cacheMiss()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(flash.output()).isEqualByComparingTo(new BigDecimal("8"));

        var pro = prices.get(1);
        assertThat(pro.model()).isEqualTo("deepseek-v4-pro");
        assertThat(pro.output()).isEqualByComparingTo(new BigDecimal("27.0"));
    }

    @Test
    void shouldDeriveOffPeakRatioFromThePage() {
        var flash = DigestAiPriceFetcher.parsePricingHtml(FIXTURE).get(0);

        // 官方口径「空闲 = 高峰的一半」：折扣从页面数字算出来，不是写死的
        assertThat(flash.offPeakRatio()).isEqualByComparingTo(new BigDecimal("0.5"));
        // 空闲价确实按一半折算
        assertThat(flash.priceAt(flash.output(), false)).isEqualByComparingTo(new BigDecimal("4"));
    }

    @Test
    void shouldSurvivePageStructureChanges() {
        // 页面改版 / 只抓到半截 HTML：返回空表交给调用方回退，绝不猜数字
        assertThat(DigestAiPriceFetcher.parsePricingHtml("")).isEmpty();
        assertThat(DigestAiPriceFetcher.parsePricingHtml("<html><body>404</body></html>")).isEmpty();
        assertThat(DigestAiPriceFetcher.parsePricingHtml(
            "<table><tr><td>模型</td><td>deepseek-flash</td></tr></table>")).isEmpty();
    }

    @Test
    void redirectsShouldBeRecognizedAndResolved() {
        // 官方在缺末尾斜杠时回 302：WebClient 默认不跟重定向，所以要自己认、
        // 自己把相对 Location 解析成绝对地址（否则就会报「返回 HTTP 302」）
        assertThat(DigestAiPriceFetcher.isRedirect(302)).isTrue();
        assertThat(DigestAiPriceFetcher.isRedirect(301)).isTrue();
        assertThat(DigestAiPriceFetcher.isRedirect(308)).isTrue();
        assertThat(DigestAiPriceFetcher.isRedirect(200)).isFalse();
        assertThat(DigestAiPriceFetcher.isRedirect(404)).isFalse();

        var base = "https://api-docs.deepseek.com/zh-cn/quick_start/pricing";
        // 线上真实的 Location 就是这种根相对路径
        assertThat(DigestAiPriceFetcher.resolveLocation(base, "/zh-cn/quick_start/pricing/"))
            .isEqualTo("https://api-docs.deepseek.com/zh-cn/quick_start/pricing/");
        // 绝对地址、协议相对、同目录相对
        assertThat(DigestAiPriceFetcher.resolveLocation(base, "https://example.com/x"))
            .isEqualTo("https://example.com/x");
        assertThat(DigestAiPriceFetcher.resolveLocation(base, "//cdn.example.com/x"))
            .isEqualTo("https://cdn.example.com/x");
        assertThat(DigestAiPriceFetcher.resolveLocation(base, "index.html"))
            .isEqualTo("https://api-docs.deepseek.com/zh-cn/quick_start/index.html");
    }

    @Test
    void shouldReadUsdPricesFromTheEnglishPage() {
        // 英文页用 $ 报数：币种要跟着变，否则人民币站点会按美元显示
        var usd = FIXTURE.replace("0.04元", "$0.006").replace("0.02元", "$0.003");
        var prices = DigestAiPriceFetcher.parsePricingHtml(usd);

        assertThat(prices.get(0).currency()).isEqualTo("USD");
        assertThat(prices.get(0).cacheHit()).isEqualByComparingTo(new BigDecimal("0.006"));
    }

    // ── 智谱价目表（横表：表头 <th>，一行一个模型，形态和 DeepSeek 那张完全不一样）──

    /**
     * 抄自 {@code docs.bigmodel.cn/cn/guide/start/pricing} 的实际形态：
     * 表头带「输入模态」列、分档的模型（GLM-5.1）出现两行、脚注、
     * 「免费」与「不支持」两种非数字价格，末尾还跟一张「刊例价」表（表头第一格也叫模型名称）。
     */
    private static final String ZHIPU_FIXTURE = """
        <table><thead>
        <tr><th>模型名称</th><th>上下文</th><th>输入单价（元/百万 Tokens）</th>
            <th>输出单价（元/百万 Tokens）</th><th>缓存存储（元/百万 Tokens/小时）</th>
            <th>缓存命中（元/百万 Tokens）</th><th>输入模态</th></tr></thead>
        <tbody>
        <tr><td>GLM-5.3<sup>(1)</sup></td><td>1M</td><td data-number="8">8</td><td>28</td>
            <td>限时免费</td><td>2</td><td>文本</td></tr>
        <tr><td>GLM-5.3-Flash</td><td>1M</td><td>0.8</td><td>2.8</td>
            <td>限时免费</td><td>0.23</td><td>图片、视频、文件、文本</td></tr>
        <tr><td>GLM-5.1</td><td>输入长度 [0, 32K)</td><td>6</td><td>24</td>
            <td>限时免费</td><td>1.3</td></tr>
        <tr><td>GLM-5.1</td><td>输入长度 ≥32K</td><td>8</td><td>28</td>
            <td>限时免费</td><td>2</td></tr>
        <tr><td>GLM-4.7-Flash</td><td>200K</td><td>免费</td><td>免费</td>
            <td>限时免费</td><td>免费</td></tr>
        <tr><td>GLM-4-AirX</td><td>8K</td><td>10</td><td>10</td>
            <td>限时免费</td><td>不支持</td></tr>
        </tbody></table>
        <table><thead><tr><th>模型名称</th><th>类型</th><th>刊例价</th></tr></thead>
        <tbody><tr><td>GLM-4-0520</td><td>语言模型</td><td>数千万元</td></tr></tbody></table>
        """;

    @Test
    void zhipuRowsShouldReadByColumnName() {
        var prices = DigestAiPriceFetcher.parseZhipuPricingHtml(ZHIPU_FIXTURE);

        var flash = byModel(prices, "glm-5.3-flash");
        // 表头多一列「输入模态」不影响：列是按表头文字认的，不是按列号
        assertThat(flash.cacheMiss()).isEqualByComparingTo(new BigDecimal("0.8"));
        assertThat(flash.output()).isEqualByComparingTo(new BigDecimal("2.8"));
        assertThat(flash.cacheHit()).isEqualByComparingTo(new BigDecimal("0.23"));
        assertThat(flash.currency()).isEqualTo("CNY");
        // 脚注必须去掉，否则匹配串成了 glm-5.3(1)
        assertThat(byModel(prices, "glm-5.3").cacheMiss()).isEqualByComparingTo("8");
    }

    @Test
    void zhipuModelsShouldCarryNoPeakDiscount() {
        var flash = byModel(DigestAiPriceFetcher.parseZhipuPricingHtml(ZHIPU_FIXTURE),
            "glm-5.3-flash");

        // 智谱没有峰谷差价：比例必须是 1，否则 DeepSeek 的 0.5 默认值会凭空砍一半
        assertThat(flash.offPeakRatio()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(flash.priceAt(flash.output(), false))
            .isEqualByComparingTo(flash.priceAt(flash.output(), true));
        assertThat(flash.describe()).contains("不分时段");
    }

    @Test
    void tieredZhipuModelShouldKeepTheLowestTier() {
        var prices = DigestAiPriceFetcher.parseZhipuPricingHtml(ZHIPU_FIXTURE);

        // GLM-5.1 在页面上是两行（[0,32K) 与 ≥32K）：只留第一行，且同名不能出现两条
        var tiers = prices.stream().filter(price -> "glm-5.1".equals(price.model())).toList();
        assertThat(tiers).hasSize(1);
        assertThat(tiers.get(0).cacheMiss()).isEqualByComparingTo(new BigDecimal("6"));
        assertThat(tiers.get(0).output()).isEqualByComparingTo(new BigDecimal("24"));
    }

    @Test
    void freeAndUncachedZhipuModelsShouldStillBePriceable() {
        var prices = DigestAiPriceFetcher.parseZhipuPricingHtml(ZHIPU_FIXTURE);

        // 「免费」是真 0：显示 ¥0.00 比显示「未配置单价」准确
        var free = byModel(prices, "glm-4.7-flash");
        assertThat(free.usable()).isTrue();
        assertThat(free.cacheMiss()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(free.output()).isEqualByComparingTo(BigDecimal.ZERO);

        // 「不支持」缓存 = 没有折扣，命中部分按未命中价算（留空会把那部分算成 0 元）
        var airx = byModel(prices, "glm-4-airx");
        assertThat(airx.cacheHit()).isEqualByComparingTo(airx.cacheMiss());
    }

    @Test
    void zhipuParserShouldIgnoreTablesWithoutPriceColumns() {
        var prices = DigestAiPriceFetcher.parseZhipuPricingHtml(ZHIPU_FIXTURE);

        // 「刊例价」那张表的表头第一格也叫「模型名称」：换表必须重新认列，
        // 否则「数千万元」会被当成单价，一条私有化报价能把费用算到离谱
        assertThat(prices).noneMatch(price -> price.model().contains("0520"));
        assertThat(prices).hasSize(5);

        assertThat(DigestAiPriceFetcher.parseZhipuPricingHtml("")).isEmpty();
        assertThat(DigestAiPriceFetcher.parseZhipuPricingHtml(
            "<html><body>404</body></html>")).isEmpty();
    }

    private static DigestAiPriceDTO byModel(List<DigestAiPriceDTO> prices, String model) {
        return prices.stream().filter(price -> model.equals(price.model())).findFirst()
            .orElseThrow(() -> new AssertionError("没解析出 " + model));
    }
}
