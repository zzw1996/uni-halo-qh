package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.dto.HaloPostPublishDTO;
import pro.uhalo.uni.dto.HaloPostPublishResultDTO;
import pro.uhalo.uni.dto.UserPublishArticleDTO;
import pro.uhalo.uni.dto.UserPublishArticleDetailDTO;
import pro.uhalo.uni.dto.UserPublishArticleRequest;
import pro.uhalo.uni.dto.UserPublishMomentRequest;
import pro.uhalo.uni.dto.UserPublishResultDTO;
import pro.uhalo.uni.dto.UserPublishStatusDTO;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.DigestHaloClient;
import pro.uhalo.uni.services.HaloPostPublisher;
import pro.uhalo.uni.services.PrivilegeService;
import pro.uhalo.uni.services.UserPublishService;
import pro.uhalo.uni.services.WxContentSecurityService;
import pro.uhalo.uni.services.WxUserService;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 用户投稿实现：阅读赚积分 → 花积分发文章 / 发瞬间。
 *
 * <p>执行顺序（顺序本身就是正确性的一部分）：</p>
 * <ol>
 *   <li>校验总开关 → 已登录 → 已绑定 Halo 账号（没有 Halo 用户就没有作者身份）</li>
 *   <li>内容校验 + 微信内容安全检测（UGC 合规要求）</li>
 *   <li>按 Halo 角色解析发布方式：direct 直发 / draft 草稿待审 / deny 不允许</li>
 *   <li>余额与每日上限校验</li>
 *   <li><b>先写幂等扣分流水，成功后再改余额</b>（与积分兑换同一套顺序，
 *       并发下只有一个请求能拿到幂等位，避免重复扣分）</li>
 *   <li>调用 Halo 发布；失败则写一条正向流水冲正并退还积分，不让用户白扣</li>
 * </ol>
 */
@Service
@Slf4j
public class UserPublishServiceImpl implements UserPublishService {

    /** 配置分组名（对应 setting.yaml 里的 group） */
    private static final String CONFIG_GROUP = "userPublishConfig";

    /** 发布方式 */
    private static final String MODE_DIRECT = "direct";
    private static final String MODE_DRAFT = "draft";
    private static final String MODE_DENY = "deny";

    /** 积分流水动作码（新增动作需在 PointsAction.labelOf 与前端 POINTS_ACTION_LABELS 补中文名） */
    private static final String ACTION_PUBLISH_ARTICLE = "publish_article";
    private static final String ACTION_PUBLISH_MOMENT = "publish_moment";
    private static final String ACTION_PUBLISH_REFUND = "publish_refund";

    /** 瞬间「写」接口（标准 CRUD）：发布/删除 */
    private static final String MOMENTS_API = "/apis/moment.halo.run/v1alpha1/moments";
    /**
     * 瞬间「读」接口（插件暴露的公开查询 API）。
     *
     * <p>注意与写接口不是同一条路径：读操作走 {@code api.moment.halo.run}，
     * 写成 {@code moment.halo.run} 会 404（曾因此在服务端代理时读列表失败）。</p>
     */
    private static final String MOMENTS_QUERY_API = "/apis/api.moment.halo.run/v1alpha1/moments";
    private static final String VOTE_SUBMIT_API =
        "/apis/api.vote.kunkunyu.com/v1alpha1/votes";
    /** 投稿管理用的 console 文章接口：列表按页拉、删除走 /recycle */
    private static final String CONSOLE_POSTS_API = "/apis/api.console.halo.run/v1alpha1/posts";
    /**
     * 核心组文章接口：按 metadata.name 读单篇（含草稿）。
     *
     * <p>编辑要走「读回来 → 改 → 整份回传」，而公开读接口（api.content.halo.run）对未发布文章
     * 一律 404，这个前缀才能读到待审草稿。写回仍走 {@link #CONSOLE_POSTS_API}。</p>
     */
    private static final String CORE_POSTS_API = Constants.HALO_CORE_POST_API_PREFIX;
    /** 「我的投稿」每页拉多少条；归属过滤在本地，所以要多翻几页 */
    private static final int MY_ARTICLES_PAGE_SIZE = 100;
    /** 最多翻几页（站点文章量级远小于 5×100） */
    private static final int MY_ARTICLES_MAX_PAGES = 5;
    /** 特权角色：站点管理员等同于博主，发布不扣分、直接公开 */
    private static final Set<String> PRIVILEGED_ROLES = Set.of("super-role", "admin");
    private static final String ROLE_NAMES_ANNO = User.ROLE_NAMES_ANNO;

    /** 默认值：配置缺省时的兜底，保证「没配也能跑，但不会放开」 */
    private static final int DEFAULT_ARTICLE_COST = 50;    private static final int DEFAULT_MOMENT_COST = 20;
    private static final int DEFAULT_MAX_TITLE_LENGTH = 60;
    private static final int DEFAULT_MAX_ARTICLE_LENGTH = 5000;
    private static final int DEFAULT_MAX_MOMENT_LENGTH = 500;
    private static final int DEFAULT_MAX_MOMENT_IMAGES = 9;

    /** 一次投稿最多带几个分类 / 标签（界面同口径；服务端再挡一道） */
    private static final int MAX_ARTICLE_CATEGORIES = 3;

    private static final int MAX_ARTICLE_TAGS = 5;
    /** 直接发布时的确认等待上限，避免一次请求拖太久 */
    private static final int PUBLISH_WAIT_SECONDS = 15;

    /** 经 DigestHaloClient 调 Halo 接口（读瞬间/删瞬间/发瞬间/投票）的超时 */
    private static final Duration HALO_CALL_TIMEOUT = Duration.ofSeconds(30);

    private final ReactiveSettingFetcher settingFetcher;
    private final WxUserService wxUserService;
    private final PointsPersistence pointsPersistence;
    private final HaloPostPublisher postPublisher;
    private final ReactiveExtensionClient extensionClient;
    private final AttachmentService attachmentService;
    private final WxContentSecurityService contentSecurityService;
    /**
     * 调 Halo 接口统一走 DigestHaloClient：接口地址可在「AI 日报 - Halo 接口地址」配置
     * （与日报同口径），默认本机回环。不能写死 127.0.0.1——部分部署（如 Docker/自定义端口）
     * 回环不可达，写死会导致删除/发布全部「投稿失败」而日报正常。
     */
    private final DigestHaloClient haloClient;
    /** 特权判定收口在这里（本站所有者 / 管理员），见 PrivilegeService 的说明 */
    private final PrivilegeService privilegeService;

    public UserPublishServiceImpl(ReactiveSettingFetcher settingFetcher,
                                  WxUserService wxUserService,
                                  PointsPersistence pointsPersistence,
                                  HaloPostPublisher postPublisher,
                                  ReactiveExtensionClient extensionClient,
                                  AttachmentService attachmentService,
                                  WxContentSecurityService contentSecurityService,
                                  DigestHaloClient haloClient,
                                  PrivilegeService privilegeService) {
        this.settingFetcher = settingFetcher;
        this.wxUserService = wxUserService;
        this.pointsPersistence = pointsPersistence;
        this.postPublisher = postPublisher;
        this.extensionClient = extensionClient;
        this.attachmentService = attachmentService;
        this.contentSecurityService = contentSecurityService;
        // 用 ObjectProvider 而不是直接注入：角色服务若在插件上下文不可用，
        // 只是退化成「读不到角色 → 走默认策略」，而不是整个插件起不来
        this.haloClient = haloClient;
        this.privilegeService = privilegeService;
    }

    // ==================== 状态 ====================

