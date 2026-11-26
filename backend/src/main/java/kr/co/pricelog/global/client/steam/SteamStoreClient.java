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
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.co.pricelog.domain.game.enums.StoreVisibility;
import kr.co.pricelog.global.client.steam.constants.SteamApiConstants;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOptions;
import kr.co.pricelog.global.client.steam.dto.SteamStoreItem;
import kr.co.pricelog.global.client.steam.dto.SteamStoreResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Steam 한국 상점에서 구매할 수 있는 상품을 조회한다. */
@Slf4j
@Component
public class SteamStoreClient {

    private static final int APP_ITEM_TYPE = 0;
    private static final int SUCCESS = 1;
    private static final int ACCESS_DENIED = 15;

    private static final String ASSET_BASE_URL =
        "https://shared.fastly.steamstatic.com/store_item_assets/";
    private static final String FILENAME = "${FILENAME}";

    private static final String PURCHASE_OPTIONS_QUERY = """
        {"ids":[{ids}],"context":{"country_code":"KR","language":"koreana"},
         "data_request":{
          "include_all_purchase_options":true,"include_assets":true,"include_basic_info":true,
          "include_release":true,"include_supported_languages":true,"include_tag_count":%d}}
        """.formatted(SteamGameDetailsMapper.TAG_COUNT);

    private static final String TAGS_QUERY = """
        {"ids":[{ids}],"context":{"country_code":"KR","language":"koreana"},
         "data_request":{"include_tag_count":%d}}
        """.formatted(SteamGameDetailsMapper.TAG_COUNT);

