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
import java.util.regex.Pattern;

/**
 * 从 DeepSeek 官方价目表页读取单价。
 *
 * <p>为什么要读官网而不是只靠内置表：官方<b>不提供价格 API</b>（只有余额接口），价格却会变；
 * 站长不该为了「价格涨了」等一次插件发版，也不该手工去抄一份。所以这里读那份文档页面。</p>
 *
 * <p><b>解析失败的兜底是明确的</b>：页面改版、网络不通都返回失败，调用方继续用内置价目表，
 * 面板上说明「本次没读到官网价」。绝不猜数字——估错价格比暂时用旧价更糟。</p>
 */
@Slf4j
@Component
public class DigestAiPriceFetcher {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /**
     * 官方价目表地址（<b>结尾的斜杠不能省</b>）。
     *
     * <p>不带斜杠时站点会回 302 跳到带斜杠的地址；虽然下面也跟重定向，
     * 但少一次往返、少一个失败点。</p>
     */
    private static final String PRIMARY_URL = DigestAiPricing.OFFICIAL_PRICING_URL;

    /** 中文页彻底不可达时的候补：英文页（价格是 USD，解析会按 $ 标记币种） */
    private static final String FALLBACK_URL =
        "https://api-docs.deepseek.com/quick_start/pricing/";

    /** 最多跟几次跳转：官方只是补斜杠，正常一次就够；设个上限免得互相跳形成环 */
    private static final int MAX_REDIRECTS = 3;

    private static final int MAX_IN_MEMORY = 4 * 1024 * 1024;

    private static final Pattern CELL = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL);

    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    /** 脚注标记 &lt;sup&gt;(1)&lt;/sup&gt;：不去掉会混进模型名（deepseek-flash(1) 就匹配不上了） */
    private static final Pattern FOOTNOTE = Pattern.compile("<sup>.*?</sup>", Pattern.DOTALL);

    private static final Pattern AMOUNT = Pattern.compile("[¥￥$]?\\s*(\\d+(?:\\.\\d+)?)\\s*元?");

    private static final String INPUT_HIT = "缓存命中";

    private static final String INPUT_MISS = "缓存未命中";

    private static final String OUTPUT = "输出";

    private static final String OFF_PEAK = "空闲";

    private static final String PEAK = "高峰";

    private final WebClient client = WebClient.builder()
        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY))
        .build();

    /** 读官网价目表；失败时给出可读原因（面板直接显示给站长看） */
    public Mono<List<DigestAiPriceDTO>> fetchOfficial() {
        return fetch(PRIMARY_URL, 0)
            .onErrorResume(ex -> {
                log.info("[UniHalo][Digest] 中文价目表读取失败，改用英文页重试：{}", ex.getMessage());
                // 候补页的价格是 USD：仍然可用（面板会显示币种），总比彻底读不到强
                return fetch(FALLBACK_URL, 0)
                    .doOnNext(prices -> log.warn("[UniHalo][Digest] 单价取自英文价目表"
                        + "（币种 {}），中文页当时不可达", prices.isEmpty() ? "未知"
                        : prices.get(0).currency()));
            })
            .onErrorResume(ex -> Mono.error(new IllegalStateException(
                "读取官网单价失败：" + ex.getMessage(), ex)));
    }

    /**
     * 抓一次页面，遇到跳转就跟过去。
     *
     * <p>WebClient 默认<b>不</b>跟重定向，而官方站点在缺末尾斜杠时会 302——这就是
     * 「返回 HTTP 302」那条报错的来源。这里显式跟，并把每一次跳转写进日志，
     * 免得将来站点换了地址只有一句「失败」可看。</p>
     */
    private Mono<List<DigestAiPriceDTO>> fetch(String url, int hop) {
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
                            return fetch(next, hop + 1);
                        }
                        if (status < 200 || status >= 300) {
                            return Mono.error(new IllegalStateException("官网价目表返回 HTTP "
                                + status + "（" + url + "）"));
                        }
                        var prices = parsePricingHtml(html);
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
