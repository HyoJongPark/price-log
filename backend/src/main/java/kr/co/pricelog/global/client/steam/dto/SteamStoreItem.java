package kr.co.pricelog.global.client.steam.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.List;

/** 상점 조회 결과의 대상과 그 상품에서 선택할 수 있는 구매 옵션. */
public record SteamStoreItem(long id, int success, @JsonProperty("item_type") Integer itemType,
                             @JsonProperty("is_free") Boolean freeToPlay,
                             JsonNode assets,
                             @JsonProperty("tagids") JsonNode tagIds,
                             JsonNode release,
                             @JsonProperty("supported_languages") JsonNode supportedLanguages,
                             @JsonProperty("basic_info") JsonNode basicInfo,
                             @JsonProperty("purchase_options") List<ObjectNode> options,
                             @JsonProperty("visible") Boolean visible,
                             @JsonProperty("unvailable_for_country_restriction")
                             Boolean regionRestricted,
                             @JsonProperty("best_purchase_option")
                             SteamPurchaseOption bestPurchaseOption) {

    /** 옵션이 없는 상품에서는 Steam이 purchase_options를 생략할 수 있다. */
    public SteamStoreItem {
        options = options == null ? Collections.emptyList() : List.copyOf(options);
    }
}
