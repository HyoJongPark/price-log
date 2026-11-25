package kr.co.pricelog.global.client.steam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import kr.co.pricelog.domain.game.enums.StoreVisibility;
import kr.co.pricelog.global.client.steam.constants.SteamApiConstants;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOptions;
import kr.co.pricelog.global.client.steam.dto.SteamStoreItem;
import kr.co.pricelog.global.client.steam.dto.SteamStoreResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Steam 한국 상점에서 구매할 수 있는 상품을 조회한다. */
@Component
public class SteamStoreClient {

    private static final int APP_ITEM_TYPE = 0;
    private static final int SUCCESS = 1;
    private static final int ACCESS_DENIED = 15;

    private static final String PURCHASE_OPTIONS_QUERY = """
        {"ids":[%s],"context":{"country_code":"KR","language":"koreana"},
         "data_request":{"include_all_purchase_options":true}}
        """;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /** 공개 상점 API에 연결한다. */
    public SteamStoreClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.baseUrl(SteamApiConstants.BASE_URL).build();
    }

    /**
     * 한국 상점에서 구매 옵션 또는 미노출 상태를 확인한 앱을 요청 순서대로 반환한다.
     * 입력은 카탈로그의 game.steam_app_id에서 읽으며, DB 제약이 ID 범위와 고유성을 보장한다.
     */
    @Retry(name = "steamRequest")
    public Map<Long, SteamPurchaseOptions> getPurchaseOptions(List<Long> steamAppIds) {
        if (steamAppIds.isEmpty()) {
            return Map.of();
        }

        try {
            return requestOptions(steamAppIds);
        } catch (HttpClientErrorException failure) {
            if (failure.getStatusCode() != HttpStatus.URI_TOO_LONG || steamAppIds.size() == 1) {
                throw failure;
            }
            // 앱 번호의 자릿수에 따라 같은 묶음 크기도 URL 길이 제한을 넘을 수 있다.
            int middle = steamAppIds.size() / 2;
            Map<Long, SteamPurchaseOptions> confirmed = new LinkedHashMap<>(
                getPurchaseOptions(steamAppIds.subList(0, middle)));
            confirmed.putAll(getPurchaseOptions(steamAppIds.subList(middle, steamAppIds.size())));
            return Collections.unmodifiableMap(confirmed);
        }
    }

    private Map<Long, SteamPurchaseOptions> requestOptions(List<Long> steamAppIds) {
        String ids = steamAppIds.stream().map(id -> "{\"appid\":" + id + "}")
            .collect(Collectors.joining(","));
        SteamStoreResponse result = restClient.get()
            .uri("/IStoreBrowseService/GetItems/v1/?input_json={query}",
                PURCHASE_OPTIONS_QUERY.formatted(ids))
            .retrieve()
            .body(SteamStoreResponse.class);

        if (result == null || result.response() == null || result.response().items() == null) {
            throw new RestClientException("Steam 상점의 구매 옵션 조회 결과가 누락되었습니다");
        }
        Map<Long, List<JsonNode>> itemsById = result.response().items().stream()
            .filter(item -> item != null && item.path("id").isIntegralNumber()
                && item.path("id").canConvertToLong())
            .collect(Collectors.groupingBy(item -> item.get("id").longValue()));

        Map<Long, SteamPurchaseOptions> confirmed = new LinkedHashMap<>();
        for (long steamAppId : steamAppIds) {
            readPurchaseOptions(itemsById.get(steamAppId))
                .ifPresent(options -> confirmed.put(steamAppId, options));
        }
        return Collections.unmodifiableMap(confirmed);
    }

    private Optional<SteamPurchaseOptions> readPurchaseOptions(List<JsonNode> items) {
        // TODO: 로깅 도입 시 제외된 앱 ID와 Steam 결과 코드·누락·중복·해석 실패 사유를 기록한다.
        if (items == null || items.size() != 1) {
            return Optional.empty();
        }

        try {
            SteamStoreItem item = objectMapper.treeToValue(items.getFirst(), SteamStoreItem.class);
            return confirmedPurchaseOptions(item);
        } catch (JsonProcessingException | RestClientException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<SteamPurchaseOptions> confirmedPurchaseOptions(SteamStoreItem item) {
        if (!Objects.equals(item.itemType(), APP_ITEM_TYPE)) {
            return Optional.empty();
        }

        // 국가 제한 앱에서도 visible=false가 올 수 있어 명시된 사유를 먼저 확인한다.
        boolean regionRestricted = Boolean.TRUE.equals(item.regionRestricted());
        boolean hidden = Boolean.FALSE.equals(item.visible());
        if (item.success() == ACCESS_DENIED && (regionRestricted || hidden)) {
            StoreVisibility visibility = regionRestricted
                ? StoreVisibility.REGION_RESTRICTED : StoreVisibility.HIDDEN;
            return Optional.of(new SteamPurchaseOptions(List.of(), null, null, visibility));
        }
        if (item.success() == SUCCESS && !regionRestricted && !hidden) {
            return Optional.of(new SteamPurchaseOptions(item.options(), item.bestPurchaseOption(),
                item.freeToPlay()));
        }
        return Optional.empty();
    }
}
