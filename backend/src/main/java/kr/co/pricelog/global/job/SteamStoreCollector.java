package kr.co.pricelog.global.job;

import io.github.resilience4j.retry.annotation.Retry;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.co.pricelog.domain.collection.dto.SteamPriceCollectionPlan;
import kr.co.pricelog.domain.collection.service.SteamPriceCollectionService;
import kr.co.pricelog.global.client.steam.SteamStoreClient;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** 카탈로그 앱의 한국 상점 구매 옵션을 읽고 상품 연결·기본 선택·무료 표기를 반영한다. */
@Component
@RequiredArgsConstructor
public class SteamStoreCollector {

    private final SteamStoreClient steamStoreClient;
    private final SteamPriceCollectionService steamPriceCollectionService;

    /** 확인한 앱만 요청 순서대로 저장하고 그 결과를 반환한다. */
    public Map<Long, SteamPurchaseOptions> collectPurchaseOptions(List<Long> steamAppIds,
                                                                  Instant slotAt) {
        Map<Long, SteamPurchaseOptions> confirmed =
            steamStoreClient.getPurchaseOptions(steamAppIds);
        Instant observedAt = Instant.now();

        steamPriceCollectionService.saveOptions(confirmed, slotAt, observedAt);
        return confirmed;
    }

    /** 고정된 본편·DLC 목록을 모두 조회하고 회차 말미에 미해결 앱을 한 번씩 다시 확인한다. */
    @Retry(name = "steamCollection")
    public void collectAll(Instant now, int batchSize) {
        Assert.isTrue(batchSize > 0, "가격 수집 묶음 크기는 양수여야 합니다");
        steamPriceCollectionService.openFullCollection(now)
            .ifPresent(plan -> collectPrices(plan, batchSize));
    }

    /** 변경분과 전수 회차가 동일한 상점 조회·저장·실패 복구 경로를 사용한다. */
    void collectPrices(SteamPriceCollectionPlan plan, int batchSize) {
        boolean allConfirmed;
        try {
            allConfirmed = collectRemaining(plan, batchSize);
        } catch (RuntimeException failure) {
            steamPriceCollectionService.markFailed(plan.executionId());
            throw failure;
        }

        if (allConfirmed) {
            steamPriceCollectionService.complete(plan.executionId());
        } else {
            steamPriceCollectionService.markFailed(plan.executionId());
        }
    }

    private boolean collectRemaining(SteamPriceCollectionPlan plan, int batchSize) {
        Set<Long> failedIds = new LinkedHashSet<>(plan.failedIds());
        List<Long> targets = plan.targetIds();
        int nextIndex = plan.nextIndex();
        while (nextIndex < targets.size()) {
            int end = nextIndex + Math.min(batchSize, targets.size() - nextIndex);
            collectBatch(plan, targets.subList(nextIndex, end), end, failedIds);
            nextIndex = end;
        }

        // 재시도 중 계속 빠지는 앱을 같은 실행에서 다시 선택하지 않는다.
        List<Long> retryIds = List.copyOf(failedIds);
        int start = 0;
        while (start < retryIds.size()) {
            int end = start + Math.min(batchSize, retryIds.size() - start);
            collectBatch(plan, retryIds.subList(start, end), nextIndex, failedIds);
            start = end;
        }

        return failedIds.isEmpty();
    }

    private void collectBatch(SteamPriceCollectionPlan plan, List<Long> ids, int nextIndex,
                              Set<Long> failedIds) {
        Map<Long, SteamPurchaseOptions> confirmed = steamStoreClient.getPurchaseOptions(ids);
        failedIds.addAll(ids);
        failedIds.removeAll(confirmed.keySet());

        steamPriceCollectionService.saveBatch(plan, confirmed, Instant.now(), nextIndex, failedIds);
    }
}
