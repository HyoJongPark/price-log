package kr.co.pricelog.global.job;

import kr.co.pricelog.domain.game.service.GameCatalogService;
import kr.co.pricelog.global.client.steam.SteamStoreClient;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOptions;
import org.springframework.stereotype.Component;

/** 카탈로그 앱의 한국 상점 구매 옵션을 읽고 게임의 무료 표기를 반영한다. */
@Component
public class SteamStoreCollector {

    private final SteamStoreClient steamStoreClient;
    private final GameCatalogService gameCatalogService;

    /** 상점 조회와 카탈로그 저장에 필요한 의존성을 받는다. */
    public SteamStoreCollector(SteamStoreClient steamStoreClient,
                              GameCatalogService gameCatalogService) {
        this.steamStoreClient = steamStoreClient;
        this.gameCatalogService = gameCatalogService;
    }

    /** 검증된 앱 응답의 무료 표기를 저장하고 같은 응답의 구매 옵션을 반환한다. */
    public SteamPurchaseOptions collectPurchaseOptions(long steamAppId) {
        SteamPurchaseOptions result = steamStoreClient.getPurchaseOptions(steamAppId);

        gameCatalogService.updateFreeToPlay(steamAppId, result.freeToPlay());
        return result;
    }
}
