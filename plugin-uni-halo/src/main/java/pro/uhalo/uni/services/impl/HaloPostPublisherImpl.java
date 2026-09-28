package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.dto.DigestCategoryViewDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.dto.DigestPreflightItemDTO;
import pro.uhalo.uni.dto.HaloPostPublishDTO;
import pro.uhalo.uni.dto.HaloPostPublishResultDTO;
import pro.uhalo.uni.services.DigestHaloClient;
import pro.uhalo.uni.services.DigestMarkdownRenderer;
import pro.uhalo.uni.services.DigestPostWriter;
import pro.uhalo.uni.services.DigestTaxonomyService;
import pro.uhalo.uni.services.HaloPostPublisher;
import pro.uhalo.uni.utils.DigestCategoryCatalog;
import pro.uhalo.uni.utils.DigestConfigHelper;
import pro.uhalo.uni.utils.DigestCoverGenerator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 用 Halo 官方 Console 接口建稿并发布。
 *
 * <p>「长文不要超时」是这块的核心约束，做法是把发布拆成三步，每一步都有独立的超时与重试，
 * 任何一步都不会变成一个长时间挂起的请求：</p>
 *
 * <ol>
 *   <li><b>建稿</b>（同步，180s 上限）：只写入 head snapshot，正文多大都是本地落盘，很快；</li>
 *   <li><b>触发发布</b>（同步，180s 上限）：带 {@code async=true}，Halo 排队后立即返回，
 *       渲染 release snapshot 的动作在服务端后台完成；</li>
 *   <li><b>轮询确认</b>（每 5s 一次，最长 {@code publishWaitSeconds}）：读文章的
 *       {@code spec.releaseSnapshot} 是否追平 head snapshot，追平即发布完成。</li>
 * </ol>
 *
 * <p>这样即使 Halo 渲染一篇接近百 KB 的日报要几分钟，插件侧也只是在轮询，
 * 不会出现「请求挂住直到网关超时」的情况。发布触发失败会自动重试一次，
 * 轮询超时后也会再补触发一次，避免异步任务偶发丢失导致当天没有文章。</p>
 */
@Slf4j
@Service
public class HaloPostPublisherImpl implements HaloPostPublisher {

    private static final Duration CREATE_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration REF_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

    private static final String POST_API = Constants.HALO_CONSOLE_POST_API_PREFIX;
    private static final String CONTENT_POST_API = Constants.HALO_POST_API_PREFIX;
    private static final String USERS_API = Constants.HALO_CORE_USERS_API;
    private static final String CONSOLE_USERS_API = Constants.HALO_CONSOLE_USERS_API;
    private static final String POSTS_LIST_API =
        Constants.HALO_CONSOLE_POST_API_PREFIX + "?page=0&size=1";
    private static final String POST_KIND = "content.halo.run/v1alpha1";
    private static final String RAW_TYPE_MARKDOWN = "markdown";

    private final DigestHaloClient haloClient;
    private final DigestMarkdownRenderer markdownRenderer;
    private final DigestPostWriter postWriter;
    private final DigestTaxonomyService taxonomy;

    public HaloPostPublisherImpl(DigestHaloClient haloClient,
        DigestMarkdownRenderer markdownRenderer, DigestPostWriter postWriter,
        DigestTaxonomyService taxonomy) {
        this.haloClient = haloClient;
        this.markdownRenderer = markdownRenderer;
        this.postWriter = postWriter;
        this.taxonomy = taxonomy;
    }

    @Override
    public Mono<HaloPostPublishResultDTO> draft(HaloPostPublishDTO post) {
        return warnIfOwnerUnavailable(post)
            .then(resolveRefs(post))
            .flatMap(resolved -> createPost(post, resolved))
            .map(created -> HaloPostPublishResultDTO.builder()
                .postName(created.postName())
                .permalink(created.permalink())
                .state("PENDING")
                .message("已建稿，尚未发布")
                .build());
    }

    @Override
    public Mono<HaloPostPublishResultDTO> publish(HaloPostPublishDTO post, int waitSeconds) {
        var budget = Duration.ofSeconds(Math.max(60, waitSeconds));
        return publishOverHttp(post, budget)
            .onErrorResume(httpError -> {
                // 官方 HTTP 接口整条链路都失败时（实测某些实例上令牌鉴权的请求体会丢字段），
                // 退回插件原生方式：直接用扩展客户端写 Post + Snapshot，绕开 HTTP 与鉴权。
                log.warn("[UniHalo][Digest] 官方 HTTP 接口建稿全部失败（{}），"
                    + "改用扩展客户端直接写入", DigestHaloClient.describe(httpError));
                return postWriter.writeAndPublish(post)
                    .doOnNext(result -> log.warn("[UniHalo][Digest] 扩展客户端写入成功：name={} "
                            + "url={}（已绕过 HTTP 接口）",
                        result.getPostName(), result.getPermalink()))
                    .onErrorMap(writeError -> new IllegalStateException(
                        "创建文章失败：HTTP 接口 " + DigestHaloClient.describe(httpError)
                            + "；扩展客户端 " + DigestHaloClient.describe(writeError), writeError));
            });
    }

