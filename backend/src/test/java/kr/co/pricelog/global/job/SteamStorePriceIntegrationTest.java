package kr.co.pricelog.global.job;

import static kr.co.pricelog.domain.game.enums.SteamAppType.DLC;
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
import kr.co.pricelog.domain.purchaseoption.service.GamePurchaseOptionService;
import kr.co.pricelog.global.client.steam.SteamStoreClient;
import kr.co.pricelog.global.client.steam.dto.SteamApp;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private static final Instant SALE_END = SLOT.plus(1, ChronoUnit.DAYS);
    private static final Instant RESALE = SALE_END.plus(1, ChronoUnit.DAYS);
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
    private GamePurchaseOptionService gamePurchaseOptionService;

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
            objectMapper), steamPriceCollectionService);
        jdbcTemplate.execute("""
            TRUNCATE pipeline_execution, price_history, game_purchase_option, purchase_option,
                     game_tag, review_history, review_collection, game
            """);
        gameCatalogService.applyPage(GAME, List.of(new SteamApp(301, "게임 A"),
            new SteamApp(302, "게임 B")), Instant.now());
    }

    @Test
    @DisplayName("전수 수집은 기본 헤더를 우선 저장하고 이후 고해상도 헤더만 오면 해당 URL로 갱신한다")
    void savesAndRefreshesGameHeader() {
        // given
        gameCatalogService.applyPage(DLC, List.of(new SteamApp(303, "추가 콘텐츠")), Map.of(303L, 301L),
            SLOT);
        respondWithItems("""
            {"item_type":0,"id":303,"success":1},
            {"item_type":0,"id":302,"success":1},
            {"item_type":0,"id":301,"success":1,"assets":{
             "asset_url_format":"steam/apps/301/${FILENAME}?t=15",
             "header":"v1/header.jpg","header_2x":"v1/header_2x.jpg"}}
            """);
        respondWithItems("""
            {"item_type":0,"id":301,"success":1,"assets":{
             "asset_url_format":"steam/apps/301/${FILENAME}?t=16","header_2x":"v2/header_2x.jpg"}},
            {"item_type":0,"id":303,"success":1,"assets":{
             "asset_url_format":"steam/apps/303/${FILENAME}","header":"dlc.jpg"}}
            """);

        // when
        steamStoreCollector.collectAll(SLOT, 3);

        // then
        String images = "SELECT image_url FROM game ORDER BY steam_app_id";
        String cdn = "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/301/";
        assertThat(jdbcTemplate.queryForList(images, String.class))
            .containsExactly(cdn + "v1/header.jpg?t=15", null, null);

        steamStoreCollector.collectPurchaseOptions(List.of(301L, 303L), SLOT.plusSeconds(1));
        assertThat(jdbcTemplate.queryForList(images, String.class))
            .containsExactly(cdn + "v2/header_2x.jpg?t=16", null,
                "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/303/dlc.jpg");
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("본편과 DLC의 짧은 설명을 저장하고 재수집 때 내용이 있는 설명만 갱신한다")
    void collectsGameDescriptions() {
        // given
        gameCatalogService.applyPage(DLC, List.of(new SteamApp(303, "추가 콘텐츠")), Map.of(303L, 301L),
            SLOT);
        respondWithItems("""
            {"item_type":0,"id":303,"success":1},
            {"item_type":0,"id":302,"success":1,"basic_info":{"short_description":"기존 소개"}},
            {"item_type":0,"id":301,"success":1,"basic_info":{"short_description":"첫 소개"}}
            """);
        respondWithItems("""
            {"item_type":0,"id":301,"success":1,"basic_info":{"short_description":"수정된 소개"}},
            {"item_type":0,"id":302,"success":1,
             "basic_info":{"short_description":" \\u00a0\\u202f "}},
            {"item_type":0,"id":303,"success":1,"basic_info":{"short_description":"DLC 소개"}}
            """);
        respondWithItems("""
            {"item_type":0,"id":301,"success":1},
            {"item_type":0,"id":302,"success":15,"visible":false}
            """);

        String descriptions = "SELECT description FROM game ORDER BY steam_app_id";

        // when
        steamStoreCollector.collectAll(SLOT, 3);
        List<String> initial = jdbcTemplate.queryForList(descriptions, String.class);
        steamStoreCollector.collectPurchaseOptions(List.of(301L, 302L, 303L), SLOT.plusSeconds(1));
        steamStoreCollector.collectPurchaseOptions(List.of(301L, 302L), SLOT.plusSeconds(2));

        // then
        assertThat(initial).containsExactly("첫 소개", "기존 소개", null);
        assertThat(jdbcTemplate.queryForList(descriptions, String.class))
            .containsExactly("수정된 소개", "기존 소개", "DLC 소개");
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("본편 출시 일정의 공개 범위와 한국어 지원이 바뀌면 같은 배치에서 갱신한다")
    void collectsReleaseAndKoreanSupport() {
        // given
        respondWithItems("""
            {"item_type":0,"id":301,"success":1,
             "release":{"steam_release_date":1830520800,"is_coming_soon":true,
                        "coming_soon_display":"date_full"},
             "supported_languages":[{"elanguage":4,"supported":true,"subtitles":true,
                                     "full_audio":false}]},
            {"item_type":0,"id":302,"success":1,"release":{"is_coming_soon":true},
             "supported_languages":[{"elanguage":0,"supported":true}]}
            """);
        respondWithItems("""
            {"item_type":0,"id":301,"success":1,
             "release":{"is_coming_soon":true,"custom_release_date_message":"발표 예정"},
             "supported_languages":[]},
            {"item_type":0,"id":302,"success":1,"release":{"steam_release_date":1478035243}}
            """);
        respondWithItems("""
            {"item_type":0,"id":301,"success":1,"supported_languages":[{"elanguage":0}]},
            {"item_type":0,"id":302,"success":15,"visible":false}
            """);

        String query = """
            SELECT release_date::text, release_text, coming_soon, korean_support_text
            FROM game ORDER BY steam_app_id
            """;

        // when
        steamStoreCollector.collectAll(SLOT, 2);
        List<Map<String, Object>> before = jdbcTemplate.queryForList(query);
        steamStoreCollector.collectPurchaseOptions(List.of(301L, 302L), SLOT.plusSeconds(1));

        List<Map<String, Object>> revised = jdbcTemplate.queryForList(query);
        steamStoreCollector.collectPurchaseOptions(List.of(301L, 302L), SLOT.plusSeconds(2));

        // then
        assertThat(before).extracting("release_date", "release_text", "coming_soon",
                "korean_support_text")
            .containsExactly(tuple("2028-01-03", null, true, "인터페이스, 자막"),
                tuple(null, null, true, "미지원"));
        assertThat(revised)
            .extracting("release_date", "release_text", "coming_soon", "korean_support_text")
            .containsExactly(tuple(null, "발표 예정", true, "인터페이스, 자막"),
                tuple("2016-11-02", null, false, "미지원"));
        assertThat(jdbcTemplate.queryForList(query))
            .extracting("release_date", "release_text", "coming_soon", "korean_support_text")
            .containsExactly(tuple(null, "발표 예정", true, "미지원"),
                tuple("2016-11-02", null, false, "미지원"));
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("날짜가 없어도 명시된 출시 예정 해제는 반영하고 기존 실제 출시일은 보존한다")
    void appliesExplicitReleaseFlagWithoutDate() {
        // given
        respondWithItems("""
            {"item_type":0,"id":301,"success":1,"release":{"is_coming_soon":true,
             "custom_release_date_message":"발표 예정"}},
            {"item_type":0,"id":302,"success":1,"release":{"steam_release_date":1478035243}}
            """);
        respondWithItems("""
            {"item_type":0,"id":301,"success":1,"release":{"is_coming_soon":false}},
            {"item_type":0,"id":302,"success":1,"release":{"is_coming_soon":false}}
            """);
        List<Long> ids = List.of(301L, 302L);
        steamStoreCollector.collectPurchaseOptions(ids, SLOT);

        // when
        steamStoreCollector.collectPurchaseOptions(ids, SLOT.plusSeconds(1));

        // then
        assertThat(jdbcTemplate.queryForList("""
            SELECT coming_soon, release_date::text, release_text FROM game ORDER BY steam_app_id
            """)).extracting("coming_soon", "release_date", "release_text")
            .containsExactly(tuple(false, null, null), tuple(false, "2016-11-02", null));
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("이미지 미제공·조회 실패·미노출 응답은 저장된 이미지를 보존하고 확인된 가격은 반영한다")
    void preservesImageWhenUnconfirmed() {
        // given
        String previous = "https://shared.fastly.steamstatic.com/store_item_assets/old.jpg";
        jdbcTemplate.update("UPDATE game SET image_url = ?", previous);
        respondWithItems(item(301, DISCOUNT) + """
            ,{"item_type":0,"id":302,"success":15}
            """);
        respondWithItems("""
            {"item_type":0,"id":301,"success":15,"visible":false}
            """);

        // when
        steamStoreCollector.collectPurchaseOptions(List.of(301L, 302L), SLOT);
        steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT.plusSeconds(1));

        // then
        assertThat(jdbcTemplate.queryForList(
            "SELECT image_url FROM game ORDER BY steam_app_id", String.class))
            .containsExactly(previous, previous);
        assertThat(currentPackagePrice()).isEqualTo(1000L);
        mockRestServiceServer.verify();
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
            WHERE l.deleted_at IS NULL AND o.deleted_at IS NULL AND o.sale_ended_at IS NULL
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

    @Test
    @DisplayName("공유 상품을 종료하면 모든 게임 연결이 닫히고 중복 종료 요청은 재판매에 영향을 주지 않는다")
    void endsSharedSaleOnce() {
        respondWithItems(item(301, DISCOUNT) + "," + item(302, DISCOUNT));
        respond(301, ZERO);
        steamStoreCollector.collectPurchaseOptions(List.of(301L, 302L), SLOT);

        long originalId = activePackageId();
        gamePurchaseOptionService.endSale(originalId, SALE_END);
        assertThat(jdbcTemplate.queryForList("""
            SELECT deleted_at FROM game_purchase_option WHERE purchase_option_id = ?
            """, Timestamp.class, originalId))
            .containsExactly(Timestamp.from(SALE_END), Timestamp.from(SALE_END));

        steamStoreCollector.collectPurchaseOptions(List.of(301L), RESALE);
        long resaleId = activePackageId();
        gamePurchaseOptionService.endSale(originalId, RESALE);

        assertThat(resaleId).isNotEqualTo(originalId);
        assertThat(activePackageId()).isEqualTo(resaleId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT sale_ended_at FROM purchase_option WHERE id = ?", Timestamp.class, originalId))
            .isEqualTo(Timestamp.from(SALE_END));
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("종료 시각과 같은 회차에는 판매 구간을 다시 열지 않는다")
    void staysClosedAtSaleEnd() {
        respond(301, DISCOUNT);
        respond(301, DISCOUNT);
        steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);
        gamePurchaseOptionService.endSale(activePackageId(), SALE_END);

        steamStoreCollector.collectPurchaseOptions(List.of(301L), SALE_END);

        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM purchase_option
            WHERE type = 'PACKAGE' AND deleted_at IS NULL AND sale_ended_at IS NULL
            """, Integer.class)).isZero();
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("판매 종료 전 회차를 다시 조회하면 종료된 가격은 보호하고 본편 태그는 갱신한다")
    void refreshesTagsWhenReplayingAnEndedSale() {
        // given
        respond(301, DISCOUNT);
        respondWithItems("""
            {"item_type":0,"success":1,"id":301,"tagids":[8,3],"purchase_options":[
             {"packageid":31,%s}]}
            """.formatted(ZERO));
        steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);
        gamePurchaseOptionService.endSale(activePackageId(), SALE_END);

        // when
        steamStoreCollector.collectPurchaseOptions(List.of(301L), SALE_END.minusSeconds(1));

        // then
        assertThat(jdbcTemplate.queryForList("""
            SELECT t.steam_tag_id FROM game_tag gt JOIN tag t ON t.id = gt.tag_id
            JOIN game g ON g.id = gt.game_id WHERE g.steam_app_id = 301 ORDER BY gt.display_order
            """, Long.class)).containsExactly(8L, 3L);
        assertThat(packageHistory()).containsExactly(1000L);
        assertThat(currentPackagePrice()).isEqualTo(1000L);
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("과거 회차의 가격은 재판매 패키지를 건너뛰고 계속 판매 중인 번들 이력에 남긴다")
    void recordsOnlyUnchangedPricesOnReplay() {
        respondWithItems(item(301, DISCOUNT, 77, ZERO));
        respondWithItems(item(301, ZERO, 77, ZERO));
        respondWithItems(item(301, DISCOUNT, 77, DISCOUNT) + "," + """
            {"item_type":0,"success":1,"id":302,"purchase_options":[{"bundleid":31,%s}]}
            """.formatted(DISCOUNT));
        steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);
        gamePurchaseOptionService.endSale(activePackageId(), SALE_END);
        steamStoreCollector.collectPurchaseOptions(List.of(301L), RESALE);

        Instant lateSlot = SALE_END.minusSeconds(1);
        steamStoreCollector.collectPurchaseOptions(List.of(301L, 302L), lateSlot);

        assertThat(jdbcTemplate.queryForList("""
            SELECT o.sale_ended_at, o.current_price, h.current_price AS historical, h.slot_at
            FROM purchase_option o JOIN price_history h ON h.purchase_option_id = o.id
            WHERE o.type = 'PACKAGE' AND o.deleted_at IS NULL ORDER BY o.id, h.slot_at
            """)).extracting(row -> row.get("sale_ended_at"), row -> row.get("current_price"),
                row -> row.get("historical"), row -> row.get("slot_at"))
            .containsExactly(tuple(Timestamp.from(SALE_END), 1000L, 1000L, Timestamp.from(SLOT)),
                tuple(null, 0L, 0L, Timestamp.from(RESALE)));
        assertThat(jdbcTemplate.queryForList("""
            SELECT g.steam_app_id, o.steam_id, o.current_price, h.current_price AS historical
            FROM purchase_option o JOIN price_history h ON h.purchase_option_id = o.id
            JOIN game_purchase_option l ON l.purchase_option_id = o.id AND l.deleted_at IS NULL
            JOIN game g ON g.id = l.game_id
            WHERE o.type = 'BUNDLE' AND h.slot_at = ? ORDER BY o.steam_id
            """, Timestamp.from(lateSlot)))
            .extracting(row -> row.get("steam_app_id"), row -> row.get("steam_id"),
                row -> row.get("current_price"), row -> row.get("historical"))
            .containsExactly(tuple(302L, 31L, 1000L, 1000L), tuple(301L, 77L, 0L, 1000L));
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("공유 상품의 첫 가격이 보호 회차의 이력 보충이면 뒤의 정상 게임도 현재가를 바꾸지 않는다")
    void keepsHistoryOnlyWhenProtectedGameOwnsFirstSharedObservation() {
        // given
        respond(301, DISCOUNT);
        // 첫 게임은 종료한 패키지에 연결되어 보호된다. 두 번째 게임은 계속 판매 중인 번들만 제공한다.
        String sharedBundle = """
            {"item_type":0,"success":1,"id":%d,"purchase_options":[{"bundleid":31,%s}]}
            """;
        String laterPrice = """
            "final_price_in_cents":"200000","formatted_final_price":"₩ 2,000"
            """;
        respondWithItems(sharedBundle.formatted(302, laterPrice) + ","
            + sharedBundle.formatted(301, DISCOUNT));

        steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);
        gamePurchaseOptionService.endSale(activePackageId(), SALE_END);
        Instant lateSlot = SALE_END.minusSeconds(1);

        // when
        steamStoreCollector.collectPurchaseOptions(List.of(301L, 302L), lateSlot);

        // then
        assertThat(jdbcTemplate.queryForMap("""
            SELECT o.current_price, h.current_price AS historical
            FROM purchase_option o JOIN price_history h ON h.purchase_option_id = o.id
            WHERE o.type = 'BUNDLE' AND h.slot_at = ?
            """, Timestamp.from(lateSlot)))
            .containsEntry("current_price", 0L).containsEntry("historical", 1000L);
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("이전에 연결되지 않은 게임의 과거 응답에 종료된 패키지가 있어도 새로 연결하지 않는다")
    void skipsEndedPackageInUnlinkedGameResponse() {
        respond(301, DISCOUNT);
        respond(301, ZERO);
        respond(302, DISCOUNT);
        steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);
        gamePurchaseOptionService.endSale(activePackageId(), SALE_END);
        steamStoreCollector.collectPurchaseOptions(List.of(301L), RESALE);

        steamStoreCollector.collectPurchaseOptions(List.of(302L), SLOT);

        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM game_purchase_option l JOIN game g ON g.id = l.game_id
            WHERE g.steam_app_id = 302 AND l.deleted_at IS NULL
            """, Integer.class)).isZero();
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("회차 전에 게임과의 연결이 끝난 상품은 그 게임의 새 구매 옵션을 막지 않는다")
    void ignoresLinksClosedBeforeSlot() {
        respond(301, DISCOUNT);
        respondWithItems("""
            {"item_type":0,"success":1,"id":301,"purchase_options":[{"packageid":72,%s}]}
            """.formatted(DISCOUNT));
        steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);

        Instant nextSlot = SLOT.plus(1, ChronoUnit.DAYS);
        jdbcTemplate.update("""
            UPDATE game_purchase_option SET deleted_at = ?
            WHERE purchase_option_id IN (SELECT id FROM purchase_option WHERE type = 'PACKAGE')
            """, Timestamp.from(nextSlot.minusSeconds(1)));
        jdbcTemplate.update("UPDATE purchase_option SET sale_ended_at = ? WHERE type = 'PACKAGE'",
            Timestamp.from(nextSlot.plusSeconds(1)));

        steamStoreCollector.collectPurchaseOptions(List.of(301L), nextSlot);

        assertThat(jdbcTemplate.queryForList("""
            SELECT o.steam_id FROM game_purchase_option l
            JOIN purchase_option o ON o.id = l.purchase_option_id WHERE l.deleted_at IS NULL
            """, Long.class)).containsExactly(72L);
        mockRestServiceServer.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("소프트 삭제한 상품은 판매 종료로 판정하지 않고 같은 Steam 번호의 정상 응답을 저장한다")
    void excludesSoftDeletionFromSaleEndDetection(boolean hadSaleEnd) {
        // given
        Timestamp endedAt = hadSaleEnd ? Timestamp.from(SALE_END) : null;
        jdbcTemplate.update("""
            INSERT INTO purchase_option (type, steam_id, created_at, updated_at, deleted_at,
                                         sale_ended_at)
            VALUES ('PACKAGE', 31, ?, ?, ?, ?)
            """, Timestamp.from(SLOT), Timestamp.from(RESALE), Timestamp.from(RESALE), endedAt);
        respond(301, DISCOUNT);

        // when
        steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);

        // then
        assertThat(jdbcTemplate.queryForList("""
            SELECT current_price FROM price_history WHERE purchase_option_id = ?
            """, Long.class, activePackageId())).containsExactly(1000L);
        mockRestServiceServer.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302})
    @DisplayName("재판매 후 기존 게임과 새 게임 모두 과거 빈 응답으로 대표 연결을 잃지 않는다")
    void keepsResaleLinkOnEmptyReplay(int resaleAppId) {
        respond(301, DISCOUNT);
        respond(resaleAppId, ZERO);
        respondWithItems("""
            {"item_type":0,"success":1,"id":%d,"purchase_options":[]}
            """.formatted(resaleAppId));
        steamStoreCollector.collectPurchaseOptions(List.of(301L), SLOT);
        gamePurchaseOptionService.endSale(activePackageId(), SALE_END);
        steamStoreCollector.collectPurchaseOptions(List.of((long) resaleAppId), RESALE);

        String bestLink = """
            SELECT l.id FROM game_purchase_option l
            JOIN game g ON g.id = l.game_id JOIN purchase_option o ON o.id = l.purchase_option_id
            WHERE g.steam_app_id = ? AND o.type = 'PACKAGE' AND o.steam_id = 31
              AND o.deleted_at IS NULL AND o.sale_ended_at IS NULL
              AND l.deleted_at IS NULL AND l.is_best_option
            """;
        Long linkId = jdbcTemplate.queryForObject(bestLink, Long.class, resaleAppId);
        steamStoreCollector.collectPurchaseOptions(List.of((long) resaleAppId), SLOT);

        assertThat(jdbcTemplate.queryForList(bestLink, Long.class, resaleAppId))
            .containsExactly(linkId);
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("한국 판매 제한과 미노출은 서로 다른 상태로 저장하고 가격 재시도 없이 회차를 완료한다")
    void completesCollectionWithRestrictedAndHiddenGames() {
        // given
        respondWithItems("""
            {"item_type":0,"id":301,"success":15,"visible":false,
             "unvailable_for_country_restriction":true},
            {"item_type":0,"id":302,"success":15,"visible":false,"appid":0}
            """);

        // when
        steamStoreCollector.collectAll(SLOT, 2);

        // then
        mockRestServiceServer.verify();
        assertThat(jdbcTemplate.queryForList(
            "SELECT store_visibility FROM game ORDER BY steam_app_id", String.class))
            .containsExactly("REGION_RESTRICTED", "HIDDEN");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT status FROM pipeline_execution WHERE pipeline = 'STEAM_PRICE'
            AND scheduled_at = ? AND next_index = 2 AND cardinality(failed_ids) = 0
            """, String.class, Timestamp.from(SLOT))).isEqualTo("COMPLETED");
        assertThat(packageHistory()).isEmpty();
    }

    @Test
    @DisplayName("미노출과 조회 실패 동안 기존 상품을 보존하고 다시 노출되면 가격 수집을 재개한다")
    void restoresVisibleGameAfterHiddenAndUnconfirmedResponses() {
        // given
        respondWithItems("""
            {"item_type":0,"id":301,"success":1,"is_free":false,
             "best_purchase_option":{"packageid":31},"purchase_options":[{"packageid":31,%s}]}
            """.formatted(DISCOUNT));
        respondWithItems("{\"item_type\":0,\"id\":301,\"success\":15,\"visible\":false}");
        respondWithItems("{\"item_type\":0,\"id\":301,\"success\":15}");
        respond(301, ZERO);
        List<Long> ids = List.of(301L);
        steamStoreCollector.collectPurchaseOptions(ids, SLOT);

        // when
        steamStoreCollector.collectPurchaseOptions(ids, SLOT.plusSeconds(1));
        steamStoreCollector.collectPurchaseOptions(ids, SLOT.plusSeconds(2));

        // then
        assertThat(jdbcTemplate.queryForObject("""
            SELECT store_visibility FROM game WHERE steam_app_id = 301 AND is_free = false
            """, String.class)).isEqualTo("HIDDEN");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM game_purchase_option WHERE deleted_at IS NULL AND is_best_option
            """, Long.class)).isEqualTo(1L);
        assertThat(currentPackagePrice()).isEqualTo(1000L);
        assertThat(packageHistory()).containsExactly(1000L);

        steamStoreCollector.collectPurchaseOptions(ids, SLOT.plusSeconds(3));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT store_visibility FROM game WHERE steam_app_id = 301", String.class))
            .isEqualTo("VISIBLE");
        assertThat(packageHistory()).containsExactly(1000L, 0L);
        mockRestServiceServer.verify();
    }

    private long activePackageId() {
        return jdbcTemplate.queryForObject("""
            SELECT id FROM purchase_option WHERE type = 'PACKAGE' AND steam_id = 31
            AND deleted_at IS NULL AND sale_ended_at IS NULL
            """, Long.class);
    }

    private void respond(int appId, String packageFields) {
        respondWithItems(item(appId, packageFields));
    }

    private String item(int appId, String packageFields) {
        return item(appId, packageFields, 31, ZERO);
    }

    private String item(int appId, String packageFields, int bundleId, String bundleFields) {
        return """
            {"item_type":0,"success":1,"id":%d,
            "best_purchase_option":{"packageid":31},"purchase_options":[
            {"packageid":31,%s},{"bundleid":%d,%s}]}
            """.formatted(appId, packageFields, bundleId, bundleFields);
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
