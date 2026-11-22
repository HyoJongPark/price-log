package kr.co.pricelog.global.client.steam;

import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import kr.co.pricelog.global.client.steam.dto.SteamApp;
import kr.co.pricelog.global.client.steam.dto.SteamAppList;
import kr.co.pricelog.global.client.steam.dto.SteamAppListResponse;
import kr.co.pricelog.global.client.steam.dto.SteamAppPage;
import kr.co.pricelog.global.client.steam.enums.SteamAppType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Steam Web API에서 본편 또는 DLC 목록을 한 페이지씩 조회한다. */
@Component
public class SteamAppListClient {

    private static final int MAX_PAGE_SIZE = 50_000;
    private static final String PAGE_QUERY =
        "{\"include_games\":%b,\"include_dlc\":%b,\"last_appid\":%d,\"max_results\":%d}";

    private final RestClient restClient;
    private final String apiKey;

    /** 목록 API에서만 서버의 Web API 키로 인증한다. */
    public SteamAppListClient(RestClient.Builder restClientBuilder,
                              @Value("${STEAM_WEB_API_KEY:}") String apiKey) {
        this.restClient = restClientBuilder.baseUrl("https://api.steampowered.com").build();
        this.apiKey = apiKey;
    }

    /** 첫 페이지는 0, 다음 페이지는 직전 응답의 nextCursor로 요청한다. */
    public SteamAppPage getPage(SteamAppType type, long lastAppId, int pageSize) {
        Objects.requireNonNull(type);
        if (lastAppId < 0 || lastAppId > SteamApp.MAX_APP_ID
            || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Steam 목록 커서 또는 페이지 크기가 범위를 벗어났습니다");
        }
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Steam 목록 조회에는 STEAM_WEB_API_KEY가 필요합니다");
        }

        String query = String.format(Locale.ROOT, PAGE_QUERY,
            type == SteamAppType.GAME, type == SteamAppType.DLC, lastAppId, pageSize);
        SteamAppListResponse result = restClient.get()
            .uri("/IStoreService/GetAppList/v1/?input_json={query}", query)
            .header("x-webapi-key", apiKey)
            .retrieve()
            .body(SteamAppListResponse.class);

        if (result == null || result.response() == null) {
            throw new RestClientException("Steam 목록 응답이 누락되었습니다");
        }
        SteamAppList list = result.response();
        return new SteamAppPage(list.apps(), nextCursor(list, lastAppId));
    }

    private OptionalLong nextCursor(SteamAppList list, long previousCursor) {
        if (!list.hasMore()) {
            return OptionalLong.empty();
        }

        Long cursor = list.lastAppId();
        if (cursor == null || cursor <= previousCursor) {
            throw new RestClientException("Steam 목록의 다음 페이지 커서가 전진하지 않습니다");
        }
        if (list.apps().isEmpty() || cursor != list.apps().getLast().appId()) {
            throw new RestClientException("Steam 목록의 커서와 마지막 앱 ID가 일치하지 않습니다");
        }
        return OptionalLong.of(cursor);
    }
}
