package kr.co.pricelog.global.job;

import static kr.co.pricelog.domain.game.enums.SteamAppType.DLC;
import static kr.co.pricelog.domain.game.enums.SteamAppType.GAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.pricelog.domain.game.enums.SteamAppType;
import kr.co.pricelog.domain.game.service.GameCatalogService;
import kr.co.pricelog.global.client.steam.SteamAppListClient;
import kr.co.pricelog.global.client.steam.SteamStoreClient;
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
@SpringBootTest(properties = {"resilience4j.retry.instances.steamCollection.wait-duration=1ms",
    "pricelog.collection.daily.batch-size=2"})
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

    @MockitoBean
    private SteamStoreClient steamStoreClient;

    @AfterEach
    void clearCatalog() {
        jdbcTemplate.update("DELETE FROM pipeline_execution");
        jdbcTemplate.update("DELETE FROM game");
    }

    @Nested
    @DisplayName("목록 수집과 재실행")
    class Collection {

        @Test
        @DisplayName("변경 목록에서 기존 부모가 삭제되거나 DLC로 바뀐 자식만 다시 확인한다")
        void rechecksDlcWithoutAnActiveBaseGame() {
            // given
            List<SteamApp> games = List.of(new SteamApp(10, "삭제"), new SteamApp(20, "재분류"),
                new SteamApp(30, "본편"));
            List<SteamApp> children = List.of(new SteamApp(11, "A"), new SteamApp(21, "B"),
                new SteamApp(31, "C"));
            gameCatalogService.applyPage(GAME, games, FIRST);
            gameCatalogService.applyPage(DLC, children,
                Map.of(11L, 10L, 21L, 20L, 31L, 30L), FIRST);
            jdbcTemplate.update("UPDATE game SET deleted_at = now() WHERE steam_app_id = 10");
            gameCatalogService.applyPage(DLC, List.of(new SteamApp(20, "재분류")), NEXT);

            // when
            List<Long> ids = gameCatalogService.findUnlinkedDlcIds(List.of(11L, 21L, 31L));

            // then
            assertThat(ids).containsExactly(11L, 21L);
        }

        @Test
        @DisplayName("같은 DLC 페이지에서 기존 본편이 재분류되면 자식의 부모도 다시 확인한다")
        void rechecksChildOfReclassifiedParentInSamePage() {
            // given
            SteamApp previous = new SteamApp(10, "이전 본편");
            SteamApp child = new SteamApp(11, "자식 DLC");
            List<SteamApp> page = List.of(previous, child);
            gameCatalogService.applyPage(GAME, List.of(previous, new SteamApp(20, "새 본편")), FIRST);
            gameCatalogService.applyPage(DLC, List.of(child), Map.of(11L, 10L), FIRST);

            // when
            List<Long> targets = gameCatalogService.findUnlinkedDlcIds(List.of(10L, 11L));
            gameCatalogService.applyPage(DLC, page, Map.of(10L, 10L, 11L, 20L), NEXT);

            // then
            assertThat(targets).containsExactly(10L, 11L);
            assertThat(jdbcTemplate.queryForList("""
                SELECT dlc.steam_app_id FROM game dlc
                JOIN game parent ON parent.id = dlc.parent_game_id
                WHERE parent.steam_app_id = 20
                """, Long.class)).containsExactly(11L);
            assertThat(jdbcTemplate.queryForList(
                "SELECT steam_app_id FROM game WHERE parent_game_id IS NULL ORDER BY steam_app_id",
                Long.class)).containsExactly(10L, 20L);
        }

        @Test
        @DisplayName("부모를 확인하지 못해도 기존 DLC와 연결은 보존하고 새 DLC만 건너뛴다")
        void preservesExistingDlcWhenParentIsUnavailable() {
            // given
            gameCatalogService.applyPage(GAME, List.of(new SteamApp(10, "본편")), FIRST);
            gameCatalogService.applyPage(DLC, List.of(new SteamApp(11, "연결됨")),
                Map.of(11L, 10L), FIRST);
            jdbcTemplate.update("""
                INSERT INTO game (steam_app_id, name, app_type, catalog_updated_at,
                                  created_at, updated_at)
                VALUES (12, '기존 미연결', 'DLC', now(), now(), now())
                """);

            // when
            gameCatalogService.applyPage(DLC, List.of(new SteamApp(11, "연결됨"),
                new SteamApp(12, "기존 미연결"), new SteamApp(13, "신규")),
                Map.of(12L, 99L, 13L, 99L), Instant.now());

            // then
            assertThat(jdbcTemplate.queryForList("""
                SELECT dlc.steam_app_id, parent.steam_app_id AS parent
                FROM game dlc LEFT JOIN game parent ON parent.id = dlc.parent_game_id
                WHERE dlc.app_type = 'DLC' AND dlc.deleted_at IS NULL ORDER BY dlc.steam_app_id
                """)).extracting("steam_app_id", "parent")
                .containsExactly(tuple(11L, 10L), tuple(12L, null));
        }

        @Test
        @DisplayName("앱 번호와 이름을 종류에 맞게 저장하고 같은 페이지 재생은 갱신하지 않는다")
        void storesGamesAndDlcOnce() {
            // given
            List<SteamApp> games = List.of(new SteamApp(10, "Counter-Strike"),
                new SteamApp(20, "Team Fortress Classic"));

            // when
            gameCatalogService.applyPage(GAME, games, FIRST);
            gameCatalogService.applyPage(DLC, List.of(new SteamApp(660, "Portal 2 MotionPack")),
                Map.of(660L, 10L), FIRST);
            gameCatalogService.applyPage(GAME, games, NEXT);

            // then
            assertThat(jdbcTemplate.queryForList("""
                SELECT steam_app_id || '/' || app_type || '/' || name
                FROM game ORDER BY steam_app_id
                """, String.class)).containsExactly("10/GAME/Counter-Strike",
                    "20/GAME/Team Fortress Classic", "660/DLC/Portal 2 MotionPack");
            assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM game WHERE NOT is_free AND NOT coming_soon
                  AND detail_checked_at IS NULL AND updated_at = created_at
                """, Integer.class)).isEqualTo(3);
        }

        @Test
        @DisplayName("게임과 DLC 및 종류 미확인 행을 같은 앱 종류 enum으로 조회한다")
        void readsStoredAppTypes() {
            // given
            gameCatalogService.applyPage(GAME, List.of(new SteamApp(10, "본편")), FIRST);
            gameCatalogService.applyPage(DLC, List.of(new SteamApp(20, "DLC")),
                Map.of(20L, 10L), FIRST);
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
            gameCatalogService.applyPage(GAME, List.of(new SteamApp(10, "본편")), FIRST);
            gameCatalogService.applyPage(DLC, List.of(new SteamApp(660, "New")),
                Map.of(660L, 10L), NEXT);

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
        @DisplayName("부모 조회 실패 후 같은 DLC 페이지를 재개해 확인된 본편만 연결한다")
        void resumesDlcParentLinks() {
            // given
            List<SteamApp> dlc = List.of(new SteamApp(660, "비노출"), new SteamApp(1256, "DLC A"),
                new SteamApp(1257, "DLC B"), new SteamApp(1258, "본편 미등록"));
            List<Long> firstBatch = List.of(660L, 1256L);
            List<Long> secondBatch = List.of(1257L, 1258L);
            when(steamAppListClient.getPage(GAME, 0, PAGE_SIZE)).thenReturn(lastPage(1250, "본편"));
            when(steamAppListClient.getPage(DLC, 0, PAGE_SIZE))
                .thenReturn(new SteamAppPage(dlc, OptionalLong.empty()));
            when(steamStoreClient.getDlcParents(firstBatch)).thenReturn(Map.of(1256L, 1250L));
            when(steamStoreClient.getDlcParents(secondBatch))
                .thenThrow(new RestClientException("상점 중단"))
                .thenReturn(Map.of(1257L, 1250L, 1258L, 9999L));

            // when
            Throwable failure = catchThrowable(() -> steamCatalogCollector.collectAll(FIRST));
            final List<Long> afterFailure = appIds();
            steamCatalogCollector.collectAll(FIRST);
            steamCatalogCollector.collectAll(FIRST);

            // then
            assertThat(failure).hasMessage("상점 중단");
            assertThat(afterFailure).containsExactly(1250L);
            assertThat(appIds()).containsExactly(1250L, 1256L, 1257L);
            assertThat(jdbcTemplate.queryForList("""
                SELECT dlc.steam_app_id FROM game dlc
                JOIN game parent ON dlc.parent_game_id = parent.id
                WHERE parent.steam_app_id = 1250 ORDER BY dlc.steam_app_id
                """, Long.class)).containsExactly(1256L, 1257L);
            verify(steamAppListClient).getPage(GAME, 0, PAGE_SIZE);
            verify(steamStoreClient, times(2)).getDlcParents(firstBatch);
            verify(steamStoreClient, times(2)).getDlcParents(secondBatch);
        }

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
            when(steamStoreClient.getDlcParents(List.of(660L))).thenReturn(Map.of(660L, 10L));
            when(steamStoreClient.getDlcParents(List.of(670L))).thenReturn(Map.of(670L, 20L));
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
            when(steamStoreClient.getDlcParents(List.of(30L))).thenReturn(Map.of(30L, 10L));
            when(steamStoreClient.getDlcParents(List.of(40L))).thenReturn(Map.of(40L, 20L));
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
