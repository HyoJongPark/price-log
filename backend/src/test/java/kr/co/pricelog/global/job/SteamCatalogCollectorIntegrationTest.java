package kr.co.pricelog.global.job;

import static kr.co.pricelog.domain.game.enums.SteamAppType.DLC;
import static kr.co.pricelog.domain.game.enums.SteamAppType.GAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.pricelog.domain.game.enums.SteamAppType;
import kr.co.pricelog.domain.game.service.GameCatalogService;
import kr.co.pricelog.global.client.steam.SteamAppListClient;
import kr.co.pricelog.global.client.steam.dto.SteamApp;
import kr.co.pricelog.global.client.steam.dto.SteamAppPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = "resilience4j.retry.instances.steamCollection.wait-duration=1ms")
class SteamCatalogCollectorIntegrationTest {

    private static final Instant FIRST = Instant.parse("2026-11-20T00:00:00Z");
    private static final Instant NEXT = Instant.parse("2026-11-21T00:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17.6-alpine");

    @Autowired
    private SteamCatalogCollector steamCatalogCollector;

    @Autowired
    private GameCatalogService gameCatalogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private SteamAppListClient steamAppListClient;

    @AfterEach
    void clearCatalog() {
        jdbcTemplate.update("DELETE FROM pipeline_execution");
        jdbcTemplate.update("DELETE FROM game");
    }

    @Nested
    @DisplayName("목록 수집과 재실행")
    class Collection {