    /** 走官方 Console 接口：建稿（三级尝试）→ 触发发布 → 轮询确认 */
    private Mono<HaloPostPublishResultDTO> publishOverHttp(HaloPostPublishDTO post, Duration budget) {
        return assertOwnerExistsForHttp(post)
            .then(resolveRefs(post))
            .flatMap(resolved -> createPost(post, resolved))
            .flatMap(created -> triggerPublish(created, true)
                .then(awaitRelease(created, budget))
                .flatMap(state -> {
                    if (state.released()) {
                        return Mono.just(state);
                    }
                    log.warn("[UniHalo][Digest] 等待发布确认超时，补触发一次 name={}",
                        created.postName());
                    var retryBudget = Duration.ofSeconds(Math.max(60, budget.getSeconds() / 2));
                    return triggerPublish(created, false)
                        .then(awaitRelease(created, retryBudget));
                })
                .map(state -> toResult(created, state, budget)));
    }

    /** 作者校验（只告警）——HTTP 路径使用；扩展客户端路径不需要 */
    private Mono<Void> assertOwnerExistsForHttp(HaloPostPublishDTO post) {
        return warnIfOwnerUnavailable(post);
    }

    @Override
    public Mono<HaloPostPublishResultDTO> republish(String postName, int waitSeconds) {
        var budget = Duration.ofSeconds(Math.max(60, waitSeconds));
        log.info("[UniHalo][Digest] 重新发布已有文章：name={}（不重新生成正文）", postName);
        return fetchPost(postName)
            .switchIfEmpty(Mono.error(new IllegalStateException("Halo 中找不到文章：" + postName)))
            .flatMap(node -> {
                var headSnapshot = node.path("spec").path("headSnapshot").asText("");
                if (!StringUtils.hasText(headSnapshot)) {
                    return Mono.error(new IllegalStateException(
                        "文章缺少 headSnapshot，无法发布：" + postName));
                }
                var permalink = node.path("status").path("permalink").asText("");
                var created = new CreatedPost(postName, headSnapshot, permalink);
                if (evaluate(node, created).released()) {
                    log.info("[UniHalo][Digest] 该文章已是发布状态，无需重新发布：name={} url={}",
                        postName, permalink);
                    return Mono.just(HaloPostPublishResultDTO.builder()
                        .postName(postName)
                        .permalink(permalink)
                        .state("PUBLISHED")
                        .message("已发布")
                        .build());
                }
                return triggerPublish(created, true)
                    .then(awaitRelease(created, budget))
                    .flatMap(state -> {
                        if (state.released()) {
                            return Mono.just(state);
                        }
                        var retryBudget = Duration.ofSeconds(Math.max(60, budget.getSeconds() / 2));
                        return triggerPublish(created, false)
                            .then(awaitRelease(created, retryBudget));
                    })
                    .map(state -> toResult(created, state, budget));
            });
    }

    /**
     * 建稿前对文章作者做一次「尽力而为」的检查。
     *
     * <p>这里**只告警、不阻断**：用户查询接口在不同 Halo 版本/权限下行为不一致
     * （核心组单查甚至会直接 404），把它当成硬性前置条件会误杀正常发布——
     * 真正的判据交给 Halo 建稿时给出的报错。查到确实不存在时会在日志里列出
     * 现有用户名，方便核对拼写。</p>
     */
    private Mono<Void> warnIfOwnerUnavailable(HaloPostPublishDTO post) {
        var owner = StringUtils.hasText(post.getOwner()) ? post.getOwner().trim() : "admin";
        return resolveUser(owner)
            .doOnNext(lookup -> {
                if (lookup.exists()) {
                    log.info("[UniHalo][Digest] 建稿前校验作者：{} 存在{}", owner,
                        StringUtils.hasText(lookup.displayName())
                            ? "（" + lookup.displayName() + "）" : "");
                } else if (lookup.verified()) {
                    log.warn("[UniHalo][Digest] 文章作者「{}」不在 Halo 用户列表里（现有用户：{}），"
                            + "仍会尝试建稿；若 Halo 报错请把「插件设置 - AI 日报 - 文章作者」改成登录名",
                        owner, lookup.sampleUsers());
                } else {
                    log.info("[UniHalo][Digest] 作者校验未能完成（{}），不影响发布",
                        lookup.reason());
                }
            })
            .onErrorResume(ex -> Mono.empty())
            .then();
    }

    /**
     * 查一个用户名是否存在。
     *
     * <p>以控制台用户列表为准（后台用户管理页用的就是它，能看到全部用户名）；
     * 列表查不了时退一步用核心组单查，且单查只用来确认「存在」，不用 404 反推「不存在」。</p>
     */
    private Mono<UserLookup> resolveUser(String owner) {
        return listUsers(owner)
            .onErrorResume(listError -> haloClient.get(USERS_API + "/" + owner, REF_TIMEOUT)
                .map(node -> UserLookup.found(node.path("spec").path("displayName").asText(""), ""))
                .onErrorResume(singleError -> Mono.just(UserLookup.unknown(
                    "用户列表 " + DigestHaloClient.describe(listError)))));
    }

