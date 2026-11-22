package kr.co.pricelog.global.client.steam;

import java.util.List;
import java.util.Locale;
import kr.co.pricelog.global.client.steam.constants.SteamApiConstants;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOption;
import kr.co.pricelog.global.client.steam.dto.SteamStoreItem;
import kr.co.pricelog.global.client.steam.dto.SteamStoreResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Steam 한국 상점에서 구매할 수 있는 상품을 조회한다. */
@Component
public class SteamStoreClient {

    private static final String PURCHASE_OPTIONS_QUERY = """
        {"ids":[{"appid":%d}],"context":{"country_code":"KR","language":"koreana"},
         "data_request":{"include_all_purchase_options":true}}
        """;

    private final RestClient restClient;

    /** 공개 상점 API에 연결한다. */
    public SteamStoreClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl(SteamApiConstants.BASE_URL).build();
    }

    /** 본편의 Steam 앱 ID로 한국 상점의 패키지와 번들 구매 옵션을 읽는다. */
    public List<SteamPurchaseOption> getPurchaseOptions(long steamAppId) {
        if (steamAppId <= 0 || steamAppId > SteamApiConstants.MAX_APP_ID) {
            throw new IllegalArgumentException("Steam 앱 ID가 범위를 벗어났습니다");
        }
        String query = String.format(Locale.ROOT, PURCHASE_OPTIONS_QUERY, steamAppId);
        SteamStoreResponse result = restClient.get()
            .uri("/IStoreBrowseService/GetItems/v1/?input_json={query}", query)
            .retrieve()
            .body(SteamStoreResponse.class);

        if (result == null || result.response() == null || result.response().items() == null
            || result.response().items().size() != 1) {
            throw new RestClientException("Steam 상점의 단건 조회 결과가 누락되거나 중복되었습니다");
        }
        SteamStoreItem item = result.response().items().getFirst();
        if (item == null || !item.isAppResultFor(steamAppId)) {
            throw new RestClientException("Steam 상점이 요청한 앱의 구매 옵션을 확인하지 못했습니다");
        }
        return item.options();
    }
}
