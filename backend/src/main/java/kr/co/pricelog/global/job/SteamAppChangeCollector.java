package kr.co.pricelog.global.job;

import io.github.resilience4j.retry.annotation.Retry;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import kr.co.pricelog.domain.collection.dto.SteamChangeListPlan;
import kr.co.pricelog.domain.collection.service.SteamPriceCollectionService;
import kr.co.pricelog.domain.game.enums.SteamAppType;
import kr.co.pricelog.domain.game.service.GameCatalogService;
import kr.co.pricelog.global.client.steam.SteamAppListClient;
import kr.co.pricelog.global.client.steam.dto.SteamAppPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** 변경된 본편·DLC를 카탈로그에 반영하고 두 종류의 한국 구매 옵션을 다시 확인한다. */
@Component
@RequiredArgsConstructor
public class SteamAppChangeCollector {

    private static final int PAGE_SIZE = 10_000;

    private final SteamAppListClient steamAppListClient;
    private final SteamDlcParentCollector steamDlcParentCollector;
    private final GameCatalogService gameCatalogService;
    private final SteamPriceCollectionService steamPriceCollectionService;
    private final SteamStoreCollector steamStoreCollector;

    /** 목록 완주 전 실패는 시간 구간을 다시 읽고, 이후 실패는 확정된 가격 대상으로 재개한다. */
    @Retry(name = "steamCollection")
    public void collect(Instant slotAt, int batchSize) {
        Assert.isTrue(batchSize > 0, "가격 수집 묶음 크기는 양수여야 합니다");
        SteamChangeListPlan changeList = steamPriceCollectionService.startChangeList(slotAt);
        if (changeList.modifiedSince().isPresent()) {
            try {
                Set<Long> gameIds = readChangedApps(changeList.modifiedSince().get());
                steamPriceCollectionService.setChangedPriceTargets(changeList.executionId(), slotAt,
                    gameIds);
            } catch (RuntimeException failure) {
                steamPriceCollectionService.markFailed(changeList.executionId());
                throw failure;
            }
        }

        steamPriceCollectionService.resumePriceCollection(changeList.executionId())
            .ifPresent(plan -> steamStoreCollector.collectPrices(plan, batchSize));
    }

    private Set<Long> readChangedApps(Instant modifiedSince) {
        Set<Long> gameIds = new LinkedHashSet<>();
        for (SteamAppType type : List.of(SteamAppType.GAME, SteamAppType.DLC)) {
            OptionalLong cursor = OptionalLong.of(0);

            while (cursor.isPresent()) {
                SteamAppPage page = steamAppListClient.getChangedPage(type, cursor.getAsLong(),
                    PAGE_SIZE, modifiedSince);
                if (!page.apps().isEmpty()) {
                    List<Long> ids = page.appIds();
                    Map<Long, Long> parents = Map.of();
                    if (type == SteamAppType.DLC) {
                        parents = steamDlcParentCollector.findParents(
                            gameCatalogService.findUnlinkedDlcIds(ids));
                    }
                    gameCatalogService.applyPage(type, page.apps(), parents, Instant.now());
                    gameIds.addAll(ids);
                }
                cursor = page.nextCursor();
            }
        }
        return gameIds;
    }
}
