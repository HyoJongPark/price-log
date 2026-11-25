package kr.co.pricelog.global.job;

import static kr.co.pricelog.domain.game.enums.SteamAppType.DLC;
import static kr.co.pricelog.domain.game.enums.SteamAppType.GAME;
import static kr.co.pricelog.global.client.steam.enums.SteamPurchaseOptionType.PACKAGE;
import static kr.co.pricelog.global.job.SteamPurchaseOptionsFixture.visiblePurchaseOptions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import kr.co.pricelog.global.client.steam.SteamAppListClient;
import kr.co.pricelog.global.client.steam.SteamStoreClient;
import kr.co.pricelog.global.client.steam.dto.SteamApp;
import kr.co.pricelog.global.client.steam.dto.SteamAppPage;
import kr.co.pricelog.global.client.steam.dto.SteamPrice;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.OneTimeTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.Task;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClientException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {"STEAM_WEB_API_KEY=test-key",
    "pricelog.collection.daily.enabled=true"})
class SteamCollectionSchedulerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17.6-alpine");

    @MockitoBean
    private SteamAppListClient steamAppListClient;

    @MockitoBean
    private SteamStoreClient steamStoreClient;

    // 예약은 Spring이 등록하고 실제 시각에 따른 실행만 막는다.
    @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
    private TaskScheduler taskScheduler;

    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduling;

    @Autowired
    private SteamCatalogCollector steamCatalogCollector;

    @Autowired
    private SteamStoreCollector steamStoreCollector;

    @Autowired
    private SteamAppChangeCollector steamAppChangeCollector;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Spring이 기동 즉시 한 번 실행할 수집과 매시 정각 변경 수집을 등록한다")
    void registersStartupAndHourlyCollection() {
        // given
        String prefix = SteamCollectionScheduler.class.getName() + ".";
        Map<String, Task> tasks = scheduling.getScheduledTasks().stream()
            .map(ScheduledTask::getTask)
            .filter(task -> task.toString().startsWith(prefix))
            .collect(Collectors.toMap(Task::toString, task -> task));
        assertThat(tasks).containsOnlyKeys(prefix + "initialize", prefix + "collectChanges");
        OneTimeTask startup = (OneTimeTask) tasks.get(prefix + "initialize");
        CronTask hourly = (CronTask) tasks.get(prefix + "collectChanges");

        // when
        SimpleTriggerContext context = new SimpleTriggerContext(
            Clock.fixed(Instant.parse("2026-11-24T01:35:00Z"), ZoneOffset.UTC));
        Instant first = hourly.getTrigger().nextExecution(context);
        context.update(first, first, first);

        // then
        assertThat(startup.getInitialDelayDuration()).isZero();
        assertThat(first).isEqualTo(Instant.parse("2026-11-24T02:00:00Z"));
        assertThat(hourly.getTrigger().nextExecution(context))
            .isEqualTo(Instant.parse("2026-11-24T03:00:00Z"));
    }

    @Test
    @DisplayName("중단된 최초 목록을 완주하면 원래 시작 시각부터 변경을 읽고 재기동해도 전수를 반복하지 않는다")
    void initializesCatalogOnceAndCatchesUpAfterDowntime() {
        // given
        Instant start = Instant.parse("2026-11-22T17:27:08Z");
        Instant resumed = start.plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)
            .plusMillis(300);
        final Instant nextHour = resumed.truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS);
        SteamAppPage game = new SteamAppPage(List.of(new SteamApp(10, "본편")), OptionalLong.empty());
        SteamAppPage dlc = new SteamAppPage(List.of(new SteamApp(20, "DLC")), OptionalLong.of(20));
        SteamAppPage empty = new SteamAppPage(List.of(), OptionalLong.empty());
        SteamAppPage added = new SteamAppPage(
            List.of(new SteamApp(10, "본편"), new SteamApp(30, "신작")), OptionalLong.empty());
        when(steamAppListClient.getPage(GAME, 0, 10000)).thenReturn(game);
        when(steamAppListClient.getPage(DLC, 0, 10000)).thenReturn(dlc);
        when(steamAppListClient.getPage(DLC, 20, 10000))
            .thenThrow(new RestClientException("목록 중단")).thenReturn(empty);
        Instant firstWindow = start.minus(1, ChronoUnit.HOURS);
        when(steamAppListClient.getChangedPage(GAME, 0, 10000, firstWindow))
            .thenReturn(added);
        when(steamAppListClient.getChangedPage(DLC, 0, 10000, firstWindow))
            .thenReturn(empty);
        Instant lastWindow = resumed.truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
        when(steamAppListClient.getChangedPage(GAME, 0, 10000, lastWindow)).thenReturn(empty);
        when(steamAppListClient.getChangedPage(DLC, 0, 10000, lastWindow)).thenReturn(empty);
        SteamPurchaseOption option = new SteamPurchaseOption(PACKAGE, 40, "기본판",
            new SteamPrice(1000, null, null));
        when(steamStoreClient.getPurchaseOptions(List.of(30L))).thenReturn(
            Map.of(30L, visiblePurchaseOptions(List.of(option), option, false)));

        SteamPurchaseOption base = new SteamPurchaseOption(PACKAGE, 50, "본편",
            new SteamPrice(2000, null, null));
        SteamPurchaseOption discounted = new SteamPurchaseOption(PACKAGE, 50, "본편",
            new SteamPrice(1500, 2000L, null));
        when(steamStoreClient.getPurchaseOptions(List.of(10L)))
            .thenReturn(Map.of(10L, visiblePurchaseOptions(List.of(base), base, false)))
            .thenReturn(Map.of(10L, visiblePurchaseOptions(List.of(discounted), discounted,
                false)));

        // when
        final Throwable failure = catchThrowable(() -> scheduler().initialize(start));
        scheduler().initialize(resumed);
        SteamCollectionScheduler restarted = scheduler();
        restarted.initialize(nextHour);
        restarted.collectRecentChanges(nextHour);

        // then
        assertThat(failure).isInstanceOf(RestClientException.class).hasMessage("목록 중단");
        assertThat(jdbcTemplate.queryForList("SELECT steam_app_id FROM game ORDER BY steam_app_id",
            Long.class)).containsExactly(10L, 20L, 30L);
        assertThat(jdbcTemplate.queryForList("""
            SELECT scheduled_at FROM pipeline_execution
            WHERE pipeline IN ('STEAM_GAME_LIST', 'STEAM_DLC_LIST') AND status = 'COMPLETED'
            """, Timestamp.class)).containsExactly(Timestamp.from(start), Timestamp.from(start));
        assertThat(jdbcTemplate.queryForList("SELECT current_price FROM price_history", Long.class))
            .containsExactlyInAnyOrder(2000L, 1500L, 1000L);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT current_price FROM purchase_option WHERE steam_id = 50", Long.class))
            .isEqualTo(1500L);
        assertThat(jdbcTemplate.queryForList("""
            SELECT scheduled_at FROM pipeline_execution WHERE pipeline = 'STEAM_PRICE'
            """, Timestamp.class)).containsExactly(Timestamp.from(resumed));
        verify(steamAppListClient).getPage(GAME, 0, 10000);
        verify(steamAppListClient).getPage(DLC, 0, 10000);
        verify(steamAppListClient, times(2)).getPage(DLC, 20, 10000);
        verify(steamAppListClient).getChangedPage(GAME, 0, 10000, firstWindow);
        verify(steamAppListClient).getChangedPage(DLC, 0, 10000, firstWindow);
        verify(steamAppListClient).getChangedPage(GAME, 0, 10000, lastWindow);
        verify(steamAppListClient).getChangedPage(DLC, 0, 10000, lastWindow);
        verifyNoMoreInteractions(steamAppListClient);
        verify(steamStoreClient, times(2)).getPurchaseOptions(List.of(10L));
        verify(steamStoreClient).getPurchaseOptions(List.of(30L));
        verifyNoMoreInteractions(steamStoreClient);
    }

    private SteamCollectionScheduler scheduler() {
        return new SteamCollectionScheduler(steamCatalogCollector, steamStoreCollector,
            steamAppChangeCollector, 1);
    }
}
