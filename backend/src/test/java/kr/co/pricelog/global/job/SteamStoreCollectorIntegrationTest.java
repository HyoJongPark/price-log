package kr.co.pricelog.global.job;

import static kr.co.pricelog.domain.game.enums.SteamAppType.GAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import kr.co.pricelog.domain.game.service.GameCatalogService;
import kr.co.pricelog.global.client.steam.SteamStoreClient;
import kr.co.pricelog.global.client.steam.dto.SteamApp;
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

@SpringBootTest
@Testcontainers
class SteamStoreCollectorIntegrationTest {

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
        when(steamStoreClient.getPurchaseOptions(requestedAppId))
            .thenReturn(new SteamPurchaseOptions(List.of(), null, true));

        // when
        steamStoreCollector.collectPurchaseOptions(requestedAppId);

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
        SteamPurchaseOptions free = new SteamPurchaseOptions(List.of(), null, true);
        when(steamStoreClient.getPurchaseOptions(steamAppId)).thenReturn(free)
            .thenThrow(new RestClientException("상점 조회 실패"))
            .thenReturn(new SteamPurchaseOptions(List.of(), null, false))
            .thenReturn(new SteamPurchaseOptions(List.of(), null, null));

        // when
        SteamPurchaseOptions result = steamStoreCollector.collectPurchaseOptions(steamAppId);
        gameCatalogService.applyPage(GAME, List.of(new SteamApp(steamAppId, "새 이름")),
            catalogTime.plusSeconds(60));
        Throwable failure = catchThrowable(() -> steamStoreCollector.collectPurchaseOptions(
            steamAppId));

        // then
        assertThat(result).isEqualTo(free);
        assertThat(failure).isInstanceOf(RestClientException.class).hasMessage("상점 조회 실패");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT name FROM game WHERE steam_app_id = ? AND is_free
              AND updated_at > catalog_updated_at
            """, String.class, steamAppId)).isEqualTo("새 이름");

        steamStoreCollector.collectPurchaseOptions(steamAppId);
        assertThat(freeFlag(steamAppId)).isFalse();

        steamStoreCollector.collectPurchaseOptions(steamAppId);
        assertThat(freeFlag(steamAppId)).isNull();
    }

    private Boolean freeFlag(long steamAppId) {
        return jdbcTemplate.queryForObject("SELECT is_free FROM game WHERE steam_app_id = ?",
            Boolean.class, steamAppId);
    }
}
