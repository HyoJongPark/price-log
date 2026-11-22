package kr.co.pricelog.global.client.steam.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import kr.co.pricelog.global.client.steam.enums.SteamPurchaseOptionType;

/** 한국 상점에 표시된 구매 옵션의 종류, 해당 종류의 Steam ID, 표시 이름. */
public record SteamPurchaseOption(SteamPurchaseOptionType type, long steamId, String name) {

    /** 종류와 ID로 상품을 식별하며, 상점에서 제공하지 않은 이름은 null로 둔다. */
    public SteamPurchaseOption {
        if (type == null || steamId <= 0) {
            throw new IllegalArgumentException("Steam 구매 옵션의 종류 또는 ID가 유효하지 않습니다");
        }
    }

    @JsonCreator
    static SteamPurchaseOption fromStore(@JsonProperty("packageid") Long packageId,
                                         @JsonProperty("bundleid") Long bundleId,
                                         @JsonProperty("purchase_option_name") String name) {
        if ((packageId == null) == (bundleId == null)) {
            throw new IllegalArgumentException("Steam 구매 옵션에 패키지 또는 번들 ID 하나가 필요합니다");
        }
        return packageId != null
            ? new SteamPurchaseOption(SteamPurchaseOptionType.PACKAGE, packageId, name)
            : new SteamPurchaseOption(SteamPurchaseOptionType.BUNDLE, bundleId, name);
    }
}
