package pro.uhalo.uni.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestAiReplyDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.dto.ToutiaoConfigDTO;
import pro.uhalo.uni.enums.DigestAiTask;
import pro.uhalo.uni.infra.ToutiaoArticleStore;
import pro.uhalo.uni.scheme.ToutiaoArticle;
import pro.uhalo.uni.utils.DigestAiChannelResolver;
import pro.uhalo.uni.utils.DigestAiErrorExplainer;
import pro.uhalo.uni.utils.DigestAiUsageLog;
import pro.uhalo.uni.utils.DigestConfigHelper;
import pro.uhalo.uni.utils.DigestCoverGenerator;
import pro.uhalo.uni.utils.ToutiaoConfigHelper;
import pro.uhalo.uni.utils.ToutiaoImageHelper;
import pro.uhalo.uni.utils.ToutiaoPromptHelper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 今日头条号写稿台（控制台管理员页面）：关键词 → AI 直接成稿 → 标题候选 + 正文 + 标签 + 配图。
 *
 * <p>与公众号/日报共用同一批零件，但链路刻意做短：</p>
 * <ul>
 *   <li><b>一次调用出一篇</b>：没有抓取、没有评分，关键词直接进提示词（
 *       {@link ToutiaoPromptHelper#buildPrompt}），成稿结果按平台口径收敛；</li>
 *   <li><b>模型通道复用日报配置</b>：写稿走 {@link DigestAiTask#TOUTIAO} 用途，
 *       站长可在「AI 日报 → 大模型通道」里单独给它绑一条强通道；
 *       没有通道勾选该用途时按既有规则回退到全部可用通道；</li>
 *   <li><b>配图两级流水线</b>：配了「图片模型」就按主题逐张 AI 生图，没配或某张失败
 *       就用文案卡片补齐（{@link ToutiaoImageHelper} + {@link DigestCoverGenerator}）——
 *       <b>每篇至少 3 张</b>是硬口径；图片全部转存进站点附件库，页面可直接复制/下载；</li>
 *   <li><b>同步等待、异步执行</b>：接口触发后立刻返回，页面轮询 {@link #progress()}——
 *       写稿加深至少一分钟起步，挂在 HTTP 请求上会被网关超时掐断。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToutiaoService {

    /** 关键词长度上限：再长既没意义，又会把提示词费用推高 */
    private static final int MAX_KEYWORD_CHARS = 100;

    /** 超过这个时长的 GENERATING 记录按「已中断」展示（进程重启时来不及收尾） */
    public static final int STALE_MINUTES = 10;

    /** 生图并发：与日报/公众号同一档（上游限流下 2 是安全值），配图顺序仍按 flatMapSequential 保序 */
    private static final int IMAGE_CONCURRENCY = 2;

    /**
     * 写稿温度。
     *
     * <p>日报/公众号的成稿用 0.3（要稳、要可复现），但那是「AI 味」的来源之一：
     * 低温下的句子四平八稳、结构千篇一律。头条号是站长自己发布的原创内容，
     * 这里刻意调到 0.8——模型的用词与句式会活一些。</p>
     */
    private static final double DRAFT_TEMPERATURE = 0.8;

    /** 去 AI 味重写的温度：再高一点，专门用来打破句式惯性 */
    private static final double REPAIR_TEMPERATURE = 0.9;

    /** 命中多少处套话就自动重写一版（1~3 处只在记录里提示，避免白花一次调用） */
    private static final int AI_TELL_REPAIR_THRESHOLD = 4;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ReactiveSettingFetcher settingFetcher;
    private final DigestAiClient aiClient;
    private final ToutiaoImageClient imageClient;
    private final DigestAssetUploader assetUploader;
    private final ToutiaoArticleStore store;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Progress progress = Progress.idle();

    /** 生成进度（管理页轮询展示；真正的结果在记录里，重启后回到空闲是合理的） */
    public record Progress(boolean running, String stage, Instant startedAt, Instant finishedAt,
                           String error, String articleName) {

        static Progress idle() {
            return new Progress(false, "", null, null, null, null);
        }
    }

    public Progress progress() {
        return progress;
    }

    public boolean isRunning() {
        return running.get();
    }

    public Mono<ToutiaoConfigDTO> loadConfig() {
        return settingFetcher.get(ToutiaoConfigHelper.groupName())
            .map(ToutiaoConfigHelper::parse)
            .defaultIfEmpty(ToutiaoConfigHelper.defaults())
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Toutiao] 读取配置失败，用默认值：{}", ex.getMessage());
                return Mono.just(ToutiaoConfigHelper.defaults());
            });
    }

    /**
     * 触发一次生成（异步：调用方立刻返回 started，页面轮询 {@link #progress()}）。
     *
     * <p>同时只允许一个任务：写稿加配图是一段几十秒到几分钟的长调用，
     * 并发触发只会把额度花在站长看不到结果的重复请求上。</p>
     */
    public Mono<ToutiaoArticle> generate(String keyword, String style, Integer words,
        Integer titleCount) {
        var theme = keyword == null ? "" : keyword.trim();
        if (!StringUtils.hasText(theme)) {
            return Mono.error(new IllegalArgumentException("请先填写主题关键词"));
        }
        if (theme.length() > MAX_KEYWORD_CHARS) {
            theme = theme.substring(0, MAX_KEYWORD_CHARS);
        }
        if (!running.compareAndSet(false, true)) {
            return Mono.error(new IllegalStateException("已有一次生成任务在执行中，请等它跑完再试"));
        }
        var draft = new Draft(theme, style, words, titleCount);
        var startedAt = Instant.now();
        progress = new Progress(true, "准备中…", startedAt, null, null, null);
        return doGenerate(draft)
            .doOnNext(article -> progress = new Progress(false, "", startedAt, Instant.now(), null,
                article.getMetadata().getName()))
            .doOnError(ex -> progress = new Progress(false, "", startedAt, Instant.now(),
                readableError(ex), null))
            .doFinally(signal -> running.set(false));
    }

    /** 一次生成的输入（配置收敛之后） */
    private record Draft(String keyword, String style, Integer words, Integer titleCount) {
    }

    private Mono<ToutiaoArticle> doGenerate(Draft draft) {
        return loadConfig().flatMap(appConfig -> prepare(appConfig, draft));
    }

    /** 配置闸门与参数收敛（管理页按篇调整的字数与标题数在这里收口） */
    private Mono<ToutiaoArticle> prepare(ToutiaoConfigDTO appConfig, Draft draft) {
        if (!appConfig.isEnabled()) {
            return Mono.error(new IllegalStateException(
                "今日头条号功能未开启：请先在插件设置「今日头条号」中打开总开关"));
        }
        var styleText = StringUtils.hasText(draft.style()) ? draft.style().trim()
            : appConfig.getDefaultStyle();
        var wordsValue = draft.words() == null ? appConfig.getWords()
            : ToutiaoConfigHelper.normalizeWords(draft.words());
        var titleCountValue = draft.titleCount() == null ? appConfig.getTitleCount()
            : ToutiaoConfigHelper.normalizeTitleCount(draft.titleCount());
        var resolved = new Draft(draft.keyword(), styleText, wordsValue, titleCountValue);
        return loadAiConfig().flatMap(aiConfig -> start(resolved, appConfig, aiConfig));
    }

    /** 建记录并立刻交给 AI（记录先落库：中途失败时页面能看到「生成中 → 失败」的完整轨迹） */
    private Mono<ToutiaoArticle> start(Draft draft, ToutiaoConfigDTO appConfig,
        DigestConfigDTO aiConfig) {
        if (!DigestAiChannelResolver.hasUsableChannel(aiConfig, DigestAiTask.TOUTIAO)) {
            return Mono.error(new IllegalStateException(
                "没有可用的大模型通道：请先在插件设置「AI 日报 → 大模型通道」里"
                    + "配置至少一条启用的通道"));
        }
        aiClient.resetForNewRun();
        var article = ToutiaoArticleStore.newArticle(draft.keyword(), draft.style(), draft.words(),
            draft.titleCount());
        log.info("[UniHalo][Toutiao] 开始生成：keyword={} style={} words={} titles={} 配图={} "
                + "图片模型={} 通道={}",
            draft.keyword(), draft.style(), draft.words(), draft.titleCount(),
            appConfig.getImageCount(),
            StringUtils.hasText(appConfig.getImageModel()) ? appConfig.getImageModel() : "（未配置，用文案卡片）",
            DigestAiChannelResolver.describe(aiConfig));
        return store.create(article).flatMap(saved -> writeWithAi(saved, draft, appConfig, aiConfig));
    }

    /** 写稿 → 去 AI 味复查（必要时重写一版）→ 配图 → 落结果；失败时把原因写进同一条记录 */
    private Mono<ToutiaoArticle> writeWithAi(ToutiaoArticle saved, Draft draft,
        ToutiaoConfigDTO appConfig, DigestConfigDTO aiConfig) {
        updateStage("AI 正在写稿（约 30~60 秒）…", saved.getMetadata().getName());
        var usage = DigestAiUsageLog.forConfig(aiConfig);
        var prompt = ToutiaoPromptHelper.buildPrompt(draft.keyword(), draft.style(), draft.words(),
            draft.titleCount(), appConfig.getVoice(), appConfig.getBannedPhrases(),
            appConfig.getImageCount());
        return aiClient.call(aiConfig, prompt, DigestAiTask.TOUTIAO, DRAFT_TEMPERATURE)
            .doOnNext(reply -> usage.record(DigestAiTask.TOUTIAO, reply))
            .flatMap(reply -> completeText(saved, reply, draft, appConfig, aiConfig, usage))
            .flatMap(article -> "READY".equals(article.getState())
                ? withImages(article, draft, appConfig, aiConfig)
                : updateAndLog(article))
            .onErrorResume(ex -> failArticle(saved, ex));
    }

    /**
     * 成稿之后的「体检 + 一次修复」：字数不够就补写，套话太多就去 AI 味，两件事合并成一次调用。
     *
     * <p>为什么值得多花这一次调用：站长的两条反馈（稿子太短、稿子像机器写的）都直接影响能不能用。
     * 触发条件是「低于字数下限」或「套话命中 ≥ {@value #AI_TELL_REPAIR_THRESHOLD} 处」；
     * 修复版是否采用由 {@link ToutiaoPromptHelper#chooseBest} 按「先字数、后套话」判定，
     * 越修越坏的版本会被丢掉。</p>
     */
    private Mono<ToutiaoArticle> completeText(ToutiaoArticle article, DigestAiReplyDTO reply,
        Draft draft, ToutiaoConfigDTO appConfig, DigestConfigDTO aiConfig,
        DigestAiUsageLog usage) {
        var first = applyReply(article, reply);
        if (!"READY".equals(first.getState())) {
            return Mono.just(withCost(first, usage));
        }
        var tells = ToutiaoPromptHelper.detectAiTells(first.getBody());
        var tooShort = first.getBody().length() < ToutiaoPromptHelper.floorOf(draft.words());
        var needFix = tooShort || tells.size() >= AI_TELL_REPAIR_THRESHOLD;
        if (!needFix || !appConfig.isRewriteEnabled()) {
            // 关掉自动修复时也要把体检结论说出来（站长据此决定是重生成还是调设置）
            appendNote(first, healthHint(tells, first.getBody().length(), draft.words(),
                appConfig.isRewriteEnabled()));
            return Mono.just(withCost(first, usage));
        }
        updateStage(stageText(tooShort, tells.size(), first.getBody().length(), draft.words()),
            first.getMetadata().getName());
        var brief = new ToutiaoPromptHelper.RepairBrief(splitLines(first.getTitles()),
            first.getBody(), tells, appConfig.getVoice(), appConfig.getBannedPhrases(),
            draft.titleCount(), draft.words(), tooShort);
        var prompt = ToutiaoPromptHelper.buildRepairPrompt(brief);
        return aiClient.call(aiConfig, prompt, DigestAiTask.TOUTIAO, REPAIR_TEMPERATURE)
            .doOnNext(repairReply -> usage.record(DigestAiTask.TOUTIAO, repairReply))
            .map(repairReply -> applyRepair(first, repairReply, draft.titleCount(), draft.words()))
            .map(article2 -> withCost(article2, usage))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Toutiao] 自动修复失败，保留初稿：{}", ex.getMessage());
                appendNote(first, "自动修复失败：" + DigestAiErrorExplainer.snippet(
                    String.valueOf(ex.getMessage())) + "，保留初稿");
                return Mono.just(withCost(first, usage));
            });
    }

    /** 进度文案：把「为什么在重写」写清楚，站长看着不疑惑 */
    private static String stageText(boolean tooShort, int tells, int length, int targetWords) {
        var reasons = new ArrayList<String>();
        if (tooShort) {
            reasons.add("初稿偏短（" + length + " 字 / 目标 " + targetWords + " 字）");
        }
        if (tells >= AI_TELL_REPAIR_THRESHOLD) {
            reasons.add("「AI 味」偏重（" + tells + " 处套话）");
        }
        return String.join("、", reasons) + "，自动重写一遍…";
    }

    /**
     * 用修复版替换正文——只在它确实更好时才替换。
     *
     * <p>「更好」的判定（{@link ToutiaoPromptHelper#chooseBest}）是纯函数，
     * 这里只负责落字段与维护 note：换版之后截断提示要按新正文重算，
     * 旧的「被截断」不能挂在新稿子上。</p>
     */
    private static ToutiaoArticle applyRepair(ToutiaoArticle article,
        DigestAiReplyDTO repairReply, int titleCount, int targetWords) {
        var original = new ToutiaoPromptHelper.Generated(splitLines(article.getTitles()),
            article.getBody(), splitTags(article.getTags()));
        var choice = ToutiaoPromptHelper.chooseBest(original, repairReply.text(), titleCount,
            targetWords);
        if (!choice.repaired()) {
            appendNote(article, choice.note());
            return article;
        }
        article.setTitles(String.join("\n", choice.chosen().titles()));
        article.setBody(choice.chosen().body());
        article.setTags(String.join(",", choice.chosen().tags()));
        article.setNote("length".equalsIgnoreCase(repairReply.finishReason())
            ? "输出撞到长度上限被截断：正文可能不完整，建议减少字数或换一条强通道" : null);
        appendNote(article, choice.note());
        log.info("[UniHalo][Toutiao] 自动修复生效：{} 字/{} 套话 → {} 字/{} 套话",
            choice.originalLength(), choice.originalTells(), choice.chosenLength(),
            choice.chosenTells());
        return article;
    }

    /**
     * 没触发修复（或开关关闭）时的一句话体检结论。
     *
     * <p>只有真的有问题才提示——干净的稿子不需要在页面上多挂一行字。</p>
     */
    private static String healthHint(List<String> tells, int length, int targetWords,
        boolean fixEnabled) {
        var floor = ToutiaoPromptHelper.floorOf(targetWords);
        var parts = new ArrayList<String>();
        if (length < floor) {
            parts.add("正文 " + length + " 字，低于目标 " + targetWords + " 字的下限（" + floor + " 字）");
        }
        if (!tells.isEmpty()) {
            parts.add("检测到 " + tells.size() + " 处套话表述（" + String.join("、", tells) + "）");
        }
        if (parts.isEmpty()) {
            return "";
        }
        var hint = String.join("；", parts);
        return fixEnabled ? hint + "，可重新生成或到设置里调整「口吻」"
            : hint + "；自动修复已在设置里关闭，可重新生成或把目标字数调低";
    }

    // ── 配图（每篇至少 3 张） ──

    /**
     * 配图流水线：图位来自正文里模型标注的 {@code 【配图N：画面描述】}，逐张生成，AI 生图优先、文案卡片兜底。
     *
     * <p>配图失败<b>不影响稿子</b>（正文已经生成好了）：这里把每张的结果与原因
     * 写进 {@code imageNote}，稿子照常标记为已生成——站长可以先发文字版，
     * 也可以修好图片通道后重新生成。</p>
     */
    private Mono<ToutiaoArticle> withImages(ToutiaoArticle article, Draft draft,
        ToutiaoConfigDTO appConfig, DigestConfigDTO aiConfig) {
        // 图位：模型在正文里标的（描述就是它对「这里该配什么图」的理解）；标少了按正文补齐到下限，
        // 「每篇至少 3 张」的硬口径在补齐这一步兜住
        var slots = ToutiaoImageHelper.padSlots(ToutiaoImageHelper.bodySlots(article.getBody()),
            article.getBody(), appConfig.getImageCount(), splitLines(article.getTitles()),
            splitTags(article.getTags()), draft.keyword());
        // 卡片文案只作为「某张图生图失败」时的兜底，与图位一一对应
        var cards = ToutiaoImageHelper.cardPlan(splitLines(article.getTitles()), article.getBody(),
            splitTags(article.getTags()), draft.style(), draft.keyword(), slots.size());
        updateStage("生成配图（" + slots.size() + " 张，按正文标注的位置）…",
            article.getMetadata().getName());
        var channel = pickImageChannel(aiConfig, appConfig);
        var aiReady = channel != null && StringUtils.hasText(appConfig.getImageModel());
        var dateText = LocalDate.now(ZONE).toString();
        var articleName = article.getMetadata().getName();
        return siteTitle().flatMap(siteName -> Flux.range(0, slots.size())
                .flatMapSequential(index -> imageAt(index, slots.get(index), cards.get(index),
                    draft, aiReady, channel, appConfig, aiConfig, articleName, dateText, siteName),
                    IMAGE_CONCURRENCY)
                .collectList())
            .map(images -> finishImages(article, images, appConfig, channel))
            .onErrorResume(ex -> {
                // 兜底：不把整篇稿子拖垮，只记一句配图失败
                log.warn("[UniHalo][Toutiao] 配图流水线异常（稿子照常保存）：{}", ex.getMessage(), ex);
                article.setImageNote("配图失败：" + readableError(ex));
                return Mono.just(article);
            })
            .flatMap(this::updateAndLog);
    }

    /** 生成第 index 张图：AI 生图失败就用文案卡片补齐，两样都失败也要留下原因 */
    private Mono<PlannedImage> imageAt(int index, ToutiaoImageHelper.BodySlot slot,
        ToutiaoImageHelper.CardSpec card, Draft draft, boolean aiReady,
        DigestAiProviderDTO channel, ToutiaoConfigDTO appConfig, DigestConfigDTO aiConfig,
        String articleName, String dateText, String siteName) {
        var baseName = articleName + "-img" + (index + 1);
        if (!aiReady) {
            return cardImage(index, card, baseName, aiConfig, dateText, siteName);
        }
        var prompt = ToutiaoImageHelper.buildSlotPrompt(draft.keyword(), draft.style(), slot,
            appConfig.getImageStylePrompt());
        return imageClient.generate(channel, appConfig.getImageModel(), appConfig.getImageSize(),
                prompt)
            .flatMap(image -> uploadGenerated(image, baseName, aiConfig))
            .map(url -> new PlannedImage(index, url, true, ""))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Toutiao] 第 {} 张 AI 配图失败，用文案卡片补齐：{}",
                    index + 1, ex.getMessage());
                return cardImage(index, card, baseName, aiConfig, dateText, siteName)
                    .map(card2 -> card2.url() == null
                        ? new PlannedImage(index, null, false,
                            "AI 生图失败（" + DigestAiErrorExplainer.snippet(
                                String.valueOf(ex.getMessage())) + "）；" + card2.reason())
                        : card2);
            });
    }

    /** 把生图结果变成附件库地址：URL 形态走 Halo 官方转存，字节形态走 multipart 上传 */
    private Mono<String> uploadGenerated(ToutiaoImageClient.GeneratedImage image, String baseName,
        DigestConfigDTO aiConfig) {
        if (StringUtils.hasText(image.url())) {
            return assetUploader.uploadImage(image.url(), baseName, aiConfig)
                .switchIfEmpty(Mono.error(
                    new IllegalStateException("图片转存失败（检查站点附件库与存储策略）")));
        }
        if (image.bytes() == null || image.bytes().length == 0) {
            return Mono.error(new IllegalStateException("图片接口返回了空图片"));
        }
        return assetUploader.uploadBytes(image.bytes(), image.mediaType(), baseName, aiConfig)
            .switchIfEmpty(Mono.error(
                new IllegalStateException("图片上传失败（检查站点附件库与存储策略）")));
    }

    /** 文案卡片：本地绘制 + 上传，零依赖零成本，是「张数不会少」的保证 */
    private Mono<PlannedImage> cardImage(int index, ToutiaoImageHelper.CardSpec spec,
        String baseName, DigestConfigDTO aiConfig, String dateText, String siteName) {
        byte[] bytes;
        try {
            bytes = DigestCoverGenerator.renderCard(spec.categoryKey(), spec.categoryLabel(),
                spec.title(), "", dateText, siteName);
        } catch (Exception ex) {
            log.warn("[UniHalo][Toutiao] 文案卡片绘制失败：{}", ex.getMessage(), ex);
            return Mono.just(new PlannedImage(index, null, false,
                "文案卡片绘制失败：" + ex.getMessage()));
        }
        return assetUploader.uploadBytes(bytes, "image/png", baseName, aiConfig)
            .map(url -> new PlannedImage(index, url, false, ""))
            .defaultIfEmpty(new PlannedImage(index, null, false,
                "文案卡片上传失败（检查站点附件库与存储策略）"))
            .onErrorResume(ex -> Mono.just(new PlannedImage(index, null, false,
                "文案卡片上传失败：" + DigestAiErrorExplainer.snippet(
                    String.valueOf(ex.getMessage())))));
    }

    /** 收尾：地址写进记录，并把图片来自哪里、哪张失败说清楚 */
    private ToutiaoArticle finishImages(ToutiaoArticle article, List<PlannedImage> images,
        ToutiaoConfigDTO appConfig, DigestAiProviderDTO channel) {
        var urls = images.stream()
            .filter(image -> StringUtils.hasText(image.url()))
            .map(PlannedImage::url)
            .toList();
        article.setImages(String.join("\n", urls));
        var aiCount = (int) images.stream()
            .filter(image -> StringUtils.hasText(image.url()) && image.aiGenerated())
            .count();
        var cardCount = urls.size() - aiCount;
        var failures = images.stream()
            .filter(image -> !StringUtils.hasText(image.url()))
            .toList();

        var note = new StringBuilder();
        if (urls.isEmpty()) {
            note.append("配图没有生成成功");
        } else if (aiCount == 0) {
            note.append(urls.size()).append(" 张配图是文案卡片");
            if (StringUtils.hasText(appConfig.getImageModel())) {
                note.append("（图片模型「").append(appConfig.getImageModel()).append("」没生效：")
                    .append(channel == null
                        ? "指定的图片通道不可用，检查「AI 日报 → 大模型通道」里的标识"
                        : "生图失败，见下方原因")
                    .append("）");
            } else {
                note.append("（未配置图片模型：在设置里填上「图片模型」即可换成 AI 生图）");
            }
        } else if (cardCount == 0) {
            note.append(urls.size()).append(" 张配图由 AI 生成（生图费用不计入上面的 token 估算）");
        } else {
            note.append("AI 生成 ").append(aiCount).append(" 张 + 文案卡片补齐 ").append(cardCount)
                .append(" 张（生图费用不计入上面的 token 估算）");
        }
        if (!failures.isEmpty()) {
            note.append("；另有 ").append(failures.size()).append(" 张没成功（第 ")
                .append(failures.get(0).index() + 1).append(" 张：")
                .append(failures.get(0).reason()).append("）");
        }
        article.setImageNote(note.toString());
        log.info("[UniHalo][Toutiao] 配图完成：{} 张（AI {} / 卡片 {}），{}",
            urls.size(), aiCount, cardCount, note);
        return article;
    }

    /**
     * 挑生图通道：优先站长指定的标识，没指定就用第一条可用通道。
     *
     * <p>指定了但不在可用清单里（被停用/删除/写错）时返回 null —— 走文案卡片，
     * 并由 {@code imageNote} 指出该去哪检查，而不是悄悄换一条通道去生图。</p>
     */
    private static DigestAiProviderDTO pickImageChannel(DigestConfigDTO aiConfig,
        ToutiaoConfigDTO appConfig) {
        var channels = DigestAiChannelResolver.usableChannels(aiConfig);
        var key = appConfig.getImageChannelKey();
        if (!StringUtils.hasText(key)) {
            return channels.isEmpty() ? null : channels.get(0);
        }
        return channels.stream()
            .filter(channel -> key.trim().equalsIgnoreCase(channel.key()))
            .findFirst()
            .orElse(null);
    }

    /** 一张配图的结果：url 为空表示这张没成功（reason 说明原因） */
    private record PlannedImage(int index, String url, boolean aiGenerated, String reason) {
    }

    private static List<String> splitLines(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        var result = new ArrayList<String>();
        for (var line : value.split("\\R")) {
            if (StringUtils.hasText(line)) {
                result.add(line.trim());
            }
        }
        return List.copyOf(result);
    }

    private static List<String> splitTags(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        var result = new ArrayList<String>();
        for (var tag : value.split("[,，]")) {
            if (StringUtils.hasText(tag)) {
                result.add(tag.trim());
            }
        }
        return List.copyOf(result);
    }

    private Mono<String> siteTitle() {
        return settingFetcher.get("basic")
            .map(node -> node.path("title").asText("").trim())
            .defaultIfEmpty("")
            .onErrorResume(ex -> Mono.just(""));
    }

    private Mono<DigestConfigDTO> loadAiConfig() {
        return settingFetcher.get(DigestConfigHelper.groupName())
            .map(DigestConfigHelper::parse)
            .defaultIfEmpty(DigestConfigHelper.defaults())
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Toutiao] 读取 AI 日报配置失败，用默认值：{}", ex.getMessage());
                return Mono.just(DigestConfigHelper.defaults());
            });
    }

    // ── 落库与工具 ──

    private Mono<ToutiaoArticle> updateAndLog(ToutiaoArticle article) {
        return store.update(article)
            .doOnNext(updated -> {
                if ("READY".equals(updated.getState())) {
                    log.info("[UniHalo][Toutiao] 生成完成：{} 字 / {} 张图（通道 {}）",
                        updated.getBody() == null ? 0 : updated.getBody().length(),
                        splitLines(updated.getImages()).size(), updated.getChannel());
                } else {
                    log.warn("[UniHalo][Toutiao] 生成未产出正文：{}", updated.getError());
                }
            });
    }

    /** 把一次成功的调用写进记录：标题候选 / 正文 / 标签 / 通道（花费在整段文字流程收尾时统一写） */
    private ToutiaoArticle applyReply(ToutiaoArticle article, DigestAiReplyDTO reply) {
        var generated = ToutiaoPromptHelper.parseResult(reply.text(),
            article.getTitleCount() == null ? 3 : article.getTitleCount());
        article.setChannel(describeChannel(reply));
        article.setFinishedAt(Instant.now());
        if (!generated.hasBody()) {
            // 调用成功但没正文：按失败记录，站长看到原因就能决定重试还是换通道
            article.setState("FAILED");
            article.setError("模型没有返回正文（可能被限流或输出被截断），可稍后重试或换一条通道");
            return article;
        }
        article.setTitles(String.join("\n", generated.titles()));
        article.setBody(generated.body());
        article.setTags(String.join(",", generated.tags()));
        article.setState("READY");
        if ("length".equalsIgnoreCase(reply.finishReason())) {
            article.setNote("输出撞到长度上限被截断：正文可能不完整，建议减少字数或换一条强通道");
        }
        return article;
    }

    private ToutiaoArticle withCost(ToutiaoArticle article, DigestAiUsageLog usage) {
        article.setAiCost(costJson(usage));
        return article;
    }

    /** 追加一句提示：note 里可能已经有截断/套话等说明，用分号接上，不互相覆盖 */
    private static void appendNote(ToutiaoArticle article, String extra) {
        if (!StringUtils.hasText(extra)) {
            return;
        }
        var current = article.getNote();
        article.setNote(StringUtils.hasText(current) ? current + "；" + extra : extra);
    }

    /** 生成失败：把原因翻成可操作的中文写进记录，绝不把记录留在「生成中」 */
    private Mono<ToutiaoArticle> failArticle(ToutiaoArticle article, Throwable ex) {
        log.warn("[UniHalo][Toutiao] 生成失败 name={}：{}",
            article.getMetadata().getName(), ex.getMessage(), ex);
        article.setState("FAILED");
        article.setError(readableError(ex));
        article.setFinishedAt(Instant.now());
        return store.update(article)
            .onErrorResume(updateEx -> {
                log.warn("[UniHalo][Toutiao] 失败状态也没写进去 name={}：{}",
                    article.getMetadata().getName(), updateEx.getMessage());
                return Mono.just(article);
            });
    }

    /**
     * 错误翻译：插件自己的前置校验（IllegalStateException）本身就是可操作的中文，
     * 原样给出；其余（网络 / 上游）交给 {@link DigestAiErrorExplainer} 的口径。
     */
    private static String readableError(Throwable ex) {
        if (ex == null) {
            return "生成失败：未知错误";
        }
        var message = String.valueOf(ex.getMessage());
        if (ex instanceof IllegalStateException && StringUtils.hasText(message)) {
            return message;
        }
        return DigestAiErrorExplainer.explainThrowable(ex, "");
    }

    private static String describeChannel(DigestAiReplyDTO reply) {
        var label = StringUtils.hasText(reply.channelLabel()) ? reply.channelLabel() : "默认通道";
        var model = StringUtils.hasText(reply.model()) ? reply.model() : "";
        return model.isEmpty() ? label : label + " / " + model;
    }

    private String costJson(DigestAiUsageLog usage) {
        try {
            if (!usage.isEmpty()) {
                return OBJECT_MAPPER.writeValueAsString(usage.snapshot());
            }
        } catch (Exception ex) {
            log.debug("[UniHalo][Toutiao] AI 花费快照序列化失败（不影响生成）：{}", ex.getMessage());
        }
        return null;
    }

    private void updateStage(String stage, String articleName) {
        var current = progress;
        progress = new Progress(true, stage, current.startedAt() == null ? Instant.now()
            : current.startedAt(), null, null, articleName);
    }
}