    private Mono<UserLookup> listUsers(String owner) {
        return haloClient.get(CONSOLE_USERS_API + "?page=0&size=100", REF_TIMEOUT)
            .map(node -> {
                var names = new ArrayList<String>();
                var matchedDisplayName = "";
                var exists = false;
                for (var item : node.path("items")) {
                    var name = item.path("metadata").path("name").asText("");
                    if (!StringUtils.hasText(name)) {
                        continue;
                    }
                    names.add(name);
                    if (owner.equalsIgnoreCase(name)) {
                        exists = true;
                        matchedDisplayName = item.path("spec").path("displayName").asText("");
                    }
                }
                if (exists) {
                    return UserLookup.found(matchedDisplayName, String.join("、", names));
                }
                if (names.isEmpty()) {
                    // 列表请求成功却一个用户都没有，几乎只可能是权限/接口层面的问题，
                    // 不能据此断言「作者不存在」（哪怕报错也要报得准）
                    return UserLookup.unknown("用户列表返回为空，无法确认");
                }
                return UserLookup.missing(String.join("、", names));
            });
    }

    // ── 发布环境自检 ──    // ── 发布环境自检 ──

    @Override
    public Mono<List<DigestPreflightItemDTO>> preflight(DigestConfigDTO config) {
        var owner = StringUtils.hasText(config.getPostOwner()) ? config.getPostOwner().trim() : "admin";
        return Mono.zip(checkConsoleAccess(), checkOwner(owner), checkRefs(config),
                checkBodyBinding())
            .map(tuple -> {
                var items = new ArrayList<DigestPreflightItemDTO>();
                items.add(tuple.getT1());
                items.add(tuple.getT2());
                items.addAll(tuple.getT3());
                items.add(tuple.getT4());
                items.add(checkCoverFallback(config));
                return items;
            });
    }

    /**
     * 配图兜底自检：生成的标题卡片用的哪个字体。
     *
     * <p>中文字体是内置的（{@code digest/cover-font.otf}），所以正常情况下一定是 ✔；
     * 这里报出来是为了让站长知道卡片上的中文靠谁渲染——真出问题时（例如发行包少了资源）
     * 也能一眼看出是字体没加载上，而不是去猜图片为什么没字。</p>
     */
    private DigestPreflightItemDTO checkCoverFallback(DigestConfigDTO config) {
        if (!config.isImageEnabled() || !config.isRequireImage()) {
            return preflightItem("配图兜底", true, "未开启「只发布带封面图的文章」，用不到兜底封面");
        }
        var mode = config.getCoverFallbackMode();
        if (!DigestConfigHelper.COVER_FALLBACK_PLACEHOLDER.equals(mode)) {
            return preflightItem("配图兜底", true,
                "配不到原文头图时按设置处理：" + DigestConfigHelper.coverFallbackLabel(mode));
        }
        return DigestCoverGenerator.cjkAvailable()
            ? preflightItem("配图兜底", true, "配不到头图时生成标题卡片封面，字体："
                + DigestCoverGenerator.fontSourceLabel())
            : preflightItem("配图兜底", false,
                "内置中文字体没能加载，且服务器上也没有中文字体：生成的标题卡片不会包含中文标题"
                    + "（日期与来源仍会显示）。请确认发行包里带着 digest/cover-font.otf，"
                    + "或在服务器安装 fonts-noto-cjk 后重启 Halo。");
    }

    /**
     * 用一次后台文章列表请求验证令牌：能读就能走同一套权限链路建稿，
     * 失败时按状态码给出可操作的结论而不是原始异常。
     */
    private Mono<DigestPreflightItemDTO> checkConsoleAccess() {
        return haloClient.get(POSTS_LIST_API, REF_TIMEOUT)
            .map(node -> preflightItem("个人令牌 / 后台权限", true,
                "令牌有效，可以访问 Halo 后台文章接口"))
            .onErrorResume(ex -> Mono.just(preflightItem("个人令牌 / 后台权限", false,
                explainAuthError(ex))));
    }

    private Mono<DigestPreflightItemDTO> checkOwner(String owner) {
        return resolveUser(owner)
            .map(lookup -> {
                if (lookup.exists()) {
                    return preflightItem("文章作者", true, "「" + owner + "」存在"
                        + (StringUtils.hasText(lookup.displayName())
                        ? "（" + lookup.displayName() + "）" : ""));
                }
                if (lookup.verified()) {
                    return preflightItem("文章作者", false, "「" + owner
                        + "」不在 Halo 用户列表里；当前用户："
                        + (StringUtils.hasText(lookup.sampleUsers())
                        ? lookup.sampleUsers() : "（列表为空）")
                        + "。请把设置里的文章作者改成这里的登录名。");
                }
                // 查不了就说查不了：不能凭一次失败的查询判定用户名有问题
                return preflightItem("文章作者", true, "未能校验作者（" + lookup.reason()
                    + "）：不影响发布，若建稿报错请再核对用户名");
            })
            .defaultIfEmpty(preflightItem("文章作者", true, "未能校验作者（无返回）"));
    }

