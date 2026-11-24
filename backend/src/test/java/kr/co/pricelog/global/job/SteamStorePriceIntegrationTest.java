package kr.co.pricelog.global.job;

import static kr.co.pricelog.domain.game.enums.SteamAppType.GAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import kr.co.pricelog.domain.collection.service.SteamPriceCollectionService;
import kr.co.pricelog.domain.game.service.GameCatalogService;
import kr.co.pricelog.global.client.steam.SteamRequestRetry;
import kr.co.pricelog.global.client.steam.SteamStoreClient;
import kr.co.pricelog.global.client.steam.dto.SteamApp;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOptions;
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
    private SteamPriceCollectionService steamPriceCollectionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SteamStoreCollector steamStoreCollector;
    private MockRestServiceServer mockRestServiceServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        mockRestServiceServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        steamStoreCollector = new SteamStoreCollector(new SteamStoreClient(restClientBuilder,
            objectMapper, new SteamRequestRetry()), steamPriceCollectionService);
        jdbcTemplate.execute("TRUNCATE price_history, game_purchase_option, purchase_option, game");
        gameCatalogService.applyPage(GAME, List.of(new SteamApp(301, "게임 A"),
            new SteamApp(302, "게임 B")), Instant.now());
    }

    @Test
    @DisplayName("앱 응답마다 공유 상품의 회차별 첫 가격을 저장하고 최신 성공값을 상품에 반영한다")
    void recordsSharedOptionOncePerSlot() {
        // given
        respondWithItems(item(302, ZERO) + "," + item(301, DISCOUNT));
        respond(301, DISCOUNT);
        respond(301, ZERO);

        // when
        steamStoreCollector.collectPurchaseOptions(List.of(301L, 302L), SLOT);

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

        steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT.plus(1, ChronoUnit.DAYS));
        steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT.plus(2, ChronoUnit.DAYS));
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
    @DisplayName("대표 패키지가 목록에서 빠지고 번들이 대표가 되어도 이전 가격 이력은 패키지에 남는다")
    void keepsHistoryWithOriginalOptionWhenBestChanges() {
        // given
        respond(301, DISCOUNT);
        mockRestServiceServer.expect(anything()).andRespond(withSuccess("""
            {"response":{"store_items":[{"item_type":0,"success":1,"id":301,
            "best_purchase_option":{"bundleid":31},"purchase_options":[
            {"bundleid":31,"formatted_final_price":"₩ 500"}]}]}}
            """, MediaType.APPLICATION_JSON));
        steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);

        Long originalHistoryId = jdbcTemplate.queryForObject("""
            SELECT h.id FROM price_history h
            JOIN purchase_option o ON o.id = h.purchase_option_id
            JOIN game_purchase_option link ON link.purchase_option_id = o.id
            WHERE link.is_best_option AND o.type = 'PACKAGE' AND o.steam_id = 31
            """, Long.class);

        // when
        Instant nextSlot = SLOT.plus(1, ChronoUnit.DAYS);
        steamStoreCollector.collectPurchaseOptions(List.of(301L), nextSlot);

        // then
        assertThat(jdbcTemplate.queryForObject("""
            SELECT h.current_price FROM price_history h
            JOIN purchase_option o ON o.id = h.purchase_option_id
            WHERE h.id = ? AND h.slot_at = ? AND o.type = 'PACKAGE' AND o.steam_id = 31
            """, Long.class, originalHistoryId, Timestamp.from(SLOT))).isEqualTo(1000L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT h.current_price FROM game_purchase_option link
            JOIN purchase_option o ON o.id = link.purchase_option_id
            JOIN price_history h ON h.purchase_option_id = o.id
            WHERE link.deleted_at IS NULL AND link.is_best_option AND o.type = 'BUNDLE'
              AND o.steam_id = 31 AND h.slot_at = ?
            """, Long.class, Timestamp.from(nextSlot))).isEqualTo(500L);
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
        steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);
        final Timestamp observedAt = jdbcTemplate.queryForObject("""
            SELECT price_observed_at FROM purchase_option WHERE type = 'PACKAGE'
            """, Timestamp.class);

        // when
        steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT.plus(1, ChronoUnit.DAYS));
        Throwable failure = catchThrowable(() ->
            steamStoreCollector.collectPurchaseOptions(List.of(301L),
                SLOT.plus(2, ChronoUnit.DAYS)));

        // then
        assertThat(failure).isInstanceOf(HttpServerErrorException.InternalServerError.class);
        assertThat(packageHistory()).containsExactly(1000L);
        assertThat(currentPackagePrice()).isEqualTo(1000L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT price_observed_at FROM purchase_option WHERE name = '새 이름'
            """, Timestamp.class)).isEqualTo(observedAt);
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("응답이 빠진 게임의 대표와 가격을 보존하고 함께 요청한 게임의 가격만 저장한다")
    void preservesMissingGameAndStoresConfirmedGame() {
        // given
        respond(301, DISCOUNT);
        respondWithItems("""
            {"item_type":0,"success":1,"id":302,"best_purchase_option":{"packageid":72},
             "purchase_options":[{"packageid":72,%s},{"packageid":73}]}
            """.formatted(ZERO));
        steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);

        // when
        Map<Long, SteamPurchaseOptions> result = steamStoreCollector.collectPurchaseOptions(
            List.of(301L, 302L), SLOT.plus(1, ChronoUnit.DAYS));

        // then
        assertThat(result).containsOnlyKeys(302L);
        assertThat(jdbcTemplate.queryForList("""
            SELECT g.steam_app_id, o.type, o.steam_id, l.is_best_option, o.current_price
            FROM game_purchase_option l JOIN game g ON g.id = l.game_id
            JOIN purchase_option o ON o.id = l.purchase_option_id
            WHERE l.deleted_at IS NULL AND o.deleted_at IS NULL
            ORDER BY g.steam_app_id, o.steam_id, o.type
            """)).extracting(row -> row.get("steam_app_id"), row -> row.get("type"),
                row -> row.get("steam_id"), row -> row.get("is_best_option"),
                row -> row.get("current_price"))
            .containsExactly(tuple(301L, "BUNDLE", 31L, false, 0L),
                tuple(301L, "PACKAGE", 31L, true, 1000L), tuple(302L, "PACKAGE", 72L, true, 0L),
                tuple(302L, "PACKAGE", 73L, false, null));
        assertThat(jdbcTemplate.queryForList("""
            SELECT h.current_price FROM price_history h
            JOIN purchase_option o ON o.id = h.purchase_option_id
            WHERE o.type = 'PACKAGE' ORDER BY h.slot_at
            """, Long.class)).containsExactly(1000L, 0L);
        mockRestServiceServer.verify();
    }

    private void respond(int appId, String packageFields) {
        respondWithItems(item(appId, packageFields));
    }

    private String item(int appId, String packageFields) {
        return """
            {"item_type":0,"success":1,"id":%d,
            "best_purchase_option":{"packageid":31},"purchase_options":[
            {"packageid":31,%s},{"bundleid":31,%s}]}
            """.formatted(appId, packageFields, ZERO);
    }

    private void respondWithItems(String items) {
        String body = """
            {"response":{"store_items":[%s]}}
            """.formatted(items);
        mockRestServiceServer.expect(request -> assertThat(request.getURI().getPath())
            .contains("/GetItems/"))
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
