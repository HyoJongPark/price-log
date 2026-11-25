package kr.co.pricelog.global.job;

import static kr.co.pricelog.domain.game.enums.SteamAppType.GAME;
import static kr.co.pricelog.global.client.steam.enums.SteamPurchaseOptionType.BUNDLE;
import static kr.co.pricelog.global.client.steam.enums.SteamPurchaseOptionType.PACKAGE;
import static kr.co.pricelog.global.job.SteamPurchaseOptionsFixture.visiblePurchaseOptions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.co.pricelog.domain.game.service.GameCatalogService;
import kr.co.pricelog.domain.purchaseoption.entity.GamePurchaseOption;
import kr.co.pricelog.global.client.steam.SteamStoreClient;
import kr.co.pricelog.global.client.steam.dto.SteamApp;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOption;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class SteamStoreCollectorIntegrationTest {

    private static final Instant SLOT = Instant.parse("2026-01-01T00:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17.6-alpine");

    @Autowired
    private SteamStoreCollector steamStoreCollector;

    @Autowired
    private GameCatalogService gameCatalogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private SteamStoreClient steamStoreClient;

    @Test
    @DisplayName("무료 표기는 요청한 Steam 앱에만 저장한다")
    void updatesOnlyRequestedGame() {
        // given
        long otherAppId = 201L;
        long requestedAppId = 202L;
        gameCatalogService.applyPage(GAME, List.of(new SteamApp(otherAppId, "다른 게임"),
            new SteamApp(requestedAppId, "조회할 게임")), Instant.now());
        when(steamStoreClient.getPurchaseOptions(List.of(requestedAppId)))
            .thenReturn(Map.of(requestedAppId, visiblePurchaseOptions(List.of(), null, true)));

        // when
        steamStoreCollector.collectPurchaseOptions(List.of(requestedAppId), SLOT);

        // then
        assertThat(freeFlag(requestedAppId)).isTrue();
        assertThat(freeFlag(otherAppId)).isNull();
    }

    @Test
    @DisplayName("무료 표기 변경과 생략을 반영하고 상점 실패와 카탈로그 재수집에서 기존 값을 보존한다")
    void persistsLatestSuccessfulFreeFlag() {
        // given
        long steamAppId = 101L;
        Instant catalogTime = Instant.parse("2020-01-01T00:00:00Z");
        gameCatalogService.applyPage(GAME, List.of(new SteamApp(steamAppId, "게임")), catalogTime);
        SteamPurchaseOptions free = visiblePurchaseOptions(List.of(), null, true);
        when(steamStoreClient.getPurchaseOptions(List.of(steamAppId)))
            .thenReturn(Map.of(steamAppId, free))
            .thenThrow(new RestClientException("상점 조회 실패"))
            .thenReturn(Map.of(steamAppId, visiblePurchaseOptions(List.of(), null, false)))
            .thenReturn(Map.of(steamAppId, visiblePurchaseOptions(List.of(), null, null)));

        // when
        Map<Long, SteamPurchaseOptions> result = steamStoreCollector.collectPurchaseOptions(
            List.of(steamAppId), SLOT);
        gameCatalogService.applyPage(GAME, List.of(new SteamApp(steamAppId, "새 이름")),
            catalogTime.plusSeconds(60));
        Throwable failure = catchThrowable(() -> steamStoreCollector.collectPurchaseOptions(
            List.of(steamAppId), SLOT));

        // then
        assertThat(result).isEqualTo(Map.of(steamAppId, free));
        assertThat(failure).isInstanceOf(RestClientException.class).hasMessage("상점 조회 실패");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT name FROM game WHERE steam_app_id = ? AND is_free
              AND updated_at > catalog_updated_at
            """, String.class, steamAppId)).isEqualTo("새 이름");

        steamStoreCollector.collectPurchaseOptions(List.of(steamAppId), SLOT);
        assertThat(freeFlag(steamAppId)).isFalse();

        steamStoreCollector.collectPurchaseOptions(List.of(steamAppId), SLOT);
        assertThat(freeFlag(steamAppId)).isNull();
    }

    @Nested
    @DisplayName("게임별 구매 옵션 저장")
    class PurchaseOptions {

        private final SteamPurchaseOption edition =
            new SteamPurchaseOption(PACKAGE, 31, "일반판", null);
        private final SteamPurchaseOption bundle = new SteamPurchaseOption(BUNDLE, 31, null, null);

        @BeforeEach
        void setUpGames() {
            jdbcTemplate.execute(
                "TRUNCATE price_history, game_purchase_option, purchase_option, game_tag, game");
            gameCatalogService.applyPage(GAME, List.of(new SteamApp(301, "게임 A"),
                new SteamApp(302, "게임 B")), Instant.now());
        }

        @Test
        @DisplayName("같은 번호의 패키지와 번들을 구별하고 재수집하면 연결을 유지하며 상품명을 갱신한다")
        void updatesOptionNameAndPreservesLinks() {
            // given
            SteamPurchaseOptions options = visiblePurchaseOptions(List.of(edition, bundle),
                bundle, false);
            SteamPurchaseOption renamedEdition =
                new SteamPurchaseOption(PACKAGE, 31, "스탠다드 에디션", null);
            SteamPurchaseOptions renamedOptions = visiblePurchaseOptions(
                List.of(renamedEdition, bundle), bundle, false);
            when(steamStoreClient.getPurchaseOptions(List.of(301L)))
                .thenReturn(Map.of(301L, options))
                .thenReturn(Map.of(301L, renamedOptions));
            steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);
            List<Long> linkIds = jdbcTemplate.queryForList(
                "SELECT id FROM game_purchase_option ORDER BY id", Long.class);

            // when
            steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);

            // then
            assertThat(activeOptions(301)).containsExactly("BUNDLE:31:true", "PACKAGE:31:false");
            assertThat(jdbcTemplate.queryForList("SELECT id FROM game_purchase_option ORDER BY id",
                Long.class)).hasSize(2).isEqualTo(linkIds);
            assertThat(jdbcTemplate.queryForList("SELECT name FROM purchase_option ORDER BY type",
                String.class)).containsExactly(null, "스탠다드 에디션");
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("새 대표가 기존 옵션이든 새 옵션이든 동일 트랜잭션에서 대표를 교체한다")
        void switchesBestOption(boolean previouslyListed) {
            // given
            List<SteamPurchaseOption> initial = previouslyListed
                ? List.of(edition, bundle) : List.of(edition);
            SteamPurchaseOptions first = visiblePurchaseOptions(initial, edition, false);
            SteamPurchaseOptions second = visiblePurchaseOptions(List.of(bundle, edition),
                bundle, false);
            when(steamStoreClient.getPurchaseOptions(List.of(301L)))
                .thenReturn(Map.of(301L, first))
                .thenReturn(Map.of(301L, second));
            steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);

            // when
            steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);

            // then
            assertThat(activeOptions(301)).containsExactly("BUNDLE:31:true", "PACKAGE:31:false");
        }

        @Test
        @DisplayName("기존 대표가 판매 목록에서 사라지면 연결을 닫고 새 대표를 저장한다")
        void replacesWithdrawnBestOption() {
            // given
            when(steamStoreClient.getPurchaseOptions(List.of(301L)))
                .thenReturn(Map.of(301L,
                    visiblePurchaseOptions(List.of(edition), edition, false)))
                .thenReturn(Map.of(301L, visiblePurchaseOptions(List.of(bundle), bundle, false)));
            steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);

            // when
            steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);

            // then
            assertThat(activeOptions(301)).containsExactly("BUNDLE:31:true");
            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM game_purchase_option WHERE deleted_at IS NOT NULL",
                Integer.class)).isEqualTo(1);
        }

        @Test
        @DisplayName("기본 지정이 생략되면 구매 옵션은 유지하고 대표 지정만 해제한다")
        void clearsOnlyBestDesignation() {
            // given
            when(steamStoreClient.getPurchaseOptions(List.of(301L)))
                .thenReturn(Map.of(301L,
                    visiblePurchaseOptions(List.of(edition), edition, false)))
                .thenReturn(Map.of(301L, visiblePurchaseOptions(List.of(edition), null, false)));
            steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);

            // when
            steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);

            // then
            assertThat(activeOptions(301)).containsExactly("PACKAGE:31:false");
        }

        @Test
        @DisplayName("두 게임이 공유하는 상품은 한 행이고 빈 목록은 해당 게임의 연결만 닫는다")
        void closesOnlyMissingGameLinks() {
            // given
            SteamPurchaseOptions options = visiblePurchaseOptions(
                List.of(edition), edition, false);
            when(steamStoreClient.getPurchaseOptions(List.of(301L)))
                .thenReturn(Map.of(301L, options))
                .thenReturn(Map.of(301L, visiblePurchaseOptions(List.of(), null, true)));
            when(steamStoreClient.getPurchaseOptions(List.of(302L)))
                .thenReturn(Map.of(302L, options));
            steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);
            steamStoreCollector.collectPurchaseOptions(List.of(302L), SLOT);

            // when
            steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);

            // then
            assertThat(activeOptions(301)).isEmpty();
            assertThat(activeOptions(302)).containsExactly("PACKAGE:31:true");
            assertThat(jdbcTemplate.queryForList("""
                SELECT deleted_at IS NULL AND sale_ended_at IS NULL FROM purchase_option
                """, Boolean.class)).containsExactly(true);
            Long closedLinkId = jdbcTemplate.queryForObject(
                "SELECT id FROM game_purchase_option WHERE deleted_at IS NOT NULL", Long.class);
            assertThat(entityManager.find(GamePurchaseOption.class, closedLinkId)).isNull();
        }

        @Test
        @DisplayName("판매 종료된 상품과 같은 Steam ID를 수집하면 새 판매 구간을 연결한다")
        void linksNewSalePeriod() {
            // given
            jdbcTemplate.update("""
                INSERT INTO purchase_option
                    (type, steam_id, name, created_at, updated_at, sale_ended_at)
                VALUES ('PACKAGE', 31, '종료된 구간', '2025-11-01Z', '2025-12-01Z', '2025-12-01Z')
                """);
            when(steamStoreClient.getPurchaseOptions(List.of(301L)))
                .thenReturn(Map.of(301L,
                    visiblePurchaseOptions(List.of(edition), edition, false)));

            // when
            steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);

            // then
            assertThat(activeOptions(301)).containsExactly("PACKAGE:31:true");
            assertThat(jdbcTemplate.queryForList("SELECT name FROM purchase_option ORDER BY id",
                String.class)).containsExactly("종료된 구간", "일반판");
        }

        @Test
        @DisplayName("삭제된 게임이 섞이면 같은 배치의 다른 게임도 반영하지 않는다")
        void rollsBackBatchContainingDeletedGame() {
            // given
            jdbcTemplate.update("UPDATE game SET deleted_at = now() WHERE steam_app_id = 302");
            List<Long> appIds = List.of(301L, 302L);
            Map<Long, SteamPurchaseOptions> confirmed = new LinkedHashMap<>();
            SteamPurchaseOptions options = visiblePurchaseOptions(
                List.of(edition), edition, false);
            appIds.forEach(appId -> confirmed.put(appId, options));
            when(steamStoreClient.getPurchaseOptions(appIds)).thenReturn(confirmed);

            // when
            Throwable failure = catchThrowable(
                () -> steamStoreCollector.collectPurchaseOptions(appIds, SLOT));

            // then
            assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessage("활성 카탈로그에 없는 Steam 앱: 302");
            assertThat(activeOptions(301)).isEmpty();
            assertThat(freeFlag(301)).isNull();
            assertThat(activeOptions(302)).isEmpty();
            assertThat(freeFlag(302)).isNull();
        }

        @Test
        @DisplayName("구매 목록 조회가 실패하면 마지막 대표와 상품 목록을 유지한다")
        void preservesOptionsOnFailure() {
            // given
            when(steamStoreClient.getPurchaseOptions(List.of(301L)))
                .thenReturn(Map.of(301L,
                    visiblePurchaseOptions(List.of(edition, bundle), edition, false)))
                .thenThrow(new RestClientException("구매 목록 조회 실패"));
            steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);

            // when
            Throwable failure = catchThrowable(
                () -> steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT));

            // then
            assertThat(failure).isInstanceOf(RestClientException.class).hasMessage("구매 목록 조회 실패");
            assertThat(activeOptions(301)).containsExactly("BUNDLE:31:false", "PACKAGE:31:true");
        }

        private List<String> activeOptions(long steamAppId) {
            return jdbcTemplate.queryForList("""
                SELECT concat(o.type, ':', o.steam_id, ':', l.is_best_option::text)
                FROM game_purchase_option l JOIN game g ON g.id = l.game_id
                    JOIN purchase_option o ON o.id = l.purchase_option_id
                WHERE g.steam_app_id = ? AND l.deleted_at IS NULL
                  AND o.deleted_at IS NULL AND o.sale_ended_at IS NULL
                ORDER BY o.type, o.steam_id
                """, String.class, steamAppId);
        }
    }

    private Boolean freeFlag(long steamAppId) {
        return jdbcTemplate.queryForObject("SELECT is_free FROM game WHERE steam_app_id = ?",
            Boolean.class, steamAppId);
    }
}
