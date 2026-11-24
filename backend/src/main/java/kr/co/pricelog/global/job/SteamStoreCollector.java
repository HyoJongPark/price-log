package kr.co.pricelog.global.job;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kr.co.pricelog.domain.collection.dto.SteamPriceCollectionPlan;
import kr.co.pricelog.domain.collection.service.SteamPriceCollectionService;
import kr.co.pricelog.global.client.steam.SteamStoreClient;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 카탈로그 앱의 한국 상점 구매 옵션을 읽고 상품 연결·기본 선택·무료 표기를 반영한다. */
@Component
@RequiredArgsConstructor
public class SteamStoreCollector {

    private final SteamStoreClient steamStoreClient;
    private final SteamPriceCollectionService steamPriceCollectionService;

    // TODO: 대상 게임 전체의 상점 응답을 수집하고, 전체 응답을 상품·가격 데이터로 변환한 뒤 청크별로 적재한다.
    /** 확인한 앱만 요청 순서대로 저장하고 그 결과를 반환한다. */
    public Map<Long, SteamPurchaseOptions> collectPurchaseOptions(List<Long> steamAppIds,
                                                                  Instant slotAt) {
        Map<Long, SteamPurchaseOptions> confirmed =
            steamStoreClient.getPurchaseOptions(steamAppIds);
        Instant observedAt = Instant.now();

        steamPriceCollectionService.saveOptions(confirmed, slotAt, observedAt);
        return confirmed;
    }

    /** 고정된 본편 목록을 모두 조회하고 회차 말미에 미해결 앱을 한 번씩 다시 확인한다. */
    public void collectAll(Instant slotAt, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("가격 수집 묶음 크기는 양수여야 합니다");
        }
        Optional<SteamPriceCollectionPlan> plan = steamPriceCollectionService.resume(slotAt);
        if (plan.isEmpty()) {
            return;
        }

        boolean allConfirmed;
        try {
            allConfirmed = collectRemaining(slotAt, batchSize, plan.get());
        } catch (RuntimeException failure) {
            steamPriceCollectionService.markFailed(slotAt);
            throw failure;
        }

        if (allConfirmed) {
            steamPriceCollectionService.complete(slotAt);
        } else {
            steamPriceCollectionService.markFailed(slotAt);
        }
    }

    private boolean collectRemaining(Instant slotAt, int batchSize, SteamPriceCollectionPlan plan) {
        Set<Long> failedIds = new LinkedHashSet<>(plan.failedIds());
        List<Long> targets = plan.targetIds();
        int nextIndex = plan.nextIndex();
        while (nextIndex < targets.size()) {
            int end = nextIndex + Math.min(batchSize, targets.size() - nextIndex);
            collectBatch(targets.subList(nextIndex, end), slotAt, end, failedIds);
            nextIndex = end;
        }

        // 재시도 중 계속 빠지는 앱을 같은 실행에서 다시 선택하지 않는다.
        List<Long> retryIds = List.copyOf(failedIds);
        int start = 0;
        while (start < retryIds.size()) {
            int end = start + Math.min(batchSize, retryIds.size() - start);
            collectBatch(retryIds.subList(start, end), slotAt, nextIndex, failedIds);
            start = end;
        }

        return failedIds.isEmpty();
    }

    private void collectBatch(List<Long> ids, Instant slotAt, int nextIndex, Set<Long> failedIds) {
        Map<Long, SteamPurchaseOptions> confirmed = steamStoreClient.getPurchaseOptions(ids);
        failedIds.addAll(ids);
        failedIds.removeAll(confirmed.keySet());

        steamPriceCollectionService.saveBatch(confirmed, slotAt, Instant.now(), nextIndex,
            failedIds);
    }
}