        @Test
        @DisplayName("앱 번호와 이름을 종류에 맞게 저장하고 같은 페이지 재생은 갱신하지 않는다")
        void storesGamesAndDlcOnce() {
            // given
            List<SteamApp> games = List.of(new SteamApp(10, "Counter-Strike"),
                new SteamApp(20, "Team Fortress Classic"));

            // when
            gameCatalogService.applyPage(GAME, games, FIRST);
            gameCatalogService.applyPage(DLC, List.of(new SteamApp(660, "Portal 2 MotionPack")),
                FIRST);
            gameCatalogService.applyPage(GAME, games, NEXT);

            // then
            assertThat(jdbcTemplate.queryForList("""
                SELECT steam_app_id || '/' || app_type || '/' || name
                FROM game ORDER BY steam_app_id
                """, String.class)).containsExactly("10/GAME/Counter-Strike",
                    "20/GAME/Team Fortress Classic", "660/DLC/Portal 2 MotionPack");
            assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM game WHERE is_free IS NULL AND parent_game_id IS NULL
                  AND detail_checked_at IS NULL AND updated_at = created_at
                """, Integer.class)).isEqualTo(3);
        }

        @Test
        @DisplayName("게임과 DLC 및 종류 미확인 행을 같은 앱 종류 enum으로 조회한다")
        void readsStoredAppTypes() {
            // given
            gameCatalogService.applyPage(GAME, List.of(new SteamApp(10, "본편")), FIRST);
            gameCatalogService.applyPage(DLC, List.of(new SteamApp(20, "DLC")), FIRST);
            jdbcTemplate.update("""
                INSERT INTO game
                    (steam_app_id, name, app_type, catalog_updated_at, created_at, updated_at)
                VALUES (30, '미확인', 'UNKNOWN', now(), now(), now())
                """);

            // when
            List<SteamAppType> types = entityManager.createQuery(
                "select g.appType from Game g order by g.steamAppId", SteamAppType.class)
                .getResultList();

            // then
            assertThat(types).containsExactly(GAME, DLC, SteamAppType.UNKNOWN);
        }

        @Test
        @DisplayName("이름이 바뀌면 같은 행을 갱신하고 무료 표기와 상세를 보존한다")
        void updatesNameAndKeepsStoreDetails() {
            // given
            gameCatalogService.applyPage(GAME, List.of(new SteamApp(10, "Old")), FIRST);
            final Long originalId = jdbcTemplate.queryForObject("SELECT id FROM game", Long.class);
            jdbcTemplate.update("""
                UPDATE game SET is_free = true, description = '상세',
                    detail_checked_at = created_at, search_aliases = ARRAY['별칭']
                """);

            // when
            gameCatalogService.applyPage(GAME, List.of(new SteamApp(10, "New")), NEXT);

            // then
            assertThat(jdbcTemplate.queryForObject("""
                SELECT id FROM game WHERE steam_app_id = 10 AND name = 'New'
                  AND is_free = true AND description = '상세'
                  AND detail_checked_at = created_at AND search_aliases = ARRAY['별칭','Old']
                  AND created_at = ? AND updated_at = ?
                """, Long.class, Timestamp.from(FIRST), Timestamp.from(NEXT)))
                .isEqualTo(originalId);
        }

        @Test
        @DisplayName("빈 페이지와 원천 실패는 기존 앱을 삭제하거나 새 행을 만들지 않는다")
        void preservesCatalogOnEmptyOrFailedPage() {
            // given
            gameCatalogService.applyPage(GAME, List.of(new SteamApp(10, "First")), FIRST);
            when(steamAppListClient.getPage(GAME, 0, 10_000))
                .thenReturn(new SteamAppPage(Collections.emptyList(), OptionalLong.empty()));
            when(steamAppListClient.getPage(DLC, 0, 10_000))
                .thenThrow(new RestClientException("원천 실패"));

            // when
            Throwable failure = catchThrowable(() -> steamCatalogCollector.collectAll(FIRST));

            // then
            assertThat(failure).isInstanceOf(RestClientException.class).hasMessage("원천 실패");
            assertThat(jdbcTemplate.queryForList("SELECT steam_app_id FROM game", Long.class))
                .containsExactly(10L);
        }
    }

    @Nested
    @DisplayName("겹치는 목록")
    class Overlap {

        @Test
        @DisplayName("늦게 반영된 이전 시각의 목록이 최신 이름과 종류를 되돌리지 않는다")
        void ignoresOlderWrite() {
            // given
            gameCatalogService.applyPage(DLC, List.of(new SteamApp(660, "New")), NEXT);

            // when
            gameCatalogService.applyPage(GAME, List.of(new SteamApp(660, "Old")), FIRST);

            // then
            assertThat(jdbcTemplate.queryForList("""
                SELECT name FROM game WHERE steam_app_id = 660 AND app_type = 'DLC'
                  AND updated_at = ? AND cardinality(search_aliases) = 0
                """, String.class, Timestamp.from(NEXT))).containsExactly("New");
        }

        @Test
        @DisplayName("두 회차가 동시에 중복 앱을 반영해도 한 행만 남는다")
        void upsertsOverlappingPages() throws Exception {
            // given
            CyclicBarrier bothPagesRead = new CyclicBarrier(2);
            List<SteamApp> repeated = List.of(new SteamApp(10, "First"), new SteamApp(10, "Last"));

            // when
            try (ExecutorService workers = Executors.newSingleThreadExecutor()) {
                Future<?> first = workers.submit(() -> {
                    bothPagesRead.await(10, TimeUnit.SECONDS);
                    gameCatalogService.applyPage(GAME, repeated, FIRST);
                    return null;
                });
                bothPagesRead.await(10, TimeUnit.SECONDS);
                gameCatalogService.applyPage(GAME, repeated, NEXT);
                first.get(15, TimeUnit.SECONDS);
            }

            // then
            assertThat(jdbcTemplate.queryForList("SELECT name FROM game WHERE steam_app_id = 10",
                String.class)).containsExactly("Last");
        }
    }

    @Nested
    @DisplayName("전체 목록 회차")
    class FullCatalog {

        private static final int PAGE_SIZE = 10_000;

        @Test
        @DisplayName("기동 중 DLC에서 429가 풀리면 전수 목록의 마지막 커서에서 자동 재개한다")
        void retriesInitializationFromDlcCursor() {
            // given
            when(steamAppListClient.getPage(GAME, 0, PAGE_SIZE)).thenReturn(lastPage(10, "본편"));
            when(steamAppListClient.getPage(DLC, 0, PAGE_SIZE)).thenReturn(morePages(20));
            when(steamAppListClient.getPage(DLC, 20, PAGE_SIZE))
                .thenThrow(HttpClientErrorException.create(
                    HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null, null, null))
                .thenReturn(lastPage(30, "DLC"));

            // when
            steamCatalogCollector.initialize(FIRST);

            // then
            verify(steamAppListClient).getPage(GAME, 0, PAGE_SIZE);
            verify(steamAppListClient).getPage(DLC, 0, PAGE_SIZE);
            verify(steamAppListClient, times(2)).getPage(DLC, 20, PAGE_SIZE);
            verifyNoMoreInteractions(steamAppListClient);
            assertThat(runStates()).containsExactly("STEAM_DLC_LIST/COMPLETED/30",
                "STEAM_GAME_LIST/COMPLETED/10");
        }

        @Test
        @DisplayName("본편 완료 후 DLC 조회가 끊기면 커밋된 DLC 커서부터 다시 읽는다")
        void resumesDlcAfterCommittedPage() {
            // given
            when(steamAppListClient.getPage(GAME, 0, PAGE_SIZE)).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return morePages(10);
            });
            when(steamAppListClient.getPage(GAME, 10, PAGE_SIZE)).thenReturn(lastPage(20, "B"));
            when(steamAppListClient.getPage(DLC, 0, PAGE_SIZE)).thenReturn(morePages(660));
            when(steamAppListClient.getPage(DLC, 660, PAGE_SIZE))
                .thenThrow(new RestClientException("DLC 조회 중단")).thenAnswer(call -> {
                    assertThat(runStates()).contains("STEAM_DLC_LIST/RUNNING/660");
                    return lastPage(670, "D");
                });

            // when
            Throwable failure = catchThrowable(() -> steamCatalogCollector.collectAll(FIRST));

            // then
            assertThat(failure).isInstanceOf(RestClientException.class);
            assertThat(runStates()).containsExactly("STEAM_DLC_LIST/FAILED/660",
                "STEAM_GAME_LIST/COMPLETED/20");
            assertThat(appIds()).containsExactly(10L, 20L, 660L);

            steamCatalogCollector.collectAll(FIRST);
            steamCatalogCollector.collectAll(FIRST);

            assertThat(runStates()).containsExactly("STEAM_DLC_LIST/COMPLETED/670",
                "STEAM_GAME_LIST/COMPLETED/20");
            assertThat(appIds()).containsExactly(10L, 20L, 660L, 670L);
            verify(steamAppListClient).getPage(GAME, 0, PAGE_SIZE);
            verify(steamAppListClient).getPage(GAME, 10, PAGE_SIZE);
            verify(steamAppListClient).getPage(DLC, 0, PAGE_SIZE);
            verify(steamAppListClient, times(2)).getPage(DLC, 660, PAGE_SIZE);
            verifyNoMoreInteractions(steamAppListClient);
        }

        @Test
        @DisplayName("본편 첫 요청 실패 시 DLC를 시작하지 않고 같은 회차의 0번 커서로 재개한다")
        void resumesGameFromFirstPage() {
            // given
            when(steamAppListClient.getPage(GAME, 0, PAGE_SIZE))
                .thenThrow(new RestClientException("본편 조회 중단")).thenAnswer(call -> {
                    assertThat(runStates()).containsExactly("STEAM_GAME_LIST/RUNNING/-");
                    return lastPage(10, "본편");
                });
            when(steamAppListClient.getPage(DLC, 0, PAGE_SIZE))
                .thenReturn(new SteamAppPage(Collections.emptyList(), OptionalLong.empty()));

            // when
            Throwable failure = catchThrowable(() -> steamCatalogCollector.collectAll(FIRST));

            // then
            assertThat(failure).isInstanceOf(RestClientException.class).hasMessage("본편 조회 중단");
            assertThat(runStates()).containsExactly("STEAM_GAME_LIST/FAILED/-");
            assertThat(appIds()).isEmpty();

            steamCatalogCollector.collectAll(FIRST);
            assertThat(runStates()).containsExactly("STEAM_DLC_LIST/COMPLETED/-",
                "STEAM_GAME_LIST/COMPLETED/10");
            verify(steamAppListClient, times(2)).getPage(GAME, 0, PAGE_SIZE);
            verify(steamAppListClient).getPage(DLC, 0, PAGE_SIZE);
        }

        @Test
        @DisplayName("커서 갱신이 실패하면 게임도 롤백하고 재실행에서 그 페이지를 다시 저장한다")
        void rollsBackGamesWithCursor() {
            // given
            when(steamAppListClient.getPage(GAME, 0, PAGE_SIZE)).thenReturn(morePages(10));
            when(steamAppListClient.getPage(GAME, 10, PAGE_SIZE)).thenReturn(lastPage(20, "B"));
            when(steamAppListClient.getPage(DLC, 0, PAGE_SIZE))
                .thenReturn(new SteamAppPage(Collections.emptyList(), OptionalLong.empty()));
            jdbcTemplate.execute("""
                ALTER TABLE pipeline_execution ADD CONSTRAINT test_fail_page
                CHECK (list_cursor IS DISTINCT FROM 20)
                """);

            // when
            Throwable failure;
            try {
                failure = catchThrowable(() -> steamCatalogCollector.collectAll(FIRST));
            } finally {
                jdbcTemplate.execute(
                    "ALTER TABLE pipeline_execution DROP CONSTRAINT test_fail_page");
            }

            // then
            assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(runStates()).containsExactly("STEAM_GAME_LIST/FAILED/10");
            assertThat(appIds()).containsExactly(10L);

            steamCatalogCollector.collectAll(FIRST);
            assertThat(runStates()).containsExactly("STEAM_DLC_LIST/COMPLETED/-",
                "STEAM_GAME_LIST/COMPLETED/20");
            assertThat(appIds()).containsExactly(10L, 20L);
            verify(steamAppListClient).getPage(GAME, 0, PAGE_SIZE);
            verify(steamAppListClient, times(2)).getPage(GAME, 10, PAGE_SIZE);
        }

        @Test
        @DisplayName("다음 예정 시각에는 본편과 DLC를 각각 첫 페이지부터 새로 수집한다")
        void startsBothListsFromZeroInNewSlot() {
            // given
            when(steamAppListClient.getPage(GAME, 0, PAGE_SIZE))
                .thenReturn(lastPage(10, "첫 본편"), lastPage(20, "새 본편"));
            when(steamAppListClient.getPage(DLC, 0, PAGE_SIZE))
                .thenReturn(lastPage(30, "첫 DLC"), lastPage(40, "새 DLC"));
            steamCatalogCollector.collectAll(FIRST);

            // when
            steamCatalogCollector.collectAll(NEXT);

            // then
            assertThat(runStates()).containsExactly("STEAM_DLC_LIST/COMPLETED/30",
                "STEAM_DLC_LIST/COMPLETED/40", "STEAM_GAME_LIST/COMPLETED/10",
                "STEAM_GAME_LIST/COMPLETED/20");
            assertThat(appIds()).containsExactly(10L, 20L, 30L, 40L);
        }

        private SteamAppPage morePages(long appId) {
            return new SteamAppPage(List.of(new SteamApp(appId, "App " + appId)),
                OptionalLong.of(appId));
        }

        private List<Long> appIds() {
            return jdbcTemplate.queryForList("SELECT steam_app_id FROM game ORDER BY steam_app_id",
                Long.class);
        }

        private List<String> runStates() {
            return jdbcTemplate.queryForList("""
                SELECT pipeline || '/' || status || '/' || coalesce(list_cursor::text, '-')
                FROM pipeline_execution ORDER BY pipeline, scheduled_at
                """, String.class);
        }
    }

    private SteamAppPage lastPage(long id, String name) {
        return new SteamAppPage(List.of(new SteamApp(id, name)), OptionalLong.empty());
    }
}