    @Override
    public Mono<UserPublishStatusDTO> getStatus(String openid) {
        var id = normalizeOpenid(openid);
        return loadConfig()
            .flatMap(config -> pointsPersistence.getOrCreateAccount(id)
                .flatMap(account -> wxUserService.fetchByOpenid(id)
                    .map(user -> new StatusContext(config, account, user))
                    .defaultIfEmpty(new StatusContext(config, account, null)))
                .flatMap(ctx -> resolveModes(ctx.config(), ctx.user())
                    .flatMap(modes -> countToday(id).map(counts -> {
                        var privileged = modes.privileged();
                        var balance = safeBalance(ctx.account());
                        var minToShow = ctx.config().minPointsToShow();
                        // 特权用户（博主/站点所有者）不受积分门槛约束：积分不足也能看到并使用投稿入口。
                        // 门槛本来是给普通用户设的「赚够积分才能投稿」，站长给自己设门槛没有意义。
                        var visible = ctx.config().enabled() && ctx.config().showButton()
                            && (privileged || balance >= minToShow);
                        var haloBound = ctx.user() != null
                            && StringUtils.hasText(ctx.user().getHaloUserName());
                        // 积分与每日次数同样对特权用户按「不设限」计算，否则会给出
                        // 「积分不足，还差 X 分」这种与实际能力不符的提示
                        var articleReason = disabledReason(ctx.config(), modes.articleMode(),
                            haloBound, balance, privileged ? 0 : ctx.config().articleCost(),
                            counts.article(), privileged ? 0 : ctx.config().dailyArticleLimit(),
                            "文章");
                        var momentReason = disabledReason(ctx.config(), modes.momentMode(),
                            haloBound, balance, privileged ? 0 : ctx.config().momentCost(),
                            counts.moment(), privileged ? 0 : ctx.config().dailyMomentLimit(),
                            "瞬间");
                        return UserPublishStatusDTO.builder()
                            .enabled(ctx.config().enabled())
                            .visible(visible)
                            .minPointsToShow(minToShow)
                            .pointsToUnlock(privileged ? 0 : Math.max(0, minToShow - (int) balance))
                            .articleCost(ctx.config().articleCost())
                            .momentCost(ctx.config().momentCost())
                            .balance(balance)
                            .loggedIn(true)
                            .haloBound(haloBound)
                            .haloUserName(haloBound ? ctx.user().getHaloUserName() : "")
                            .articleMode(modes.articleMode())
                            .momentMode(modes.momentMode())
                            .privileged(privileged)
                            .canPublishArticle(articleReason.isEmpty())
                            .canPublishMoment(momentReason.isEmpty())
                            .articleDisabledReason(articleReason)
                            .momentDisabledReason(momentReason)
                            .todayArticleCount(counts.article())
                            .todayMomentCount(counts.moment())
                            .dailyArticleLimit(ctx.config().dailyArticleLimit())
                            .dailyMomentLimit(ctx.config().dailyMomentLimit())
                            .maxArticleTitleLength(ctx.config().maxArticleTitleLength())
                            .maxArticleContentLength(ctx.config().maxArticleContentLength())
                            .maxMomentLength(ctx.config().maxMomentLength())
                            .maxMomentImages(ctx.config().maxMomentImages())
                            .contentSecurityEnabled(ctx.config().contentSecurityEnabled())
                            .build();
                    }))));
    }

    // ==================== 发文章 ====================

    @Override
    public Mono<UserPublishResultDTO> publishArticle(String openid,
                                                     UserPublishArticleRequest request) {
        var id = normalizeOpenid(openid);
        if (request == null) {
            return Mono.error(new WxAuthException("请求体格式错误"));
        }
        var title = trim(request.getTitle());
        var content = trim(request.getContent());
        if (!StringUtils.hasText(title)) {
            return Mono.error(new WxAuthException("标题不能为空"));
        }
        if (!StringUtils.hasText(content)) {
            return Mono.error(new WxAuthException("正文不能为空"));
        }
        return loadConfig()
            .flatMap(config -> {
                ensureEnabled(config);
                if (title.length() > config.maxArticleTitleLength()) {
                    return Mono.error(new WxAuthException(
                        "标题不能超过 " + config.maxArticleTitleLength() + " 个字"));
                }
                if (content.length() > config.maxArticleContentLength()) {
                    return Mono.error(new WxAuthException(
                        "正文不能超过 " + config.maxArticleContentLength() + " 个字"));
                }
                return requirePublisher(id)
                    .flatMap(ctx -> resolveModeFor(ctx, config)
                        .flatMap(resolved -> {
                            var mode = resolved.mode();
                            if (MODE_DENY.equals(mode)) {
                                return Mono.error(new WxAuthException(
                                    "当前角色不允许投稿文章，如需开通请联系站长"));
                            }
                            var privileged = resolved.privileged();
                            return (privileged ? Mono.<Void>empty()
                                    : ensureDailyLimit(id, ACTION_PUBLISH_ARTICLE,
                                        config.dailyArticleLimit(), "文章"))
                                .then(privileged ? Mono.<Void>empty()
                                    : verifyContent(config, id, title + "\n" + content))
                                .then(chargePoints(id, ACTION_PUBLISH_ARTICLE,
                                    config.articleCost(), "投稿文章", privileged))
                                .flatMap(charged -> publishArticleToHalo(config, ctx, mode,
                                        privileged, title, content, request)
                                    .onErrorResume(ex -> refundAndFail(id, charged,
                                        ACTION_PUBLISH_ARTICLE, config.articleCost(),
                                        "文章发布失败：" + resolveMessage(ex))));
                        }));
            });
    }

    private Mono<UserPublishResultDTO> publishArticleToHalo(PublishConfig config, PublishContext ctx,
                                                            String mode, boolean privileged,
                                                            String title, String content,
                                                            UserPublishArticleRequest request) {
        var dto = HaloPostPublishDTO.builder()
            .title(title)
            // 建稿必须带 slug：Halo 后台建稿、AI 日报发布都带，站点上 225 篇文章没有一篇是空的；
            // 投稿这条路径此前是唯一不设 slug 的，也是唯一在线上被 Halo 以 HTTP 500 拒绝的建稿请求。
            // Halo 服务端不会自己生成 slug，这里补上以对齐已知可用的发布路径。
            .slug(buildArticleSlug(title))
            .markdown(content)
            .cover(trim(request.getCover()))
            // 作者手选的分类/标签：只匹配已有（写错不会造出分类/标签），最多各 3 / 5 个
            .categoryNames(normalizeRefNames(request.getCategories(), MAX_ARTICLE_CATEGORIES))
            .tagNames(normalizeRefNames(request.getTags(), MAX_ARTICLE_TAGS))
            .owner(ctx.haloUserName())
            .build();
        var direct = MODE_DIRECT.equals(mode);
        log.info("[UniHalo][UserPublish] 投稿文章：openid={} owner={} mode={} 特权={} 标题=「{}」 正文 {} 字",
            ctx.openid(), ctx.haloUserName(), mode, privileged, title, content.length());
        var publish = direct
            ? postPublisher.publish(dto, PUBLISH_WAIT_SECONDS)
            : postPublisher.draft(dto);
        return publish.map(result -> UserPublishResultDTO.builder()
            .type("article")
            .mode(mode)
            .statusText(direct ? "已发布" : "已提交，等待站长审核后发布")
            .name(result.getPostName())
            .permalink(absolutize(result.getPermalink()))
            .pointsCost(privileged ? 0 : config.articleCost())
            .balance(0)
            .build());
    }

