package kr.co.pricelog.global.client.steam.dto;

import java.util.List;
import kr.co.pricelog.domain.game.enums.StoreVisibility;
import kr.co.pricelog.global.client.steam.SteamResponseException;

/** 한국 상점의 전체 구매 옵션과 Steam이 지정한 기본 옵션. 지정이 없으면 기본 옵션은 null이다. */
public record SteamPurchaseOptions(List<SteamPurchaseOption> purchaseOptions,
                                   SteamPurchaseOption bestPurchaseOption, Boolean freeToPlay,
                                   StoreVisibility storeVisibility, String imageUrl,
                                   String description, SteamRelease release,
                                   String koreanSupportText, List<Long> tagIds) {

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
            throw new SteamResponseException("대표 구매 옵션 %s %d, 목록에서 %d건 일치"
                .formatted(bestPurchaseOption.type(), bestPurchaseOption.steamId(),
                    matches.size()));
        }
        return matches.getFirst();
    }
}
