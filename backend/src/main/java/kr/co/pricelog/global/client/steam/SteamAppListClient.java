package kr.co.pricelog.global.client.steam;

import io.github.resilience4j.retry.annotation.Retry;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import kr.co.pricelog.domain.game.enums.SteamAppType;
import kr.co.pricelog.global.client.steam.constants.SteamApiConstants;
import kr.co.pricelog.global.client.steam.dto.SteamAppList;
import kr.co.pricelog.global.client.steam.dto.SteamAppListResponse;
import kr.co.pricelog.global.client.steam.dto.SteamAppPage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Steam Web API에서 본편 또는 DLC 목록을 한 페이지씩 조회한다. */
@Component
public class SteamAppListClient {

    private static final int MAX_PAGE_SIZE = 50_000;
    private static final String PAGE_QUERY =
        "{\"include_games\":%b,\"include_dlc\":%b,\"last_appid\":%d,\"max_results\":%d%s}";

    private final RestClient restClient;
    private final String apiKey;

    /** 목록 API에서만 서버의 Web API 키로 인증한다. */
    public SteamAppListClient(RestClient.Builder restClientBuilder,
                              @Value("${STEAM_WEB_API_KEY}") String apiKey) {
        Assert.hasText(apiKey, "STEAM_WEB_API_KEY에 빈 값을 사용할 수 없습니다");
        this.restClient = restClientBuilder.baseUrl(SteamApiConstants.BASE_URL).build();
        this.apiKey = apiKey;
    }

    /** 첫 페이지는 0, 다음 페이지는 직전 응답의 nextCursor로 요청한다. */
    @Retry(name = "steamRequest")
    public SteamAppPage getPage(SteamAppType type, long lastAppId, int pageSize) {
        return requestPage(type, lastAppId, pageSize, "");
    }

    /** 시간 필터는 같은 목록의 모든 페이지에서 동일한 Unix 초를 사용한다. */
    @Retry(name = "steamRequest")
    public SteamAppPage getChangedPage(SteamAppType type, long lastAppId, int pageSize,
                                       Instant modifiedSince) {
        return requestPage(type, lastAppId, pageSize,
            ",\"if_modified_since\":" + modifiedSince.getEpochSecond());
    }

    private SteamAppPage requestPage(SteamAppType type, long lastAppId, int pageSize,
                                     String timeFilter) {
        Objects.requireNonNull(type);
        if (type == SteamAppType.UNKNOWN) {
            throw new IllegalArgumentException("미확인 앱은 목록 수집 대상이 아닙니다");
        }
        if (lastAppId < 0 || lastAppId > SteamApiConstants.MAX_APP_ID
            || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Steam 목록 커서 또는 페이지 크기가 범위를 벗어났습니다");
        }

        String query = String.format(Locale.ROOT, PAGE_QUERY,
            type == SteamAppType.GAME, type == SteamAppType.DLC, lastAppId, pageSize, timeFilter);
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
        if (list.apps().stream().anyMatch(app -> app.appId() <= previousCursor)) {
            throw new RestClientException("Steam 목록에 요청 커서 이후의 앱이 아닌 항목이 있습니다");
        }
        if (!list.hasMore()) {
            return OptionalLong.empty();
        }

        Long cursor = list.lastAppId();
        if (cursor == null) {
            throw new RestClientException("Steam 목록의 다음 페이지 커서가 누락되었습니다");
        }
        if (list.apps().isEmpty() || cursor != list.apps().getLast().appId()) {
            throw new RestClientException("Steam 목록의 커서와 마지막 앱 ID가 일치하지 않습니다");
        }
        return OptionalLong.of(cursor);
    }
}