    /**
     * 作者手选的分类/标签名归一化：去空白、去重、限量。
     *
     * <p>限量是服务端自己的一道闸——界面最多给到 3 个分类 / 5 个标签，
     * 但接口是公开的，不能指望调用方守规矩；真正能不能落到站点上仍由
     * 发布器按「只匹配已有」的规则决定。</p>
     */
    static List<String> normalizeRefNames(List<String> names, int limit) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        var seen = new LinkedHashSet<String>();
        for (var name : names) {
            var trimmed = trim(name);
            if (StringUtils.hasText(trimmed) && trimmed.length() <= 64) {
                seen.add(trimmed);
            }
            if (seen.size() >= limit) {
                break;
            }
        }
        return List.copyOf(seen);
    }

    /**
     * 生成投稿文章的 slug。
     *
     * <p>Halo 的建稿接口不带 slug 会被线上以 500 拒绝（后台 UI 建稿与 AI 日报都带 slug），
     * 这里自己造一个：标题里的 ASCII 片段转连字符形式；中文标题取不到片段时退回
     * {@code post-日期}。末尾统一加 5 位随机后缀，避免同名标题互相覆盖
     * （slug 会进 {@code /archives/{slug}} 永久链接）。</p>
     */
    static String buildArticleSlug(String title) {
        var ascii = (title == null ? "" : title)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+)|(-+$)", "");
        if (ascii.length() > 60) {
            ascii = ascii.substring(0, 60).replaceAll("-+$", "");
        }
        if (!StringUtils.hasText(ascii)) {
            var today = LocalDate.now();
            ascii = String.format("post-%04d%02d%02d",
                today.getYear(), today.getMonthValue(), today.getDayOfMonth());
        }
        return ascii + "-" + String.format("%05x",
            ThreadLocalRandom.current().nextInt(0x100000));
    }

    // ==================== 我的投稿（列表 / 删除） ====================

    @Override
    public Mono<List<UserPublishArticleDTO>> listMyArticles(String openid) {
        var id = normalizeOpenid(openid);
        return requirePublisher(id).flatMap(this::fetchMyArticles);
    }

    /**
     * 拉某个作者的文章列表。
     *
     * <p>「只看自己的」在<b>这里</b>按 {@code spec.owner} 过滤，不能指望 Halo 替我们筛：
     * console 列表接口没有 owner 查询参数（PostQuery 里的 username 恒为 null，传了也被忽略），
     * 实测传了就返回全站第一页——列表里混进别人的文章，删除的归属判断也会跟着失灵。</p>
     */
    private Mono<List<UserPublishArticleDTO>> fetchMyArticles(PublishContext ctx) {
        return collectArticles(ctx.haloUserName(), 0, new ArrayList<>());
    }

    /**
     * 逐页收集自己的文章。
     *
     * <p>归属过滤发生在本地（见 {@link #toArticleList}），所以不能只拉第一页——第一页可能
     * 大半是别人的文章。按 {@value #MY_ARTICLES_PAGE_SIZE}/页 翻到没有下一页为止，
     * 封顶 {@value #MY_ARTICLES_MAX_PAGES} 页，避免站点文章极多时一次拉太久。</p>
     */
    private Mono<List<UserPublishArticleDTO>> collectArticles(String ownerName, int page,
                                                              List<UserPublishArticleDTO> collected) {
        var path = CONSOLE_POSTS_API + "?page=" + page + "&size=" + MY_ARTICLES_PAGE_SIZE
            + "&sort=metadata.creationTimestamp,desc";
        return haloClient.get(path, HALO_CALL_TIMEOUT)
            .onErrorMap(WebClientException.class, ex -> {
                log.warn("[UniHalo][UserPublish] 读取我的投稿失败：owner={} page={} {}",
                    ownerName, page, ex.getMessage(), ex);
                return describeWebClientError("读取我的投稿失败", ex);
            })
            .flatMap(node -> {
                collected.addAll(toArticleList(node.path("items"), ownerName));
                if (!node.path("hasNext").asBoolean(false)) {
                    return Mono.just(List.copyOf(collected));
                }
                if (page + 1 >= MY_ARTICLES_MAX_PAGES) {
                    log.warn("[UniHalo][UserPublish] 我的投稿超过 {} 页，先展示前 {} 篇",
                        MY_ARTICLES_MAX_PAGES, collected.size());
                    return Mono.just(List.copyOf(collected));
                }
                return collectArticles(ownerName, page + 1, collected);
            });
    }

    private static List<UserPublishArticleDTO> toArticleList(JsonNode items, String ownerName) {
        if (items == null || !items.isArray()) {
            return List.of();
        }
        var list = new ArrayList<UserPublishArticleDTO>();
        items.forEach(raw -> {
            // console 列表返回的是 ListedPost：文章本体嵌在 post 字段里
            // （平铺形态也认，Halo 各版本/其它接口口径不一致时不至于读成空标题）
            var item = raw.path("post").isMissingNode() ? raw : raw.path("post");
            var spec = item.path("spec");
            // 回收站里的不再列出：删掉的立刻从列表消失，才符合用户预期
            if (spec.path("deleted").asBoolean(false)) {
                return;
            }
            // 只列自己的：站长/管理员绑定的 Halo 账号就是作者，全站文章因此都在这里
            if (!ownerName.equals(trim(spec.path("owner").asText("")))) {
                return;
            }
            var published = spec.path("publish").asBoolean(false);
            // stats 与 post 在列表项里同级（ListedPost.stats）；文章本体被平铺时读不到，按 0 处理
            var stats = raw.path("stats");
            list.add(UserPublishArticleDTO.builder()
                .name(item.path("metadata").path("name").asText(""))
                .title(spec.path("title").asText(""))
                .slug(spec.path("slug").asText(""))
                .state(published ? "published" : "pending")
                .stateText(published ? "已发布" : "待审 / 未发布")
                .cover(spec.path("cover").asText(""))
                .permalink(item.path("status").path("permalink").asText(""))
                .publishTime(spec.path("publishTime").asText(""))
                .createTime(item.path("metadata").path("creationTimestamp").asText(""))
                .categories(textList(spec.path("categories")))
                .tags(textList(spec.path("tags")))
                .allowComment(spec.path("allowComment").asBoolean(true))
                .hasPendingChanges(hasPendingChanges(spec))
                .lastModifyTime(item.path("status").path("lastModifyTime").asText(""))
                .visitCount(intField(stats, "visit"))
                .upvoteCount(intField(stats, "upvote"))
                .commentCount(intField(stats, "totalComment"))
                .build());
        });
        return List.copyOf(list);
    }

    /**
     * 已发布、但有尚未生效的修改。
     *
     * <p>判据是 head 快照与 release 快照不一致：Halo 前台只渲染 release 快照，
     * 所以作者改完（没有直发权限时）站点上仍是旧内容——列表要如实显示，别让人以为
     * 「保存成功」就等于「已经改了」。</p>
     */
    private static boolean hasPendingChanges(JsonNode spec) {
        if (!spec.path("publish").asBoolean(false)) {
            return false;
        }
        var head = trim(spec.path("headSnapshot").asText(""));
        var release = trim(spec.path("releaseSnapshot").asText(""));
        return StringUtils.hasText(head) && !head.equals(release);
    }

    private static int intField(JsonNode node, String field) {
        var value = node == null ? null : node.path(field);
        return value != null && value.isNumber() ? value.asInt() : 0;
    }

    private static List<String> textList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        var values = new ArrayList<String>();
        node.forEach((value) -> {
            var text = trim(value.asText(""));
            if (StringUtils.hasText(text)) {
                values.add(text);
            }
        });
        return List.copyOf(values);
    }

    @Override
    public Mono<Void> deleteArticle(String openid, String postName) {
        var id = normalizeOpenid(openid);
        var name = trim(postName);
        if (!StringUtils.hasText(name)) {
            return Mono.error(new WxAuthException("缺少文章标识"));
        }
        return requirePublisher(id)
            .flatMap(ctx -> resolveRoles(ctx.haloUserName())
                .flatMap(roles -> resolvePrivileged(ctx, roles)
                    .flatMap(privileged -> {
                        if (privileged) {
                            // 站长/管理员：可直接回收任意一篇（替作者清理也走这里）
                            return recycleArticle(name);
                        }
                        // 普通用户：归属判定复用「按 owner 拉自己的列表」——
                        // 自己的列表里没有这篇，就不是他的文章
                        return fetchMyArticles(ctx).flatMap(items -> items.stream()
                            .anyMatch(item -> name.equals(item.getName()))
                            ? recycleArticle(name)
                            : Mono.error(new WxAuthException("只能删除自己发布的文章")));
                    })));
    }

    /** 回收文章（Halo 的 {@code /recycle}）：进回收站而不是物理删除，站长还能恢复 */
    private Mono<Void> recycleArticle(String name) {
        return haloClient.put(CONSOLE_POSTS_API + "/" + name + "/recycle", null, HALO_CALL_TIMEOUT)
            .then()
            .onErrorMap(WebClientException.class, ex -> {
                log.warn("[UniHalo][UserPublish] 回收文章失败：name={} {}", name, ex.getMessage(), ex);
                return describeWebClientError("删除文章失败", ex);
            });
    }

    // ==================== 我的投稿：编辑 / 发布 / 撤回 ====================

    @Override
    public Mono<UserPublishArticleDetailDTO> getArticle(String openid, String postName) {
        var id = normalizeOpenid(openid);
        var name = trim(postName);
        if (!StringUtils.hasText(name)) {
            return Mono.error(new WxAuthException("缺少文章标识"));
        }
        return requirePublisher(id)
            .flatMap(ctx -> requireOwnedArticle(ctx, name)
                .flatMap(node -> fetchHeadContent(name).map(raw -> toArticleDetail(node, raw))));
    }

    @Override
    public Mono<UserPublishResultDTO> updateArticle(String openid, String postName,
                                                    UserPublishArticleRequest request) {
        var id = normalizeOpenid(openid);
        var name = trim(postName);
        if (!StringUtils.hasText(name)) {
            return Mono.error(new WxAuthException("缺少文章标识"));
        }
        if (request == null) {
            return Mono.error(new WxAuthException("请求体格式错误"));
        }
        var title = trim(request.getTitle());
        var content = trim(request.getContent());
        if (!StringUtils.hasText(title)) {
            return Mono.error(new WxAuthException("标题不能为空"));
        }
        if (!StringUtils.hasText(content)) {
            return Mono.error(new WxAuthException("正文不能为空"));
        }
        return loadConfig()
            .flatMap(config -> {
                // 刻意不检查总开关：开关管的是「能不能投新稿」，作者改自己已经投过的稿不该被连坐；
                // 但字数上限仍按配置挡一道（配置没变，规则就不该变）
                if (title.length() > config.maxArticleTitleLength()) {
                    return Mono.error(new WxAuthException(
                        "标题不能超过 " + config.maxArticleTitleLength() + " 个字"));
                }
                if (content.length() > config.maxArticleContentLength()) {
                    return Mono.error(new WxAuthException(
                        "正文不能超过 " + config.maxArticleContentLength() + " 个字"));
                }
                return requirePublisher(id)
                    .flatMap(ctx -> requireOwnedArticle(ctx, name)
                        .flatMap(node -> resolveModeFor(ctx, config)
                            .flatMap(resolved -> {
                                var spec = node.path("spec");
                                var published = spec.path("publish").asBoolean(false);
                                var privileged = resolved.privileged();
                                // 只有直发权限的角色改完能立刻上线；待审投稿的修改只落到草稿，
                                // 前台仍渲染旧的 release 快照，等站长发布时才生效
                                var republish = published
                                    && (privileged || MODE_DIRECT.equals(resolved.mode()));
                                var dto = HaloPostPublishDTO.builder()
                                    .title(title)
                                    // slug 沿用原值：它是永久链接的一部分，改了会让已分享的链接 404
                                    .slug(spec.path("slug").asText(""))
                                    .markdown(content)
                                    .cover(trim(request.getCover()))
                                    .categoryNames(normalizeRefNames(request.getCategories(),
                                        MAX_ARTICLE_CATEGORIES))
                                    .tagNames(normalizeRefNames(request.getTags(), MAX_ARTICLE_TAGS))
                                    // 作者不跟着改：站长代改别人的文章时，署名仍应是原作者
                                    .owner(spec.path("owner").asText(ctx.haloUserName()))
                                    .allowComment(request.getAllowComment() == null
                                        ? spec.path("allowComment").asBoolean(true)
                                        : request.getAllowComment())
                                    .build();
                                log.info("[UniHalo][UserPublish] 修改投稿：openid={} name={} owner={} "
                                        + "已发布={} 保存后即发布={} 特权={} 标题=「{}」 正文 {} 字",
                                    ctx.openid(), name, dto.getOwner(), published, republish,
                                    privileged, title, content.length());
                                return (privileged ? Mono.<Void>empty()
                                        : verifyContent(config, id, title + "\n" + content))
                                    .then(postPublisher.update(name, dto, republish,
                                        PUBLISH_WAIT_SECONDS))
                                    .map(result -> UserPublishResultDTO.builder()
                                        .type("article")
                                        .mode(resolved.mode())
                                        .statusText(updateStatusText(result, published, republish))
                                        .name(result.getPostName())
                                        .permalink(absolutize(result.getPermalink()))
                                        // 修改不再扣积分：投稿时那次已经扣过，改同一篇内容不该再收费
                                        .pointsCost(0)
                                        .balance(0)
                                        .build());
                            })));
            });
    }

    /**
     * 保存修改后的结果文案。
     *
     * <p>三种结局必须说清楚，否则用户会把「保存成功」直接读成「站点上已经改了」：
     * 还没发布 → 等站长审核；已发布但需审核 → 站点仍是旧内容；有直发权限 → 已生效。</p>
     */
    private static String updateStatusText(HaloPostPublishResultDTO result, boolean published,
                                           boolean republish) {
        if (!published) {
            return "修改已保存，文章仍在待审，站长通过后按最新内容发布";
        }
        if (!republish) {
            return "修改已保存；文章已发布，需站长重新发布后才会生效";
        }
        return "PUBLISHED".equals(result.getState()) ? "修改已保存并已生效" : result.getMessage();
    }

    @Override
    public Mono<UserPublishResultDTO> publishArticleNow(String openid, String postName) {
        var id = normalizeOpenid(openid);
        var name = trim(postName);
        if (!StringUtils.hasText(name)) {
            return Mono.error(new WxAuthException("缺少文章标识"));
        }
        return loadConfig()
            .flatMap(config -> requirePublisher(id)
                .flatMap(ctx -> requireOwnedArticle(ctx, name)
                    .flatMap(node -> resolveModeFor(ctx, config)
                        .flatMap(resolved -> {
                            if (!resolved.privileged() && !MODE_DIRECT.equals(resolved.mode())) {
                                // 待审投稿若能自行发布，审核就只是界面上的装饰
                                return Mono.error(new WxAuthException(
                                    "你的投稿需要站长审核后发布，暂不能自行发布"));
                            }
                            var spec = node.path("spec");
                            var permalink = node.path("status").path("permalink").asText("");
                            var published = spec.path("publish").asBoolean(false);
                            if (published && !hasPendingChanges(spec)) {
                                // 已经是发布状态且没有待生效的修改：当成功返回，别让用户以为是失败
                                log.info("[UniHalo][UserPublish] 文章已是发布状态，跳过发布：name={}", name);
                                return Mono.just(UserPublishResultDTO.builder()
                                    .type("article")
                                    .mode(MODE_DIRECT)
                                    .statusText("文章当前已是发布状态")
                                    .name(name)
                                    .permalink(absolutize(permalink))
                                    .pointsCost(0)
                                    .balance(0)
                                    .build());
                            }
                            log.info("[UniHalo][UserPublish] 发布投稿：openid={} name={} 已发布={} 特权={}",
                                ctx.openid(), name, published, resolved.privileged());
                            return postPublisher.republish(name, PUBLISH_WAIT_SECONDS)
                                .map(result -> UserPublishResultDTO.builder()
                                    .type("article")
                                    .mode(MODE_DIRECT)
                                    .statusText("PUBLISHED".equals(result.getState())
                                        ? "已发布" : result.getMessage())
                                    .name(name)
                                    .permalink(absolutize(StringUtils.hasText(result.getPermalink())
                                        ? result.getPermalink() : permalink))
                                    .pointsCost(0)
                                    .balance(0)
                                    .build());
                        }))));
    }

    @Override
    public Mono<Void> unpublishArticle(String openid, String postName) {
        var id = normalizeOpenid(openid);
        var name = trim(postName);
        if (!StringUtils.hasText(name)) {
            return Mono.error(new WxAuthException("缺少文章标识"));
        }
        return loadConfig()
            .flatMap(config -> requirePublisher(id)
                .flatMap(ctx -> requireOwnedArticle(ctx, name)
                    .flatMap(node -> resolveModeFor(ctx, config)
                        .flatMap(resolved -> {
                            if (!resolved.privileged() && !MODE_DIRECT.equals(resolved.mode())) {
                                // 与发布同一道门槛：能推上线的人才能撤回来，否则撤回是一条死路
                                return Mono.error(new WxAuthException(
                                    "你的投稿由站长审核管理，暂不能自行撤回，请联系站长"));
                            }
                            if (!node.path("spec").path("publish").asBoolean(false)) {
                                return Mono.error(new WxAuthException("文章当前不是发布状态，无需撤回"));
                            }
                            log.info("[UniHalo][UserPublish] 撤回发布：openid={} name={}",
                                ctx.openid(), name);
                            return postPublisher.unpublish(name)
                                .onErrorMap(WebClientException.class, ex -> {
                                    log.warn("[UniHalo][UserPublish] 撤回发布失败：name={} {}",
                                        name, ex.getMessage(), ex);
                                    return describeWebClientError("撤回发布失败", ex);
                                });
                        }))));
    }

    /**
     * 读一篇文章并校验操作权：作者本人，或博主/管理员。
     *
     * <p>与删除同一口径（先读到再判归属），但读的是<b>单篇</b>而不是自己的列表——
     * 编辑要拿整篇文章回传，列表里的 DTO 不够用；顺带也省掉了「翻 5 页才找到这篇」。</p>
     */
    private Mono<JsonNode> requireOwnedArticle(PublishContext ctx, String name) {
        return fetchArticleNode(name).flatMap(node -> {
            if (node.path("spec").path("deleted").asBoolean(false)) {
                return Mono.error(new WxAuthException("文章已在回收站中，无法编辑"));
            }
            var owner = trim(node.path("spec").path("owner").asText(""));
            if (owner.equals(ctx.haloUserName())) {
                return Mono.just(node);
            }
            return resolveRoles(ctx.haloUserName())
                .flatMap(roles -> resolvePrivileged(ctx, roles)
                    .flatMap(privileged -> privileged
                        ? Mono.just(node)
                        : Mono.error(new WxAuthException("只能操作自己发布的文章"))));
        });
    }

    /**
     * 按 metadata.name 读一篇文章（含未发布草稿）。
     *
     * <p>走核心组的 {@code content.halo.run/posts/{name}}：公开读接口对未发布文章一律 404，
     * 而作者最常编辑的恰恰是那些还没发布的投稿。</p>
     */
    private Mono<JsonNode> fetchArticleNode(String name) {
        return haloClient.get(CORE_POSTS_API + "/" + name, HALO_CALL_TIMEOUT)
            .filter(node -> name.equals(node.path("metadata").path("name").asText("")))
            .switchIfEmpty(Mono.error(new WxAuthException("文章不存在或已被删除")))
            .onErrorResume(WebClientResponseException.NotFound.class,
                ex -> Mono.error(new WxAuthException("文章不存在或已被删除")))
            .onErrorMap(WebClientException.class, ex -> {
                log.warn("[UniHalo][UserPublish] 读取文章失败：name={} {}", name, ex.getMessage(), ex);
                return describeWebClientError("读取文章失败", ex);
            });
    }

    /**
     * 读正文原文（Markdown）用于编辑回填。
     *
     * <p>读不到就报错而不是回填空串：编辑保存是「整份替换」，正文读失败却继续下去，
     * 等于让用户用一次保存把文章正文清空。</p>
     */
    private Mono<String> fetchHeadContent(String name) {
        return haloClient.get(CONSOLE_POSTS_API + "/" + name + "/head-content", HALO_CALL_TIMEOUT)
            .map(node -> node.path("raw").asText(""))
            .onErrorMap(WebClientException.class, ex -> {
                log.warn("[UniHalo][UserPublish] 读取正文失败：name={} {}", name, ex.getMessage(), ex);
                return describeWebClientError("读取文章正文失败", ex);
            });
    }

    private static UserPublishArticleDetailDTO toArticleDetail(JsonNode node, String raw) {
        var spec = node.path("spec");
        var published = spec.path("publish").asBoolean(false);
        return UserPublishArticleDetailDTO.builder()
            .name(node.path("metadata").path("name").asText(""))
            .title(spec.path("title").asText(""))
            .content(raw)
            .slug(spec.path("slug").asText(""))
            .cover(spec.path("cover").asText(""))
            .categories(textList(spec.path("categories")))
            .tags(textList(spec.path("tags")))
            .allowComment(spec.path("allowComment").asBoolean(true))
            .published(published)
            .hasPendingChanges(hasPendingChanges(spec))
            .stateText(published ? "已发布" : "待审 / 未发布")
            .permalink(node.path("status").path("permalink").asText(""))
            .publishTime(spec.path("publishTime").asText(""))
            .createTime(node.path("metadata").path("creationTimestamp").asText(""))
            .build();
    }

    // ==================== 发瞬间 ====================
    @Override
    public Mono<UserPublishResultDTO> publishMoment(String openid,
                                                    UserPublishMomentRequest request) {
        var id = normalizeOpenid(openid);
        if (request == null) {
            return Mono.error(new WxAuthException("请求体格式错误"));
        }
        var content = trim(request.getContent());
        var images = request.getImages() == null ? List.<String>of() : request.getImages().stream()
            .map(UserPublishServiceImpl::trim)
            .filter(StringUtils::hasText)
            .toList();
        if (!StringUtils.hasText(content) && images.isEmpty()) {
            return Mono.error(new WxAuthException("请输入内容或选择图片"));
        }
        return loadConfig()
            .flatMap(config -> {
                ensureEnabled(config);
                if (content.length() > config.maxMomentLength()) {
                    return Mono.error(new WxAuthException(
                        "内容不能超过 " + config.maxMomentLength() + " 个字"));
                }
                if (images.size() > config.maxMomentImages()) {
                    return Mono.error(new WxAuthException(
                        "图片最多 " + config.maxMomentImages() + " 张"));
                }
                return requirePublisher(id)
                    .flatMap(ctx -> resolveModeFor(ctx, config)
                        .flatMap(resolved -> {
                            var mode = resolved.mode();
                            if (MODE_DENY.equals(mode)) {
                                return Mono.error(new WxAuthException(
                                    "当前角色不允许发布瞬间，如需开通请联系站长"));
                            }
                            var privileged = resolved.privileged();
                            return (privileged ? Mono.<Void>empty()
                                    : ensureDailyLimit(id, ACTION_PUBLISH_MOMENT,
                                        config.dailyMomentLimit(), "瞬间"))
                                .then(privileged ? Mono.<Void>empty()
                                    : verifyContent(config, id, content))
                                .then(chargePoints(id, ACTION_PUBLISH_MOMENT,
                                    config.momentCost(), "发布瞬间", privileged))
                                .flatMap(charged -> publishMomentToHalo(config, ctx, mode,
                                        privileged, content, images)
                                    .onErrorResume(ex -> refundAndFail(id, charged,
                                        ACTION_PUBLISH_MOMENT, config.momentCost(),
                                        "瞬间发布失败：" + resolveMessage(ex))));
                        }));
            });
    }

    private Mono<UserPublishResultDTO> publishMomentToHalo(PublishConfig config, PublishContext ctx,
                                                           String mode, boolean privileged,
                                                           String content, List<String> images) {
        var html = plainTextToHtml(content);
        var medium = new ArrayList<Map<String, String>>();
        for (var url : images) {
            medium.add(Map.of("type", "PHOTO", "url", url));
        }
        var spec = new HashMap<String, Object>();
        spec.put("content", Map.of(
            "raw", StringUtils.hasText(html) ? html : content,
            "html", html,
            "medium", medium));
        spec.put("owner", ctx.haloUserName());
        // 必须是带时区的 ISO-8601（Instant）：Moment 模型的 releaseTime 是 Instant 类型，
        // 传 LocalDateTime（如 2026-09-30T16:45:12.345，无 Z）会让 Halo 反序列化失败并返回 400
        spec.put("releaseTime", Instant.now().toString());
        // 瞬间插件没有草稿态，「待审」映射为 approved=false，由站长在瞬间管理里通过后才公开
        spec.put("approved", MODE_DIRECT.equals(mode));
        spec.put("visible", "PUBLIC");
        var payload = Map.of(
            "apiVersion", "moment.halo.run/v1alpha1",
            "kind", "Moment",
            "metadata", Map.of("generateName", "moment-"),
            "spec", spec);
        log.info("[UniHalo][UserPublish] 投稿瞬间：openid={} owner={} mode={} 特权={} 文字 {} 字 图片 {} 张",
            ctx.openid(), ctx.haloUserName(), mode, privileged, content.length(), images.size());
        return haloWrite(MOMENTS_API, payload)
            .map(name -> UserPublishResultDTO.builder()
                .type("moment")
                .mode(mode)
                .statusText(MODE_DIRECT.equals(mode) ? "已发布" : "已提交，等待站长审核")
                .name(name)
                .permalink("")
                .pointsCost(privileged ? 0 : config.momentCost())
                .balance(0)
                .build());
    }

    @Override
    public Mono<String> uploadMomentImage(String openid, FilePart filePart) {
        var id = normalizeOpenid(openid);
        if (filePart == null) {
            return Mono.error(new WxAuthException("缺少 file 字段"));
        }
        return loadConfig()
            .flatMap(config -> {
                ensureEnabled(config);
                return requirePublisher(id)
                    .then(loadAttachmentPolicy())
                    .flatMap(policy -> attachmentService.upload("admin", policy.policyName(),
                            policy.fileGroupName(), filePart, null)
                        .flatMap(attachment -> attachmentService.getPermalink(attachment)
                            .map(URI::toString)));
            });
    }

    @Override
    public Mono<Void> deleteMoment(String openid, String momentName) {
        var id = normalizeOpenid(openid);
        var name = trim(momentName);
        if (!StringUtils.hasText(name)) {
            return Mono.error(new WxAuthException("缺少瞬间标识"));
        }
        return requirePublisher(id)
            .flatMap(ctx -> resolveRoles(ctx.haloUserName())
                .flatMap(roles -> resolvePrivileged(ctx, roles)
                    .flatMap(privileged -> fetchMoment(name)
                        .flatMap(node -> {
                            var owner = trim(node.path("spec").path("owner").asText(""));
                            if (!privileged && !owner.equals(ctx.haloUserName())) {
                                return Mono.error(new WxAuthException("只能删除自己发布的瞬间"));
                            }
                            return deleteMomentByApi(name);
                        }))));
    }

    /** WebClient 全家桶（连接失败/非 JSON 响应/HTTP 错误状态）统一转成带原因的业务异常 */
    private WxAuthException describeWebClientError(String action, WebClientException ex) {
        if (ex instanceof WebClientResponseException resp) {
            // 带上 Halo 的响应体：它通常直接说明是哪个字段不合法（owner 不存在、spec 校验失败…），
            // 只给状态码的话站长在客户端只能看到「HTTP 403」，无从下手
            return new WxAuthException(action + "（HTTP " + resp.getStatusCode().value()
                + summarizeBody(resp.getResponseBodyAsString()) + "）");
        }
        var message = ex.getMessage();
        return StringUtils.hasText(message)
            ? new WxAuthException(action + "：" + message)
            : new WxAuthException(action + "失败");
    }

    /** 把 Halo 的错误响应体压成一行摘要（截断到 160 字符），供客户端提示与日志使用 */
    private static String summarizeBody(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        var oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 160 ? "：" + oneLine : "：" + oneLine.substring(0, 160) + "…";
    }

    /** 读取单个瞬间（删除前校验归属用）：优先公开查询 API，404 时退回标准路径 */
    private Mono<JsonNode> fetchMoment(String name) {
        if (!StringUtils.hasText(name)) {
            return Mono.error(new WxAuthException("缺少瞬间标识"));
        }
        return loadPersonalToken().flatMap(token -> haloClient
            .get(MOMENTS_QUERY_API + "/" + name, HALO_CALL_TIMEOUT)
            .onErrorResume(WebClientResponseException.NotFound.class, ex -> {
                log.warn("[UniHalo][UserPublish] 公开查询 API 返回 404，改用标准路径重试读取瞬间");
                return haloClient.get(MOMENTS_API + "/" + name, HALO_CALL_TIMEOUT);
            })
            // 只捕 WebClientResponseException 会漏掉连接失败/非 JSON 响应，
            // 它们会一路裸抛变成「投稿失败，请稍后重试」，看不出病根
            .onErrorMap(WebClientException.class, ex -> {
                log.warn("[UniHalo][UserPublish] 读取瞬间失败：{}", ex.getMessage(), ex);
                return describeWebClientError("读取瞬间失败", ex);
            }));
    }

    private Mono<Void> deleteMomentByApi(String name) {
        return loadPersonalToken().flatMap(token -> haloClient
            .delete(MOMENTS_API + "/" + name, HALO_CALL_TIMEOUT)
            .onErrorResume(WebClientException.class, ex -> {
                log.warn("[UniHalo][UserPublish] 删除瞬间失败：{}", ex.getMessage(), ex);
                return Mono.error(describeWebClientError("删除失败", ex));
            }));
    }

    @Override
    public Mono<JsonNode> submitVote(String openid, String voteName, JsonNode body) {
        var id = normalizeOpenid(openid);
        var name = trim(voteName);
        if (!StringUtils.hasText(name)) {
            return Mono.error(new WxAuthException("缺少投票标识"));
        }
        return wxUserService.fetchByOpenid(id)
            .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))
            .then(loadPersonalToken())
            .flatMap(token -> haloClient
                .post(VOTE_SUBMIT_API + "/" + name + "/submit",
                    body == null ? Map.of() : body, HALO_CALL_TIMEOUT)
                .onErrorMap(WebClientException.class, ex -> {
                    log.warn("[UniHalo][UserPublish] 提交投票失败：{}", ex.getMessage(), ex);
                    return describeWebClientError("投票提交失败", ex);
                }));
    }

    // ==================== 校验与副作用 ====================

    private Mono<PublishContext> requirePublisher(String openid) {
        return requirePublisher(openid, null);
    }

    private Mono<PublishContext> requirePublisher(String openid, PublishConfig config) {
        return wxUserService.fetchByOpenid(openid)
            .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))
            .flatMap(user -> {
                if (!StringUtils.hasText(user.getHaloUserName())) {
                    if (config == null || config.requireHaloBind()) {
                        return Mono.error(new WxAuthException(
                            "请先关联 Halo 账号后再投稿（我的 → 关联 Halo 账号）"));
                    }
                    return Mono.error(new WxAuthException(
                        "当前微信账号未关联 Halo 用户，无法确定作者身份"));
                }
                return Mono.just(new PublishContext(openid, user.getHaloUserName(), false,
                    config));
            });
    }

    /** 解析该用户两类内容的发布方式（角色 → 策略表） */
    private Mono<RoleModes> resolveModes(PublishConfig config, WxUser user) {
        if (user == null || !StringUtils.hasText(user.getHaloUserName())) {
            return Mono.just(new RoleModes(config.defaultMode(), config.defaultMode(), Set.of(),
                false));
        }
        return resolveRoles(user.getHaloUserName())
            .flatMap(roles -> isPrivileged(user, roles).map(privileged -> {
                if (privileged) {
                    // 博主/管理员：直接发布且不扣分——他们本来就是站点所有者，
                    // 让自己给自己交积分的规则没有意义
                    return new RoleModes(MODE_DIRECT, MODE_DIRECT, roles, true);
                }
                return new RoleModes(modeFor(config, roles), modeFor(config, roles), roles, false);
            }));
    }

    /**
     * 是否站点所有者（博主）/管理员。
     *
     * <p>两条判定：① 微信 openid 与后台配置的博主 openid 一致（
     * {@code wxAppConfig.ownerOpenid}，站长显式声明）；② Halo 角色含 super-role/admin。
     * 命中即为特权：发布免费、直接公开、不受每日上限约束、跳过内容检测。</p>
     */
    private Mono<Boolean> isPrivileged(WxUser user, Set<String> roles) {
        return privilegeService.isPrivileged(user, roles);
    }

    /** 解析发布方式与特权身份（特权=博主/管理员：免费、直发、不限次） */
    private Mono<ResolvedMode> resolveModeFor(PublishContext ctx, PublishConfig config) {
        return resolveRoles(ctx.haloUserName())
            .flatMap(roles -> resolvePrivileged(ctx, roles)
                .map(privileged -> new ResolvedMode(modeFor(config, roles), privileged)));
    }

    private Mono<Boolean> resolvePrivileged(PublishContext ctx, Set<String> roles) {
        if (roles.stream().anyMatch(r -> PRIVILEGED_ROLES.contains(r.toLowerCase(Locale.ROOT)))) {
            return Mono.just(true);
        }
        return settingFetcher.get("wxAppConfig")
            .map(config -> {
                var ownerOpenid = trim(config.path("ownerOpenid").asText(""));
                return StringUtils.hasText(ownerOpenid) && ownerOpenid.equals(ctx.openid());
            })
            .defaultIfEmpty(false);
    }

    /**
     * 角色 → 发布方式。
     *
     * <p>命中角色策略表就用表里的；没命中用默认策略（默认 draft，安全优先：
     * 站长新加的自定义角色不会因为「没登记」而被当成可信角色直接发布）。</p>
     *
     * <p>文章与瞬间共用同一张表，但可以分别覆盖：策略表里 mode 决定「直发/待审/禁止」，
     * 落到文章是草稿、落到瞬间是待审核。</p>
     */
    private String modeFor(PublishConfig config, Set<String> roles) {
        for (var role : roles) {
            var mode = config.roleModes().get(role);
            if (StringUtils.hasText(mode)) {
                return normalizeMode(mode);
            }
        }
        return normalizeMode(config.defaultMode());
    }

    /** 读取用户角色（委托给统一实现，见 PrivilegeService） */
    private Mono<Set<String>> resolveRoles(String haloUserName) {
        return privilegeService.rolesOf(haloUserName);
    }


    private Mono<Void> ensureDailyLimit(String openid, String action, int limit, String label) {
        if (limit <= 0) {
            return Mono.empty();
        }
        return countToday(openid, action)
            .flatMap(count -> count >= limit
                ? Mono.error(new WxAuthException("今日" + label + "已达上限（" + limit + " 次）"))
                : Mono.empty());
    }

    /** 内容安全检测：仅在开启时调用，检测不通过直接拒绝（UGC 合规要求） */
    private Mono<Void> verifyContent(PublishConfig config, String openid, String content) {
        if (!config.contentSecurityEnabled() || !StringUtils.hasText(content)) {
            return Mono.empty();
        }
        return contentSecurityService.checkText(openid, content);
    }

    /**
     * 扣分：先写幂等流水，成功后再改余额。
     *
     * <p>与积分兑换同一套顺序——流水资源名由 earnKey 派生故唯一，并发下只有一个能创建成功；
     * 若反过来先改余额，两个并发请求会各自扣一次却只记一笔账。</p>
     */
    private Mono<Charged> chargePoints(String openid, String action, int cost, String reason,
                                       boolean privileged) {
        // 博主/管理员免费：他们发布的是站内内容，不该自己给自己交积分
        if (cost <= 0 || privileged) {
            return Mono.just(new Charged(0, 0));
        }
        return pointsPersistence.getOrCreateAccount(openid)
            .flatMap(account -> {
                var balance = safeBalance(account);
                if (balance < cost) {
                    return Mono.error(new WxAuthException(
                        "积分不足：本次需要 " + cost + " 积分，当前 " + balance + " 积分"));
                }
                var earnKey = buildLedgerKey(openid, action);
                // 参数语义与积分兑换一致：description 是给用户看的名称，refName 是详细说明
                var record = pointsPersistence.newRecord(openid, action, -cost, balance - cost,
                    reason, "投稿消耗积分", earnKey, account.getWxUserId());                return pointsPersistence.appendRecord(record)
                    .flatMap(saved -> {
                        account.setBalance(balance - cost);
                        return pointsPersistence.saveAccount(account)
                            .thenReturn(new Charged(cost, balance - cost));
                    });
            });
    }

    /** 发布失败：补一条正向流水把积分退回去，避免「扣了积分但内容没发出去」 */
    private <T> Mono<T> refundAndFail(String openid, Charged charged, String action,
                                      int cost, String message) {
        if (charged.cost() <= 0) {
            return Mono.error(new WxAuthException(message));
        }
        return pointsPersistence.getOrCreateAccount(openid)
            .flatMap(account -> {
                var balance = safeBalance(account);
                var earnKey = buildLedgerKey(openid, ACTION_PUBLISH_REFUND);
                var record = pointsPersistence.newRecord(openid, ACTION_PUBLISH_REFUND, cost,
                    balance + cost, "投稿失败退还", action, earnKey, account.getWxUserId());
                return pointsPersistence.appendRecord(record)
                    .flatMap(saved -> {
                        account.setBalance(balance + cost);
                        return pointsPersistence.saveAccount(account);
                    })
                    .onErrorResume(ex -> {
                        log.error("[UniHalo][UserPublish] 退还积分失败：openid={} cost={}",
                            openid, cost, ex);
                        return Mono.empty();
                    });
            })
            .then(Mono.error(new WxAuthException(message + "（已退还积分）")));
    }

    private Mono<String> haloWrite(String path, Object payload) {
        return loadPersonalToken()
            .flatMap(token -> haloClient.post(path, payload, HALO_CALL_TIMEOUT)
                .map(node -> node.path("metadata").path("name").asText(""))
                .onErrorMap(WebClientException.class, ex -> {
                    log.warn("[UniHalo][UserPublish] 写瞬间失败：{}", ex.getMessage(), ex);
                    return describeWebClientError("瞬间接口调用失败", ex);
                }));
    }

    private Mono<String> loadPersonalToken() {
        return settingFetcher.get("basicConfig")
            .map(config -> trim(config.path("tokenConfig").path("personalToken").asText("")))
            .filter(StringUtils::hasText)
            .switchIfEmpty(Mono.error(new WxAuthException(
                "未配置 basicConfig.tokenConfig.personalToken，请在插件基本配置中填写个人令牌")));
    }

    private Mono<AttachmentPolicy> loadAttachmentPolicy() {
        return settingFetcher.get("appConfig")
            .map(config -> {
                var appInfo = config.path("appInfo");
                var policyName = trim(appInfo.path("policyName").asText(""));
                return new AttachmentPolicy(
                    StringUtils.hasText(policyName) ? policyName : "default-policy",
                    trim(appInfo.path("fileGroupName").asText("")));
            })
            .defaultIfEmpty(new AttachmentPolicy("default-policy", ""));
    }

    private static String plainTextToHtml(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        var sb = new StringBuilder();
        for (var line : text.split("\\r?\\n")) {
            sb.append("<p>").append(escapeHtml(line)).append("</p>");
        }
        return sb.toString();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private Mono<DayCount> countToday(String openid) {
        return Mono.zip(countToday(openid, ACTION_PUBLISH_ARTICLE),
            countToday(openid, ACTION_PUBLISH_MOMENT), DayCount::new);
    }

    private Mono<Integer> countToday(String openid, String action) {
        var today = LocalDate.now().toString();
        return pointsPersistence.listRecordsByOpenid(openid)
            .map(records -> {
                var count = 0;
                for (PointsRecord record : records) {
                    if (!action.equals(record.getAction())) {
                        continue;
                    }
                    var key = record.getEarnKey();
                    if (StringUtils.hasText(key) && key.contains(today)) {
                        count++;
                    }
                }
                return count;
            })
            .defaultIfEmpty(0);
    }

    /** 幂等键：openid + 日期 + 动作 + 纳秒时间戳（并发下同纳秒的概率可忽略） */
    private static String buildLedgerKey(String openid, String action) {
        return openid + ":" + LocalDate.now() + ":" + action + ":"
            + System.nanoTime();
    }

    private static long safeBalance(UserPoints account) {
        if (account == null || account.getBalance() == null) {
            return 0;
        }
        return account.getBalance();
    }

    private static String absolutize(String url) {
        return url == null ? "" : url.trim();
    }

    /** 不可发布的原因（空串=可以发布），前端直接展示这句话 */
    private static String disabledReason(PublishConfig config, String mode, boolean haloBound,
                                         long balance, int cost, int todayCount, int dailyLimit,
                                         String label) {
        if (!config.enabled()) {
            return "站长未开启投稿功能";
        }
        if (MODE_DENY.equals(mode)) {
            return "当前角色不允许发布" + label;
        }
        if (!haloBound) {
            return "请先关联 Halo 账号";
        }
        if (dailyLimit > 0 && todayCount >= dailyLimit) {
            return "今日" + label + "已达上限";
        }
        if (balance < cost) {
            return "积分不足，还差 " + (cost - balance) + " 积分";
        }
        return "";
    }

    private Mono<PublishConfig> loadConfig() {
        return settingFetcher.get(CONFIG_GROUP)
            .map(UserPublishServiceImpl::toConfig)
            .defaultIfEmpty(toConfig(null));
    }

    private static PublishConfig toConfig(JsonNode node) {
        if (node == null || node.isNull()) {
            return new PublishConfig(false, false, 0, DEFAULT_ARTICLE_COST, DEFAULT_MOMENT_COST,
                true, 0, 0, DEFAULT_MAX_TITLE_LENGTH, DEFAULT_MAX_ARTICLE_LENGTH,
                DEFAULT_MAX_MOMENT_LENGTH, DEFAULT_MAX_MOMENT_IMAGES, true, MODE_DRAFT, Map.of());
        }
        var roleModes = new HashMap<String, String>();
        var roles = node.path("rolePolicies");
        if (roles.isArray()) {
            for (var item : roles) {
                var role = trim(item.path("role").asText("")).toLowerCase(Locale.ROOT);
                var mode = normalizeMode(item.path("mode").asText(""));
                if (StringUtils.hasText(role)) {
                    roleModes.put(role, mode);
                }
            }
        }
        return new PublishConfig(
            node.path("enabled").asBoolean(false),
            node.path("showButton").asBoolean(true),
            Math.max(0, node.path("minPointsToShow").asInt(0)),
            Math.max(0, node.path("articleCost").asInt(DEFAULT_ARTICLE_COST)),
            Math.max(0, node.path("momentCost").asInt(DEFAULT_MOMENT_COST)),
            node.path("requireHaloBind").asBoolean(true),
            Math.max(0, node.path("dailyArticleLimit").asInt(0)),
            Math.max(0, node.path("dailyMomentLimit").asInt(0)),
            Math.max(1, node.path("maxArticleTitleLength").asInt(DEFAULT_MAX_TITLE_LENGTH)),
            Math.max(1, node.path("maxArticleContentLength").asInt(DEFAULT_MAX_ARTICLE_LENGTH)),
            Math.max(1, node.path("maxMomentLength").asInt(DEFAULT_MAX_MOMENT_LENGTH)),
            Math.max(0, node.path("maxMomentImages").asInt(DEFAULT_MAX_MOMENT_IMAGES)),
            node.path("contentSecurityEnabled").asBoolean(true),
            normalizeMode(node.path("defaultRoleMode").asText(MODE_DRAFT)),
            roleModes);
    }

    private static void ensureEnabled(PublishConfig config) {
        if (!config.enabled()) {
            throw new WxAuthException("站长尚未开启投稿功能");
        }
    }

    private static String normalizeMode(String raw) {
        var mode = trim(raw).toLowerCase(Locale.ROOT);
        return switch (mode) {
            case MODE_DIRECT -> MODE_DIRECT;
            case MODE_DENY -> MODE_DENY;
            default -> MODE_DRAFT;
        };
    }

    private static String normalizeOpenid(String openid) {
        if (!StringUtils.hasText(openid)) {
            throw new WxAuthException("缺少 Wechat-Session-Id");
        }
        return openid.trim();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private static String resolveMessage(Throwable ex) {
        var message = ex.getMessage();
        return StringUtils.hasText(message) ? message : "未知错误";
    }

    private record PublishConfig(boolean enabled, boolean showButton, int minPointsToShow,
                                 int articleCost, int momentCost, boolean requireHaloBind,
                                 int dailyArticleLimit, int dailyMomentLimit,
                                 int maxArticleTitleLength, int maxArticleContentLength,
                                 int maxMomentLength, int maxMomentImages,
                                 boolean contentSecurityEnabled, String defaultMode,
                                 Map<String, String> roleModes) {
    }

    private record PublishContext(String openid, String haloUserName, boolean privileged,
                                  PublishConfig config) {
    }

    private record StatusContext(PublishConfig config, UserPoints account, WxUser user) {
    }

    private record RoleModes(String articleMode, String momentMode, Set<String> roles,
                             boolean privileged) {
    }

    private record ResolvedMode(String mode, boolean privileged) {
    }

    private record Charged(int cost, long balance) {
    }

    private record DayCount(int article, int moment) {
    }

    private record AttachmentPolicy(String policyName, String fileGroupName) {
    }
}
