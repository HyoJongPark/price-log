package kr.co.pricelog.global.job;

import static kr.co.pricelog.domain.game.enums.SteamAppType.DLC;
import static kr.co.pricelog.domain.game.enums.SteamAppType.GAME;
import static kr.co.pricelog.global.client.steam.enums.SteamPurchaseOptionType.PACKAGE;
import static kr.co.pricelog.global.job.SteamPurchaseOptionsFixture.visiblePurchaseOptions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.http.HttpTimeoutException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {"STEAM_WEB_API_KEY=test-key",
    "resilience4j.retry.instances.steamCollection.wait-duration=1ms"})
class SteamAppChangeCollectorIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17.6-alpine");

    @MockitoBean
    private SteamAppListClient steamAppListClient;

    @MockitoBean
    private SteamStoreClient steamStoreClient;

    @Autowired
    private SteamStoreCollector steamStoreCollector;

    @Autowired
    private SteamAppChangeCollector steamAppChangeCollector;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Instant MIDNIGHT = Instant.parse("2026-11-23T15:00:00Z");
    private static final Instant CATALOG_START = MIDNIGHT.minus(2, ChronoUnit.DAYS);
    private static final SteamAppPage EMPTY = new SteamAppPage(List.of(), OptionalLong.empty());

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute("TRUNCATE pipeline_execution, purchase_option, game CASCADE");
        jdbcTemplate.update("""
            INSERT INTO pipeline_execution
                (pipeline, scheduled_at, status, finished_at, created_at, updated_at)
            VALUES ('STEAM_DLC_LIST', ?, 'COMPLETED', now(), now(), now())
            """, Timestamp.from(CATALOG_START));
    }

    @Test
    @DisplayName("전수 이력이 여러 개인 DB의 첫 변경 조회는 가장 이른 완주 회차부터 따라잡는다")
    void catchesUpFromEarliestCompletedCatalog() {
        // given
        jdbcTemplate.update("""
            INSERT INTO pipeline_execution
                (pipeline, scheduled_at, status, finished_at, created_at, updated_at)
            VALUES ('STEAM_DLC_LIST', ?, 'FAILED', now(), now(), now()),
                   ('STEAM_DLC_LIST', ?, 'COMPLETED', now(), now(), now())
            """, Timestamp.from(CATALOG_START.minus(1, ChronoUnit.DAYS)),
            Timestamp.from(CATALOG_START.plus(1, ChronoUnit.DAYS)));
        Instant firstWindow = CATALOG_START.minus(1, ChronoUnit.HOURS);
        Instant nextWindow = MIDNIGHT.minus(1, ChronoUnit.HOURS);
        when(steamAppListClient.getChangedPage(GAME, 0, 10000, firstWindow)).thenReturn(page(70));
        when(steamAppListClient.getChangedPage(DLC, 0, 10000, firstWindow)).thenReturn(EMPTY);
        when(steamAppListClient.getChangedPage(GAME, 0, 10000, nextWindow)).thenReturn(EMPTY);
        when(steamAppListClient.getChangedPage(DLC, 0, 10000, nextWindow)).thenReturn(EMPTY);
        when(steamStoreClient.getPurchaseOptions(List.of(70L)))
            .thenReturn(Map.of(70L, option(70L)));

        // when
        steamAppChangeCollector.collect(MIDNIGHT, 260);
        steamAppChangeCollector.collect(MIDNIGHT.plus(1, ChronoUnit.HOURS), 260);

        // then
        assertThat(jdbcTemplate.queryForList("SELECT slot_at FROM price_history", Timestamp.class))
            .containsExactly(Timestamp.from(MIDNIGHT));
        verify(steamAppListClient).getChangedPage(GAME, 0, 10000, nextWindow);
        verify(steamAppListClient).getChangedPage(DLC, 0, 10000, nextWindow);
    }

    @Test
    @DisplayName("같은 변경 회차에서 목록 429는 구간을 다시 읽고 가격 429는 미저장 묶음부터 재개한다")
    void retriesChangeDiscoveryAndPriceBatches() {
        // given
        Instant since = CATALOG_START.minus(1, ChronoUnit.HOURS);
        HttpClientErrorException rateLimit = HttpClientErrorException.create(
            HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null, null, null);
        when(steamAppListClient.getChangedPage(GAME, 0, 10000, since)).thenReturn(page(10, 20));
        when(steamAppListClient.getChangedPage(DLC, 0, 10000, since))
            .thenThrow(rateLimit).thenReturn(EMPTY);
        when(steamStoreClient.getPurchaseOptions(List.of(10L)))
            .thenReturn(Map.of(10L, option(10L)));
        when(steamStoreClient.getPurchaseOptions(List.of(20L)))
            .thenThrow(rateLimit).thenReturn(Map.of(20L, option(20L)));

        // when
        steamAppChangeCollector.collect(MIDNIGHT, 1);

        // then
        verify(steamAppListClient, times(2)).getChangedPage(GAME, 0, 10000, since);
        verify(steamAppListClient, times(2)).getChangedPage(DLC, 0, 10000, since);
        verify(steamStoreClient).getPurchaseOptions(List.of(10L));
        verify(steamStoreClient, times(2)).getPurchaseOptions(List.of(20L));
        verifyNoMoreInteractions(steamAppListClient, steamStoreClient);
        assertThat(status(MIDNIGHT)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForList("SELECT slot_at FROM price_history", Timestamp.class))
            .containsExactly(Timestamp.from(MIDNIGHT), Timestamp.from(MIDNIGHT));
    }

    @Test
    @DisplayName("첫 조회가 실패해도 다음 회차는 이전 구간부터 다시 읽고 등록된 본편과 DLC의 가격을 함께 확인한다")
    void retriesListWindowAndSharesHistoryWithFullCollection() {
        // given
        Instant since = CATALOG_START.minus(1, ChronoUnit.HOURS);
        final Instant nextHour = MIDNIGHT.plus(1, ChronoUnit.HOURS);
        SteamAppPage first = new SteamAppPage(List.of(new SteamApp(10, "게임")), OptionalLong.of(10));
        when(steamAppListClient.getChangedPage(GAME, 0, 10000, since)).thenReturn(first);
        when(steamAppListClient.getChangedPage(GAME, 10, 10000, since))
            .thenThrow(new RestClientException("중단")).thenReturn(EMPTY);
        when(steamAppListClient.getChangedPage(DLC, 0, 10000, since)).thenReturn(page(20, 21));
        when(steamStoreClient.getDlcParents(List.of(20L, 21L))).thenReturn(Map.of(20L, 10L));
        when(steamStoreClient.getPurchaseOptions(List.of(10L, 20L)))
            .thenReturn(Map.of(10L, option(10L), 20L, option(20L)));

        // when
        final Throwable failure =
            catchThrowable(() -> steamAppChangeCollector.collect(MIDNIGHT, 260));
        steamAppChangeCollector.collect(nextHour, 260);
        steamAppChangeCollector.collect(nextHour, 260);
        steamStoreCollector.collectAll(nextHour, 260);

        // then
        assertThat(failure).hasMessage("중단");
        assertThat(status(MIDNIGHT)).isEqualTo("FAILED");
        assertThat(status(nextHour)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForList("SELECT app_type FROM game ORDER BY steam_app_id",
            String.class)).containsExactly("GAME", "DLC");
        assertThat(jdbcTemplate.queryForList("""
            SELECT g.steam_app_id, o.steam_id, h.slot_at
            FROM price_history h JOIN purchase_option o ON o.id = h.purchase_option_id
            JOIN game_purchase_option link ON link.purchase_option_id = o.id
            JOIN game g ON g.id = link.game_id
            WHERE link.deleted_at IS NULL ORDER BY g.steam_app_id
            """)).containsExactly(
                Map.of("steam_app_id", 10L, "steam_id", 10L, "slot_at", Timestamp.from(nextHour)),
                Map.of("steam_app_id", 20L, "steam_id", 20L, "slot_at", Timestamp.from(nextHour)));
        verify(steamStoreClient, times(2)).getPurchaseOptions(List.of(10L, 20L));
        assertThat(jdbcTemplate.queryForList("""
            SELECT dlc.steam_app_id FROM game dlc JOIN game parent ON parent.id = dlc.parent_game_id
            WHERE parent.steam_app_id = 10
            """, Long.class)).containsExactly(20L);
        verify(steamStoreClient).getDlcParents(List.of(20L, 21L));
        verifyNoMoreInteractions(steamStoreClient);
    }

    @Test
    @DisplayName("같은 예정 시각에 전수 수집이 실패해도 변경분 회차의 진행과 가격 이력은 유지된다")
    void isolatesFailedFullCollectionFromCompletedChangeCollection() {
        // given
        when(steamAppListClient.getChangedPage(eq(GAME), eq(0L), eq(10000), any()))
            .thenReturn(page(10));
        when(steamAppListClient.getChangedPage(eq(DLC), eq(0L), eq(10000), any()))
            .thenReturn(EMPTY);
        when(steamStoreClient.getPurchaseOptions(List.of(10L)))
            .thenReturn(Map.of(10L, option(10L)))
            .thenThrow(new RestClientException("전수 수집 중단"))
            .thenReturn(Map.of(10L, option(10L)));

        // when
        steamAppChangeCollector.collect(MIDNIGHT, 1);
        Throwable failure = catchThrowable(() -> steamStoreCollector.collectAll(MIDNIGHT, 1));

        // then
        assertThat(failure).hasMessage("전수 수집 중단");
        String progress = """
            SELECT pipeline, status, next_index FROM pipeline_execution
            WHERE scheduled_at = ? ORDER BY pipeline
            """;
        Timestamp slot = Timestamp.from(MIDNIGHT);
        assertThat(jdbcTemplate.queryForList(progress, slot)).containsExactly(
            Map.of("pipeline", "STEAM_CHANGED_APPS", "status", "COMPLETED", "next_index", 1),
            Map.of("pipeline", "STEAM_PRICE", "status", "FAILED", "next_index", 0));

        steamStoreCollector.collectAll(MIDNIGHT, 1);
        assertThat(jdbcTemplate.queryForList(progress, slot)).containsExactly(
            Map.of("pipeline", "STEAM_CHANGED_APPS", "status", "COMPLETED", "next_index", 1),
            Map.of("pipeline", "STEAM_PRICE", "status", "COMPLETED", "next_index", 1));
        assertThat(jdbcTemplate.queryForList("SELECT slot_at FROM price_history", Timestamp.class))
            .containsExactly(slot);
    }

    @Test
    @DisplayName("변경분 가격 수집을 같은 회차로 재개하면 실패한 묶음부터 이어서 저장한다")
    void resumesChangedPricesFromLastCommittedBatch() {
        // given
        Instant since = CATALOG_START.minus(1, ChronoUnit.HOURS);
        when(steamAppListClient.getChangedPage(GAME, 0, 10000, since)).thenReturn(page(10, 20));
        when(steamAppListClient.getChangedPage(DLC, 0, 10000, since)).thenReturn(EMPTY);
        when(steamStoreClient.getPurchaseOptions(List.of(10L)))
            .thenReturn(Map.of(10L, option(10L)));
        when(steamStoreClient.getPurchaseOptions(List.of(20L)))
            .thenThrow(new RestClientException("상점 중단")).thenReturn(Map.of(20L, option(20L)));

        Throwable failure =
            catchThrowable(() -> steamAppChangeCollector.collect(MIDNIGHT, 1));
        assertThat(failure).isInstanceOf(RestClientException.class).hasMessage("상점 중단");
        Timestamp slot = Timestamp.from(MIDNIGHT);
        String execution = """
            SELECT id FROM pipeline_execution WHERE pipeline = 'STEAM_CHANGED_APPS'
            AND scheduled_at = ? AND status = ? AND next_index = ?
            """;
        final Long executionId =
            jdbcTemplate.queryForObject(execution, Long.class, slot, "FAILED", 1);
        String observedPackages = """
            SELECT p.steam_id FROM price_history h
            JOIN purchase_option p ON p.id = h.purchase_option_id
            WHERE h.slot_at = ? ORDER BY p.steam_id
            """;
        assertThat(jdbcTemplate.queryForList(observedPackages, Long.class, slot))
            .containsExactly(10L);

        // when
        steamAppChangeCollector.collect(MIDNIGHT, 1);

        // then
        verify(steamAppListClient).getChangedPage(GAME, 0, 10000, since);
        verify(steamAppListClient).getChangedPage(DLC, 0, 10000, since);
        verify(steamStoreClient).getPurchaseOptions(List.of(10L));
        verify(steamStoreClient, times(2)).getPurchaseOptions(List.of(20L));
        verifyNoMoreInteractions(steamAppListClient, steamStoreClient);
        assertThat(jdbcTemplate.queryForObject(execution, Long.class, slot, "COMPLETED", 2))
            .isEqualTo(executionId);
        assertThat(jdbcTemplate.queryForList(observedPackages, Long.class, slot))
            .containsExactly(10L, 20L);
    }

    @Test
    @DisplayName("목록 완주 후 상점이 중단되면 다음 회차는 미확인 앱과 아직 요청하지 못한 앱을 이어받는다")
    void carriesPendingPricesAndAdvancesDiscoveryIndependently() {
        // given
        final Instant nextHour = MIDNIGHT.plus(1, ChronoUnit.HOURS);
        when(steamAppListClient.getChangedPage(eq(GAME), eq(0L), eq(10000), any()))
            .thenReturn(page(10, 20, 30, 35, 40, 50), page(15, 30));
        when(steamAppListClient.getChangedPage(eq(DLC), eq(0L), eq(10000), any()))
            .thenReturn(EMPTY, page(40));
        when(steamStoreClient.getPurchaseOptions(List.of(10L)))
            .thenReturn(Map.of(10L, option(10L)));
        when(steamStoreClient.getPurchaseOptions(List.of(20L))).thenReturn(Map.of());
        when(steamStoreClient.getPurchaseOptions(List.of(30L)))
            .thenThrow(new RestClientException("상점 중단"));
        when(steamStoreClient.getPurchaseOptions(List.of(15L, 20L, 30L, 35L, 40L)))
            .thenReturn(Map.of(15L, option(15L), 20L, option(20L), 30L, option(30L),
                35L, option(35L), 40L, option(40L)));

        // when
        final Throwable failure =
            catchThrowable(() -> steamAppChangeCollector.collect(MIDNIGHT, 1));
        jdbcTemplate.update("UPDATE game SET deleted_at = ? WHERE steam_app_id = 50",
            Timestamp.from(nextHour));
        steamAppChangeCollector.collect(nextHour, 260);

        // then
        assertThat(failure).hasMessage("상점 중단");
        assertThat(status(MIDNIGHT)).isEqualTo("FAILED");
        assertThat(status(nextHour)).isEqualTo("COMPLETED");
        verify(steamAppListClient).getChangedPage(GAME, 0, 10000,
            MIDNIGHT.minus(1, ChronoUnit.HOURS));
        verify(steamStoreClient).getPurchaseOptions(List.of(15L, 20L, 30L, 35L, 40L));
        verify(steamStoreClient).getPurchaseOptions(List.of(10L));
        verify(steamStoreClient).getPurchaseOptions(List.of(20L));
        verify(steamStoreClient).getPurchaseOptions(List.of(30L));
        verify(steamStoreClient).getDlcParents(List.of(40L));
        verifyNoMoreInteractions(steamStoreClient);
        assertThat(jdbcTemplate.queryForList("SELECT slot_at FROM price_history ORDER BY slot_at",
            Timestamp.class)).containsExactly(Timestamp.from(MIDNIGHT), Timestamp.from(nextHour),
            Timestamp.from(nextHour), Timestamp.from(nextHour), Timestamp.from(nextHour),
            Timestamp.from(nextHour));
    }

    @Test
    @DisplayName("01시 목록 실패 후 이틀 뒤에도 마지막 완주 시각보다 한 시간 전부터 다시 읽는다")
    void readsFromLastCompletedListAfterMissedSlot() {
        // given
        final Instant nextHour = MIDNIGHT.plus(1, ChronoUnit.HOURS);
        final Instant afterDowntime = MIDNIGHT.plus(2, ChronoUnit.DAYS);
        Instant boundary = MIDNIGHT.minus(1, ChronoUnit.HOURS);
        when(steamAppListClient.getChangedPage(eq(GAME), eq(0L), eq(10000), any()))
            .thenReturn(EMPTY);
        when(steamAppListClient.getChangedPage(eq(DLC), eq(0L), eq(10000), any()))
            .thenReturn(EMPTY);
        HttpTimeoutException timeout = new HttpTimeoutException("timeout");
        when(steamAppListClient.getChangedPage(GAME, 0, 10000, boundary))
            .thenThrow(new ResourceAccessException("Steam 응답 대기 만료", timeout))
            .thenReturn(page(70));
        when(steamStoreClient.getPurchaseOptions(List.of(70L)))
            .thenReturn(Map.of(70L, option(70L)));

        // when
        steamAppChangeCollector.collect(MIDNIGHT, 260);
        Throwable failure = catchThrowable(() -> steamAppChangeCollector.collect(nextHour, 260));
        steamAppChangeCollector.collect(afterDowntime, 260);

        // then
        assertThat(failure).isInstanceOf(ResourceAccessException.class)
            .hasCauseInstanceOf(HttpTimeoutException.class);
        verify(steamAppListClient, times(2)).getChangedPage(GAME, 0, 10000, boundary);
        assertThat(status(nextHour)).isEqualTo("FAILED");
        assertThat(status(afterDowntime)).isEqualTo("COMPLETED");
        verify(steamStoreClient).getPurchaseOptions(List.of(70L));
        assertThat(jdbcTemplate.queryForList("SELECT slot_at FROM price_history", Timestamp.class))
            .containsExactly(Timestamp.from(afterDowntime));
    }

    private SteamAppPage page(long... ids) {
        return new SteamAppPage(Arrays.stream(ids)
            .mapToObj(id -> new SteamApp(id, "앱 " + id)).toList(), OptionalLong.empty());
    }

    private SteamPurchaseOptions option(long id) {
        SteamPurchaseOption option = new SteamPurchaseOption(PACKAGE, id, "상품",
            new SteamPrice(1000, null, null));
        return visiblePurchaseOptions(List.of(option), option, false);
    }

    private String status(Instant slotAt) {
        return jdbcTemplate.queryForObject("""
            SELECT status FROM pipeline_execution
            WHERE pipeline = 'STEAM_CHANGED_APPS' AND scheduled_at = ?
            """, String.class, Timestamp.from(slotAt));
    }
}
