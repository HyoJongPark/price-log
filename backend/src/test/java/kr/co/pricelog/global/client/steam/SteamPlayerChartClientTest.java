package kr.co.pricelog.global.client.steam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import kr.co.pricelog.global.client.steam.dto.SteamPlayerChart;
import kr.co.pricelog.global.client.steam.dto.SteamPlayerCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class SteamPlayerChartClientTest {

    private final ObjectNode body = new ObjectMapper().createObjectNode();
    private ArrayNode ranks;
    private MockRestServiceServer server;
    private SteamPlayerChartClient steamPlayerChartClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        steamPlayerChartClient = new SteamPlayerChartClient(builder);

        ranks = body.putObject("response").put("last_update", 1_700_000_000).putArray("ranks");
        for (int rank = 1; rank <= 100; rank++) {
            ranks.addObject().put("appid", rank + 729).put("rank", rank)
                .put("concurrent_in_game", 100 - rank).put("peak_in_game", (100 - rank) * 2);
        }
    }

    @Test
    @DisplayName("한 번의 응답에서 원천 시각과 100개 앱의 현재·최고 동접을 읽고 0명도 보존한다")
    void readsCurrentCountsFromOneChart() {
        // given
        respond();

        // when
        SteamPlayerChart chart = steamPlayerChartClient.getCurrentChart();

        // then
        assertThat(chart.observedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000));
        assertThat(chart.entries()).hasSize(100).contains(
            new SteamPlayerCount(730, 1, 99, 198), new SteamPlayerCount(829, 100, 0, 0));
        server.verify();
    }

    @Test
    @DisplayName("99개만 받은 판은 완성된 차트로 처리하지 않는다")
    void rejectsIncompleteChart() {
        // given
        ranks.remove(99);
        respond();

        // when
        Throwable failure = catchThrowable(steamPlayerChartClient::getCurrentChart);

        // then
        assertThat(failure).isInstanceOf(RestClientException.class).hasMessageContaining("100개");
    }

    @ParameterizedTest
    @ValueSource(strings = {"appid", "rank"})
    @DisplayName("같은 앱이나 순위가 반복되면 판 전체를 거절한다")
    void rejectsDuplicateEntry(String field) {
        // given
        ((ObjectNode) ranks.get(99)).set(field, ranks.get(0).get(field));
        respond();

        // when
        Throwable failure = catchThrowable(steamPlayerChartClient::getCurrentChart);

        // then
        assertThat(failure).isInstanceOf(RestClientException.class).hasMessageContaining("중복");
    }

    @ParameterizedTest
    @ValueSource(strings = {"concurrent_in_game", "peak_in_game"})
    @DisplayName("현재 또는 최고 동접이 누락되면 해당 필드를 밝히고 판 전체를 거절한다")
    void rejectsMissingCount(String field) {
        // given
        ((ObjectNode) ranks.get(0)).remove(field);
        respond();

        // when
        Throwable failure = catchThrowable(steamPlayerChartClient::getCurrentChart);

        // then
        assertThat(failure).isInstanceOf(RestClientException.class).hasMessageContaining(field);
    }

    private void respond() {
        server.expect(requestTo(
            "https://api.steampowered.com/ISteamChartsService/GetGamesByConcurrentPlayers/v1/"))
            .andRespond(withSuccess(body.toString(), MediaType.APPLICATION_JSON));
    }
}
