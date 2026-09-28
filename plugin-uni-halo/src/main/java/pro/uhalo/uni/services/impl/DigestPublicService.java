package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestArticleEntryDTO;
import pro.uhalo.uni.dto.DigestCategoryDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.dto.DigestPostResultDTO;
import pro.uhalo.uni.dto.DigestPublicArticleDTO;
import pro.uhalo.uni.dto.DigestPublicListResponse;
import pro.uhalo.uni.dto.DigestFilterOptionsDTO;
import pro.uhalo.uni.dto.DigestSubscriptionDTO;
import pro.uhalo.uni.dto.DigestSubscriptionRequest;
import pro.uhalo.uni.dto.DigestMyFeedResponse;
import pro.uhalo.uni.dto.DigestOptionItemDTO;
import pro.uhalo.uni.dto.DigestPublicReportDTO;
import pro.uhalo.uni.dto.DigestSharedDTO;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.DigestPreferenceMatcher;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.DigestPersistence;
import pro.uhalo.uni.scheme.DigestReport;
import pro.uhalo.uni.utils.DigestConfigHelper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 小程序端的 AI 日报读取服务（只读、公开）。
 *
 * <p>日报此前只有后台接口，小程序用户完全看不到——每天生成的内容等于白做。
 * 本服务把已发布的日报整理成列表数据，是「日报 → 用户」这条链路的地基。</p>
 *
 * <p>两条边界：</p>
 * <ul>
 *   <li><b>只返回已发布的内容</b>：报告在发布前就先落库（先存稿再发布是刻意设计），
 *       因此未发布的草稿绝不能被用户看到；</li>
 *   <li><b>不返回正文</b>：正文在已发布的 Halo 文章里，前端跳文章详情页看——
 *       这样阅读量统计、评论、付费判定都自然复用现有链路，不必另搞一套。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DigestPublicService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 日报日期按东八区理解（与生成端一致） */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String STATUS_SUCCESS = "SUCCESS";

    /** 单次最多返回的日报份数：够刷一阵子，又不会把响应撑爆 */
    private static final int MAX_PAGE_SIZE = 30;

    /** 参与分页的日报总量上限：日报一天最多几份，取最近 200 份已远超实际需要 */
    private static final int RECENT_LIMIT = 200;

    /**
     * 个性化与筛选项的统计窗口（天）。
     *
     * <p>只回看最近这些天，而不是全部历史：个性化看的是「最近有什么值得读」，
     * 把几个月前的内容混进来会让「我的订阅」看起来像存档而不是推荐。</p>
     */
    private static final int PREFERENCE_WINDOW_DAYS = 14;

    /** 筛选项里关键词 / 来源最多给出多少个（按出现频次取前几名） */
    private static final int OPTION_LIMIT = 20;

    private final DigestPersistence persistence;
    private final ReactiveSettingFetcher settingFetcher;
    private final DigestSubscriptionService subscriptionService;
    private final WxUserService wxUserService;

    public Mono<DigestPublicListResponse> list(int page, int size) {
        var normalizedPage = Math.max(0, page);
        var normalizedSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return loadEnabled()
            .flatMap(enabled -> {
                if (!enabled) {
                    return Mono.just(empty(normalizedPage, normalizedSize));
                }
                return persistence.listRecent(RECENT_LIMIT)
                    .flatMapMany(reports -> reactor.core.publisher.Flux.fromIterable(reports))
                    .filter(DigestPublicService::isVisibleToUsers)
                    .map(DigestPublicService::toReport)
                    // 未发布成功的报告（文章全失败）对用户没有意义，直接不出现；
                    // 同样地，渲染不出任何文章的报告也不出现
                    .filter(report -> report.getPublishedCount() > 0)
                    .filter(DigestPublicService::hasReadableArticles)
                    .collectList()
                    .map(reports -> {
                        var sorted = reports.stream()
                            .sorted(Comparator.comparing(DigestPublicReportDTO::getDate,
                                    Comparator.nullsLast(Comparator.reverseOrder()))
                                .thenComparing(DigestPublicReportDTO::getSlot,
                                    Comparator.nullsLast(Comparator.reverseOrder())))
                            .toList();
                        var from = Math.min(normalizedPage * normalizedSize, sorted.size());
                        var to = Math.min(from + normalizedSize, sorted.size());
                        var pageItems = sorted.subList(from, to);
                        return DigestPublicListResponse.builder()
                            .enabled(true)
                            .items(List.copyOf(pageItems))
                            .categories(categoriesOf(pageItems))
                            .page(normalizedPage)
                            .size(normalizedSize)
                            .hasMore(to < sorted.size())
                            .build();
                    });
            });
    }

    /**
     * 「我的订阅」日报流：按订阅偏好筛选最近窗口内的文章。
     *
     * <p>没有配偏好时等同于「全部」——空偏好返回空列表会让用户以为功能坏了。</p>
     *
     * <p>筛选<b>只作用于已生成的内容</b>：日报是服务端按天批量生成，为每个用户单独生成
     * 会让 AI 调用量随用户数线性增长。所以这里不产生任何 AI 成本，
     * 这也意味着偏好配得太窄时可能确实没有内容——出现这种情况会明确提示并给出可点选的热门分类，
     * 而不是留下一片无法理解的空白。</p>
     */
    public Mono<DigestMyFeedResponse> myFeed(String openid, int pageNo, int size) {
        var normalizedPage = Math.max(0, pageNo);
        var normalizedSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return loadEnabled().flatMap(enabled -> {
            if (!enabled) {
                return Mono.just(DigestMyFeedResponse.builder()
                    .enabled(false)
                    .items(List.of())
                    .suggestions(List.of())
                    .days(PREFERENCE_WINDOW_DAYS)
                    .build());
            }
            return subscriptionService.load(openid)
                .flatMap(subscription -> visibleReports().map(reports -> {
                    var totalArticles = reports.stream()
                        .mapToInt(report -> report.getArticles().size())
                        .sum();
                    var options = buildOptions(reports, PREFERENCE_WINDOW_DAYS);

                    if (!DigestPreferenceMatcher.hasPreferences(subscription)) {
                        return DigestMyFeedResponse.builder()
                            .enabled(true)
                            .subscribed(subscription.isEnabled())
                            .personalized(false)
                            // 没设条件时明确说「这就是全部」，而不是让用户以为筛选失效了
                            .summary("还没有设置关注条件，这里展示最近 " + PREFERENCE_WINDOW_DAYS
                                + " 天的全部 " + totalArticles + " 篇")
                            .matchedCount(totalArticles)
                            .totalCount(totalArticles)
                            .days(PREFERENCE_WINDOW_DAYS)
                            .items(paginate(reports, normalizedPage, normalizedSize))
                            .suggestions(List.of())
                            .build();
                    }

                    // 逐份日报内做匹配：日报之间保持日期倒序，命中强的在日报内排前面
                    var matchedReports = new ArrayList<DigestPublicReportDTO>();
                    var matchedCount = 0;
                    for (var report : reports) {
                        var matched = DigestPreferenceMatcher.apply(report.getArticles(), subscription);
                        if (matched.isEmpty()) {
                            continue;
                        }
                        matchedCount += matched.size();
                        matchedReports.add(copyWithArticles(report, matched));
                    }
                    var empty = matchedCount == 0;
                    return DigestMyFeedResponse.builder()
                        .enabled(true)
                        .subscribed(subscription.isEnabled())
                        .personalized(true)
                        .matchedCount(matchedCount)
                        .totalCount(totalArticles)
                        .days(PREFERENCE_WINDOW_DAYS)
                        .items(paginate(matchedReports, normalizedPage, normalizedSize))
                        .suggestions(empty ? topCategories(options, 6) : List.of())
                        .summary(empty
                            ? "最近 " + PREFERENCE_WINDOW_DAYS + " 天没有符合关注条件的内容"
                            : "最近 " + PREFERENCE_WINDOW_DAYS + " 天共 " + totalArticles
                                + " 篇，符合关注条件的 " + matchedCount + " 篇")
                        .message(empty ? "可以放宽关注条件，或直接点下面的热门分类查看" : null)
                        .build();
                }));
        });
    }

    /** 分享落地页最多展示多少篇：够体现「精选」的分量，又不至于让人读不完 */
    private static final int SHARED_ARTICLE_LIMIT = 12;

    /**
     * 分享落地页数据：按邀请码找到分享人，返回他视角下的精选。
     *
     * <p>落地页看到的是<b>真实内容</b>而不是一张邀请海报，这是它比普通邀请更容易转化的原因：
     * 被分享者能立刻判断「这些内容我感不感兴趣」。</p>
     *
     * <p>隐私边界：只给出昵称、头像、命中的文章，以及由这些文章推导出的<b>分类</b>摘要；
     * 不给出分享人的关键词与来源偏好——他分享的是「我挑的内容」，不是「我的兴趣标签」。</p>
     */
    public Mono<DigestSharedDTO> shared(String inviteCode) {
        return loadEnabled().flatMap(enabled -> {
            if (!enabled) {
                return Mono.just(DigestSharedDTO.builder()
                    .enabled(false)
                    .valid(false)
                    .categories(List.of())
                    .articles(List.of())
                    .message("站点还没开启 AI 日报")
                    .build());
            }
            if (!StringUtils.hasText(inviteCode)) {
                return Mono.just(invalidShare("分享链接缺少邀请码"));
            }
            return wxUserService.fetchByInviteCode(inviteCode.trim().toUpperCase())
                .flatMap(sharer -> subscriptionService.load(sharer.getOpenid())
                    .flatMap(subscription -> visibleReports()
                        .map(reports -> buildShared(sharer, subscription, reports))))
                // 邀请码无效（拼错、或分享人已注销）时明确提示，而不是给一个空页面
                .defaultIfEmpty(invalidShare("分享链接已失效，你可以直接浏览全部日报"));
        });
    }

    /**
     * 一键订阅「同款方向」。
     *
     * <p>只复制分享人的<b>分类与关键词</b>，不复制来源站点：分类与关键词都是内容话题层面的词，
     * 而来源站点更接近「他关注哪些具体作者」，复制过去既不必要也更像在泄露别人的订阅习惯。</p>
     *
     * <p>订阅之后由既有的登录流程负责绑定邀请关系并发奖（分享链接里的邀请码会被存进本地，
     * 登录时随请求提交），因此这里不重复实现邀请奖励。</p>
     */
    public Mono<DigestSubscriptionDTO> subscribeFromShare(String viewerOpenid, String inviteCode) {
        if (!StringUtils.hasText(viewerOpenid)) {
            return Mono.error(new WxAuthException("请先登录"));
        }
        if (!StringUtils.hasText(inviteCode)) {
            return Mono.error(new WxAuthException("分享链接缺少邀请码"));
        }
        return wxUserService.fetchByInviteCode(inviteCode.trim().toUpperCase())
            .switchIfEmpty(Mono.error(new WxAuthException("分享链接已失效")))
            .flatMap(sharer -> subscriptionService.load(sharer.getOpenid())
                .flatMap(shared -> {
                    var request = new DigestSubscriptionRequest();
                    request.setEnabled(true);
                    request.setCategories(shared.getCategories());
                    request.setKeywords(shared.getKeywords());
                    // 站点分类也复制：它是站点公开的分类体系（不是个人兴趣标签），
                    // 而且不复制它的话，被分享者的首页不会跟着变，等于只订阅了一半
                    request.setPostCategories(shared.getPostCategories());
                    // 明确不带来源：见方法注释
                    request.setSources(List.of());
                    return subscriptionService.save(viewerOpenid, request);
                }));
    }

    private static DigestSharedDTO invalidShare(String message) {
        return DigestSharedDTO.builder()
            .enabled(true)
            .valid(false)
            .categories(List.of())
            .articles(List.of())
            .message(message)
            .build();
    }

    private DigestSharedDTO buildShared(WxUser sharer, DigestSubscriptionDTO subscription,
        List<DigestPublicReportDTO> reports) {
        var totalArticles = reports.stream()
            .mapToInt(report -> report.getArticles().size())
            .sum();
        var picked = new ArrayList<DigestPublicArticleDTO>();
        var personalized = DigestPreferenceMatcher.hasPreferences(subscription);
        if (personalized) {
            for (var report : reports) {
                picked.addAll(DigestPreferenceMatcher.apply(report.getArticles(), subscription));
                if (picked.size() >= SHARED_ARTICLE_LIMIT) {
                    break;
                }
            }
        } else {
            // 分享人没配关注条件时，他分享的其实就是「全部日报」——
            // 如实呈现，不假装是「个人精选」
            for (var report : reports) {
                picked.addAll(report.getArticles());
                if (picked.size() >= SHARED_ARTICLE_LIMIT) {
                    break;
                }
            }
        }
        var limited = picked.size() > SHARED_ARTICLE_LIMIT
            ? List.copyOf(picked.subList(0, SHARED_ARTICLE_LIMIT)) : List.copyOf(picked);
        // 命中原因是给「自己的订阅」解释「为什么这篇出现」用的，它会把偏好原样写出来
        // （如「关键词 · transformer · 关注来源 · simonwillison.net」）。
        // 放到别人的落地页上就成了泄露分享人的兴趣标签，因此这里只保留粗粒度的分类，
        // 其余改写为不暴露具体偏好的说法。
        limited = limited.stream().map(DigestPublicService::sanitizeSharedReason).toList();

        return DigestSharedDTO.builder()
            .enabled(true)
            .valid(true)
            .sharerName(StringUtils.hasText(sharer.getNickname()) ? sharer.getNickname() : null)
            .sharerAvatar(sharer.getAvatarUrl())
            .articleCount(limited.size())
            .totalCount(totalArticles)
            .days(PREFERENCE_WINDOW_DAYS)
            .categories(summaryCategories(limited))
            .articles(limited)
            .message(personalized ? null : "这位读者分享的是当期全部精选")
            .build();
    }

    /**
     * 由命中文章推导分类摘要。
     *
     * <p>用「文章的分类」而不是「分享人的分类偏好」：前者是内容属性（本来就会展示），
     * 后者才是他的个人设置。</p>
     */
    private static List<DigestCategoryDTO> summaryCategories(List<DigestPublicArticleDTO> articles) {
        var counts = new LinkedHashMap<String, Integer>();
        var labels = new LinkedHashMap<String, String>();
        var emojis = new LinkedHashMap<String, String>();
        for (var article : articles) {
            var key = StringUtils.hasText(article.getCategory()) ? article.getCategory() : "other";
            counts.merge(key, 1, Integer::sum);
            labels.putIfAbsent(key, article.getCategoryLabel());
            emojis.putIfAbsent(key, article.getCategoryEmoji());
        }
        var categories = new ArrayList<DigestCategoryDTO>();
        counts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(6)
            .forEach(entry -> categories.add(DigestCategoryDTO.builder()
                .key(entry.getKey())
                .label(labels.get(entry.getKey()))
                .emoji(emojis.get(entry.getKey()))
                .count(entry.getValue())
                .build()));
        return List.copyOf(categories);
    }

    /** 可选筛选项：全部从窗口内实际出现过的内容里统计，保证点选之后有内容。 */
    public Mono<DigestFilterOptionsDTO> filterOptions() {
        return filterOptions(PREFERENCE_WINDOW_DAYS);
    }

    /** 可选筛选项（窗口天数可指定，供「AI 定制内容」按其配置的窗口取候选）。 */
    public Mono<DigestFilterOptionsDTO> filterOptions(int windowDays) {
        var days = Math.max(1, windowDays);
        return visibleReports(days)
            .map(reports -> buildOptions(reports, days))
            .defaultIfEmpty(DigestFilterOptionsDTO.builder()
                .categories(List.of())
                .keywords(List.of())
                .sources(List.of())
                .days(days)
                .optionLimit(OPTION_LIMIT)
                .build());
    }

    /**
     * 候选池：窗口内已发布日报的全部文章条目（已对齐可跳转的文章名）。
     *
     * <p>给「AI 定制内容」用：它按用户偏好在这批候选里筛。走的是同一套可见性判定，
     * 因此不会出现「订阅里能看到、简报里却筛不到」的不一致。</p>
     */
    public Mono<List<DigestPublicArticleDTO>> candidateArticles(int windowDays) {
        return visibleReports(windowDays)
            .map(reports -> reports.stream()
                .flatMap(report -> report.getArticles().stream())
                .toList());
    }

    /**
     * 候选池的来源分布（只给实际出现过的来源，带篇数）。
     *
     * <p>用户端「来源」这一维度必须只给出现过的选项：凭想象填一个来源，
     * 结果简报里一篇都没有，用户会以为功能坏了。</p>
     */
    public Mono<List<DigestOptionItemDTO>> sourceOptions(int windowDays) {
        return filterOptions(windowDays).map(DigestFilterOptionsDTO::getSources);
    }

    /** 取窗口内已发布的日报（按日期倒序，文章条目已解析）。 */
    private Mono<List<DigestPublicReportDTO>> visibleReports() {
        return visibleReports(PREFERENCE_WINDOW_DAYS);
    }

    /**
     * 取窗口内已发布的日报（窗口天数可指定）。
     *
     * <p>窗口做成参数是因为「AI 定制内容」的窗口由站长配置（默认 7 天），
     * 而个性化流固定 14 天——两者共用同一套「哪些报告对用户可见」的判定，
     * 避免出现「订阅里看得到的日报、简报里却筛不到」这种自相矛盾。</p>
     */
    private Mono<List<DigestPublicReportDTO>> visibleReports(int windowDays) {
        var days = Math.max(1, windowDays);
        var from = LocalDate.now(ZONE).minusDays(days - 1L);
        return persistence.listRecent(RECENT_LIMIT)
            .flatMapMany(Flux::fromIterable)
            .filter(DigestPublicService::isVisibleToUsers)
            .filter(report -> isWithinWindow(report, from))
            .map(DigestPublicService::toReport)
            .filter(report -> report.getPublishedCount() > 0)
            .filter(DigestPublicService::hasReadableArticles)
            .collectList()
            .map(reports -> reports.stream()
                .sorted(Comparator.comparing(DigestPublicReportDTO::getDate,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(DigestPublicReportDTO::getSlot,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList());
    }

    /**
     * 是否在统计窗口内。
     *
     * <p>日期缺失或格式不对的历史记录一律排除：无法判断新旧时，
     * 宁可少显示也不要把陈年老日报混进「最近」里。</p>
     */
    private static boolean isWithinWindow(DigestReport report, LocalDate from) {
        var date = report.getDate();
        if (!StringUtils.hasText(date)) {
            return false;
        }
        try {
            return !LocalDate.parse(date).isBefore(from);
        } catch (Exception ex) {
            return false;
        }
    }

    /** 统计筛选项：分类 / 关键词 / 来源的出现频次，按频次倒序并截断。 */
    private static DigestFilterOptionsDTO buildOptions(List<DigestPublicReportDTO> reports,
        int windowDays) {
        var categoryCounts = new LinkedHashMap<String, Integer>();
        var categoryLabels = new LinkedHashMap<String, String>();
        var categoryEmojis = new LinkedHashMap<String, String>();
        var keywordCounts = new LinkedHashMap<String, Integer>();
        var sourceCounts = new LinkedHashMap<String, Integer>();
        var articleCount = 0;

        for (var report : reports) {
            for (var article : report.getArticles()) {
                articleCount++;
                var category = StringUtils.hasText(article.getCategory())
                    ? article.getCategory() : "other";
                categoryCounts.merge(category, 1, Integer::sum);
                categoryLabels.putIfAbsent(category, article.getCategoryLabel());
                categoryEmojis.putIfAbsent(category, article.getCategoryEmoji());
                for (var keyword : article.getKeywords() == null
                    ? List.<String>of() : article.getKeywords()) {
                    if (StringUtils.hasText(keyword)) {
                        keywordCounts.merge(keyword.trim(), 1, Integer::sum);
                    }
                }
                if (StringUtils.hasText(article.getSourceName())) {
                    sourceCounts.merge(article.getSourceName().trim(), 1, Integer::sum);
                }
            }
        }

        var categories = new ArrayList<DigestCategoryDTO>();
        categoryCounts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .forEach(entry -> categories.add(DigestCategoryDTO.builder()
                .key(entry.getKey())
                .label(categoryLabels.get(entry.getKey()))
                .emoji(categoryEmojis.get(entry.getKey()))
                .count(entry.getValue())
                .build()));

        return DigestFilterOptionsDTO.builder()
            .categories(List.copyOf(categories))
            .keywords(optionsOf(keywordCounts))
            .sources(optionsOf(sourceCounts))
            .days(windowDays)
            .reportCount(reports.size())
            .articleCount(articleCount)
            .optionLimit(OPTION_LIMIT)
            .build();
    }

    private static List<DigestOptionItemDTO> optionsOf(LinkedHashMap<String, Integer> counts) {
        return counts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(OPTION_LIMIT)
            .map(entry -> DigestOptionItemDTO.builder()
                .value(entry.getKey())
                .count(entry.getValue())
                .build())
            .toList();
    }

    private static List<DigestCategoryDTO> topCategories(DigestFilterOptionsDTO options, int limit) {
        return options.getCategories().stream().limit(limit).toList();
    }

    /**
     * 分享落地页上的命中原因：只保留分类这一粗粒度信息。
     *
     * <p>分享人主动分享的是「我挑的内容」，不是「我的兴趣标签列表」；
     * 而关键词与来源已经细到能反推出他关注什么。分类只有六种、
     * 且已经从文章本身推导展示在落地卡片上，说明它不带来额外泄露。</p>
     */
    private static DigestPublicArticleDTO sanitizeSharedReason(DigestPublicArticleDTO article) {
        if (!StringUtils.hasText(article.getCategoryLabel())) {
            article.setMatchedReason(article.getMatchedReason() == null ? null : "精选");
        } else {
            article.setMatchedReason("精选方向 · " + article.getCategoryLabel());
        }
        return article;
    }

    /** 复制一份日报并替换其文章列表（筛选后只保留命中的文章）。 */
    private static DigestPublicReportDTO copyWithArticles(DigestPublicReportDTO report,
        List<DigestPublicArticleDTO> articles) {
        return DigestPublicReportDTO.builder()
            .date(report.getDate())
            .slot(report.getSlot())
            .cover(report.getCover())
            .articleCount(articles.size())
            .totalArticles(report.getTotalArticles())
            .sourceCount(report.getSourceCount())
            .publishedCount(report.getPublishedCount())
            .reportPostName(report.getReportPostName())
            .articles(articles)
            .build();
    }

    private static List<DigestPublicReportDTO> paginate(List<DigestPublicReportDTO> reports,
        int pageNo, int size) {
        var from = Math.min(pageNo * size, reports.size());
        var to = Math.min(from + size, reports.size());
        return List.copyOf(reports.subList(from, to));
    }

    /**
     * 这份报告能否对用户可见。
     *
     * <p>要求发布成功<b>且</b>至少有一篇文章真的发出来了。状态判断用白名单而不是
     * 「非 FAILED」：状态字段可能因升级/中断留下意料之外的值，白名单才是安全的失败方向
     * （宁可少显示一份日报，也不要把半成品推到用户面前）。</p>
     */
    private static boolean isVisibleToUsers(DigestReport report) {
        return STATUS_SUCCESS.equalsIgnoreCase(report.getStatus())
            && report.getPostCount() != null
            && report.getPostCount() > 0;
    }

    /**
     * 这份日报有没有能展示给用户的文章。
     *
     * <p>必须单独判一次：报告状态与发布计数都正常、但文章条目解析不出来的情况确实存在
     * （升级前的数据、或条目 JSON 损坏）。这种报告渲染出来只有「一个日期 + 一句 5 篇」，
     * 下面空无一物。</p>
     */
    private static boolean hasReadableArticles(DigestPublicReportDTO report) {
        return report.getArticles() != null && !report.getArticles().isEmpty();
    }

    private static DigestPublicReportDTO toReport(DigestReport report) {
        var posts = parsePosts(report.getPosts());
        var entries = parseEntries(report.getArticleEntries());
        if (entries.isEmpty()) {
            // 兜底：articleEntries 是后加的字段，升级前生成的日报没有它。
            // 这些日报的 articleCount 仍有值（老字段），于是会渲染出「写着 5 篇、
            // 下面一篇文章都点不开」的页面——比不显示更让人困惑。
            // 这里用发布结果反推出最小可用的条目，内容至少能读、能点开。
            entries = deriveEntriesFromPosts(posts);
        }
        var stats = parseStats(report.getStats());
        var articles = new ArrayList<DigestPublicArticleDTO>();
        for (var entry : entries) {
            var matched = matchPublished(posts, entry.getTitle());
            // 逐篇发布 → 跳该文章；合并发布 → 回落到日报正文，前端无需区分发布模式
            var postName = matched != null && StringUtils.hasText(matched.getPostName())
                ? matched.getPostName() : report.getPostName();
            if (!StringUtils.hasText(postName)) {
                // 连可跳转的文章都没有，这条对用户没用（例如补发还没做完）
                continue;
            }
            articles.add(DigestPublicArticleDTO.builder()
                .title(entry.getTitle())
                .cover(entry.getCover())
                .category(entry.getCategory())
                .categoryLabel(entry.getCategoryLabel())
                .categoryEmoji(entry.getCategoryEmoji())
                .keywords(entry.getKeywords() == null ? List.of() : entry.getKeywords())
                .sourceName(entry.getSourceName())
                .sourceUrl(entry.getSourceUrl())
                .summary(entry.getSummary())
                .link(entry.getLink())
                .postName(postName)
                .build());
        }
        return DigestPublicReportDTO.builder()
            .date(report.getDate())
            .slot(report.getSlot())
            .cover(StringUtils.hasText(report.getCover()) ? report.getCover()
                : firstCover(articles))
            .articleCount(report.getArticleCount() == null ? articles.size()
                : report.getArticleCount())
            .totalArticles(stats == null ? 0 : stats.path("totalArticles").asInt(0))
            .sourceCount(stats == null ? 0 : stats.path("successFeeds").asInt(0))
            .publishedCount(report.getPostCount() == null ? 0 : report.getPostCount())
            .reportPostName(report.getPostName())
            .articles(articles)
            .build();
    }

    /**
     * 按标题把文章条目与发布结果对齐。
     *
     * <p>为什么读的时候才对齐、而不是发布后回写：报告在发布前就先落库，
     * 发布失败还能补发。若把文章名写进条目里，补发之后就得回头重写这份数据，
     * 多一条容易漏的写入路径。</p>
     */
    /**
     * 从发布结果反推文章条目（升级前日报的兜底）。
     *
     * <p>只带标题：分类、关键词、摘要当时没有落库，无法凭空恢复，
     * 因此这些日报的卡片没有封面与分类——如实呈现即可。
     * 但「能读到内容」与「整页空白」是能不能用的分水岭，所以必须兜住。</p>
     */
    private static List<DigestArticleEntryDTO> deriveEntriesFromPosts(List<DigestPostResultDTO> posts) {
        var entries = new ArrayList<DigestArticleEntryDTO>();
        for (var post : posts) {
            if (!StringUtils.hasText(post.getTitle()) || !StringUtils.hasText(post.getPostName())) {
                continue;
            }
            entries.add(DigestArticleEntryDTO.builder().title(post.getTitle()).build());
        }
        return List.copyOf(entries);
    }

    private static DigestPostResultDTO matchPublished(List<DigestPostResultDTO> posts, String title) {
        if (!StringUtils.hasText(title)) {
            return null;
        }
        return posts.stream()
            .filter(post -> title.equals(post.getTitle()))
            .filter(post -> StringUtils.hasText(post.getPostName()))
            .findFirst()
            .orElse(null);
    }

    private static String firstCover(List<DigestPublicArticleDTO> articles) {
        return articles.stream()
            .map(DigestPublicArticleDTO::getCover)
            .filter(StringUtils::hasText)
            .findFirst()
            .orElse("");
    }

    /** 分类筛选条：只列出当前返回数据里实际出现过的分类，并带上篇数。 */
    private static List<DigestCategoryDTO> categoriesOf(List<DigestPublicReportDTO> reports) {
        var counts = new LinkedHashMap<String, Integer>();
        var labels = new LinkedHashMap<String, String>();
        var emojis = new LinkedHashMap<String, String>();
        for (var report : reports) {
            for (var article : report.getArticles()) {
                var key = StringUtils.hasText(article.getCategory()) ? article.getCategory() : "other";
                counts.merge(key, 1, Integer::sum);
                labels.putIfAbsent(key, article.getCategoryLabel());
                emojis.putIfAbsent(key, article.getCategoryEmoji());
            }
        }
        var categories = new ArrayList<DigestCategoryDTO>();
        counts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .forEach(entry -> categories.add(DigestCategoryDTO.builder()
                .key(entry.getKey())
                .label(labels.get(entry.getKey()))
                .emoji(emojis.get(entry.getKey()))
                .count(entry.getValue())
                .build()));
        return List.copyOf(categories);
    }

    private Mono<Boolean> loadEnabled() {
        return settingFetcher.get(DigestConfigHelper.groupName())
            .map(DigestConfigHelper::parse)
            .map(DigestConfigDTO::isEnabled)
            .defaultIfEmpty(false)
            .onErrorResume(ex -> {
                log.warn("读取 AI 日报配置失败，按未开启处理：{}", ex.getMessage());
                return Mono.just(false);
            });
    }

    private static DigestPublicListResponse empty(int page, int size) {
        return DigestPublicListResponse.builder()
            .enabled(false)
            .items(List.of())
            .categories(List.of())
            .page(page)
            .size(size)
            .hasMore(false)
            .build();
    }

    private static List<DigestArticleEntryDTO> parseEntries(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(rawJson, new TypeReference<List<DigestArticleEntryDTO>>() {
            });
        } catch (Exception ex) {
            log.warn("解析日报文章条目失败，本次列表将不含条目明细：{}", ex.getMessage());
            return List.of();
        }
    }

    private static List<DigestPostResultDTO> parsePosts(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(rawJson, new TypeReference<List<DigestPostResultDTO>>() {
            });
        } catch (Exception ex) {
            log.warn("解析日报发布结果失败：{}", ex.getMessage());
            return List.of();
        }
    }

    private static JsonNode parseStats(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(rawJson);
        } catch (Exception ex) {
            return null;
        }
    }
}
