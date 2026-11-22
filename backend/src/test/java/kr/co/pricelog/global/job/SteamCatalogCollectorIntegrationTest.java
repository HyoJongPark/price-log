package kr.co.pricelog.global.job;

import static kr.co.pricelog.global.client.steam.enums.SteamAppType.DLC;
import static kr.co.pricelog.global.client.steam.enums.SteamAppType.GAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClientException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
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

    @MockitoBean
    private SteamAppListClient steamAppListClient;

    @AfterEach
    void clearCatalog() {
        jdbcTemplate.update("DELETE FROM game");
    }

    @Nested
    @DisplayName("목록 수집과 재실행")
    class Collection {

        @Test
        @DisplayName("앱 번호와 이름을 종류에 맞게 저장하고 같은 페이지 재생은 갱신하지 않는다")
        void storesGamesAndDlcOnce() {
            // given
            SteamAppPage games = new SteamAppPage(List.of(new SteamApp(10, "Counter-Strike"),
                new SteamApp(20, "Team Fortress Classic")), OptionalLong.of(20));
            when(steamAppListClient.getPage(GAME, 0, 2)).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return games;
            });
            when(steamAppListClient.getPage(DLC, 0, 2))
                .thenReturn(lastPage(660, "Portal 2 MotionPack"));

            // when
            final SteamAppPage first = steamCatalogCollector.collectPage(GAME, 0, 2);
            steamCatalogCollector.collectPage(DLC, 0, 2);
            steamCatalogCollector.collectPage(GAME, 0, 2);

            // then
            assertThat(first.nextCursor()).hasValue(20);
            assertThat(jdbcTemplate.queryForList("""
                SELECT steam_app_id || '/' || app_type || '/' || name
                FROM game ORDER BY steam_app_id
                """, String.class)).containsExactly("10/game/Counter-Strike",
                    "20/game/Team Fortress Classic", "660/dlc/Portal 2 MotionPack");
            assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM game WHERE sale_status = 'unknown' AND parent_game_id IS NULL
                  AND detail_checked_at IS NULL AND updated_at = created_at
                """, Integer.class)).isEqualTo(3);
        }

        @Test
        @DisplayName("이름이 바뀌면 같은 행을 갱신하고 한국 판매 상태와 상세를 보존한다")
        void updatesNameAndKeepsStoreDetails() {
            // given
            gameCatalogService.applyPage(GAME, List.of(new SteamApp(10, "Old")), FIRST);
            final Long originalId = jdbcTemplate.queryForObject("SELECT id FROM game", Long.class);
            jdbcTemplate.update("""
                UPDATE game SET sale_status = 'base_available', description = '상세',
                    detail_checked_at = created_at, search_aliases = ARRAY['별칭']
                """);

            // when
            gameCatalogService.applyPage(GAME, List.of(new SteamApp(10, "New")), NEXT);

            // then
            assertThat(jdbcTemplate.queryForObject("""
                SELECT id FROM game WHERE steam_app_id = 10 AND name = 'New'
                  AND sale_status = 'base_available' AND description = '상세'
                  AND detail_checked_at = created_at AND search_aliases = ARRAY['별칭','Old']
                  AND created_at = ? AND updated_at = ?
                """, Long.class, Timestamp.from(FIRST), Timestamp.from(NEXT)))
                .isEqualTo(originalId);
        }

        @Test
        @DisplayName("빈 페이지와 원천 실패는 기존 앱을 삭제하거나 새 행을 만들지 않는다")
        void preservesCatalogOnEmptyOrFailedPage() {
            // given
            when(steamAppListClient.getPage(GAME, 0, 1)).thenReturn(lastPage(10, "First"));
            steamCatalogCollector.collectPage(GAME, 0, 1);
            when(steamAppListClient.getPage(GAME, 10, 1))
                .thenReturn(new SteamAppPage(List.of(), OptionalLong.empty()))
                .thenThrow(new RestClientException("원천 실패"));

            // when
            SteamAppPage empty = steamCatalogCollector.collectPage(GAME, 10, 1);
            Throwable failure = catchThrowable(
                () -> steamCatalogCollector.collectPage(GAME, 10, 1));

            // then
            assertThat(empty.nextCursor()).isEmpty();
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
                SELECT name FROM game WHERE steam_app_id = 660 AND app_type = 'dlc'
                  AND updated_at = ? AND cardinality(search_aliases) = 0
                """, String.class, Timestamp.from(NEXT))).containsExactly("New");
        }

        @Test
        @DisplayName("두 회차가 동시에 중복 앱을 반영해도 한 행만 남는다")
        void upsertsOverlappingPages() throws Exception {
            // given
            CyclicBarrier bothPagesRead = new CyclicBarrier(2);
            SteamAppPage repeated = new SteamAppPage(List.of(new SteamApp(10, "First"),
                new SteamApp(10, "Last")), OptionalLong.empty());
            when(steamAppListClient.getPage(GAME, 0, 2)).thenAnswer(call -> {
                bothPagesRead.await(10, TimeUnit.SECONDS);
                return repeated;
            });
            Callable<SteamAppPage> collect = () -> steamCatalogCollector.collectPage(GAME, 0, 2);

            // when
            try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
                Future<SteamAppPage> first = workers.submit(collect);
                Future<SteamAppPage> second = workers.submit(collect);
                first.get(15, TimeUnit.SECONDS);
                second.get(15, TimeUnit.SECONDS);
            }

            // then
            assertThat(jdbcTemplate.queryForList("SELECT name FROM game WHERE steam_app_id = 10",
                String.class)).containsExactly("Last");
        }
    }

    private SteamAppPage lastPage(long id, String name) {
        return new SteamAppPage(List.of(new SteamApp(id, name)), OptionalLong.empty());
    }
}
