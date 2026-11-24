package kr.co.pricelog.global.job;

import static kr.co.pricelog.domain.game.enums.SteamAppType.GAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import kr.co.pricelog.domain.collection.service.SteamPriceCollectionService;
import kr.co.pricelog.domain.game.service.GameCatalogService;
import kr.co.pricelog.global.client.steam.SteamRequestRetry;
import kr.co.pricelog.global.client.steam.SteamStoreClient;
import kr.co.pricelog.global.client.steam.dto.SteamApp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = "STEAM_WEB_API_KEY=test-key")
class SteamPriceRecoveryIntegrationTest {

    private static final Instant SLOT = Instant.parse("2026-01-01T00:00:00Z");
    private static final List<Long> TARGETS = LongStream.rangeClosed(1, 30).boxed().toList();
    private static final List<Long> MISSING = List.of(17L);

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

    private MockRestServiceServer server;
    private SteamStoreCollector steamStoreCollector;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        steamStoreCollector = new SteamStoreCollector(
            new SteamStoreClient(builder, objectMapper, new SteamRequestRetry()),
            steamPriceCollectionService);
        jdbcTemplate.execute("""
            TRUNCATE pipeline_execution, price_history, game_purchase_option, purchase_option, game
            """);
        gameCatalogService.applyPage(GAME, TARGETS.stream().map(id -> new SteamApp(id, "게임 " + id))
            .toList(), SLOT);
    }

    @Test
    @DisplayName("커밋 전 실패는 30개, 부분 커밋 후에는 누락 1개, 완료 후에는 0개를 다시 요청한다")
    void resumesFromLastCommittedBatch() {
        // given
        List<Long> confirmed = TARGETS.stream().filter(id -> id != 17L).toList();
        respond(TARGETS, confirmed);
        respond(TARGETS, confirmed);
        expectIds(MISSING).andRespond(request -> {
            // 실패 상태를 기록할 기회 없이 프로세스가 종료된 시점을 재현한다.
            throw new AssertionError("부분 커밋 후 중단");
        });
        respond(MISSING, MISSING);
        jdbcTemplate.execute("""
            ALTER TABLE pipeline_execution ADD CONSTRAINT test_interrupt_commit
            CHECK (next_index = 0)
            """);

        // when
        Throwable beforeCommit;
        try {
            beforeCommit = catchThrowable(() -> steamStoreCollector.collectAll(SLOT, 30));
        } finally {
            jdbcTemplate.execute(
                "ALTER TABLE pipeline_execution DROP CONSTRAINT test_interrupt_commit");
        }

        // then
        assertThat(beforeCommit).isInstanceOf(DataIntegrityViolationException.class)
            .hasStackTraceContaining("test_interrupt_commit");
        assertProgress("FAILED", 0, "{}", 0);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM purchase_option",
            Long.class)).isZero();

        gameCatalogService.applyPage(GAME, List.of(new SteamApp(31, "나중에 추가된 게임")), SLOT);
        Throwable afterCommit = catchThrowable(() -> steamStoreCollector.collectAll(SLOT, 30));
        assertThat(afterCommit).isInstanceOf(AssertionError.class).hasMessage("부분 커밋 후 중단");
        assertProgress("RUNNING", 30, "{17}", 29);

        steamStoreCollector.collectAll(SLOT, 30);
        steamStoreCollector.collectAll(SLOT, 30);
        assertProgress("COMPLETED", 30, "{}", 30);
        server.verify();
    }

    @Test
    @DisplayName("429 재시도를 소진하면 위치를 보존하고 재개 후 같은 묶음에서 429가 풀리면 저장한다")
    void resumesAfterRateLimit() {
        // given
        List<Long> first = TARGETS.subList(0, 15);
        List<Long> second = TARGETS.subList(15, 30);
        respond(first, first);
        for (int attempt = 0; attempt < 4; attempt++) {
            expectIds(second).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "0"));
        }
        respond(second, second);

        // when
        Throwable failure = catchThrowable(() -> steamStoreCollector.collectAll(SLOT, 15));

        // then
        assertThat(failure).isInstanceOf(HttpClientErrorException.TooManyRequests.class);
        assertProgress("FAILED", 15, "{}", 15);
        steamStoreCollector.collectAll(SLOT, 15);
        assertProgress("COMPLETED", 30, "{}", 30);
        server.verify();
    }

    @Test
    @DisplayName("미해결 앱이 남아도 다음 묶음을 저장하고 마지막에 한 번만 다시 확인한다")
    void continuesPastMissingApps() {
        // given
        List<Long> first = TARGETS.subList(0, 15);
        List<Long> second = TARGETS.subList(15, 30);
        respond(first, first.subList(1, 15));
        respond(second, second);
        respond(List.of(1L), List.of());

        // when
        steamStoreCollector.collectAll(SLOT, 15);

        // then
        assertProgress("FAILED", 30, "{1}", 29);
        server.verify();
    }

    private void assertProgress(String status, int nextIndex, String failedIds, int historyCount) {
        assertThat(jdbcTemplate.queryForMap("""
            SELECT status, next_index, failed_ids::text, cardinality(target_ids) AS targets
            FROM pipeline_execution
            """)).isEqualTo(Map.of("status", status, "next_index", nextIndex, "failed_ids",
                failedIds, "targets", 30));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM price_history",
            Integer.class)).isEqualTo(historyCount);
    }

    private ResponseActions expectIds(List<Long> ids) {
        return server.expect(request -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String query = request.getURI().getQuery().substring("input_json=".length());
            assertThat(objectMapper.readTree(query).get("ids").findValues("appid"))
                .extracting(id -> id.longValue()).containsExactlyElementsOf(ids);
        });
    }

    private void respond(List<Long> requested, List<Long> confirmed) {
        String items = confirmed.stream().map(id -> """
            {"item_type":0,"success":1,"id":%d,"purchase_options":[
             {"packageid":%d,"formatted_final_price":"₩ 1,000"}]}
            """.formatted(id, id)).collect(Collectors.joining(","));
        expectIds(requested).andRespond(withSuccess(
            "{\"response\":{\"store_items\":[" + items + "]}}", MediaType.APPLICATION_JSON));
    }
}
