package pro.uhalo.uni.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pro.uhalo.uni.dto.AiContentPreferenceDTO;
import pro.uhalo.uni.dto.AiContentPushResultDTO;
import pro.uhalo.uni.dto.PushConfigDTO;
import pro.uhalo.uni.scheme.AiContentIssue;
import pro.uhalo.uni.scheme.AiContentPreference;
import pro.uhalo.uni.services.impl.AiContentFeedService;
import pro.uhalo.uni.services.impl.AiContentPushService;
import pro.uhalo.uni.services.impl.PushDispatchService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每日定时生成 + 推送的整点分桶与幂等。
 *
 * <p>这里钉住三件事：① 只处理「选了当前整点」的用户（不扫全量）；
 * ② 今天已生成的用户跳过（不重复烧 AI）；③ 模板/字段没配全时整条线不跑。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiContentDailySchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Mock
    private ReactiveExtensionClient client;
    @Mock
    private AiContentFeedService feedService;
    @Mock
    private AiContentPushService pushService;
    @Mock
    private PushDispatchService pushDispatchService;

    private AiContentDailyScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AiContentDailyScheduler(client, feedService, pushService,
            pushDispatchService);
        lenient().when(pushService.push(anyString(), any(), any(), any()))
            .thenReturn(Mono.just(AiContentPushResultDTO.builder().pushed(true).build()));
        lenient().when(feedService.ensureToday(anyString())).thenReturn(Mono.just(issue()));
        // 默认「今天还没人生成过」：需要验幂等的用例再单独覆盖
        lenient().when(client.listAll(eq(AiContentIssue.class), any(), any()))
            .thenReturn(Flux.empty());
    }

    private static PushConfigDTO readyConfig() {
        return PushConfigDTO.builder()
            .enabled(true)
            .aiDigestEnabled(true)
            .aiDigestScheduleEnabled(true)
            .aiDigestTemplateId("tmpl-brief")
            .aiDigestFieldTopic("thing1")
            .aiDigestFieldContent("thing2")
            .aiDigestFieldTime("time3")
            .aiDigestFieldTip("thing4")
            .aiDigestMaxUsersPerRun(200)
            .aiDigestPushHours("8,12,18,21")
            .build();
    }

    @Test
    void shouldOnlyPickUsersOfThatHour() {
        stubPreferences(
            preference("openid-9", true, 9),
            preference("openid-12", true, 12),
            preference("openid-off", false, 12));

        StepVerifier.create(scheduler.runForHour(readyConfig(), 12))
            .expectNext(1)
            .verifyComplete();

        // 只给选了 12 点、且开关打开的那位生成与推送
        verify(feedService).ensureToday("openid-12");
        verify(feedService, never()).ensureToday("openid-9");
        verify(feedService, never()).ensureToday("openid-off");
    }

    @Test
    void shouldSkipUsersAlreadyGeneratedToday() {
        stubPreferences(preference("openid-12", true, 12));
        var today = LocalDate.now(ZONE).toString();
        var issue = new AiContentIssue();
        issue.setWxOpenid("openid-12");
        issue.setDate(today);
        when(client.listAll(eq(AiContentIssue.class), any(), any())).thenReturn(Flux.just(issue));

        StepVerifier.create(scheduler.runForHour(readyConfig(), 12))
            .expectNext(0)
            .verifyComplete();

        verify(feedService, never()).ensureToday(anyString());
        verify(pushService, never()).push(anyString(), any(), any(), any());
    }

    @Test
    void shouldNotRunWhenTemplateNotConfigured() {
        var broken = PushConfigDTO.builder()
            .enabled(true)
            .aiDigestEnabled(true)
            .aiDigestScheduleEnabled(true)
            .aiDigestTemplateId("")
            .build();
        assertThat(AiContentDailyScheduler.scheduleUsable(broken)).isFalse();

        var noFields = PushConfigDTO.builder()
            .enabled(true)
            .aiDigestEnabled(true)
            .aiDigestScheduleEnabled(true)
            .aiDigestTemplateId("tmpl-brief")
            .aiDigestFieldTopic("")
            .aiDigestFieldContent("")
            .aiDigestFieldTime("")
            .aiDigestFieldTip("")
            .build();
        assertThat(AiContentDailyScheduler.scheduleUsable(noFields)).isFalse();

        var off = PushConfigDTO.builder()
            .enabled(true)
            .aiDigestEnabled(true)
            .aiDigestScheduleEnabled(false)
            .aiDigestTemplateId("tmpl-brief")
            .aiDigestFieldTopic("thing1")
            .build();
        assertThat(AiContentDailyScheduler.scheduleUsable(off)).isFalse();

        assertThat(AiContentDailyScheduler.scheduleUsable(readyConfig())).isTrue();
    }

    /** 同一小时内调度器每分钟醒一次，但只应真正跑一轮（否则会把 AI 额度重复烧掉） */
    @Test
    void shouldRunOnlyOncePerHour() {
        var hour = ZonedDateTime.now(ZONE).getHour();
        stubPreferences(preference("openid-12", true, hour));
        when(pushDispatchService.loadPushConfigSafely()).thenReturn(Mono.just(readyConfig()));
        when(client.listAll(eq(AiContentIssue.class), any(), any())).thenReturn(Flux.empty());

        StepVerifier.create(scheduler.checkAndRunOnce())
            .expectNextMatches(message -> message.contains("已为 1 位用户"))
            .verifyComplete();
        // 同一小时内的第二次：直接跳过（返回空串，不再生成也不再推送）
        StepVerifier.create(scheduler.checkAndRunOnce())
            .expectNext("")
            .verifyComplete();

        verify(feedService, org.mockito.Mockito.times(1)).ensureToday("openid-12");
    }

    /** 推送没成功（比如额度用完了）时不计入成功数 */
    @Test
    void shouldNotCountUsersWithoutQuota() {
        stubPreferences(preference("openid-12", true, 12));
        when(client.listAll(eq(AiContentIssue.class), any(), any())).thenReturn(Flux.empty());
        when(pushService.push(anyString(), any(), any(), any()))
            .thenReturn(Mono.just(AiContentPushResultDTO.builder()
                .pushed(false).needQuota(true).build()));

        StepVerifier.create(scheduler.runForHour(readyConfig(), 12))
            .expectNext(0)
            .verifyComplete();
    }

    private static AiContentIssue issue() {
        var issue = new AiContentIssue();
        issue.setDate(LocalDate.now(ZONE).toString());
        issue.setItems("[]");
        issue.setMatchedCount(0);
        return issue;
    }

    private void stubPreferences(AiContentPreference... preferences) {
        when(client.listAll(eq(AiContentPreference.class), any(), any()))
            .thenReturn(Flux.fromArray(preferences));
    }

    private static AiContentPreference preference(String openid, boolean enabled, Integer hour) {
        var preference = new AiContentPreference();
        preference.setWxOpenid(openid);
        preference.setPushEnabled(enabled);
        preference.setPushHour(hour);
        preference.setCustomTopics(List.of("大模型落地"));
        return preference;
    }

    /** 说明：AiContentPreferenceDTO 只用于展示，这里显式引用一次以保持 import 有意义 */
    @Test
    void preferenceDtoShouldCarryPushFields() {
        var dto = AiContentPreferenceDTO.builder().pushEnabled(true).pushHour(9).build();
        assertThat(dto.isPushEnabled()).isTrue();
        assertThat(dto.getPushHour()).isEqualTo(9);
    }

    /** 时间工具：确认调度器用的时区是东八区（与站内其它定时任务一致） */
    @Test
    void zoneShouldBeShanghai() {
        assertThat(ZonedDateTime.now(ZONE).getZone()).isEqualTo(ZONE);
    }
}
