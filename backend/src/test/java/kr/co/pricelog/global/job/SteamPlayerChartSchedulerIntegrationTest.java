package kr.co.pricelog.global.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import kr.co.pricelog.domain.playercount.service.PlayerCountService;
import kr.co.pricelog.global.client.steam.SteamPlayerChartClient;
import kr.co.pricelog.global.client.steam.dto.SteamPlayerChart;
import kr.co.pricelog.global.client.steam.dto.SteamPlayerCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = "STEAM_WEB_API_KEY=test-key")
class SteamPlayerChartSchedulerIntegrationTest {

    private static final Instant FIRST = Instant.parse("2026-01-01T03:00:00Z");
    private static final Instant NEXT = FIRST.plusSeconds(900);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17.6-alpine");

    @Autowired
    private PlayerCountService playerCountService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final SteamPlayerChartClient steamPlayerChartClient =
        mock(SteamPlayerChartClient.class);
    private SteamPlayerChartScheduler scheduler;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE ccu_history");
        scheduler = new SteamPlayerChartScheduler(steamPlayerChartClient, playerCountService);
    }

    @Test
    @DisplayName("빈 카탈로그에서도 새 판 100개씩 적재하고 같은 시각과 역행한 판은 건너뛴다")
    void storesOnlyNewCompleteSnapshots() {
        // given
        when(steamPlayerChartClient.getCurrentChart()).thenReturn(
            chart(FIRST, 1, 10), chart(NEXT, 101, 0), chart(NEXT, 102, 30),
            chart(FIRST.minusSeconds(900), 103, 40));

        // when
        scheduler.collect();
        scheduler.collect();
        scheduler.collect();
        scheduler.collect();

        // then
        assertThat(jdbcTemplate.queryForList("""
            SELECT count(*) FROM ccu_history GROUP BY observed_at ORDER BY observed_at
            """, Integer.class)).containsExactly(100, 100);
        assertThat(jdbcTemplate.queryForList("""
            SELECT steam_app_id FROM ccu_history WHERE source_rank = 1 ORDER BY observed_at
            """, Long.class)).containsExactly(1L, 101L);
        assertThat(jdbcTemplate.queryForList("""
            SELECT player_count FROM ccu_history WHERE steam_app_id = 100 AND source_rank = 100
            ORDER BY observed_at
            """, Integer.class)).containsExactly(10, 0);
        assertThat(jdbcTemplate.queryForList("""
            SELECT peak_player_count FROM ccu_history WHERE steam_app_id = 100 AND source_rank = 100
            ORDER BY observed_at
            """, Integer.class)).containsExactly(85, 75);
    }

    @Test
    @DisplayName("새 판 적재 중 제약 위반은 전체 롤백되고 다음 정상 응답으로 복구된다")
    void rollsBackWholeSnapshot() {
        // given
        when(steamPlayerChartClient.getCurrentChart()).thenReturn(chart(FIRST, 1, 10),
            chart(NEXT, 101, -1), chart(NEXT, 101, 20));
        scheduler.collect();

        // when
        Throwable failure = catchThrowable(scheduler::collect);
        List<Instant> storedAfterFailure = jdbcTemplate.query("""
            SELECT DISTINCT observed_at FROM ccu_history
            """, (row, index) -> row.getTimestamp("observed_at").toInstant());
        scheduler.collect();

        // then
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(storedAfterFailure).containsExactly(FIRST);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ccu_history", Integer.class))
            .isEqualTo(200);
    }

    private SteamPlayerChart chart(Instant observedAt, long firstAppId, int lastCount) {
        List<SteamPlayerCount> entries = IntStream.rangeClosed(1, 100)
            .mapToObj(rank -> new SteamPlayerCount(rank == 1 ? firstAppId : rank, rank,
                rank == 100 ? lastCount : 200 - rank, rank == 100 ? lastCount + 75 : 300 - rank))
            .toList();
        return new SteamPlayerChart(observedAt, entries);
    }
}
