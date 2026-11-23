package kr.co.pricelog.global.client.steam.dto;

import java.util.List;
import org.springframework.web.client.RestClientException;

/** 한국 상점의 전체 구매 옵션과 Steam이 지정한 기본 옵션. 지정이 없으면 기본 옵션은 null이다. */
public record SteamPurchaseOptions(List<SteamPurchaseOption> purchaseOptions,
                                   SteamPurchaseOption bestPurchaseOption, Boolean freeToPlay) {

    /** 기본 옵션의 종류와 ID를 구매 목록에 대응시켜 해당 목록의 상품을 반환한다. */
    public SteamPurchaseOptions {
        purchaseOptions = List.copyOf(purchaseOptions);
        if (bestPurchaseOption != null) {
            bestPurchaseOption = findListedOption(purchaseOptions, bestPurchaseOption);
        }
    }

    private static SteamPurchaseOption findListedOption(List<SteamPurchaseOption> purchaseOptions,
                                                       SteamPurchaseOption bestPurchaseOption) {
        List<SteamPurchaseOption> matches = purchaseOptions.stream()
            .filter(option -> option.type() == bestPurchaseOption.type()
                && option.steamId() == bestPurchaseOption.steamId())
            .toList();
        if (matches.size() != 1) {
            throw new RestClientException("Steam 기본 구매 옵션을 구매 목록의 한 상품에 대응할 수 없습니다");
        }
        return matches.getFirst();
    }
}
