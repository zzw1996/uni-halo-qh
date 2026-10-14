package pro.uhalo.uni.services.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import pro.uhalo.uni.dto.DigestAiPriceDTO;
import pro.uhalo.uni.utils.DigestAiPricing;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 从官方价目表页读取单价（DeepSeek 与智谱两家）。
 *
 * <p>为什么要读官网而不是只靠内置表：两家<b>都不提供价格 API</b>（DeepSeek 只有余额接口，
 * 智谱连余额都只能在控制台看），价格却会变；站长不该为了「价格涨了」等一次插件发版，
 * 也不该手工去抄一份。所以这里读那两份文档页面。</p>
 *
 * <p><b>解析失败的兜底是明确的</b>：页面改版、网络不通都算这一家失败，调用方继续用
 * 内置价目表，面板上说明「本次没读到官网价」。绝不猜数字——估错价格比暂时用旧价更糟。
 * 两家都读不到才整次失败。</p>
 *
 * <p>两家的表格形状不一样：DeepSeek 用 {@code <td colspan>} 拼出来的竖表（价格名只在
 * 每组第一行出现，还要区分高峰/空闲两行），智谱是一张横表（表头 {@code <th>}，
 * 一行一个模型，分档的模型同名出现多次）。所以解析器分开写，只共用抓页面这一段。</p>
 */
@Slf4j
@Component
public class DigestAiPriceFetcher {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /**
     * DeepSeek 官方价目表地址（<b>结尾的斜杠不能省</b>）。
     *
     * <p>不带斜杠时站点会回 302 跳到带斜杠的地址；虽然下面也跟重定向，
     * 但少一次往返、少一个失败点。</p>
     */
    private static final String PRIMARY_URL = DigestAiPricing.OFFICIAL_PRICING_URL;

    /** 中文页彻底不可达时的候补：英文页（价格是 USD，解析会按 $ 标记币种） */
    private static final String FALLBACK_URL =
        "https://api-docs.deepseek.com/quick_start/pricing/";

    /** 智谱官方价目表页（GLM 按量计费的唯一权威来源，官方同样没有价格 API） */
    private static final String ZHIPU_URL = DigestAiPricing.ZHIPU_PRICING_URL;

    /** 最多跟几次跳转：官方只是补斜杠，正常一次就够；设个上限免得互相跳形成环 */
    private static final int MAX_REDIRECTS = 3;

    private static final int MAX_IN_MEMORY = 4 * 1024 * 1024;

