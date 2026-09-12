package pro.uhalo.uni.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.HaloPostPublishDTO;
import pro.uhalo.uni.dto.HaloPostPublishResultDTO;
import pro.uhalo.uni.dto.PostResidueDTO;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用扩展客户端直接写入并发布文章（不经过 HTTP 接口）。
 *
 * <p>为什么需要这条路：Halo 的 Console 接口需要「个人令牌」鉴权，而实测在部分实例上
 * 经令牌鉴权的 POST 请求体在到达处理器时字段会丢失（同一请求体用会话鉴权则正常），
 * 结果就是 `draftPost` 拿到 null 的 post 直接 500。插件跑在 Halo 进程内，
 * 完全可以像写自己的数据表一样直接写 Post/Snapshot 扩展——绕开 HTTP、鉴权与请求体绑定。</p>
 *
 * <p>发布动作交给 Halo 自己的声明式流水线：把 {@code spec.publish} 置为 true 并给出
 * {@code releaseSnapshot}，PostReconciler 会完成发布快照与永久链接，这里只负责轮询确认。</p>
 */
@Slf4j
@Service
public class DigestPostWriter {

    private static final Duration RELEASE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
    private static final int MAX_CREATE_ATTEMPTS = 2;

    private static final String RAW_TYPE_MARKDOWN = "markdown";

    /** 打在文章上的来源标记：清理残留时只认带这个标记、且未发布成功的文章 */
    private static final String DIGEST_MARKER_ANNO = "uni.uhalo.pro/digest-generated";
    private static final String VISIBLE_PUBLIC = "PUBLIC";

    private final ReactiveExtensionClient client;
    private final DigestMarkdownRenderer markdownRenderer;
    private final Duration releaseTimeout;
    private final Duration pollInterval;

    /** Spring 用这个构造器装配；时间窗口用默认值 */
    @org.springframework.beans.factory.annotation.Autowired
    public DigestPostWriter(ReactiveExtensionClient client, DigestMarkdownRenderer markdownRenderer) {
        this(client, markdownRenderer, RELEASE_TIMEOUT, POLL_INTERVAL);
    }

    /** 允许测试注入更短的确认窗口，避免单测等满两分钟 */
    DigestPostWriter(ReactiveExtensionClient client, DigestMarkdownRenderer markdownRenderer,
        Duration releaseTimeout, Duration pollInterval) {
        this.client = client;
        this.markdownRenderer = markdownRenderer;
        this.releaseTimeout = releaseTimeout;
        this.pollInterval = pollInterval;
    }

    /**
     * 写入并发布一篇文章。
     *
     * @return 与官方接口一致的发布结果
     */
    public Mono<HaloPostPublishResultDTO> writeAndPublish(HaloPostPublishDTO post) {
        var postName = UUID.randomUUID().toString().replace("-", "");
        var snapshotName = UUID.randomUUID().toString();
        var owner = StringUtils.hasText(post.getOwner()) ? post.getOwner().trim() : "admin";
        var raw = post.getMarkdown() == null ? "" : post.getMarkdown();
        var html = markdownRenderer.render(raw);

        log.info("[UniHalo][Digest] 扩展客户端写入文章：name={} title=「{}」 正文 {} 字符（HTML {} 字符）",
            postName, post.getTitle(), raw.length(), html.length());

        // 分三步，顺序与 Halo 自己建稿一致：先建草稿 → 再建快照 → 最后回填快照引用并置为发布。
        // 一把梭（创建时就带快照引用）会让协调器处理到「引用了还不存在的快照」的中间态。
        return resolveNames(Category.class, post.getCategoryNames())
            .zipWith(resolveNames(Tag.class, post.getTagNames()))
            .flatMap(refs -> client.create(buildPost(post, postName, owner, refs.getT1(), refs.getT2()))
                .flatMap(created -> client.create(buildSnapshot(postName, snapshotName, owner, raw, html)))
                .flatMap(snapshotCreated -> attachSnapshotAndPublish(postName, snapshotName)))
            .flatMap(published -> awaitPublished(postName, MAX_CREATE_ATTEMPTS))
            .flatMap(state -> {
                if (state.published()) {
                    return Mono.just(HaloPostPublishResultDTO.builder()
                        .postName(postName)
                        .permalink(state.permalink())
                        .state("PUBLISHED")
                        .message("已发布（扩展客户端）")
                        .build());
                }
                // 没能确认发布就回滚：留在库里的会是一篇打不开、又删不掉的残文
                log.warn("[UniHalo][Digest] 文章未确认发布（{}），自动回滚删除 name={}",
                    state.message(), postName);
                return rollback(postName, snapshotName)
                    .thenReturn(HaloPostPublishResultDTO.builder()
                        .postName(postName)
                        .state("FAILED")
                        .message("未能完成发布，已自动回滚（避免留下打不开的残留）：" + state.message())
                        .build());
            });
    }

