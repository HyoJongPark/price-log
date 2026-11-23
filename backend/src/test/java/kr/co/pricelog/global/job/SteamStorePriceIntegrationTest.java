package kr.co.pricelog.global.job;

import static kr.co.pricelog.domain.game.enums.SteamAppType.GAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import kr.co.pricelog.domain.game.service.GameCatalogService;
import kr.co.pricelog.domain.purchaseoption.service.GamePurchaseOptionService;
import kr.co.pricelog.global.client.steam.SteamStoreClient;
import kr.co.pricelog.global.client.steam.dto.SteamApp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = "STEAM_WEB_API_KEY=test-key")
class SteamStorePriceIntegrationTest {

    private static final Instant SLOT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String DISCOUNT = """
        "final_price_in_cents":"100000","formatted_final_price":"₩ 1,000",
        "original_price_in_cents":"200000","formatted_original_price":"₩ 2,000",
        "active_discounts":[{"discount_end_date":1767312000}]
        """;
    private static final String ZERO = """
        "final_price_in_cents":"0","formatted_final_price":"₩ 0"
        """;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17.6-alpine");

    @Autowired
    private GameCatalogService gameCatalogService;

    @Autowired
    private GamePurchaseOptionService gamePurchaseOptionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SteamStoreCollector steamStoreCollector;
    private MockRestServiceServer mockRestServiceServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        mockRestServiceServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        steamStoreCollector = new SteamStoreCollector(new SteamStoreClient(restClientBuilder),
            gamePurchaseOptionService);
        jdbcTemplate.execute("TRUNCATE price_history, game_purchase_option, purchase_option, game");
        gameCatalogService.applyPage(GAME, List.of(new SteamApp(301, "게임 A"),
            new SteamApp(302, "게임 B")), Instant.now());
    }

    @Test
    @DisplayName("앱 응답마다 공유 상품의 회차별 첫 가격을 저장하고 최신 성공값을 상품에 반영한다")
    void recordsSharedOptionOncePerSlot() {
        // given
        respond(301, DISCOUNT);
        respond(302, ZERO);
        respond(301, DISCOUNT);
        respond(301, ZERO);

        // when
        steamStoreCollector.collectPurchaseOptions(301, SLOT);
        steamStoreCollector.collectPurchaseOptions(302, SLOT);

        // then
        assertThat(currentPackagePrice()).isEqualTo(1000L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT regular_price = 2000 AND discount_ends_at = to_timestamp(1767312000)
            FROM purchase_option WHERE type = 'PACKAGE'
            """, Boolean.class)).isTrue();
        assertThat(packageHistory()).containsExactly(1000L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(DISTINCT observed_at) FROM price_history
            """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT current_price FROM purchase_option WHERE type = 'BUNDLE'
            """, Long.class)).isZero();

        steamStoreCollector.collectPurchaseOptions(301, SLOT.plus(1, ChronoUnit.DAYS));
        steamStoreCollector.collectPurchaseOptions(301, SLOT.plus(2, ChronoUnit.DAYS));
        assertThat(packageHistory()).containsExactly(1000L, 1000L, 0L);
        assertThat(currentPackagePrice()).isZero();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT o.price_observed_at = h.observed_at AND o.regular_price IS NULL
              AND o.discount_ends_at IS NULL
            FROM purchase_option o JOIN price_history h ON h.purchase_option_id = o.id
            WHERE o.type = 'PACKAGE' ORDER BY h.slot_at DESC LIMIT 1
            """, Boolean.class)).isTrue();
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("가격 누락과 상점 실패 시 이전 확인 가격과 시각을 보존한다")
    void keepsLastConfirmedPrice() {
        // given
        respond(301, DISCOUNT);
        respond(301, "\"purchase_option_name\":\"새 이름\"");
        mockRestServiceServer.expect(anything())
            .andRespond(withServerError());
        steamStoreCollector.collectPurchaseOptions(301, SLOT);
        final Timestamp observedAt = jdbcTemplate.queryForObject("""
            SELECT price_observed_at FROM purchase_option WHERE type = 'PACKAGE'
            """, Timestamp.class);

        // when
        steamStoreCollector.collectPurchaseOptions(301, SLOT.plus(1, ChronoUnit.DAYS));
        Throwable failure = catchThrowable(() ->
            steamStoreCollector.collectPurchaseOptions(301, SLOT.plus(2, ChronoUnit.DAYS)));

        // then
        assertThat(failure).isInstanceOf(HttpServerErrorException.InternalServerError.class);
        assertThat(packageHistory()).containsExactly(1000L);
        assertThat(currentPackagePrice()).isEqualTo(1000L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT price_observed_at FROM purchase_option WHERE name = '새 이름'
            """, Timestamp.class)).isEqualTo(observedAt);
        mockRestServiceServer.verify();
    }

    private void respond(int appId, String packageFields) {
        String body = """
            {"response":{"store_items":[{"item_type":0,"success":1,"id":%d,
            "best_purchase_option":{"packageid":31},"purchase_options":[
            {"packageid":31,%s},{"bundleid":31,%s}]}]}}
            """.formatted(appId, packageFields, ZERO);
        mockRestServiceServer.expect(request -> assertThat(request.getURI().getQuery())
            .contains("\"appid\":" + appId))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private Long currentPackagePrice() {
        return jdbcTemplate.queryForObject("""
            SELECT current_price FROM purchase_option WHERE type = 'PACKAGE'
            """, Long.class);
    }

    private List<Long> packageHistory() {
        return jdbcTemplate.queryForList("""
            SELECT h.current_price FROM price_history h
            JOIN purchase_option o ON o.id = h.purchase_option_id
            WHERE o.type = 'PACKAGE' ORDER BY h.slot_at
            """, Long.class);
    }
}
