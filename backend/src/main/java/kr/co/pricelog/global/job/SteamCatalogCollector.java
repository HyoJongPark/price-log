package kr.co.pricelog.global.job;

import io.github.resilience4j.retry.annotation.Retry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import kr.co.pricelog.domain.collection.service.SteamCatalogCollectionService;
import kr.co.pricelog.domain.game.enums.SteamAppType;
import kr.co.pricelog.global.client.steam.SteamAppListClient;
import kr.co.pricelog.global.client.steam.dto.SteamAppPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Steam 본편과 DLC를 페이지 단위로 수집한다. */
@Component
@RequiredArgsConstructor
public class SteamCatalogCollector {

    private static final int PAGE_SIZE = 10_000;

    private final SteamAppListClient steamAppListClient;
    private final SteamDlcParentCollector steamDlcParentCollector;
    private final SteamCatalogCollectionService steamCatalogCollectionService;

    /** 최초 적재가 남아 있을 때만 새 목록을 읽거나 저장한 커서에서 이어간다. */
    @Retry(name = "steamCollection")
    public void initialize(Instant now) {
        steamCatalogCollectionService.pendingCatalogSlot(now).ifPresent(this::collectAll);
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
                Map<Long, Long> parents = type == SteamAppType.DLC
                    ? steamDlcParentCollector.findParents(page.appIds())
                    : Map.of();
                nextCursor = steamCatalogCollectionService.savePageAndAdvanceCursor(
                    type, scheduledAt, page, parents, Instant.now());
            }
        } catch (RuntimeException collectionFailure) {
            steamCatalogCollectionService.markFailed(type, scheduledAt);
            throw collectionFailure;
        }
    }
}
