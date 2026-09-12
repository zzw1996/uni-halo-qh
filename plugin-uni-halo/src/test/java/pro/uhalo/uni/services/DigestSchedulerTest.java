package pro.uhalo.uni.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.infra.DigestPersistence;
import pro.uhalo.uni.scheme.DigestReport;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 定时器口径：到点才跑、同一时段当天只跑一次、失败可重试但不无限重试。
 */
@ExtendWith(MockitoExtension.class)
class DigestSchedulerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 固定在 2026-09-11 08:00（站点时区），07:00 已经到点、09:00 还没到 */
    private static final ZonedDateTime FIXED_NOW =
        ZonedDateTime.of(2026, 9, 11, 8, 0, 0, 0, ZONE);
    private static final String TODAY = "2026-09-11";

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private DigestPersistence persistence;

    @Mock
    private DigestService digestService;

    private DigestScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = Mockito.spy(new DigestScheduler(settingFetcher, persistence, digestService));
        lenient().doReturn(FIXED_NOW).when(scheduler).now(any(ZoneId.class));
    }

    private void stubConfig(String json) throws Exception {
        when(settingFetcher.get("aiDigestConfig")).thenReturn(Mono.just(MAPPER.readTree(json)));
    }

    private static DigestReport report(String status, String slot, Instant startedAt) {
        var report = new DigestReport();
        report.setStatus(status);
        report.setSlot(slot);
        report.setDate(TODAY);
        report.setStartedAt(startedAt);
        return report;
    }

    @Test
    void shouldSkipWhenDisabled() throws Exception {
        stubConfig("{\"enabled\":false,\"scheduleTimes\":\"07:00\"}");

        StepVerifier.create(scheduler.checkAndRunOnce()).verifyComplete();

        verifyNoInteractions(digestService);
    }

    @Test
    void shouldSkipWhenScheduleDisabled() throws Exception {
        stubConfig("{\"enabled\":true,\"scheduleEnabled\":false,\"scheduleTimes\":\"07:00\"}");

        StepVerifier.create(scheduler.checkAndRunOnce()).verifyComplete();

        verifyNoInteractions(digestService);
    }

    @Test
    void shouldSkipBeforeScheduledTime() throws Exception {
        // 08:00 检查 09:00 的任务：还没到点，不能提前跑
        stubConfig("{\"enabled\":true,\"scheduleTimes\":\"09:00\"}");
        when(digestService.isRunning()).thenReturn(false);

        StepVerifier.create(scheduler.checkAndRunOnce()).verifyComplete();

        verify(digestService, never()).run(any(), anyBoolean());
    }

    @Test
    void shouldRunAtFirstCheckAfterScheduledTime() throws Exception {
        // 07:00 的计划在 08:00 检查时补跑：插件重启/服务器离线错过时间点也能补上
        stubConfig("{\"enabled\":true,\"scheduleTimes\":\"07:00\",\"publishEnabled\":true}");
        when(digestService.isRunning()).thenReturn(false);
        when(persistence.listByDate(TODAY)).thenReturn(Mono.just(List.of()));
        when(digestService.run("07:00", true)).thenReturn(Mono.just(report("SUCCESS", "07:00", null)));

        StepVerifier.create(scheduler.checkAndRunOnce())
            .expectNextMatches(message -> message.contains("07:00") && message.contains("SUCCESS"))
            .verifyComplete();

        verify(digestService).run("07:00", true);
    }

    @Test
    void shouldNotRunTwiceForTheSameSlot() throws Exception {
        stubConfig("{\"enabled\":true,\"scheduleTimes\":\"07:00\"}");
        when(digestService.isRunning()).thenReturn(false);
        when(persistence.listByDate(TODAY)).thenReturn(Mono.just(List.of(
            report("SUCCESS", "07:00", FIXED_NOW.toInstant().minusSeconds(1800)))));

        StepVerifier.create(scheduler.checkAndRunOnce()).verifyComplete();

        verify(digestService, never()).run(any(), anyBoolean());
    }

    @Test
    void shouldSkipWhileAnotherRunIsInFlight() throws Exception {
        stubConfig("{\"enabled\":true,\"scheduleTimes\":\"07:00\"}");
        when(digestService.isRunning()).thenReturn(true);

        StepVerifier.create(scheduler.checkAndRunOnce()).verifyComplete();

        verify(digestService, never()).run(any(), anyBoolean());
    }

    @Test
    void shouldNotRetryForeverAfterRepeatedFailures() throws Exception {
        // 同一时段已经失败两次：不再重试，避免每分钟重跑烧掉 AI 额度
        stubConfig("{\"enabled\":true,\"scheduleTimes\":\"07:00\"}");
        when(digestService.isRunning()).thenReturn(false);
        when(persistence.listByDate(TODAY)).thenReturn(Mono.just(List.of(
            report("FAILED", "07:00", FIXED_NOW.toInstant().minusSeconds(3600)),
            report("FAILED", "07:00", FIXED_NOW.toInstant().minusSeconds(1800)))));

        StepVerifier.create(scheduler.checkAndRunOnce()).verifyComplete();

        verify(digestService, never()).run(any(), anyBoolean());
    }

    @Test
    void shouldRetryOnceAfterASingleFailure() throws Exception {
        stubConfig("{\"enabled\":true,\"scheduleTimes\":\"07:00\"}");
        when(digestService.isRunning()).thenReturn(false);
        when(persistence.listByDate(TODAY)).thenReturn(Mono.just(List.of(
            report("FAILED", "07:00", FIXED_NOW.toInstant().minusSeconds(1800)))));
        when(digestService.run("07:00", true)).thenReturn(Mono.just(report("SUCCESS", "07:00", null)));

        StepVerifier.create(scheduler.checkAndRunOnce())
            .expectNextMatches(message -> message.contains("07:00"))
            .verifyComplete();

        verify(digestService).run("07:00", true);
    }

    @Test
    void shouldTreatStaleRunningRecordAsRetryable() throws Exception {
        // 进程崩溃留下的 RUNNING（超过 30 分钟）不能被当成「正在跑」而永久卡住当天任务
        stubConfig("{\"enabled\":true,\"scheduleTimes\":\"07:00\"}");
        when(digestService.isRunning()).thenReturn(false);
        when(persistence.listByDate(TODAY)).thenReturn(Mono.just(List.of(
            report("RUNNING", "07:00", FIXED_NOW.toInstant().minusSeconds(3 * 3600)))));
        when(digestService.run("07:00", true)).thenReturn(Mono.just(report("SUCCESS", "07:00", null)));

        StepVerifier.create(scheduler.checkAndRunOnce())
            .expectNextMatches(message -> message.contains("07:00"))
            .verifyComplete();

        verify(digestService).run("07:00", true);
    }

    @Test
    void shouldSkipFreshRunningRecord() throws Exception {
        stubConfig("{\"enabled\":true,\"scheduleTimes\":\"07:00\"}");
        when(digestService.isRunning()).thenReturn(false);
        when(persistence.listByDate(TODAY)).thenReturn(Mono.just(List.of(
            report("RUNNING", "07:00", FIXED_NOW.toInstant().minusSeconds(120)))));

        StepVerifier.create(scheduler.checkAndRunOnce()).verifyComplete();

        verify(digestService, never()).run(any(), anyBoolean());
    }

    @Test
    void shouldPickLatestDueSlotForMultipleTimesPerDay() throws Exception {
        // 配置 07:00 与 19:00，20:00 检查时 07:00 已跑完，19:00 还没跑 → 跑 19:00
        stubConfig("{\"enabled\":true,\"scheduleTimes\":\"07:00,19:00\"}");
        lenient().doReturn(ZonedDateTime.of(2026, 9, 11, 20, 0, 0, 0, ZONE))
            .when(scheduler).now(any(ZoneId.class));
        when(digestService.isRunning()).thenReturn(false);
        when(persistence.listByDate(TODAY)).thenReturn(Mono.just(List.of(
            report("SUCCESS", "07:00", FIXED_NOW.toInstant()))));
        when(digestService.run("19:00", true)).thenReturn(Mono.just(report("SUCCESS", "19:00", null)));

        StepVerifier.create(scheduler.checkAndRunOnce())
            .expectNextMatches(message -> message.contains("19:00"))
            .verifyComplete();

        verify(digestService).run(eq("19:00"), eq(true));
    }

    @Test
    void nextRunInstantShouldPointToNextConfiguredTime() throws Exception {
        stubConfig("{\"enabled\":true,\"scheduleTimes\":\"07:00,19:00\"}");

        StepVerifier.create(scheduler.nextRunInstant())
            .expectNextMatches(instant -> instant.equals(
                ZonedDateTime.of(2026, 9, 11, 19, 0, 0, 0, ZONE).toInstant()))
            .verifyComplete();
    }

    @Test
    void nextRunInstantShouldRollOverToTomorrow() throws Exception {
        stubConfig("{\"enabled\":true,\"scheduleTimes\":\"07:00\"}");
        lenient().doReturn(ZonedDateTime.of(2026, 9, 11, 23, 0, 0, 0, ZONE))
            .when(scheduler).now(any(ZoneId.class));

        StepVerifier.create(scheduler.nextRunInstant())
            .expectNextMatches(instant -> instant.equals(
                ZonedDateTime.of(2026, 9, 12, 7, 0, 0, 0, ZONE).toInstant()))
            .verifyComplete();
    }

    @Test
    void nextRunInstantShouldBeEmptyWhenDisabled() throws Exception {
        stubConfig("{\"enabled\":false}");

        StepVerifier.create(scheduler.nextRunInstant()).verifyComplete();
    }

    @Test
    void manualSlotShouldBeUniquePerSecond() {
        var slot = DigestPersistence.manualSlot(FIXED_NOW);
        assertThat(slot).isEqualTo("manual-080000");
    }
}
