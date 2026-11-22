package kr.co.pricelog.global.job;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import kr.co.pricelog.domain.collection.service.SteamCatalogCollectionService;
import kr.co.pricelog.domain.game.enums.SteamAppType;
import kr.co.pricelog.global.client.steam.SteamAppListClient;
import kr.co.pricelog.global.client.steam.dto.SteamAppPage;
import org.springframework.stereotype.Component;

/** Steam 본편과 DLC를 페이지 단위로 수집한다. */
@Component
public class SteamCatalogCollector {

    private static final int PAGE_SIZE = 10_000;

    private final SteamAppListClient steamAppListClient;
    private final SteamCatalogCollectionService steamCatalogCollectionService;

    /** 페이지 조회와 저장에 필요한 의존성을 받는다. */
    public SteamCatalogCollector(SteamAppListClient steamAppListClient,
                                SteamCatalogCollectionService steamCatalogCollectionService) {
        this.steamAppListClient = steamAppListClient;
        this.steamCatalogCollectionService = steamCatalogCollectionService;
    }

    /** 같은 예정 시각으로 재호출하면 각 목록의 마지막 커밋 다음부터 수집한다. */
    public void collectAll(Instant scheduledAt) {
        for (SteamAppType type : List.of(SteamAppType.GAME, SteamAppType.DLC)) {
            collectList(type, scheduledAt);
        }
    }

    private void collectList(SteamAppType type, Instant scheduledAt) {
        OptionalLong nextCursor = steamCatalogCollectionService.resumeCursor(type, scheduledAt);

        try {
            while (nextCursor.isPresent()) {
                SteamAppPage page = steamAppListClient.getPage(type, nextCursor.getAsLong(),
                    PAGE_SIZE);
                nextCursor = steamCatalogCollectionService.savePageAndAdvanceCursor(
                    type, scheduledAt, page, Instant.now());
            }
        } catch (RuntimeException collectionFailure) {
            steamCatalogCollectionService.markFailed(type, scheduledAt);
            throw collectionFailure;
        }
    }
}
