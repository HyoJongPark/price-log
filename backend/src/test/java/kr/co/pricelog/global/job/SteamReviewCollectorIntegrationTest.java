package kr.co.pricelog.global.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import kr.co.pricelog.domain.game.enums.SteamAppType;
import kr.co.pricelog.domain.game.service.GameCatalogService;
import kr.co.pricelog.global.client.steam.SteamReviewClient;
import kr.co.pricelog.global.client.steam.dto.SteamApp;
import kr.co.pricelog.global.client.steam.dto.SteamReviewCounts;
import kr.co.pricelog.global.client.steam.dto.SteamReviewWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {"STEAM_WEB_API_KEY=test-key",
    "resilience4j.retry.instances.steamCollection.max-attempts=1"})
class SteamReviewCollectorIntegrationTest {

    private static final LocalDate MONDAY = LocalDate.of(2025, 11, 24);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17.6-alpine")
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");

    @Autowired
    private SteamReviewCollector steamReviewCollector;

    @Autowired
    private GameCatalogService gameCatalogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private SteamReviewClient steamReviewClient;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE game CASCADE");
    }

    @Test
    @DisplayName("담당 요일의 본편·DLC만 7일치를 읽고 실패한 앱만 재실행하며 미수집 주는 비워 둔다")
    void resumesWeeklyCoverage() {
        // given
        gameCatalogService.applyPage(SteamAppType.GAME, List.of(new SteamApp(7, "본편"),
            new SteamApp(8, "화요일 본편"), new SteamApp(28, "삭제 본편"),
            new SteamApp(15, "등록 후 삭제된 화요일 본편")), Instant.now());
        gameCatalogService.applyPage(SteamAppType.DLC, List.of(new SteamApp(14, "DLC")),
            Map.of(14L, 7L), Instant.now());
        jdbcTemplate.update("UPDATE game SET deleted_at = now() WHERE steam_app_id = 28");
        LocalDate start = MONDAY.minusWeeks(1);
        LocalDate end = MONDAY.minusDays(1);
        when(steamReviewClient.getReviewWindow(7, start, end)).thenReturn(
            new SteamReviewWindow(5, start, Map.of(end, new SteamReviewCounts(3, 5))));
        when(steamReviewClient.getReviewWindow(14, start, end))
            .thenThrow(new RestClientException("중단"))
            .thenReturn(new SteamReviewWindow(0, start, Map.of()));

        // when
        steamReviewCollector.collectDue(MONDAY);
        jdbcTemplate.update("UPDATE game SET deleted_at = now() WHERE steam_app_id = 15");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM review_collection WHERE collected_through IS NOT NULL",
            Integer.class)).isOne();
        steamReviewCollector.collectDue(MONDAY);
        steamReviewCollector.collectDue(MONDAY);
        LocalDate twoWeeksLater = MONDAY.plusWeeks(2);
        when(steamReviewClient.getReviewWindow(anyLong(), any(), any())).thenAnswer(call ->
            new SteamReviewWindow(0, call.getArgument(1), Map.of()));
        steamReviewCollector.collectDue(MONDAY.plusDays(1));
        steamReviewCollector.collectDue(twoWeeksLater);

        // then
        verify(steamReviewClient).getReviewWindow(7, start, end);
        verify(steamReviewClient, times(2)).getReviewWindow(14, start, end);
        verify(steamReviewClient).getReviewWindow(7, MONDAY.plusWeeks(1),
            twoWeeksLater.minusDays(1));
        verify(steamReviewClient).getReviewWindow(14, MONDAY.plusWeeks(1),
            twoWeeksLater.minusDays(1));
        verify(steamReviewClient).getReviewWindow(8, MONDAY.minusDays(6), MONDAY);
        assertThat(jdbcTemplate.queryForList("""
            SELECT c.first_review_date FROM review_collection c JOIN game g ON g.id = c.game_id
            WHERE g.steam_app_id = 7 ORDER BY c.first_review_date
            """, LocalDate.class)).containsExactly(start, MONDAY.plusWeeks(1));
        verifyNoMoreInteractions(steamReviewClient);
        assertThat(jdbcTemplate.queryForList("""
            SELECT g.steam_app_id, h.total_count FROM review_history h JOIN game g ON g.id=h.game_id
            """)).containsExactly(Map.of("steam_app_id", 7L, "total_count", 5));
    }

    @Test
    @DisplayName("지난주 중간까지 확인한 앱은 7일을 확인한 뒤 기존 구간을 연장한다")
    void extendsPartiallyCoveredWeek() {
        // given
        gameCatalogService.applyPage(SteamAppType.GAME, List.of(new SteamApp(7, "게임")),
            Instant.now());
        LocalDate start = MONDAY.minusWeeks(1);
        LocalDate end = MONDAY.minusDays(1);
        LocalDate earlier = start.minusWeeks(2);
        jdbcTemplate.update("""
            INSERT INTO review_collection(game_id, first_review_date, collected_through)
            SELECT id, ?, ? FROM game WHERE steam_app_id = 7
            """, earlier, start.plusDays(1));
        when(steamReviewClient.getReviewWindow(7, start, end))
            .thenReturn(new SteamReviewWindow(0, start, Map.of()));

        // when
        steamReviewCollector.collectDue(MONDAY);

        // then
        assertThat(jdbcTemplate.queryForList("""
            SELECT first_review_date FROM review_collection WHERE collected_through = ?
            """, LocalDate.class, end)).containsExactly(earlier);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM review_collection",
            Integer.class)).isOne();
    }

    @Test
    @DisplayName("143개 앱의 1001일을 1000행 이하로 나눠 저장하고 재실행은 건너뛴다")
    void insertsBoundedCrossGameBatches() {
        // given
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        gameCatalogService.applyPage(SteamAppType.GAME,
            IntStream.rangeClosed(1, 143).mapToObj(i -> new SteamApp(i * 7L, "본편")).toList(),
            Instant.now());
        when(steamReviewClient.getReviewWindow(anyLong(), any(), any())).thenAnswer(call -> {
            LocalDate start = call.getArgument(1);
            LocalDate end = call.getArgument(2);
            Map<LocalDate, SteamReviewCounts> counts = new HashMap<>();
            start.datesUntil(end.plusDays(1))
                .forEach(date -> counts.put(date, new SteamReviewCounts(1, 1)));
            return new SteamReviewWindow(7, start, counts);
        });
        jdbcTemplate.execute("SELECT pg_stat_statements_reset()");

        // when
        steamReviewCollector.collectDue(MONDAY);
        steamReviewCollector.collectDue(MONDAY);

        // then
        assertThat(jdbcTemplate.queryForList("""
            SELECT count(*) FROM review_history GROUP BY xmin::text ORDER BY count(*)
            """, Integer.class)).containsExactly(7, 994);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT sum(calls) FROM pg_stat_statements WHERE query LIKE 'WITH covered AS (%'
            """, Integer.class)).isEqualTo(2);
        verify(steamReviewClient, times(143)).getReviewWindow(anyLong(), any(), any());
    }

    @Test
    @DisplayName("0건인 앱도 250개씩 진행 위치를 저장하고 이미 완료한 앱은 다시 요청하지 않는다")
    void checkpointsEmptyAppsInBoundedTransactions() {
        // given
        List<SteamApp> games = IntStream.rangeClosed(1, 251)
            .mapToObj(i -> new SteamApp(i * 7L, "게임 " + i)).toList();
        gameCatalogService.applyPage(SteamAppType.GAME, games, Instant.now());
        when(steamReviewClient.getReviewWindow(anyLong(), any(), any()))
            .thenReturn(new SteamReviewWindow(0, MONDAY.minusWeeks(1), Map.of()));

        // when
        steamReviewCollector.collectDue(MONDAY);
        steamReviewCollector.collectDue(MONDAY);

        // then
        assertThat(jdbcTemplate.queryForList("""
            SELECT count(*) FROM review_collection WHERE collected_through IS NOT NULL
            GROUP BY xmin::text ORDER BY count(*)
            """, Integer.class)).containsExactly(1, 250);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM review_history",
            Integer.class)).isZero();
        verify(steamReviewClient, times(251)).getReviewWindow(anyLong(), any(), any());
    }

    @Test
    @DisplayName("주간 리뷰 700건이 마지막 날에 몰리면 그날만 저장하고 앞선 6일도 확인 완료한다")
    void collectsWeekWithAllReviewsOnLastDay() {
        // given
        gameCatalogService.applyPage(SteamAppType.GAME, List.of(new SteamApp(7, "게임")),
            Instant.now());
        LocalDate start = MONDAY.minusWeeks(1);
        when(steamReviewClient.getReviewWindow(7, start, MONDAY.minusDays(1)))
            .thenReturn(new SteamReviewWindow(0, start, Map.of()));
        steamReviewCollector.collectDue(MONDAY);

        LocalDate nextMonday = MONDAY.plusWeeks(1);
        LocalDate sunday = nextMonday.minusDays(1);
        // 첫 페이지가 일요일 중간에서 잘려 완결된 날짜가 하나도 없는 응답이다.
        when(steamReviewClient.getReviewWindow(7, MONDAY, sunday))
            .thenReturn(new SteamReviewWindow(700, nextMonday, Map.of()));
        MONDAY.datesUntil(sunday).forEach(day -> when(steamReviewClient.getDailyCounts(7, day))
            .thenReturn(new SteamReviewCounts(0, 0)));
        when(steamReviewClient.getDailyCounts(7, sunday))
            .thenReturn(new SteamReviewCounts(520, 700));

        // when
        steamReviewCollector.collectDue(nextMonday);

        // then
        assertThat(jdbcTemplate.queryForList("SELECT review_date FROM review_history",
            LocalDate.class)).containsExactly(sunday);
        assertThat(jdbcTemplate.queryForMap(
            "SELECT positive_count, total_count FROM review_history"))
            .containsExactlyInAnyOrderEntriesOf(Map.of("positive_count", 520, "total_count", 700));
        assertThat(jdbcTemplate.queryForObject("SELECT collected_through FROM review_collection",
            LocalDate.class)).isEqualTo(sunday);
        assertThat(jdbcTemplate.queryForObject("SELECT first_review_date FROM review_collection",
            LocalDate.class)).isEqualTo(start);
    }

    @Test
    @DisplayName("429가 소진되면 앞선 앱을 저장하고 재실행은 실패한 앱의 같은 주를 조회한다")
    void resumesWeekAfterRateLimit() {
        // given
        gameCatalogService.applyPage(SteamAppType.GAME, List.of(new SteamApp(7, "게임"),
            new SteamApp(14, "다음 게임")), Instant.now());
        LocalDate start = MONDAY.minusWeeks(1);
        LocalDate end = MONDAY.minusDays(1);
        SteamReviewWindow empty = new SteamReviewWindow(0, start, Map.of());
        when(steamReviewClient.getReviewWindow(7, start, end)).thenReturn(empty);
        when(steamReviewClient.getReviewWindow(14, start, end))
            .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "제한",
                HttpHeaders.EMPTY, null, null))
            .thenReturn(empty);

        // when
        Throwable failure = catchThrowable(() -> steamReviewCollector.collectDue(MONDAY));
        steamReviewCollector.collectDue(MONDAY);

        // then
        assertThat(failure).isInstanceOf(HttpClientErrorException.TooManyRequests.class);
        verify(steamReviewClient).getReviewWindow(7, start, end);
        verify(steamReviewClient, times(2)).getReviewWindow(14, start, end);
        assertThat(jdbcTemplate.queryForList("SELECT collected_through FROM review_collection",
            LocalDate.class)).containsExactly(end, end);
    }

    @Test
    @DisplayName("이력 삽입에 실패하면 같은 청크의 확인 범위도 롤백한다")
    void rollsBackCoverageOnInsertFailure() {
        // given
        gameCatalogService.applyPage(SteamAppType.GAME, List.of(new SteamApp(7, "게임")),
            Instant.now());
        LocalDate end = MONDAY.minusDays(1);
        when(steamReviewClient.getReviewWindow(7, MONDAY.minusWeeks(1), end))
            .thenReturn(new SteamReviewWindow(1, MONDAY.minusWeeks(1),
                Map.of(end, new SteamReviewCounts(2, 1))));

        // when
        Throwable failure = catchThrowable(() -> steamReviewCollector.collectDue(MONDAY));

        // then
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM review_collection",
            Integer.class)).isZero();
    }
}