    /**
     * 请求体解析自检：发一个「字段类型故意不对」的请求体，用 Halo 的反应判断请求体到底有没有被读到。
     *
     * <p>正常情况 Halo 会因为类型不符回 400（说明它读到了字段）；
     * 如果回的还是那个「post is null」的 500，说明请求体在到达 Halo 解析器之前就被改动或丢弃了——
     * 这时问题不在插件侧，而在站点上的某个请求包装插件（页面缓存类最常见）或中间层。</p>
     */
    private Mono<DigestPreflightItemDTO> checkBodyBinding() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var probe = mapper.createObjectNode();
        // 故意把 post 写成字符串：能被解析就一定报类型错误，不可能悄悄建出文章
        probe.put("post", "not-an-object-on-purpose");
        probe.putNull("content");
        return haloClient.post(POST_API, probe, REF_TIMEOUT)
            .map(node -> preflightItem("请求体解析", true,
                "Halo 接受了这个故意写错的请求体（未预期的成功），请把该情况反馈给插件维护者"))
            .onErrorResume(ex -> {
                if (ex instanceof WebClientResponseException response) {
                    var status = response.getStatusCode().value();
                    if (status == 400 || status == 422) {
                        return Mono.just(preflightItem("请求体解析", true,
                            "Halo 能读到请求体并识别出字段类型不对（HTTP " + status
                                + "）——说明请求体送达正常，建稿失败的原因在字段内容上"));
                    }
                    if (status >= 500) {
                        return Mono.just(preflightItem("请求体解析", false,
                            "Halo 收到了请求却读不到其中的字段（HTTP " + status
                                + "，与建稿失败是同一现象）——请求体在到达 Halo 解析器之前被改动或丢弃。"
                                + "插件侧已无法再优化，建议临时停用「页面缓存」类插件后重试；"
                                + "若站点前面还有反向代理/防火墙，也请检查其是否改写 POST 请求体"));
                    }
                }
                return Mono.just(preflightItem("请求体解析", true,
                    "未能完成校验：" + DigestHaloClient.describe(ex)));
            });
    }

    private Mono<List<DigestPreflightItemDTO>> checkRefs(DigestConfigDTO config) {
        var categories = DigestConfigHelper.splitNames(config.getPostCategories());
        var tags = DigestConfigHelper.splitNames(config.getPostTags());
        return Mono.zip(
                taxonomy.resolveExistingCategories(categories),
                taxonomy.resolveExistingTags(tags),
                taxonomy.categoryMatchNames())
            .map(tuple -> List.of(
                refItem("额外附加分类", categories, tuple.getT1()),
                refItem("额外附加标签", tags, tuple.getT2()),
                autoRefItem(config),
                categoryCatalogItem(config, tuple.getT3())))
            .defaultIfEmpty(List.of());
    }

    /**
     * 分类清单自检项。
     *
     * <p>把「清单里每一行发布时会落到哪里」的结论直接摆进自检：reuse / map 模式下
     * 匹配不上的行是静默跳过的，不提示就等于让站长以为配置已经生效了。</p>
     */
    private static DigestPreflightItemDTO categoryCatalogItem(DigestConfigDTO config,
        java.util.Set<String> existingNames) {
        var view = DigestCategoryViewDTO.of(
            DigestCategoryCatalog.from(config.getDigestCategories()),
            config.getCategoryMode(), config.getUnknownCategoryPolicy(), existingNames);
        var message = new StringBuilder(view.source()).append(" · ").append(view.modeLabel())
            .append(" · 共 ").append(view.count()).append(" 个分类")
            .append(DigestConfigHelper.UNKNOWN_CATEGORY_DROP.equalsIgnoreCase(
                view.unknownCategoryPolicy())
                ? "；AI 给出清单外的分类时不打分类"
                : "；AI 给出清单外的分类时归入兜底分类");
        if (view.problemCount() == 0) {
            return preflightItem("日报分类", true, message.toString());
        }
        var problems = view.items().stream()
            .filter(DigestCategoryViewDTO.Item::problem)
            .map(item -> item.key() + "（" + item.message() + "）")
            .toList();
        return preflightItem("日报分类", false, message.append("。其中 ").append(view.problemCount())
            .append(" 行发布时会被跳过：").append(String.join("；", problems))
            .append("。可在「日报分类」面板查看完整清单").toString());
    }

    /**
     * 自动打标的说明项。
     *
     * <p>早前自检会告诉你「哪些名字匹配不到、发布时会被忽略」，言下之意是「你得先去后台把
     * 分类标签建好」。现在主题分类与关键词标签由插件自动创建（可按配置改成只复用或映射），
     * 无需预建——自检要把这层变化讲清楚，否则站长还会以为要手工配。</p>
     */
    private static DigestPreflightItemDTO autoRefItem(DigestConfigDTO config) {
        var parts = new ArrayList<String>();
        if (config.isAutoCategoryEnabled()) {
            parts.add("按文章主题自动归入站点分类（"
                + DigestConfigHelper.categoryModeLabel(config.getCategoryMode()) + "）");
        }
        if (config.isAutoTagEnabled()) {
            parts.add("把一轮里出现 ≥" + config.getAutoTagMinCount() + " 次的关键词建成标签");
        }
        if (parts.isEmpty()) {
            return preflightItem("自动分类/标签", true, "已关闭，文章只会带上你填的额外附加项");
        }
        return preflightItem("自动分类/标签", true, "已开启：" + String.join("；", parts)
            + "。自动建出来的分类/标签带 " + DigestTaxonomyService.AUTO_GENERATED_LABEL
            + " 标签，便于在后台区分。");
    }

    private static DigestPreflightItemDTO refItem(String label, List<String> wanted,
        List<String> matched) {
        if (wanted.isEmpty()) {
            return preflightItem(label, true, "未填写（可不填：自动分类已覆盖常见场景）");
        }
        if (matched.size() == wanted.size()) {
            return preflightItem(label, true, "全部匹配：" + String.join("、", matched));
        }
        var missing = wanted.stream().filter(name -> !matched.contains(name)).toList();
        // 手填的名字匹配不到只会被忽略，不影响发布成功，因此仍算通过但要提醒
        return preflightItem(label, true, "匹配到 " + matched.size() + "/" + wanted.size()
            + "，未匹配的会被忽略（手填项不会自动创建，避免笔误造出分类）："
            + String.join("、", missing));
    }

    private static DigestPreflightItemDTO preflightItem(String item, boolean ok, String message) {
        return DigestPreflightItemDTO.builder().item(item).ok(ok).message(message).build();
    }

    private static String explainAuthError(Throwable ex) {
        if (ex instanceof WebClientResponseException response) {
            return switch (response.getStatusCode().value()) {
                case 401 -> "令牌无效或已过期：请在 Halo 后台「个人资料 - 个人令牌」重新生成后"
                    + "填到「插件设置 - 基本配置 - 个人令牌」";
                case 403 -> "令牌权限不足：请使用超级管理员的个人令牌（该接口需要后台文章权限）";
                case 404 -> "接口路径不存在：当前 Halo 版本可能不支持该接口（需要 >= 2.17）";
                default -> "请求失败：" + DigestHaloClient.describe(ex);
            };
        }
        return "无法连接 Halo 接口：" + DigestHaloClient.describe(ex);
    }

    // ── 第一步：建稿 ──

    private Mono<CreatedPost> createPost(HaloPostPublishDTO post, ResolvedRefs refs) {
        var name = UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
        var fullBody = buildCreateBody(post, refs, name);
        var minimalBody = buildMinimalCreateBody(post, name);
        // 三级尝试逐级收敛，每一级对应一种病因，日志能直接看出问题出在哪一层：
        //   1) 完整字段（{post, content} 包裹 + 全部可选字段）
        //   2) 精简字段（包裹不变，只留必需项）—— 成功则说明是某个可选字段被拒
        //   3) 两步法：先只发 post 建空稿，再用扁平 body 单独设置内容
        //      —— 成功则说明是 {post, content} 这个包裹结构本身绑不进去
        return postForCreate(fullBody, name, "完整字段")
            .onErrorResume(fullError -> {
                log.warn("[UniHalo][Digest] 建稿失败（{}），改用精简字段重试。本次请求体：{}",
                    DigestHaloClient.describe(fullError), abbreviate(fullBody.toString(), 1500));
                return postForCreate(minimalBody, name, "精简字段")
                    .doOnNext(created -> log.warn("[UniHalo][Digest] 精简字段建稿成功——问题出在"
                        + "被省略的可选字段上（封面 / excerpt / 分类 / 标签 / htmlMetas / slug / "
                        + "priority 之一），上面的请求体可用于定位"))
                    .onErrorResume(minimalError -> {
                        log.warn("[UniHalo][Digest] 精简字段仍失败（{}），改用两步法："
                                + "先只发 post 建空稿，再用扁平 body 写内容",
                            DigestHaloClient.describe(minimalError));
                        return createPostThenSetContent(post, refs, name)
                            .doOnNext(created -> log.warn("[UniHalo][Digest] 两步法建稿成功——"
                                + "说明问题出在 {post, content} 包裹结构上（Halo 绑定该包裹时 "
                                + "post 为空），已绕过"))
                            .onErrorMap(stepError -> new IllegalStateException(
                                "创建文章失败：完整字段 " + DigestHaloClient.describe(fullError)
                                    + "；精简字段 " + DigestHaloClient.describe(minimalError)
                                    + "；两步法 " + DigestHaloClient.describe(stepError),
                                stepError));
                    });
            });
    }

    /**
     * 两步法建稿：先只提交 post，再单独写内容。
     *
     * <p>两步的请求体都不含 {@code {post, content}} 包裹：
     * 第一步只发 post；第二步发的是扁平的 {@code {raw, content, rawType}}，
     * 由 Halo 服务端自己组装成 PostRequest 再落快照——因此完全绕开包裹结构的绑定。</p>
     */
    private Mono<CreatedPost> createPostThenSetContent(HaloPostPublishDTO post, ResolvedRefs refs,
        String name) {
        return postForCreate(buildPostOnlyBody(post, refs, name), name, "两步法-仅 post")
            .flatMap(created -> setPostContent(created, post.getMarkdown()));
    }

    private Mono<CreatedPost> setPostContent(CreatedPost created, String markdown) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var body = mapper.createObjectNode();
        var raw = markdown == null ? "" : markdown;
        body.put("raw", raw);
        // 同样要自己渲染：这个接口的 content 也是给前台展示用的 HTML
        body.put("content", markdownRenderer.render(raw));
        body.put("rawType", RAW_TYPE_MARKDOWN);
        log.info("[UniHalo][Digest] 两步法第二步：设置文章内容 PUT {}/{}/content （正文 {} 字符）",
            POST_API, created.postName(), markdown == null ? 0 : markdown.length());
        return haloClient.put(POST_API + "/" + created.postName() + "/content", body, CREATE_TIMEOUT)
            .map(response -> {
                var headSnapshot = response.path("spec").path("headSnapshot").asText("");
                if (!StringUtils.hasText(headSnapshot)) {
                    throw new IllegalStateException("设置文章内容成功但未返回 headSnapshot，无法发布");
                }
                log.info("[UniHalo][Digest] 两步法第二步完成：name={} headSnapshot={}",
                    created.postName(), headSnapshot);
                return new CreatedPost(created.postName(), headSnapshot, created.permalink());
            });
    }

    /** 只带 post 的建稿请求体（不含 content 兄弟姐妹字段） */
    private ObjectNode buildPostOnlyBody(HaloPostPublishDTO post, ResolvedRefs refs, String name) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.createObjectNode();
        var postNode = root.putObject("post");
        postNode.put("apiVersion", POST_KIND);
        postNode.put("kind", "Post");
        var metadata = postNode.putObject("metadata");
        metadata.put("name", name);
        metadata.putObject("annotations");
        var spec = postNode.putObject("spec");
        spec.put("title", post.getTitle());
        spec.put("slug", post.getSlug());
        spec.put("owner", StringUtils.hasText(post.getOwner()) ? post.getOwner() : "admin");
        spec.put("template", "");
        spec.put("cover", post.getCover() == null ? "" : post.getCover());
        spec.put("deleted", false);
        spec.put("publish", false);
        spec.put("pinned", false);
        spec.put("allowComment", post.isAllowComment());
        spec.put("visible", "PUBLIC");
        spec.put("priority", 0);
        spec.putObject("excerpt").put("autoGenerate", true);
        var categories = spec.putArray("categories");
        refs.categories().forEach(categories::add);
        var tags = spec.putArray("tags");
        refs.tags().forEach(tags::add);
        spec.putArray("htmlMetas");
        return root;
    }

    private Mono<CreatedPost> postForCreate(ObjectNode body, String name, String label) {
        var startedAt = System.currentTimeMillis();
        log.info("[UniHalo][Digest] 调用 Halo 官方接口建稿（{}）：POST {} name={}", label, POST_API, name);
        return haloClient.post(POST_API, body, CREATE_TIMEOUT)
            .map(response -> {
                // 建稿响应可能是 Post 本身，也可能包一层 post 字段
                var node = response.has("post") ? response.path("post") : response;
                var postName = node.path("metadata").path("name").asText("");
                var headSnapshot = node.path("spec").path("headSnapshot").asText("");
                var permalink = node.path("status").path("permalink").asText("");
                if (!StringUtils.hasText(postName)) {
                    throw new IllegalStateException("Halo 建稿成功但未返回文章标识：" + response);
                }
                if (!StringUtils.hasText(headSnapshot)) {
                    // 两步法的第一步只建空稿，快照要等第二步写内容才生成，这里不算失败
                    log.info("[UniHalo][Digest] 建稿成功（{}）：name={} 暂未生成快照（等设置内容）",
                        label, postName);
                } else {
                    log.info("[UniHalo][Digest] 建稿成功（{}）：name={} headSnapshot={} 耗时 {}ms",
                        label, postName, headSnapshot, System.currentTimeMillis() - startedAt);
                }
                return new CreatedPost(postName, headSnapshot, permalink);
            });
    }

    /**
     * 写入正文：raw 存 markdown 原文，content 存渲染后的 HTML。
     *
     * <p>Halo 不会在服务端渲染 markdown，content 为空的话文章正文就是空的，
     * 所以这里必须自己渲染。</p>
     */
    private void putContent(ObjectNode root, String markdown) {
        var raw = markdown == null ? "" : markdown;
        var content = root.putObject("content");
        content.put("raw", raw);
        content.put("content", markdownRenderer.render(raw));
        content.put("rawType", RAW_TYPE_MARKDOWN);
    }

    /** 完整字段的建稿请求体（封面、摘要、分类、标签、htmlMetas 等一并带上） */
    private ObjectNode buildCreateBody(HaloPostPublishDTO post, ResolvedRefs refs, String name) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.createObjectNode();

        var postNode = root.putObject("post");
        postNode.put("apiVersion", POST_KIND);
        postNode.put("kind", "Post");

        var metadata = postNode.putObject("metadata");
        metadata.put("name", name);
        metadata.putObject("annotations");

        var spec = postNode.putObject("spec");
        spec.put("title", post.getTitle());
        spec.put("slug", post.getSlug());
        spec.put("owner", StringUtils.hasText(post.getOwner()) ? post.getOwner() : "admin");
        spec.put("template", "");
        spec.put("cover", post.getCover() == null ? "" : post.getCover());
        spec.put("deleted", false);
        // 先按草稿创建，发布单独走 /publish，两段分开才能分别设超时
        spec.put("publish", false);
        spec.put("pinned", false);
        spec.put("allowComment", post.isAllowComment());
        spec.put("visible", "PUBLIC");
        spec.put("priority", 0);
        spec.putObject("excerpt").put("autoGenerate", true);
        var categories = spec.putArray("categories");
        refs.categories().forEach(categories::add);
        var tags = spec.putArray("tags");
        refs.tags().forEach(tags::add);
        spec.putArray("htmlMetas");

        putContent(root, post.getMarkdown());
        return root;
    }

    /** 精简字段：只保留 Halo 建稿必需的项，用于在完整字段被拒时定位问题 */
    private ObjectNode buildMinimalCreateBody(HaloPostPublishDTO post, String name) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.createObjectNode();
        var postNode = root.putObject("post");
        postNode.put("apiVersion", POST_KIND);
        postNode.put("kind", "Post");
        postNode.putObject("metadata").put("name", name);
        var spec = postNode.putObject("spec");
        spec.put("title", post.getTitle());
        spec.put("owner", StringUtils.hasText(post.getOwner()) ? post.getOwner() : "admin");
        // 先建草稿，发布单独走 /publish
        spec.put("publish", false);
        spec.put("visible", "PUBLIC");
        putContent(root, post.getMarkdown());
        return root;
    }

    private static String abbreviate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "…（已截断）";
    }

    // ── 第二步：触发发布 ──

    private Mono<JsonNode> triggerPublish(CreatedPost created, boolean allowRetry) {
        if (!StringUtils.hasText(created.headSnapshot())) {
            return Mono.error(new IllegalStateException(
                "文章 name=" + created.postName() + " 没有 headSnapshot（内容未写入成功），无法发布"));
        }
        var path = POST_API + "/" + created.postName() + "/publish"
            + "?headSnapshot=" + URLEncoder.encode(created.headSnapshot(), StandardCharsets.UTF_8)
            + "&async=true";
        var startedAt = System.currentTimeMillis();
        log.info("[UniHalo][Digest] 触发发布（异步）：PUT {} 文章 name={}", path, created.postName());
        return haloClient.put(path, null, PUBLISH_TIMEOUT)
            .doOnSuccess(ignored -> log.info(
                "[UniHalo][Digest] 发布请求已受理（异步渲染中）name={} 耗时 {}ms",
                created.postName(), System.currentTimeMillis() - startedAt))
            .onErrorResume(ex -> {
                if (!allowRetry) {
                    return Mono.error(new IllegalStateException(
                        "触发发布失败：" + DigestHaloClient.describe(ex), ex));
                }
                log.warn("[UniHalo][Digest] 触发发布失败，5 秒后重试一次：{}",
                    DigestHaloClient.describe(ex));
                return Mono.delay(Duration.ofSeconds(5))
                    .then(triggerPublish(created, false));
            });
    }

    // ── 第三步：轮询确认 ──

    private Mono<ReleaseState> awaitRelease(CreatedPost created, Duration budget) {
        var maxPolls = Math.max(1, budget.getSeconds() / POLL_INTERVAL.getSeconds());
        log.info("[UniHalo][Digest] 开始轮询发布结果：最长 {}s，每 {}s 一次（name={}）",
            budget.getSeconds(), POLL_INTERVAL.getSeconds(), created.postName());
        return Flux.interval(Duration.ZERO, POLL_INTERVAL)
            .take(maxPolls)
            .index()
            .concatMap(tick -> fetchPost(created.postName())
                .map(node -> evaluate(node, created))
                .doOnNext(state -> logProgress(tick.getT2(), state, created))
                .onErrorResume(ex -> Mono.just(ReleaseState.pending("",
                    "轮询文章状态失败：" + DigestHaloClient.describe(ex)))))
            .takeUntil(ReleaseState::released)
            .last()
            .timeout(budget.plusSeconds(60))
            .doOnNext(state -> {
                if (state.released()) {
                    log.info("[UniHalo][Digest] 发布确认完成：name={} permalink={}",
                        created.postName(), state.permalink());
                }
            });
    }

    /** 每 30 秒打一次进度，便于日志里看出「卡在哪一步」而不是一片安静 */
    private void logProgress(long ticks, ReleaseState state, CreatedPost created) {
        if (state.released() || ticks == 0 || ticks % 6 != 0) {
            return;
        }
        log.info("[UniHalo][Digest] 等待发布快照中…已等待 {}s（name={} 说明={}）",
            ticks * POLL_INTERVAL.getSeconds(), created.postName(), state.message());
    }

    private Mono<JsonNode> fetchPost(String postName) {
        return haloClient.get(CONTENT_POST_API + "/" + postName, REF_TIMEOUT);
    }

    private ReleaseState evaluate(JsonNode post, CreatedPost created) {
        var spec = post.path("spec");
        var releaseSnapshot = spec.path("releaseSnapshot").asText("");
        var currentHead = spec.path("headSnapshot").asText("");
        var labels = post.path("metadata").path("labels");
        var published = "true".equalsIgnoreCase(
            labels.path(Constants.HALO_POST_PUBLISHED_LABEL).asText(""));
        var phase = post.path("status").path("phase").asText("");
        var permalink = post.path("status").path("permalink").asText("");

        // release snapshot 追平 head snapshot 即视为发布完成；published 标签与
        // PUBLISHED 阶段任一同向命中都算数，避免个别版本状态更新顺序差异导致误判
        var effectiveHead = StringUtils.hasText(currentHead) ? currentHead : created.headSnapshot();
        var released = (StringUtils.hasText(releaseSnapshot) && releaseSnapshot.equals(effectiveHead))
            || published
            || "PUBLISHED".equalsIgnoreCase(phase);

        if (released) {
            return ReleaseState.released(StringUtils.hasText(permalink)
                ? permalink : created.permalink());
        }
        // 把观察到的字段带进消息里：卡住时日志能直接看出是「快照没生成」还是「状态没刷新」
        return ReleaseState.pending(permalink, "等待服务端生成发布快照（head=" + brief(effectiveHead)
            + " release=" + brief(releaseSnapshot) + " phase=" + (StringUtils.hasText(phase) ? phase : "-")
            + " published=" + published + "）");
    }

    private static String brief(String value) {
        if (!StringUtils.hasText(value)) {
            return "-";
        }
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private HaloPostPublishResultDTO toResult(CreatedPost created, ReleaseState state,
        Duration budget) {
        if (state.released()) {
            return HaloPostPublishResultDTO.builder()
                .postName(created.postName())
                .permalink(state.permalink())
                .state("PUBLISHED")
                .message("已发布")
                .build();
        }
        log.warn("[UniHalo][Digest] {} 秒内未确认发布完成（{}），文章已建稿 name={}，"
                + "可在 Halo 后台文章列表确认或点「重新发布」",
            budget.getSeconds(), state.message(), created.postName());
        return HaloPostPublishResultDTO.builder()
            .postName(created.postName())
            .permalink(StringUtils.hasText(state.permalink())
                ? state.permalink() : created.permalink())
            .state("PENDING")
            .message("文章已建稿但未能在 " + budget.getSeconds() + " 秒内确认发布完成（"
                + state.message() + "），可在 Halo 后台文章列表确认或点「重新发布」")
            .build();
    }

    // ── 分类 / 标签解析 ──

    /**
     * 解析分类与标签。
     *
     * <p>这里统一交给 {@link DigestTaxonomyService}，两条发布路径（本类的 HTTP 路径与
     * {@code DigestPostWriter} 的扩展客户端路径）共用同一套逻辑——
     * 否则会出现「一边把分类建出来了、另一边还在静默忽略」的分裂行为。</p>
     *
     * <p>两类名字的处理方式刻意不同：手填的「额外附加」只匹配已有（手误不该造出分类），
     * 而 AI 判定出来的分类与关键词<b>不存在则自动创建</b>，这才是「不需要站长手工配置」；
     * 自动分类是否允许创建、是否按清单映射，由 {@link HaloPostPublishDTO#getCategoryMode()} 决定
     * （{@code DigestTaxonomyService#resolveAiCategories}）。</p>
     */
    private Mono<ResolvedRefs> resolveRefs(HaloPostPublishDTO post) {
        return Mono.zip(
                merge(taxonomy.resolveExistingCategories(post.getCategoryNames()),
                    taxonomy.resolveAiCategories(post.getAiCategories(), post.getCatalog(),
                        post.getCategoryMode())),
                merge(taxonomy.resolveExistingTags(post.getTagNames()),
                    taxonomy.resolveOrCreateTags(post.getKeywords())))
            .map(tuple -> new ResolvedRefs(tuple.getT1(), tuple.getT2()));
    }

    /** 合并两类来源的分类/标签名：去重并保持先后顺序 */
    private static Mono<List<String>> merge(Mono<List<String>> manual, Mono<List<String>> auto) {
        return manual.zipWith(auto).map(tuple -> {
            var merged = new java.util.LinkedHashSet<String>();
            merged.addAll(tuple.getT1());
            merged.addAll(tuple.getT2());
            return List.copyOf(merged);
        });
    }

    /**
     * 把配置里的分类/标签名称换算成 metadata.name。
     *
     * <p>匹配不到的直接跳过：宁可文章少一个分类，也不要因为站点没建过这个分类就发布失败。</p>
     */
    private Mono<List<String>> resolveNames(String apiPath, List<String> wanted) {
        if (wanted == null || wanted.isEmpty()) {
            return Mono.just(List.of());
        }
        return haloClient.get(apiPath, REF_TIMEOUT)
            .<List<String>>map(response -> matchNames(response, wanted))
            .defaultIfEmpty(List.of())
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 读取 {} 失败，跳过该项：{}",
                    apiPath, DigestHaloClient.describe(ex));
                return Mono.just(List.of());
            });
    }

    private static List<String> matchNames(JsonNode response, List<String> wanted) {
        var items = response.path("items");
        List<String> resolved = new ArrayList<>();
        for (var name : wanted) {
            if (!StringUtils.hasText(name)) {
                continue;
            }
            var target = name.trim();
            for (var item : items) {
                var metadataName = item.path("metadata").path("name").asText("");
                var displayName = item.path("spec").path("displayName").asText("");
                if (target.equalsIgnoreCase(metadataName)
                    || target.equalsIgnoreCase(displayName)) {
                    if (!resolved.contains(metadataName)) {
                        resolved.add(metadataName);
                    }
                    break;
                }
            }
        }
        return resolved;
    }

    private record CreatedPost(String postName, String headSnapshot, String permalink) {
    }

    private record ResolvedRefs(List<String> categories, List<String> tags) {
    }

    /**
     * 用户名查询结果。
     *
     * @param verified    查询本身是否成功（false 表示查不了，不能据此下结论）
     * @param exists      用户是否存在
     * @param displayName 匹配到的显示名
     * @param sampleUsers 现有用户名，用于核对拼写
     * @param reason      查不了时的原因
     */
    private record UserLookup(boolean verified, boolean exists, String displayName,
                              String sampleUsers, String reason) {

        static UserLookup found(String displayName, String sampleUsers) {
            return new UserLookup(true, true, displayName, sampleUsers, null);
        }

        static UserLookup missing(String sampleUsers) {
            return new UserLookup(true, false, null, sampleUsers, null);
        }

        static UserLookup unknown(String reason) {
            return new UserLookup(false, false, null, null, reason);
        }
    }

    private record ReleaseState(boolean released, String permalink, String message) {

        static ReleaseState released(String permalink) {
            return new ReleaseState(true, permalink == null ? "" : permalink, "已发布");
        }

        static ReleaseState pending(String permalink, String message) {
            return new ReleaseState(false, permalink == null ? "" : permalink, message);
        }
    }
}
