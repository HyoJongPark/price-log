package kr.co.pricelog.global.job;

import static kr.co.pricelog.domain.game.enums.SteamAppType.DLC;
import static kr.co.pricelog.domain.game.enums.SteamAppType.GAME;
import static kr.co.pricelog.global.client.steam.enums.SteamPurchaseOptionType.PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import kr.co.pricelog.global.client.steam.SteamAppListClient;
import kr.co.pricelog.global.client.steam.SteamStoreClient;
import kr.co.pricelog.global.client.steam.dto.SteamApp;
import kr.co.pricelog.global.client.steam.dto.SteamAppPage;
import kr.co.pricelog.global.client.steam.dto.SteamPrice;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOption;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClientException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = "STEAM_WEB_API_KEY=test-key")
class SteamDailyCollectionSchedulerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17.6-alpine");

    @MockitoBean
    private SteamAppListClient steamAppListClient;

    @MockitoBean
    private SteamStoreClient steamStoreClient;

    @Autowired
    private SteamCatalogCollector steamCatalogCollector;

    @Autowired
    private SteamStoreCollector steamStoreCollector;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("카탈로그가 실패한 날은 비워 두고, 다음 날 같은 본편의 오전·오후 가격을 각각 기록한다")
    void collectsAfterCatalogCompletesAndSkipsCompletedSlot() {
        // given
        Instant firstSlot = Instant.parse("2026-11-22T18:10:00Z");
        SteamDailyCollectionScheduler scheduler = new SteamDailyCollectionScheduler(
            steamCatalogCollector, steamStoreCollector, 1);
        SteamAppPage game = new SteamAppPage(List.of(new SteamApp(10, "본편")), OptionalLong.empty());
        SteamAppPage dlc = new SteamAppPage(List.of(new SteamApp(20, "DLC")), OptionalLong.empty());
        when(steamAppListClient.getPage(GAME, 0, 10000)).thenReturn(game);
        when(steamAppListClient.getPage(DLC, 0, 10000))
            .thenThrow(new RestClientException("목록 수집 실패")).thenReturn(dlc);
        SteamPurchaseOption option = new SteamPurchaseOption(PACKAGE, 30, "기본판",
            new SteamPrice(1000, null, null));
        when(steamStoreClient.getPurchaseOptions(List.of(10L))).thenReturn(
            Map.of(10L, new SteamPurchaseOptions(List.of(option), option, false)));

        // when
        final Throwable failure = catchThrowable(() -> scheduler.collectToday(firstSlot));
        Instant nextSlot = firstSlot.plus(1, ChronoUnit.DAYS);
        scheduler.collectToday(nextSlot.minusSeconds(1));
        scheduler.collectToday(nextSlot);
        Instant afternoon = nextSlot.plus(12, ChronoUnit.HOURS);
        scheduler.collectToday(afternoon);
        SteamDailyCollectionScheduler restarted = new SteamDailyCollectionScheduler(
            steamCatalogCollector, steamStoreCollector, 1);
        restarted.collectToday(afternoon.plusSeconds(120));

        // then
        assertThat(failure).isInstanceOf(RestClientException.class).hasMessage("목록 수집 실패");
        assertThat(jdbcTemplate.queryForList(
            "SELECT slot_at FROM price_history ORDER BY slot_at", Timestamp.class))
            .containsExactly(Timestamp.from(nextSlot), Timestamp.from(afternoon));
        assertThat(jdbcTemplate.queryForList("""
            SELECT status FROM pipeline_execution WHERE pipeline = 'STEAM_DLC_LIST'
            ORDER BY scheduled_at
            """, String.class)).containsExactly("FAILED", "COMPLETED");
        verify(steamAppListClient, times(2)).getPage(GAME, 0, 10000);
        verify(steamAppListClient, times(2)).getPage(DLC, 0, 10000);
        verify(steamStoreClient, times(2)).getPurchaseOptions(List.of(10L));
        verifyNoMoreInteractions(steamStoreClient);
    }
}