    private static final Pattern CELL = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL);

    /** 智谱那张横表的表头是 {@code <th>}：只认 td 会连「哪一列是输入价」都找不到 */
    private static final Pattern GRID_CELL =
        Pattern.compile("<t[hd][^>]*>(.*?)</t[hd]>", Pattern.DOTALL);

    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    /** 脚注标记 &lt;sup&gt;(1)&lt;/sup&gt;：不去掉会混进模型名（deepseek-flash(1) 就匹配不上了） */
    private static final Pattern FOOTNOTE = Pattern.compile("<sup>.*?</sup>", Pattern.DOTALL);

    private static final Pattern AMOUNT = Pattern.compile("[¥￥$]?\\s*(\\d+(?:\\.\\d+)?)\\s*元?");

    private static final String INPUT_HIT = "缓存命中";

    private static final String INPUT_MISS = "缓存未命中";

    private static final String OUTPUT = "输出";

    private static final String OFF_PEAK = "空闲";

    private static final String PEAK = "高峰";

    /** 智谱价目表里的两种非数字价格：「免费」是真 0，「不支持」是没有缓存折扣 */
    private static final String FREE = "免费";

    private static final String NOT_SUPPORTED = "不支持";

    /** 智谱表的表头文字（用于按列名定位，而不是按列号——官方加一列就不准了） */
    private static final String ZHIPU_HEADER = "模型名称";

    /** 这份文档页只报人民币价（Z.ai 海外站是另一份 USD 文档，不在这里读） */
    private static final String ZHIPU_CURRENCY = "CNY";

    /** GLM 不分时段：比例必须是 1，不能走 {@link DigestAiPriceDTO} 的 0.5 默认值 */
    private static final BigDecimal NO_PEAK_RATIO = BigDecimal.ONE;

    private final WebClient client = WebClient.builder()
        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY))
        .build();

    /**
     * 一次多来源刷新的结果。
     *
     * @param prices 两家读到的单价合并（顺序：DeepSeek 在前）
     * @param failures 哪一家这次没读到（面板/日志里说明，不影响另一家入库）
     */
    public record Sources(List<DigestAiPriceDTO> prices, List<String> failures) {
    }

    /**
     * 读所有官方价目表（DeepSeek + 智谱），单边失败不拖垮整次刷新。
     *
     * <p>一次点击把两家的价都刷新的理由是：价格表是<b>一张混合表</b>（站长可能同时在用
     * DeepSeek 和 GLM），只刷一家会让另一行的「读取于」看起来像全都新。</p>
     *
     * @throws IllegalStateException 两家都没读到时（调用方回退内置表）
     */
    public Mono<Sources> fetchAllSources() {
        return Mono.zip(attempt("DeepSeek", fetchDeepSeek()), attempt("智谱", fetchZhipu()))
            .map(pair -> {
                var prices = new ArrayList<DigestAiPriceDTO>();
                prices.addAll(pair.getT1().prices());
                prices.addAll(pair.getT2().prices());
                var failures = new ArrayList<String>();
                if (pair.getT1().failure() != null) {
                    failures.add(pair.getT1().failure());
                }
                if (pair.getT2().failure() != null) {
                    failures.add(pair.getT2().failure());
                }
                return new Sources(List.copyOf(prices), List.copyOf(failures));
            })
            .flatMap(sources -> sources.prices().isEmpty()
                ? Mono.error(new IllegalStateException("读取官网单价失败："
                    + String.join("；", sources.failures())))
                : Mono.just(sources));
    }

    /** 抓一家并把失败转成「这一家没读到」，而不是让整次刷新报错 */
    private Mono<Attempt> attempt(String vendor, Mono<List<DigestAiPriceDTO>> prices) {
        return prices
            .map(list -> new Attempt(list, null))
            .onErrorResume(ex -> {
                var reason = vendor + "：" + (ex.getMessage() == null
                    ? ex.getClass().getSimpleName() : ex.getMessage());
                log.warn("[UniHalo][Digest] {} 官网单价这次没读到，这一部分继续用表里的旧价：{}",
                    vendor, ex.getMessage());
                return Mono.just(new Attempt(List.of(), reason));
            });
    }

    private record Attempt(List<DigestAiPriceDTO> prices, String failure) {
    }

    /** 读 DeepSeek 价目表；失败时给出可读原因（面板直接显示给站长看） */
    public Mono<List<DigestAiPriceDTO>> fetchDeepSeek() {
        return fetch(PRIMARY_URL, 0, DigestAiPriceFetcher::parsePricingHtml)
            .onErrorResume(ex -> {
                log.info("[UniHalo][Digest] 中文价目表读取失败，改用英文页重试：{}", ex.getMessage());
                // 候补页的价格是 USD：仍然可用（面板会显示币种），总比彻底读不到强
                return fetch(FALLBACK_URL, 0, DigestAiPriceFetcher::parsePricingHtml)
                    .doOnNext(prices -> log.warn("[UniHalo][Digest] 单价取自英文价目表"
                        + "（币种 {}），中文页当时不可达", prices.isEmpty() ? "未知"
                        : prices.get(0).currency()));
            })
            .onErrorResume(ex -> Mono.error(new IllegalStateException(
                "读取官网单价失败：" + ex.getMessage(), ex)));
    }

    /** 读智谱 GLM 价目表 */
    public Mono<List<DigestAiPriceDTO>> fetchZhipu() {
        return fetch(ZHIPU_URL, 0, DigestAiPriceFetcher::parseZhipuPricingHtml)
            .onErrorResume(ex -> Mono.error(new IllegalStateException(
                "读取智谱单价失败：" + ex.getMessage(), ex)));
    }

    /**
     * 抓一次页面，遇到跳转就跟过去。
     *
     * <p>WebClient 默认<b>不</b>跟重定向，而官方站点在缺末尾斜杠时会 302——这就是
     * 「返回 HTTP 302」那条报错的来源。这里显式跟，并把每一次跳转写进日志，
     * 免得将来站点换了地址只有一句「失败」可看。</p>
     */
    private Mono<List<DigestAiPriceDTO>> fetch(String url, int hop,
        Function<String, List<DigestAiPriceDTO>> parser) {
        return client.get()
            .uri(url)
            .header(HttpHeaders.USER_AGENT, "uni-halo-plugin")
            .accept(MediaType.TEXT_HTML)
            .exchangeToMono(response -> {
                var status = response.statusCode().value();
                var location = response.headers().header(HttpHeaders.LOCATION).stream()
                    .findFirst().orElse("");
                return response.bodyToMono(String.class).defaultIfEmpty("")
                    .flatMap(html -> {
                        if (isRedirect(status)) {
                            if (location.isBlank()) {
                                return Mono.error(new IllegalStateException(
                                    "官网返回 HTTP " + status + " 但没有跳转地址"));
                            }
                            if (hop >= MAX_REDIRECTS) {
                                return Mono.error(new IllegalStateException(
                                    "官网跳转次数超过 " + MAX_REDIRECTS + " 次，放弃：" + url));
                            }
                            var next = resolveLocation(url, location);
                            log.info("[UniHalo][Digest] 官网价目表跳转（HTTP {}）：{} → {}",
                                status, url, next);
                            return fetch(next, hop + 1, parser);
                        }
                        if (status < 200 || status >= 300) {
                            return Mono.error(new IllegalStateException("官网价目表返回 HTTP "
                                + status + "（" + url + "）"));
                        }
                        var prices = parser.apply(html);
                        if (prices.isEmpty()) {
                            return Mono.error(new IllegalStateException("没能从官网页面里认出价目表"
                                + "（页面结构可能变了；可继续用内置价目表，或手动填写）"));
                        }
                        return Mono.just(prices);
                    });
            })
            .timeout(TIMEOUT)
            .doOnNext(prices -> log.info("[UniHalo][Digest] 已从官网读取 {} 个模型的单价：{}",
                prices.size(), prices.stream().map(DigestAiPriceDTO::model).toList()));
    }

    /** 3xx 一律按跳转处理：官方用的是 302，但代理/网关可能给 301/307/308 */
    static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * 把 {@code Location} 解析成绝对地址。
     *
     * <p>官方的 Location 是相对路径（{@code /zh-cn/quick_start/pricing/}），
     * 光看这一条就会踩坑：直接拿它当 URL 请求必然失败。四种形态都要认：
     * 绝对地址、协议相对（{@code //host/path}）、根相对（{@code /path}）、路径相对（{@code ./x}）。</p>
     */
    static String resolveLocation(String baseUrl, String location) {
        if (location == null || location.isBlank()) {
            return baseUrl;
        }
        var target = location.trim();
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return target;
        }
        try {
            var base = java.net.URI.create(baseUrl);
            if (target.startsWith("//")) {
                return base.getScheme() + ":" + target;
            }
            var resolved = base.resolve(target);
            return resolved.toString();
        } catch (Exception ex) {
            // 连 URL 都解析不了时原样返回，让上层给出「无法解析」的报错
            return target;
        }
    }

    /**
     * 解析价目表页面。
     *
     * <p>只依赖页面上的<b>语义标签</b>（模型名行、缓存命中/未命中、输出、空闲/高峰、元/符号），
     * 不依赖具体数字与单元格顺序：官方改价、加说明都不会让它失效，页面结构大改时返回空表，
     * 由调用方回退到内置价目表。</p>
     *
     * <p>表格用了 rowspan：价格名只在每组第一行出现（「百万tokens输入（缓存命中）」），
     * 下一行（高峰时段）没有标签，所以要在行之间记住上一个标签。</p>
     */
    static List<DigestAiPriceDTO> parsePricingHtml(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        var modelNames = new ArrayList<String>();
        Map<String, Map<String, String>> peak = new LinkedHashMap<>();
        Map<String, Map<String, String>> offPeak = new LinkedHashMap<>();
        var currency = "CNY";
        String lastKind = null;

        for (var row : html.split("<tr")) {
            var cells = cellsOf(row);
            if (cells.isEmpty()) {
                continue;
            }
            // 表头行：模型名在这一行，后面所有价格都按列顺序对齐
            if (modelNames.isEmpty() && isModelRow(cells)) {
                for (var index = 1; index < cells.size(); index++) {
                    var name = cells.get(index);
                    if (!name.isBlank() && !name.contains("版本")) {
                        modelNames.add(name);
                    }
                }
                for (var name : modelNames) {
                    peak.put(name, new LinkedHashMap<>());
                    offPeak.put(name, new LinkedHashMap<>());
                }
                continue;
            }
            if (modelNames.isEmpty()) {
                continue;
            }
            var kind = kindOf(cells);
            if (kind != null) {
                lastKind = kind;
            } else {
                kind = lastKind;
            }
            if (kind == null) {
                continue;
            }
            var offPeakRow = row.contains(OFF_PEAK);
            var peakRow = !offPeakRow && row.contains(PEAK);
            if (!offPeakRow && !peakRow) {
                continue;
            }
            var values = new ArrayList<String>();
            for (var cell : cells) {
                if (isAmount(cell)) {
                    values.add(cell);
                    if (cell.contains("$")) {
                        currency = "USD";
                    }
                }
            }
            var target = offPeakRow ? offPeak : peak;
            for (var index = 0; index < modelNames.size() && index < values.size(); index++) {
                target.get(modelNames.get(index)).put(kind, values.get(index));
            }
        }

        var result = new ArrayList<DigestAiPriceDTO>();
        for (var name : modelNames) {
            var peakPrices = peak.get(name);
            var offPrices = offPeak.get(name);
            if (peakPrices == null || peakPrices.isEmpty()) {
                continue;
            }
            result.add(new DigestAiPriceDTO(name, currency, decimal(peakPrices.get(INPUT_HIT)),
                decimal(peakPrices.get(INPUT_MISS)), decimal(peakPrices.get(OUTPUT)),
                ratioOf(peakPrices, offPrices)));
        }
        return List.copyOf(result);
    }

    /**
     * 解析智谱 GLM 的价目表页（横表：表头 {@code <th>}，一行一个模型）。
     *
     * <p>三处和 DeepSeek 不一样的地方，都来自这页的实际形态：</p>
     * <ul>
     *   <li><b>列按表头认</b>：同一份文档里有的表带「输入模态」列、有的不带，
     *       按列号取会静默拿错价；</li>
     *   <li><b>分档取最低档</b>：一个模型会按「输入长度 [0,32K) / ≥32K」出现好几行，
     *       日报的单篇提示词都在低档，所以取第一行；跨档时会低估，价格说明里写明了；</li>
     *   <li><b>没有峰谷两行</b>：GLM 不分时段，比例填 1——留空会走 DeepSeek 的 0.5，
     *       凭空把费用砍一半。</li>
     * </ul>
     *
     * <p>「免费」按 0 计（显示 ¥0.00 比显示「未配置单价」准确）；缓存命中写「不支持」的
     * 模型按未命中价计，因为这类模型本来就不会给出命中 token，留空会让那部分白算成 0 元。</p>
     */
    static List<DigestAiPriceDTO> parseZhipuPricingHtml(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        int inputCol = -1;
        int outputCol = -1;
        int hitCol = -1;
        var taken = new java.util.HashSet<String>();
        var result = new ArrayList<DigestAiPriceDTO>();
        for (var row : html.split("<tr")) {
            var cells = gridCellsOf(row);
            if (cells.isEmpty()) {
                continue;
            }
            if (ZHIPU_HEADER.equals(cells.get(0))) {
                // 换一张表就重新认列：私有化「刊例价」表的表头里也有「模型名称」，
                // 沿用上表的列号会把「数千万元」之类的当单价
                inputCol = -1;
                outputCol = -1;
                hitCol = -1;
                for (var index = 0; index < cells.size(); index++) {
                    var header = cells.get(index);
                    if (header.contains(INPUT_HIT)) {
                        hitCol = index;
                    } else if (header.contains("单价") && header.contains("输入")) {
                        inputCol = index;
                    } else if (header.contains("单价") && header.contains(OUTPUT)) {
                        outputCol = index;
                    }
                }
                continue;
            }
            if (inputCol < 0 || outputCol < 0
                || cells.size() <= Math.max(inputCol, outputCol)) {
                continue;
            }
            var model = cells.get(0);
            if (!isModelId(model) || !taken.add(model)) {
                continue;
            }
            var cacheMiss = zhipuAmount(cells.get(inputCol));
            var output = zhipuAmount(cells.get(outputCol));
            var hitCell = hitCol >= 0 && hitCol < cells.size() ? cells.get(hitCol) : "";
            var cacheHit = hitCell.contains(NOT_SUPPORTED)
                ? cacheMiss : zhipuAmount(hitCell);
            result.add(new DigestAiPriceDTO(model, ZHIPU_CURRENCY, cacheHit, cacheMiss, output,
                NO_PEAK_RATIO));
        }
        return List.copyOf(result);
    }

    /** 模型名列的判断：小写字母/数字/点/横线，且不含空格（含空白的第一格是分组说明不是模型） */
    private static boolean isModelId(String cell) {
        return !cell.isBlank() && cell.length() <= 48 && cell.matches("[a-z0-9.\\-_]+");
    }

    /** 价格格 → 数字：「免费」是 0，认不出数字返回 null（由调用方留空，不猜） */
    private static BigDecimal zhipuAmount(String cell) {
        if (cell == null || cell.isBlank()) {
            return null;
        }
        if (cell.contains(FREE)) {
            return BigDecimal.ZERO;
        }
        var matcher = AMOUNT.matcher(cell);
        return matcher.matches() ? new BigDecimal(matcher.group(1)) : null;
    }

    /** 横表用的一格取文本（表头是 {@code <th>}，正文是 {@code <td>}） */
    private static List<String> gridCellsOf(String row) {
        var cells = new ArrayList<String>();
        var matcher = GRID_CELL.matcher(row);
        while (matcher.find()) {
            cells.add(clean(matcher.group(1)));
        }
        return cells;
    }

    /** 表头行：第一格写「模型」，后面是模型名 */
    private static boolean isModelRow(List<String> cells) {
        return cells.size() >= 2 && "模型".equals(cells.get(0));
    }

    /** 这一行说的是哪种价格；没有标签（rowspan 续行）时返回 null，由调用方沿用上一行的 */
    private static String kindOf(List<String> cells) {
        for (var cell : cells) {
            if (cell.contains(INPUT_HIT)) {
                return INPUT_HIT;
            }
            if (cell.contains(INPUT_MISS)) {
                return INPUT_MISS;
            }
            if (cell.contains(OUTPUT)) {
                return OUTPUT;
            }
            // 「百万tokens输入」不带括号说明时按未命中算（价目表里未命中是主价）
            if (cell.contains("输入")) {
                return INPUT_MISS;
            }
        }
        return null;
    }

    /** 空闲/高峰价之比（官方口径 0.5）：从页面上的数字算，而不是写死 */
    private static BigDecimal ratioOf(Map<String, String> peakPrices,
        Map<String, String> offPrices) {
        if (offPrices == null) {
            return null;
        }
        for (var kind : List.of(INPUT_MISS, INPUT_HIT, OUTPUT)) {
            var peakValue = decimal(peakPrices.get(kind));
            var offValue = decimal(offPrices.get(kind));
            if (peakValue != null && offValue != null && peakValue.signum() > 0) {
                return offValue.divide(peakValue, 4, RoundingMode.HALF_UP).stripTrailingZeros();
            }
        }
        return null;
    }

    private static List<String> cellsOf(String row) {
        var cells = new ArrayList<String>();
        var matcher = CELL.matcher(row);
        while (matcher.find()) {
            cells.add(clean(matcher.group(1)));
        }
        return cells;
    }

    private static boolean isAmount(String cell) {
        if (cell.isBlank() || cell.length() > 24) {
            return false;
        }
        return AMOUNT.matcher(cell).matches() && cell.matches(".*\\d.*");
    }

    private static BigDecimal decimal(String cell) {
        if (cell == null) {
            return null;
        }
        var matcher = AMOUNT.matcher(cell);
        return matcher.find() ? new BigDecimal(matcher.group(1)) : null;
    }

    /** 去脚注、去标签、压空白、转小写：模型名与关键词都靠这层归一化 */
    private static String clean(String html) {
        return TAG.matcher(FOOTNOTE.matcher(html).replaceAll("")).replaceAll(" ")
            .replace("&nbsp;", " ")
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase(Locale.ROOT);
    }
}