    private static final String DLC_PARENTS_QUERY = """
        {"ids":[{ids}],"context":{"country_code":"KR","language":"koreana"},
         "data_request":{"include_related_items":true}}
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
        return getItems(steamAppIds, PURCHASE_OPTIONS_QUERY, this::readPurchaseOptions);
    }

    /** 기존 카탈로그를 보완할 때에는 구매 옵션 없이 태그만 묶어서 읽는다. */
    @Retry(name = "steamRequest")
    public Map<Long, List<Long>> getGameTags(List<Long> steamAppIds) {
        return getItems(steamAppIds, TAGS_QUERY, item -> {
            if (!isVisibleApp(item)) {
                return Optional.empty();
            }
            return Optional.ofNullable(SteamGameDetailsMapper.tagIds(item.get("tagids")));
        });
    }

    /** 한국 상점에서 확인된 DLC 앱 번호와 부모 앱 번호를 반환한다. */
    @Retry(name = "steamRequest")
    public Map<Long, Long> getDlcParents(List<Long> steamAppIds) {
        return getItems(steamAppIds, DLC_PARENTS_QUERY, SteamStoreClient::readParentAppId);
    }

    private <T> Map<Long, T> getItems(List<Long> steamAppIds, String query,
                                      Function<JsonNode, Optional<T>> readItem) {
        if (steamAppIds.isEmpty()) {
            return Map.of();
        }

        try {
            return requestItems(steamAppIds, query, readItem);
        } catch (HttpClientErrorException failure) {
            if (failure.getStatusCode() != HttpStatus.URI_TOO_LONG || steamAppIds.size() == 1) {
                throw failure;
            }
            // 앱 번호의 자릿수에 따라 같은 묶음 크기도 URL 길이 제한을 넘을 수 있다.
            int middle = steamAppIds.size() / 2;
            Map<Long, T> confirmed = new LinkedHashMap<>(
                getItems(steamAppIds.subList(0, middle), query, readItem));
            confirmed.putAll(getItems(steamAppIds.subList(middle, steamAppIds.size()), query,
                readItem));
            return Collections.unmodifiableMap(confirmed);
        }
    }

    private <T> Map<Long, T> requestItems(List<Long> steamAppIds, String query,
                                          Function<JsonNode, Optional<T>> readItem) {
        String ids = steamAppIds.stream().map(id -> "{\"appid\":" + id + "}")
            .collect(Collectors.joining(","));
        SteamStoreResponse result = restClient.get()
            .uri("/IStoreBrowseService/GetItems/v1/?input_json={query}",
                query.replace("{ids}", ids))
            .retrieve()
            .body(SteamStoreResponse.class);

        if (result == null || result.response() == null || result.response().items() == null) {
            throw new SteamResponseException("Steam 상점의 앱 조회 결과가 누락되었습니다");
        }
        Map<Long, List<JsonNode>> itemsById = result.response().items().stream()
            .filter(item -> item != null && item.path("id").isIntegralNumber()
                && item.path("id").canConvertToLong())
            .collect(Collectors.groupingBy(item -> item.get("id").longValue()));

        Map<Long, T> confirmed = new LinkedHashMap<>();
        for (long steamAppId : steamAppIds) {
            List<JsonNode> items = itemsById.get(steamAppId);
            if (items == null || items.size() != 1) {
                continue;
            }

            JsonNode item = items.getFirst();
            if (hasValidVisibilityFields(item)) {
                readItem.apply(item)
                    .ifPresent(value -> confirmed.put(steamAppId, value));
            }
        }
        return Collections.unmodifiableMap(confirmed);
    }

    /** Steam은 노출 플래그를 생략할 수 있다. 명시된 값이 깨졌으면 해당 앱만 건너뛴다. */
    private static boolean hasValidVisibilityFields(JsonNode item) {
        JsonNode visible = item.path("visible");
        JsonNode restricted = item.path("unvailable_for_country_restriction");
        return (visible.isMissingNode() || visible.isBoolean())
            && (restricted.isMissingNode() || restricted.isBoolean());
    }

    private static boolean isVisibleApp(JsonNode item) {
        return item.path("item_type").asInt(-1) == APP_ITEM_TYPE
            && item.path("success").asInt() == SUCCESS && item.path("visible").asBoolean(true)
            && !item.path("unvailable_for_country_restriction").asBoolean();
    }

    private static Optional<Long> readParentAppId(JsonNode item) {
        JsonNode parent = item.path("related_items").path("parent_appid");
        if (!isVisibleApp(item) || !parent.isIntegralNumber() || !parent.canConvertToLong()
            || parent.longValue() <= 0) {
            return Optional.empty();
        }
        return Optional.of(parent.longValue());
    }

    private Optional<SteamPurchaseOptions> readPurchaseOptions(JsonNode rawItem) {
        try {
            SteamStoreItem item = objectMapper.treeToValue(rawItem, SteamStoreItem.class);
            return confirmedPurchaseOptions(item);
        } catch (JsonProcessingException | RestClientException failure) {
            log.warn("상점 응답 해석 실패. 앱 {}, 사유 {}", rawItem.path("id").asLong(),
                SteamResponseException.reasonForLog(failure));
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
            return Optional.of(new SteamPurchaseOptions(List.of(), null, null, visibility,
                null, null, null, null, null));
        }
        if (item.success() == SUCCESS && !regionRestricted && !hidden) {
            String description = item.basicInfo() == null ? null
                : SteamGameDetailsMapper.textOrNull(item.basicInfo().path("short_description"));
            return Optional.of(new SteamPurchaseOptions(item.options(), item.bestPurchaseOption(),
                item.freeToPlay(), StoreVisibility.VISIBLE, headerImageUrl(item.assets()),
                description, SteamGameDetailsMapper.release(item.release()),
                SteamGameDetailsMapper.koreanSupport(item.supportedLanguages()),
                SteamGameDetailsMapper.tagIds(item.tagIds())));
        }
        return Optional.empty();
    }

    /** 상점이 제공한 파일명에는 버전별 하위 경로가 포함될 수 있다. */
    private static String headerImageUrl(JsonNode assets) {
        if (assets == null) {
            return null;
        }
        String format = assets.path("asset_url_format").textValue();
        String header = assets.path("header").textValue();
        if (!StringUtils.hasText(header)) {
            header = assets.path("header_2x").textValue();
        }
        if (format == null || !format.contains(FILENAME) || !StringUtils.hasText(header)) {
            return null;
        }

        return ASSET_BASE_URL + format.replace(FILENAME, header);
    }
}
