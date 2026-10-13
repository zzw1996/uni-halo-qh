package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pro.uhalo.uni.dto.AiContentConfigDTO;
import pro.uhalo.uni.dto.DigestPublicArticleDTO;
import pro.uhalo.uni.dto.PushConfigDTO;
import run.halo.app.plugin.ReactiveSettingFetcher;
import pro.uhalo.uni.scheme.AiContentIssue;
import pro.uhalo.uni.dto.AiContentPreferenceDTO;
import pro.uhalo.uni.infra.DigestPersistence;
import pro.uhalo.uni.scheme.DigestReport;
import pro.uhalo.uni.scheme.DigestSubscription;
import pro.uhalo.uni.scheme.PushRecord;
import pro.uhalo.uni.scheme.PushState;
import pro.uhalo.uni.scheme.PushSubscription;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.services.AiContentEntitlementService;
import pro.uhalo.uni.services.WxSubscribeMessageService;
import pro.uhalo.uni.utils.AiContentConfigHelper;
import pro.uhalo.uni.utils.PushConfigHelper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 推送派发：决定「给谁发、发什么、发几条」。
 *
 * <p>一次性订阅的额度一次授权只有一条，所以这里的每一条约束都是在省钱、也都是在少打扰用户：
 * 没内容不推、每人每天最多一条、没额度静默跳过、同一件事不重复推。</p>
 *
 * <p><b>简报线的成本边界</b>：本服务<b>不依赖任何 AI 客户端</b>——「命中 N 篇」是用
 * {@code DigestPreferenceMatcher} 在候选池上纯筛选算出来的，用户点进消息才会真正生成简报。
 * 也就是说推送本身零 AI 成本，这一点由构造参数保证（没有 AI 依赖 = 不可能调用 AI）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushDispatchServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String OPENID = "openid-1";
    private static final String TODAY = LocalDate.now(ZONE).toString();

    @Mock
    private ReactiveExtensionClient client;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private PushSubscriptionService subscriptionService;

    @Mock
    private WxSubscribeMessageService messageService;

    @Mock
    private DigestPersistence digestPersistence;

    @Mock
    private DigestPublicService digestPublicService;

    @Mock
    private AiContentEntitlementService entitlementService;

    /** 订阅额度：批量解析上限，缺省按免费额度 5 个 */
    @Mock
    private SubscriptionQuotaService quotaService;

    private PushDispatchService service;

    @BeforeEach
    void setUp() {
        service = new PushDispatchService(client, settingFetcher, subscriptionService,
            messageService, quotaService);
        // 额度按默认（免费 5）参与筛选：与首页生效集合同一口径
        lenient().when(quotaService.limitsByOpenid(any()))
            .thenReturn(Mono.just(java.util.Map.of()));
        lenient().when(client.create(any(PushState.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        lenient().when(client.update(any(PushState.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        lenient().when(client.create(any(PushRecord.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        lenient().when(messageService.send(anyString(), anyString(),
                any(WxSubscribeMessageService.Message.class), anyString(), anyString(), any()))
            .thenReturn(Mono.just(new WxSubscribeMessageService.SendResult(true, 0, "", false,
                false)));
        lenient().when(subscriptionService.consume(anyString(), anyString()))
            .thenReturn(Mono.just(new PushSubscription()));
        lenient().when(subscriptionService.clearQuota(anyString(), anyString()))
            .thenReturn(Mono.just(new PushSubscription()));
        // 默认「没有推送记录」= 今天还没推过（Mockito 的 mock 返回 null 会在链上炸成 NPE）
        lenient().when(client.fetch(eq(PushRecord.class), anyString()))
            .thenReturn(Mono.error(new IllegalStateException("not found")));
        lenient().when(entitlementService.loadConfig())
            .thenReturn(Mono.just(AiContentConfigDTO.builder().enabled(true).windowDays(7).build()));
    }

    private static PushConfigDTO config() {
        return PushConfigDTO.builder()
            .enabled(true)
            .templateId("tmpl-new-post")
            .aiDigestTemplateId("tmpl-brief")
            .aiDigestFieldTopic("thing1")
            .aiDigestFieldContent("thing2")
            .aiDigestFieldTime("time3")
            .aiDigestFieldTip("thing4")
            .newPostEnabled(true)
            .aiDigestEnabled(true)
            .checkIntervalMinutes(5)
            .maxItemsPerMessage(3)
            .dailySendLimit(500)
            .lookbackHours(24)
            .miniprogramState("formal")
            .build();
    }

    private void stubState(PushState state) {
        when(client.fetch(eq(PushState.class), eq(PushState.RESOURCE_NAME)))
            .thenReturn(Mono.just(state));
    }

    private void stubArticles(Post... posts) {
        when(client.listAll(eq(Post.class), any(), any())).thenReturn(Flux.fromArray(posts));
    }

    private void stubSubscriptions(DigestSubscription... subscriptions) {
        when(client.listAll(eq(DigestSubscription.class), any(), any()))
            .thenReturn(Flux.fromArray(subscriptions));
    }

    private void stubQuotas(PushSubscription... quotas) {
        when(subscriptionService.listAll()).thenReturn(Mono.just(List.of(quotas)));
    }

    private static PushState state() {
        var state = new PushState();
        state.setMetadata(new Metadata());
        state.getMetadata().setName(PushState.RESOURCE_NAME);
        state.setDailyDate(TODAY);
        state.setDailySent(0);
        state.setDailySkipped(0);
        return state;
    }

    private static Post post(String name, String title, String category, Instant publishTime) {
        var post = new Post();
        var metadata = new Metadata();
        metadata.setName(name);
        post.setMetadata(metadata);
        var spec = new Post.PostSpec();
        spec.setTitle(title);
        spec.setPublish(true);
        spec.setDeleted(false);
        spec.setVisible(Post.VisibleEnum.PUBLIC);
        spec.setPublishTime(publishTime);
        spec.setCategories(List.of(category));
        post.setSpec(spec);
        return post;
    }

    private static DigestSubscription subscription(String openid, String... categories) {
        var subscription = new DigestSubscription();
        subscription.setWxOpenid(openid);
        subscription.setEnabled(true);
        subscription.setPostCategories(List.of(categories));
        return subscription;
    }

    private static PushSubscription quota(String openid, int newPost, int aiDigest) {
        var subscription = new PushSubscription();
        subscription.setWxOpenid(openid);
        subscription.setEnabled(true);
        subscription.setNewPostQuota(newPost);
        subscription.setAiDigestQuota(aiDigest);
        return subscription;
    }

    // ── 新文章线 ──

    /** 一次检查里命中多篇 → 合并成一条消息，额度只消耗一条 */
    @Test
    void multipleNewArticlesShouldMergeIntoOneMessage() {
        var state = state();
        stubState(state);
        stubArticles(
            post("p1", "第一篇", "tech", Instant.now().minusSeconds(600)),
            post("p2", "第二篇", "tech", Instant.now().minusSeconds(300)),
            post("p3", "不相关", "life", Instant.now().minusSeconds(120)));
        stubSubscriptions(subscription(OPENID, "tech"));
        stubQuotas(quota(OPENID, 1, 0));

        StepVerifier.create(service.dispatchNewPosts(config()))
            .expectNext(1)
            .verifyComplete();

        var captor = org.mockito.ArgumentCaptor.forClass(
            WxSubscribeMessageService.Message.class);
        verify(messageService, times(1)).send(eq(OPENID), eq("tmpl-new-post"),
            captor.capture(), eq("pages/tabbar/home/home"), eq("formal"), any());
        var message = captor.getValue();
        assertThat(message.title()).isEqualTo("第一篇");
        // 合并后说明「共 2 篇」，而不是把两篇都塞进标题
        assertThat(message.summary()).isEqualTo("共 2 篇新文章");
        assertThat(message.source()).isEqualTo("tech");
        verify(subscriptionService, times(1)).consume(OPENID, PushConfigHelper.KIND_NEW_POST);
    }

    /** 单篇时直接跳文章详情页 */
    @Test
    void singleNewArticleShouldOpenTheArticlePage() {
        stubState(state());
        stubArticles(post("p1", "只有一篇", "tech", Instant.now().minusSeconds(60)));
        stubSubscriptions(subscription(OPENID, "tech"));
        stubQuotas(quota(OPENID, 1, 0));

        StepVerifier.create(service.dispatchNewPosts(config()))
            .expectNext(1)
            .verifyComplete();

        verify(messageService).send(eq(OPENID), anyString(),
            any(WxSubscribeMessageService.Message.class),
            eq("pagesA/article-detail/article-detail?name=p1"), anyString(), any());
    }

    /** 每人每天最多一条：当天已有记录就不再打扰（这是额度最有效的用法） */
    @Test
    void alreadyPushedTodayShouldNotSendAgain() {
        stubState(state());
        stubArticles(post("p1", "第一篇", "tech", Instant.now().minusSeconds(60)));
        stubSubscriptions(subscription(OPENID, "tech"));
        stubQuotas(quota(OPENID, 5, 0));
        var existing = new PushRecord();
        existing.setWxOpenid(OPENID);
        when(client.fetch(eq(PushRecord.class), anyString())).thenReturn(Mono.just(existing));

        StepVerifier.create(service.dispatchNewPosts(config()))
            .expectNext(0)
            .verifyComplete();

        verify(messageService, never()).send(anyString(), anyString(),
            any(WxSubscribeMessageService.Message.class), anyString(), anyString());
    }

    /** 没额度就不推：推了也只会拿到 43101 */
    @Test
    void userWithoutQuotaShouldBeSkipped() {
        stubState(state());
        stubArticles(post("p1", "第一篇", "tech", Instant.now().minusSeconds(60)));
        stubSubscriptions(subscription(OPENID, "tech"));
        stubQuotas(quota(OPENID, 0, 0));

        StepVerifier.create(service.dispatchNewPosts(config()))
            .expectNext(0)
            .verifyComplete();

        verify(messageService, never()).send(anyString(), anyString(),
            any(WxSubscribeMessageService.Message.class), anyString(), anyString());
        verify(client, never()).create(any(PushRecord.class));
    }

    /** 用户订阅的分类与本批新文章没有交集 → 不推 */
    @Test
    void userWithUnrelatedSubscriptionShouldBeSkipped() {
        stubState(state());
        stubArticles(post("p1", "第一篇", "tech", Instant.now().minusSeconds(60)));
        stubSubscriptions(subscription(OPENID, "life", "games"));
        stubQuotas(quota(OPENID, 5, 0));

        StepVerifier.create(service.dispatchNewPosts(config()))
            .expectNext(0)
            .verifyComplete();

        verify(messageService, never()).send(anyString(), anyString(),
            any(WxSubscribeMessageService.Message.class), anyString(), anyString());
    }

    /** 没有新文章：什么都不发，只推进水位 */
    @Test
    void noNewArticlesShouldDoNothing() {
        stubState(state());
        stubArticles();

        StepVerifier.create(service.dispatchNewPosts(config()))
            .expectNext(0)
            .verifyComplete();

        verify(messageService, never()).send(anyString(), anyString(),
            any(WxSubscribeMessageService.Message.class), anyString(), anyString());
        // 没有新内容时不写状态：状态里记的是「上次处理到内容的时间」，
        // 空转也写一次会让每 5 分钟产生一次无意义的扩展写入
        verify(client, never()).update(any(PushState.class));
    }

    /** 未发布/定时发布/回收站的文章不参与推送（与「公众可见」同一口径） */
    @Test
    void draftOrScheduledPostsShouldBeIgnored() {
        var draft = post("draft", "草稿", "tech", Instant.now().minusSeconds(60));
        draft.getSpec().setPublish(false);
        var scheduled = post("scheduled", "定时", "tech", Instant.now().plusSeconds(3600));
        stubState(state());
        stubArticles(draft, scheduled);
        stubSubscriptions(subscription(OPENID, "tech"));
        stubQuotas(quota(OPENID, 5, 0));

        StepVerifier.create(service.dispatchNewPosts(config()))
            .expectNext(0)
            .verifyComplete();

        verify(messageService, never()).send(anyString(), anyString(),
            any(WxSubscribeMessageService.Message.class), anyString(), anyString());
    }

    /** 微信返回 43101（用户没额度/拒收）→ 清零额度并把原因记进 PushRecord */
    @Test
    void quotaExhaustedShouldClearQuotaAndRecordFailure() {
        stubState(state());
        stubArticles(post("p1", "第一篇", "tech", Instant.now().minusSeconds(60)));
        stubSubscriptions(subscription(OPENID, "tech"));
        stubQuotas(quota(OPENID, 1, 0));
        when(messageService.send(anyString(), anyString(),
            any(WxSubscribeMessageService.Message.class), anyString(), anyString(), any()))
            .thenReturn(Mono.just(new WxSubscribeMessageService.SendResult(false, 43101,
                "user refuse to accept the msg", true, false)));

        StepVerifier.create(service.dispatchNewPosts(config()))
            .expectNext(0)
            .verifyComplete();

        verify(subscriptionService).clearQuota(OPENID, PushConfigHelper.KIND_NEW_POST);
        var captor = org.mockito.ArgumentCaptor.forClass(PushRecord.class);
        verify(client).create(captor.capture());
        assertThat(captor.getValue().getErrorCode()).isEqualTo(43101);
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
    }

    /** 整站日上限：到顶后不再发送（保护微信接口） */
    @Test
    void dailySendLimitShouldStopSending() {
        var state = state();
        state.setDailySent(500);
        stubState(state);
        stubArticles(post("p1", "第一篇", "tech", Instant.now().minusSeconds(60)));
        stubSubscriptions(subscription(OPENID, "tech"));
        stubQuotas(quota(OPENID, 5, 0));

        StepVerifier.create(service.dispatchNewPosts(config()))
            .expectNext(0)
            .verifyComplete();

        verify(messageService, never()).send(anyString(), anyString(),
            any(WxSubscribeMessageService.Message.class), anyString(), anyString());
    }

    // ── 简报线（新：按人推送已生成的简报） ──

    /**
     * 有额度 + 今天没推过 → 发出「你的定制简报已生成」，并消耗一条额度。
     *
     * <p>推送的判据是「这位用户自己的简报已生成」，而不是「站长的日报已生成」——
     * 用户点开就是成品。</p>
     */
    @Test
    void shouldPushGeneratedBriefingToUser() {
        var issue = issue(TODAY, 3);
        stubQuotas(quota(OPENID, 0, 1));
        stubState(state());
        stubSubscriptionLoad(quota(OPENID, 0, 1));
        stubNoRecord();
        when(messageService.send(anyString(), anyString(),
            any(WxSubscribeMessageService.Message.class), anyString(), anyString(), any()))
            .thenReturn(Mono.just(WxSubscribeMessageService.SendResult.success()));

        StepVerifier.create(service.pushAiContentReady(OPENID, config(), issue,
                preference("大模型落地"), false))
            .assertNext(attempt -> assertThat(attempt.outcome())
                .isEqualTo(PushDispatchService.PushOutcome.SENT))
            .verifyComplete();

        var captor = org.mockito.ArgumentCaptor.forClass(WxSubscribeMessageService.Message.class);
        verify(messageService).send(eq(OPENID), eq("tmpl-brief"), captor.capture(),
            eq("pagesA/ai-content/ai-content"), eq("formal"), any());
        assertThat(captor.getValue().title()).isEqualTo("你的定制简报已生成");
        assertThat(captor.getValue().summary()).contains("2 条内容").contains("大模型落地");
        verify(subscriptionService).consume(OPENID, PushConfigHelper.KIND_AI_DIGEST);
    }

    /** 没有推送额度（一次性订阅没授权/用完了）→ 不发，也不报错 */
    @Test
    void shouldSkipWhenNoPushQuota() {
        stubSubscriptionLoad(quota(OPENID, 1, 0));

        StepVerifier.create(service.pushAiContentReady(OPENID, config(), issue(TODAY, 3),
                preference("主题"), false))
            .assertNext(attempt -> assertThat(attempt.outcome())
                .isEqualTo(PushDispatchService.PushOutcome.NO_QUOTA))
            .verifyComplete();

        verify(messageService, never()).send(anyString(), anyString(),
            any(WxSubscribeMessageService.Message.class), anyString(), anyString(), any());
    }

    /**
     * 手动推送只看额度：用户没打开「每天定时推送」（订阅总开关关着）也照样能推。
     *
     * <p>这是线上真实踩到的坑：页面下方明明写着「剩余 3 条」，点「立即推送」却提示
     * 「额度不足」——因为手动推送错误地复用了自动推送的判定（额度 + 用户总开关）。</p>
     */
    @Test
    void manualPushShouldIgnoreUserSwitch() {
        var switchedOff = quota(OPENID, 0, 3);
        switchedOff.setEnabled(false);
        stubSubscriptionLoad(switchedOff);
        stubState(state());
        stubNoRecord();

        StepVerifier.create(service.pushAiContentReady(OPENID, config(), issue(TODAY, 3),
                preference("主题"), true))
            .assertNext(attempt -> assertThat(attempt.outcome())
                .isEqualTo(PushDispatchService.PushOutcome.SENT))
            .verifyComplete();

        verify(messageService).send(anyString(), anyString(),
            any(WxSubscribeMessageService.Message.class), anyString(), anyString(), any());
    }

    /** 自动推送（定时线）仍然尊重用户总开关：关掉了就不打扰 */
    @Test
    void scheduledPushShouldRespectUserSwitch() {
        var switchedOff = quota(OPENID, 0, 3);
        switchedOff.setEnabled(false);
        stubSubscriptionLoad(switchedOff);

        StepVerifier.create(service.pushAiContentReady(OPENID, config(), issue(TODAY, 3),
                preference("主题"), false))
            .assertNext(attempt -> assertThat(attempt.outcome())
                .isEqualTo(PushDispatchService.PushOutcome.DISABLED))
            .verifyComplete();

        verify(messageService, never()).send(anyString(), anyString(),
            any(WxSubscribeMessageService.Message.class), anyString(), anyString(), any());
    }

    /** 今天已经推过 → 不重复推（每人每天一条），并且要如实说明是「已推过」而不是「没额度」 */
    @Test
    void shouldSkipWhenAlreadyPushedToday() {
        stubSubscriptionLoad(quota(OPENID, 0, 2));
        var record = new PushRecord();
        record.setMetadata(new Metadata());
        record.getMetadata().setName("push-existing");
        when(client.fetch(eq(PushRecord.class), anyString())).thenReturn(Mono.just(record));

        StepVerifier.create(service.pushAiContentReady(OPENID, config(), issue(TODAY, 3),
                preference("主题"), false))
            .assertNext(attempt -> assertThat(attempt.outcome())
                .isEqualTo(PushDispatchService.PushOutcome.ALREADY_PUSHED))
            .verifyComplete();

        verify(messageService, never()).send(anyString(), anyString(),
            any(WxSubscribeMessageService.Message.class), anyString(), anyString(), any());
    }

    /** 用户没设定制主题：文案退到「你的关注方向」，不能出现空标题 */
    @Test
    void shouldFallbackTopicWhenNoCustomTopic() {
        stubSubscriptionLoad(quota(OPENID, 0, 1));
        stubState(state());
        stubNoRecord();
        when(messageService.send(anyString(), anyString(),
            any(WxSubscribeMessageService.Message.class), anyString(), anyString(), any()))
            .thenReturn(Mono.just(WxSubscribeMessageService.SendResult.success()));

        StepVerifier.create(service.pushAiContentReady(OPENID, config(), issue(TODAY, 2),
                preference(null), false))
            .assertNext(attempt -> assertThat(attempt.outcome())
                .isEqualTo(PushDispatchService.PushOutcome.SENT))
            .verifyComplete();

        var captor = org.mockito.ArgumentCaptor.forClass(WxSubscribeMessageService.Message.class);
        verify(messageService).send(anyString(), anyString(), captor.capture(), anyString(),
            anyString(), any());
        assertThat(captor.getValue().summary()).contains("你的关注方向");
    }

    private static AiContentIssue issue(String date, int matched) {
        var issue = new AiContentIssue();
        issue.setWxOpenid(OPENID);
        issue.setDate(date);
        issue.setIntro("导语");
        issue.setMatchedCount(matched);
        issue.setItems("[{\"title\":\"a\"},{\"title\":\"b\"}]");
        issue.setCreatedAt(Instant.now());
        return issue;
    }

    private static AiContentPreferenceDTO preference(String topic) {
        return AiContentPreferenceDTO.builder()
            .customTopics(topic == null ? List.of() : List.of(topic))
            .build();
    }

    private void stubSubscriptionLoad(PushSubscription subscription) {
        when(subscriptionService.load(anyString())).thenReturn(Mono.just(subscription));
    }

    private void stubNoRecord() {
        when(client.fetch(eq(PushRecord.class), anyString()))
            .thenReturn(Mono.error(new IllegalStateException("not found")));
    }

    /** 永久买断的权益同样有效（远期时间表示法） */
    @Test
    void permanentEntitlementShouldReceiveBriefing() {
        assertThat(AiContentConfigHelper.isPermanent(AiContentConfigHelper.PERMANENT_EXPIRE_AT))
            .isTrue();
        assertThat(AiContentConfigHelper.isActive(AiContentConfigHelper.PERMANENT_EXPIRE_AT))
            .isTrue();
    }

    /** 状态单例缺失时自动创建（首次部署不该因为「没有状态记录」而一推不发） */
    @Test
    void missingStateShouldBeCreated() {
        when(client.fetch(eq(PushState.class), anyString()))
            .thenReturn(Mono.error(new IllegalStateException("not found")));
        var created = new AtomicInteger();
        when(client.create(any(PushState.class))).thenAnswer(invocation -> {
            created.incrementAndGet();
            return Mono.just(invocation.getArgument(0));
        });
        stubArticles();

        StepVerifier.create(service.dispatchNewPosts(config()))
            .expectNext(0)
            .verifyComplete();

        assertThat(created.get()).isEqualTo(1);
    }
}