    /**
     * 回滚：删掉刚写入的文章与快照。
     *
     * <p>删除前必须**先摘掉所有终态器**：站点上若有插件（例如 AI 助手）给文章挂了终态器，
     * 而它的协调器又一直失败，文章会永远卡在「删除中」。</p>
     */
    private Mono<Void> rollback(String postName, String snapshotName) {
        return clearFinalizers(postName)
            .flatMap(client::delete)
            .then(client.fetch(Snapshot.class, snapshotName).flatMap(client::delete).then())
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 回滚失败 name={}：{}", postName, ex.getMessage());
                return Mono.empty();
            })
            .then();
    }

    // ── 残留清理 ──

    /**
     * 找出「插件写入但没发布成功」的残留文章。
     *
     * <p>判定条件：没有 {@code content.halo.run/published} 标签，且带插件的来源标记，
     * 或者（老版本写入的）没有任何发布相关注解。只挑这些，避免误删站长自己写的草稿。</p>
     */
    public Mono<List<PostResidueDTO>> previewResidues() {
        return client.listAll(Post.class, new run.halo.app.extension.ListOptions(), null)
            .filter(DigestPostWriter::isResidue)
            .map(post -> PostResidueDTO.builder()
                .name(post.getMetadata().getName())
                .title(post.getSpec() == null ? "" : post.getSpec().getTitle())
                .createdAt(post.getMetadata().getCreationTimestamp())
                .build())
            .collectList();
    }

    private static boolean isResidue(Post post) {
        var metadata = post.getMetadata();
        if (metadata == null) {
            return false;
        }
        var labels = metadata.getLabels();
        if (labels != null && "true".equalsIgnoreCase(labels.get(Post.PUBLISHED_LABEL))) {
            return false;
        }
        var annotations = metadata.getAnnotations();
        if (annotations != null && annotations.containsKey(DIGEST_MARKER_ANNO)) {
            return true;
        }
        // 老版本写入的残留：没有任何发布相关注解（正常文章会被协调器补上这些）
        return annotations == null || !annotations.containsKey(Post.LAST_RELEASED_SNAPSHOT_ANNO);
    }

    /** 逐篇清理：摘终态器 → 删除文章 → 尽力删掉它的快照 */
    public Mono<List<String>> purgeResidues() {
        return previewResidues()
            .flatMapMany(reactor.core.publisher.Flux::fromIterable)
            .concatMap(residue -> purgeOne(residue.getName()))
            .collectList();
    }

    private Mono<String> purgeOne(String postName) {
        return client.fetch(Post.class, postName)
            .flatMap(post -> {
                var snapshotNames = post.getSpec() == null ? List.<String>of()
                    : java.util.stream.Stream.of(post.getSpec().getBaseSnapshot(),
                            post.getSpec().getHeadSnapshot(), post.getSpec().getReleaseSnapshot())
                        .filter(StringUtils::hasText).distinct().toList();
                return clearFinalizers(postName)
                    .flatMap(client::delete)
                    .then(reactor.core.publisher.Flux.fromIterable(snapshotNames)
                        .concatMap(name -> client.fetch(Snapshot.class, name)
                            .flatMap(client::delete)
                            .onErrorResume(ex -> Mono.empty()))
                        .then())
                    .thenReturn(postName);
            })
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 清理残留失败 name={}：{}", postName, ex.getMessage());
                return Mono.empty();
            });
    }

    // ── 构建扩展 ──

    private Post buildPost(HaloPostPublishDTO post, String postName, String owner,
        List<String> categories, List<String> tags) {
        var labels = new LinkedHashMap<String, String>();
        labels.put(Post.OWNER_LABEL, owner);
        labels.put(Post.VISIBLE_LABEL, VISIBLE_PUBLIC);
        labels.put(Post.DELETED_LABEL, "false");

        var metadata = new Metadata();
        metadata.setName(postName);
        metadata.setLabels(labels);
        var annotations = new LinkedHashMap<String, String>();
        annotations.put(DIGEST_MARKER_ANNO, "true");
        metadata.setAnnotations(annotations);

        var spec = new Post.PostSpec();
        spec.setTitle(post.getTitle());
        spec.setSlug(post.getSlug());
        spec.setOwner(owner);
        spec.setTemplate("");
        spec.setCover(post.getCover() == null ? "" : post.getCover());
        spec.setDeleted(false);
        spec.setPublish(false);
        spec.setPinned(false);
        spec.setAllowComment(post.isAllowComment());
        spec.setVisible(Post.VisibleEnum.PUBLIC);
        spec.setPriority(0);
        var excerpt = new Post.Excerpt();
        excerpt.setAutoGenerate(true);
        excerpt.setRaw("");
        spec.setExcerpt(excerpt);
        spec.setCategories(categories);
        spec.setTags(tags);
        spec.setHtmlMetas(List.of());

        var extension = new Post();
        extension.setMetadata(metadata);
        extension.setSpec(spec);
        return extension;
    }

    /**
     * 回填快照引用并置为发布；发布快照由 PostReconciler 据此生成。
     *
     * <p>必须带乐观锁重试：文章刚写入时 Halo 的协调器正在并发处理它，直接用读到的版本号
     * 更新会被拒绝（"Was the entity updated or deleted concurrently?"）。Halo 自己的
     * 建稿流程也是这么重试的——每次重试都重新读取最新版本再更新。</p>
     */
    private Mono<Post> attachSnapshotAndPublish(String postName, String snapshotName) {
        return Mono.defer(() -> client.fetch(Post.class, postName)
                .switchIfEmpty(Mono.error(new IllegalStateException("文章创建后读取不到：" + postName)))
                .flatMap(current -> {
                    var spec = current.getSpec();
                    spec.setBaseSnapshot(snapshotName);
                    spec.setHeadSnapshot(snapshotName);
                    spec.setReleaseSnapshot(snapshotName);
                    spec.setPublish(true);
                    spec.setPublishTime(Instant.now());
                    return client.update(current);
                }))
            .retryWhen(reactor.util.retry.Retry.backoff(8, Duration.ofMillis(150))
                .filter(DigestPostWriter::isConcurrentModification)
                .doBeforeRetry(signal -> log.info(
                    "[UniHalo][Digest] 文章正被协调器并发修改，重新读取后再更新 name={}（第 {} 次）",
                    postName, signal.totalRetries() + 1)));
    }

    /**
     * 是否是并发修改冲突。
     *
     * <p>按异常消息判断：Halo 乐观锁失败的原文是
     * {@code Failed to update versioned entity ... Was the entity updated or deleted concurrently?}；
     * 它专用的异常类在 application 包里，插件编译期拿不到，只能识别消息。</p>
     */
    private static boolean isConcurrentModification(Throwable error) {
        var message = error.getMessage();
        return message != null && message.contains("concurrently");
    }

    /** 清理终态器时同样要防并发冲突 */
    private Mono<Post> clearFinalizers(String postName) {
        return Mono.defer(() -> client.fetch(Post.class, postName).flatMap(post -> {
                var metadata = post.getMetadata();
                if (metadata != null && metadata.getFinalizers() != null
                    && !metadata.getFinalizers().isEmpty()) {
                    var removed = new java.util.ArrayList<>(metadata.getFinalizers());
                    metadata.getFinalizers().clear();
                    log.info("[UniHalo][Digest] 回滚前摘除终态器 name={} 原终态器={}", postName, removed);
                    return client.update(post);
                }
                return Mono.just(post);
            }))
            .retryWhen(reactor.util.retry.Retry.backoff(5, Duration.ofMillis(150))
                .filter(DigestPostWriter::isConcurrentModification));
    }

    // ── 分类 / 标签：进程内解析成已存在的 metadata.name ──

    /**
     * 把配置里的分类/标签名解析成 Halo 里真实存在的 {@code metadata.name}。
     *
     * <p>必须解析：直接写入不存在的名字会让文章在读取/渲染时出错（前台 500）。
     * 匹配不到的一律丢弃——宁可少一个分类，也不能让文章打不开。</p>
     */
    private Mono<List<String>> resolveNames(
        Class<? extends run.halo.app.extension.Extension> type, List<String> wanted) {
        if (wanted == null || wanted.isEmpty()) {
            return Mono.just(List.of());
        }
        return client.listAll(type, new run.halo.app.extension.ListOptions(), null)
            .map(extension -> {
                var metadata = extension.getMetadata();
                var name = metadata == null ? "" : metadata.getName();
                var displayName = extension instanceof Category category
                    ? (category.getSpec() == null ? "" : category.getSpec().getDisplayName())
                    : (extension instanceof Tag tag
                        ? (tag.getSpec() == null ? "" : tag.getSpec().getDisplayName()) : "");
                return new NameCandidate(name, displayName);
            })
            .collectList()
            .map(candidates -> matchNames(wanted, candidates, type.getSimpleName()));
    }

    private List<String> matchNames(List<String> wanted, List<NameCandidate> candidates,
        String label) {
        var resolved = new java.util.ArrayList<String>();
        var missing = new java.util.ArrayList<String>();
        for (var name : wanted) {
            if (!StringUtils.hasText(name)) {
                continue;
            }
            var target = name.trim();
            var hit = candidates.stream()
                .filter(candidate -> target.equalsIgnoreCase(candidate.name())
                    || target.equalsIgnoreCase(candidate.displayName()))
                .map(NameCandidate::name)
                .findFirst()
                .orElse(null);
            if (hit == null) {
                missing.add(target);
            } else if (!resolved.contains(hit)) {
                resolved.add(hit);
            }
        }
        if (!missing.isEmpty()) {
            log.warn("[UniHalo][Digest] {} 里找不到这些名称，已忽略（避免文章打不开）：{}",
                label, missing);
        }
        return resolved;
    }

    private record NameCandidate(String name, String displayName) {
    }

    private Snapshot buildSnapshot(String postName, String snapshotName, String owner,
        String raw, String html) {
        var metadata = new Metadata();
        metadata.setName(snapshotName);
        // 关键：keep-raw 必须是「注解」而不是「标签」——Halo 的 Snapshot.isBaseSnapshot()
        // 只读 metadata.annotations 里的这个键；写错位置快照就不会被认作 base snapshot，
        // 协调器会在生成摘要时抛 "is not a base snapshot"，文章既发不出去也打不开。
        metadata.setAnnotations(new LinkedHashMap<>(Map.of(Snapshot.KEEP_RAW_ANNO, "true")));

        var spec = new Snapshot.SnapShotSpec();
        spec.setSubjectRef(Ref.of(postName, Post.GVK));
        spec.setRawType(RAW_TYPE_MARKDOWN);
        spec.setRawPatch(raw);
        // Halo 服务端不渲染 markdown，contentPatch 必须由我们提供渲染后的 HTML
        spec.setContentPatch(html);
        spec.setOwner(owner);
        spec.setLastModifyTime(Instant.now());

        var snapshot = new Snapshot();
        snapshot.setMetadata(metadata);
        snapshot.setSpec(spec);
        Snapshot.addContributor(snapshot, owner);
        return snapshot;
    }

    // ── 轮询确认 ──

    private Mono<ReleaseState> awaitPublished(String postName, int remainingAttempts) {
        // 用毫秒换算：确认窗口可能短到不足 1 秒（测试会注入短窗口）
        var maxPolls = Math.max(1,
            (int) (releaseTimeout.toMillis() / Math.max(1, pollInterval.toMillis())));
        return Flux.interval(Duration.ZERO, pollInterval)
            .take(maxPolls)
            .concatMap(tick -> client.fetch(Post.class, postName)
                .map(this::evaluate)
                // 读不到也必须给一个状态：否则整个轮询流会空完成，last() 直接抛异常
                .defaultIfEmpty(new ReleaseState(false, "", "读取不到文章状态"))
                .onErrorResume(ex -> Mono.just(new ReleaseState(false, "",
                    "读取文章状态失败：" + ex.getMessage())))
                // 标记为已发布但迟迟没有 release 快照时，补一次更新推动协调器
                .flatMap(state -> !state.published() && tick > 0 && tick % 15 == 0
                    ? nudgePublish(postName).thenReturn(state)
                    : Mono.just(state)))
            .takeUntil(ReleaseState::published)
            .last()
            .timeout(releaseTimeout.plusSeconds(30))
            .flatMap(state -> {
                if (state.published() || remainingAttempts <= 1) {
                    return Mono.just(state);
                }
                // 还没确认发布：再推一次协调器，然后用剩余预算继续等
                log.warn("[UniHalo][Digest] 等待发布快照超时，再推动一次协调器 name={}", postName);
                return nudgePublish(postName)
                    .then(awaitPublished(postName, remainingAttempts - 1));
            });
    }

    /** 重新提交一次文章，推动 PostReconciler 处理（不改变任何业务字段） */
    private Mono<Post> nudgePublish(String postName) {
        return client.fetch(Post.class, postName)
            .flatMap(post -> {
                if (post.getSpec() == null) {
                    return Mono.just(post);
                }
                post.getSpec().setPublish(true);
                return client.update(post);
            })
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 推动协调器失败 name={}：{}", postName, ex.getMessage());
                return Mono.empty();
            });
    }

    private ReleaseState evaluate(Post post) {
        var status = post.getStatus();
        var phase = status == null ? null : status.getPhase();
        var permalink = status == null || status.getPermalink() == null ? "" : status.getPermalink();
        var labels = post.getMetadata() == null ? null : post.getMetadata().getLabels();
        var published = labels != null
            && "true".equalsIgnoreCase(labels.get(Post.PUBLISHED_LABEL));
        if (published || Post.PostPhase.PUBLISHED.name().equalsIgnoreCase(phase)) {
            return new ReleaseState(true, permalink, "已发布");
        }
        return new ReleaseState(false, permalink,
            "等待 Halo 生成发布快照（phase=" + phase + " published=" + published + "）");
    }

    private record ReleaseState(boolean published, String permalink, String message) {
    }
}
