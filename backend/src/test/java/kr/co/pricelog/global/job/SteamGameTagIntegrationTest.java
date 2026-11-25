package kr.co.pricelog.global.job;

import static kr.co.pricelog.domain.game.enums.SteamAppType.DLC;
import static kr.co.pricelog.domain.game.enums.SteamAppType.GAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.co.pricelog.domain.collection.service.SteamPriceCollectionService;
import kr.co.pricelog.domain.game.service.GameCatalogService;
import kr.co.pricelog.domain.game.service.GameTagService;
import kr.co.pricelog.global.client.steam.SteamStoreClient;
import kr.co.pricelog.global.client.steam.SteamTagDictionaryClient;
import kr.co.pricelog.global.client.steam.dto.SteamApp;
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
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = "STEAM_WEB_API_KEY=test-key")
class SteamGameTagIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17.6-alpine");

    @Autowired
    private GameCatalogService gameCatalogService;

    @Autowired
    private GameTagService gameTagService;

    @Autowired
    private SteamPriceCollectionService steamPriceCollectionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SteamStoreClient steamStoreClient;
    private SteamTagDictionaryClient steamTagDictionaryClient;
    private SteamStoreCollector steamStoreCollector;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        steamStoreClient = new SteamStoreClient(builder, objectMapper);
        steamTagDictionaryClient = new SteamTagDictionaryClient(builder, objectMapper);
        steamStoreCollector = new SteamStoreCollector(steamStoreClient,
            steamPriceCollectionService);
        jdbcTemplate.execute("TRUNCATE game, tag, purchase_option, pipeline_execution CASCADE");
        gameCatalogService.applyPage(GAME, List.of(new SteamApp(10, "A"), new SteamApp(20, "B")),
            Instant.now());
    }

    @Test
    @DisplayName("사전은 저장된 버전을 보내고 잘못된 새 사전이 와도 이전 버전부터 다시 확인한다")
    void refreshesDictionaryAfterFailure() {
        // given
        final SteamTagDictionaryScheduler scheduler = new SteamTagDictionaryScheduler(
            steamTagDictionaryClient, gameTagService);
        dictionary(null, """
            {"version_hash":"v1","tags":[{"tagid":42,"name":"농장"}]}
            """);
        dictionary("v1", "{}");
        dictionary("v1", """
            {"version_hash":"v2","tags":[{"tagid":42,"name":"다른 이름"},{"tagid":5}]}
            """);
        dictionary("v1", """
            {"version_hash":"v2","tags":[{"tagid":42,"name":"농장 시뮬레이션"}]}
            """);

        // when
        scheduler.refresh();
        scheduler.refresh();
        Throwable failure = catchThrowable(scheduler::refresh);
        List<String> beforeRetry = jdbcTemplate.queryForList("SELECT name_ko FROM tag",
            String.class);
        scheduler.refresh();

        // then
        assertThat(failure).isInstanceOf(RestClientException.class).hasMessageContaining("태그 사전");
        assertThat(beforeRetry).containsExactly("농장");
        assertThat(jdbcTemplate.queryForList("SELECT name_ko FROM tag", String.class))
            .containsExactly("농장 시뮬레이션");
        server.verify();
    }

    @Test
    @DisplayName("가격과 함께 태그 순서를 반영하고 빈 배열은 해제하며 누락·잘못된 배열·거부는 보존한다")
    void syncsGameTagsWithStoreBatch() {
        // given
        final List<Long> ids = List.of(10L, 20L);
        String reordered = item(10, "[2,1]") + "," + item(20, "[]");
        String rankedTags = "[21,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,1]";
        store(item(20, "[2]") + "," + item(10, rankedTags));
        store(reordered);
        store(reordered);
        store(item(10, "null"));
        store(item(10, "[3,0]"));
        store("{\"item_type\":0,\"id\":10,\"success\":15,\"visible\":false}");
        Instant slot = Instant.now();

        // when
        steamStoreCollector.collectPurchaseOptions(ids, slot);
        List<Long> initial = tags(10);
        List<Long> shared = tags(20);
        for (int i = 1; i <= 5; i++) {
            steamStoreCollector.collectPurchaseOptions(ids, slot.plusSeconds(i));
        }

        // then
        assertThat(initial).containsExactly(21L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L,
            11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L);
        assertThat(shared).containsExactly(2L);
        assertThat(tags(10)).containsExactly(2L, 1L);
        assertThat(tags(20)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tag", Long.class))
            .isEqualTo(21L);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"visible", "unvailable_for_country_restriction"})
    @DisplayName("태그 보완에서 노출 상태가 깨진 게임은 보존하고 정상 빈 목록인 게임만 해제한다")
    void clearsOnlyConfirmedEmptyTagsOnBackfill(String flag) {
        // given
        store(item(10, "[1]") + "," + item(20, "[2]"));
        store("""
            {"item_type":0,"success":1,"id":10,"%s":{},"tagids":[]},
            {"item_type":0,"success":1,"id":20,"visible":true,"tagids":[]}
            """.formatted(flag));
        steamStoreCollector.collectPurchaseOptions(List.of(10L, 20L), Instant.now());

        // when
        new SteamGameTagBackfillRunner(steamStoreClient, gameTagService, 0, 2, false).run();

        // then
        assertThat(tags(10)).containsExactly(1L);
        assertThat(tags(20)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("기존 설치의 보완은 지정한 커서 다음 본편만 조회하며 가격 회차와 이력을 보존한다")
    void backfillsOnlyRemainingBaseGames() {
        // given
        gameCatalogService.applyPage(DLC, List.of(new SteamApp(30, "DLC")), Map.of(30L, 10L),
            Instant.now());
        store("""
            {"item_type":0,"success":1,"id":20,"purchase_options":[{"packageid":31,
             "final_price_in_cents":"100000","formatted_final_price":"₩ 1,000"}]},
            """ + item(10, "[]"));
        server.expect(request -> {
            String input = request.getURI().getQuery().substring("input_json=".length());
            JsonNode query = objectMapper.readTree(input);
            assertThat(query.path("ids").toString()).isEqualTo("[{\"appid\":20}]");
            assertThat(query.path("data_request").toString())
                .isEqualTo("{\"include_tag_count\":20}");
        }).andRespond(withSuccess("{\"response\":{\"store_items\":[" + item(20, "[42]") + "]}}",
            MediaType.APPLICATION_JSON));
        dictionary(null, """
            {"version_hash":"v1","tags":[{"tagid":42,"name":"전략"}]}
            """);

        steamStoreCollector.collectAll(Instant.now(), 2);
        String prices = "SELECT id, purchase_option_id, slot_at, observed_at, current_price "
            + "FROM price_history";
        String executions = "SELECT id, pipeline, scheduled_at, status, next_index "
            + "FROM pipeline_execution";
        List<Map<String, Object>> originalPrices = jdbcTemplate.queryForList(prices);
        List<Map<String, Object>> originalExecutions = jdbcTemplate.queryForList(executions);
        assertThat(originalPrices).singleElement().satisfies(row ->
            assertThat(row).containsEntry("current_price", 1000L));
        assertThat(originalExecutions).singleElement().satisfies(row ->
            assertThat(row).containsEntry("status", "COMPLETED"));

        // when
        new SteamGameTagBackfillRunner(steamStoreClient, gameTagService, 10, 1, false).run();
        new SteamTagDictionaryScheduler(steamTagDictionaryClient, gameTagService).refresh();

        // then
        assertThat(tags(10)).isEmpty();
        assertThat(tags(20)).containsExactly(42L);
        assertThat(jdbcTemplate.queryForList("""
            SELECT t.name_ko FROM tag t JOIN game_tag gt ON gt.tag_id = t.id
            """, String.class)).containsExactly("전략");
        assertThat(jdbcTemplate.queryForList(prices)).isEqualTo(originalPrices);
        assertThat(jdbcTemplate.queryForList(executions)).isEqualTo(originalExecutions);
        server.verify();
    }

    private void dictionary(String version, String response) {
        server.expect(request -> {
            assertThat(request.getURI().getPath()).endsWith("/GetTagList/v1/");
            String input = request.getURI().getQuery().substring("input_json=".length());
            JsonNode query = objectMapper.readTree(input);
            assertThat(query.path("language").textValue()).isEqualTo("koreana");
            assertThat(query.path("have_version_hash").textValue()).isEqualTo(version);
        }).andRespond(withSuccess("{\"response\":" + response + "}", MediaType.APPLICATION_JSON));
    }

    private void store(String items) {
        server.expect(request -> assertThat(request.getURI().getPath()).endsWith("/GetItems/v1/"))
            .andRespond(withSuccess("{\"response\":{\"store_items\":[" + items + "]}}",
                MediaType.APPLICATION_JSON));
    }

    private String item(long appId, String tags) {
        return "{\"item_type\":0,\"success\":1,\"id\":" + appId + ",\"tagids\":" + tags + "}";
    }

    private List<Long> tags(long appId) {
        return jdbcTemplate.queryForList("""
            SELECT t.steam_tag_id FROM game_tag gt JOIN game g ON g.id = gt.game_id
            JOIN tag t ON t.id = gt.tag_id WHERE g.steam_app_id = ? ORDER BY gt.display_order
            """, Long.class, appId);
    }
}
