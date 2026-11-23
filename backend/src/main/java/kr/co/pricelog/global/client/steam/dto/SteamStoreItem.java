package kr.co.pricelog.global.client.steam.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 상점 조회 결과의 대상과 그 상품에서 선택할 수 있는 구매 옵션. */
public record SteamStoreItem(long id, int success, @JsonProperty("item_type") Integer itemType,
                             @JsonProperty("is_free") Boolean freeToPlay,
                             @JsonProperty("purchase_options") List<SteamPurchaseOption> options,
                             @JsonProperty("best_purchase_option")
                             SteamPurchaseOption bestPurchaseOption) {

    private static final int APP_ITEM_TYPE = 0;
    private static final int SUCCESS = 1;

    /** 옵션이 없는 상품에서는 Steam이 purchase_options를 생략할 수 있다. */
    public SteamStoreItem {
        options = options == null ? Collections.emptyList() : List.copyOf(options);
    }

    /** 요청한 앱 자체의 성공 응답인지 확인한다. */
    public boolean isAppResultFor(long steamAppId) {
        return success == SUCCESS && id == steamAppId && Objects.equals(itemType, APP_ITEM_TYPE);
    }
}
