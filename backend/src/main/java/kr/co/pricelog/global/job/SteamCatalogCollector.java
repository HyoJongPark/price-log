package kr.co.pricelog.global.job;

import java.time.Instant;
import kr.co.pricelog.domain.game.service.GameCatalogService;
import kr.co.pricelog.global.client.steam.SteamAppListClient;
import kr.co.pricelog.global.client.steam.dto.SteamAppPage;
import kr.co.pricelog.global.client.steam.enums.SteamAppType;
import org.springframework.stereotype.Component;

/** Steam에서 한 페이지를 읽고 카탈로그 갱신을 요청한다. */
@Component
public class SteamCatalogCollector {

    private final SteamAppListClient steamAppListClient;
    private final GameCatalogService gameCatalogService;

    /** 페이지 조회와 저장에 필요한 의존성을 받는다. */
    public SteamCatalogCollector(SteamAppListClient steamAppListClient,
                                GameCatalogService gameCatalogService) {
        this.steamAppListClient = steamAppListClient;
        this.gameCatalogService = gameCatalogService;
    }

    /** 페이지 반영이 끝나면 다음 요청에 사용할 커서를 포함해 반환한다. */
    public SteamAppPage collectPage(SteamAppType type, long cursor, int pageSize) {
        SteamAppPage page = steamAppListClient.getPage(type, cursor, pageSize);
        if (page.apps().isEmpty()) {
            return page;
        }

        gameCatalogService.applyPage(type, page.apps(), Instant.now());
        return page;
    }
}
