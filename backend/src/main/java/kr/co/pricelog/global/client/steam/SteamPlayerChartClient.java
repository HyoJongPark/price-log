package kr.co.pricelog.global.client.steam;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kr.co.pricelog.global.client.steam.constants.SteamApiConstants;
import kr.co.pricelog.global.client.steam.dto.SteamPlayerChart;
import kr.co.pricelog.global.client.steam.dto.SteamPlayerCount;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Steam 전 세계 현재 동접 차트를 한 번에 읽는다. */
@Component
public class SteamPlayerChartClient {

    private static final int CHART_SIZE = 100;

    private final RestClient restClient;

    /** 공개 차트는 인증 키 없이 조회한다. */
    public SteamPlayerChartClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl(SteamApiConstants.BASE_URL).build();
    }

    /** 누락된 순위나 중복 앱이 있으면 판 전체를 미수집으로 남긴다. */
    @Retry(name = "steamRequest")
    public SteamPlayerChart getCurrentChart() {
        JsonNode body = restClient.get().uri("/ISteamChartsService/GetGamesByConcurrentPlayers/v1/")
            .retrieve().body(JsonNode.class);
        if (body == null) {
            throw new RestClientException("Steam 동접 차트 응답이 비어 있습니다");
        }

        JsonNode response = body.path("response");
        Instant observedAt = Instant.ofEpochSecond(number(response, "last_update", 1,
            Instant.now().getEpochSecond()));
        JsonNode ranks = response.path("ranks");
        if (!ranks.isArray() || ranks.size() != CHART_SIZE) {
            throw new RestClientException("Steam 동접 차트 100개 순위를 확인할 수 없습니다");
        }

        List<SteamPlayerCount> entries = new ArrayList<>(CHART_SIZE);
        Set<Long> appIds = new HashSet<>();
        Set<Integer> positions = new HashSet<>();
        for (JsonNode row : ranks) {
            long appId = number(row, "appid", 1, SteamApiConstants.MAX_APP_ID);
            int rank = (int) number(row, "rank", 1, CHART_SIZE);
            int players = (int) number(row, "concurrent_in_game", 0, Integer.MAX_VALUE);
            int peakPlayers = (int) number(row, "peak_in_game", 0, Integer.MAX_VALUE);
            if (!appIds.add(appId) || !positions.add(rank)) {
                throw new RestClientException("Steam 동접 차트에 중복된 앱 또는 순위가 있습니다");
            }
            entries.add(new SteamPlayerCount(appId, rank, players, peakPlayers));
        }
        return new SteamPlayerChart(observedAt, List.copyOf(entries));
    }

    private static long number(JsonNode row, String field, long min, long max) {
        JsonNode value = row.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()
            || value.longValue() < min || value.longValue() > max) {
            throw new RestClientException("Steam 동접 차트의 숫자 필드를 확인할 수 없습니다: " + field);
        }
        return value.longValue();
    }
}
