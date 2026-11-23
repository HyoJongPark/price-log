package kr.co.pricelog.global.job;

import kr.co.pricelog.domain.purchaseoption.service.GamePurchaseOptionService;
import kr.co.pricelog.global.client.steam.SteamStoreClient;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 카탈로그 앱의 한국 상점 구매 옵션을 읽고 상품 연결·기본 선택·무료 표기를 반영한다. */
@Component
@RequiredArgsConstructor
public class SteamStoreCollector {

    private final SteamStoreClient steamStoreClient;
    private final GamePurchaseOptionService gamePurchaseOptionService;

    /** 검증된 앱 응답의 상품 목록과 무료 표기를 저장하고 같은 응답의 구매 옵션을 반환한다. */
    public SteamPurchaseOptions collectPurchaseOptions(long steamAppId) {
        SteamPurchaseOptions result = steamStoreClient.getPurchaseOptions(steamAppId);

        gamePurchaseOptionService.applyPurchaseOptions(steamAppId, result);
        return result;
    }
}
