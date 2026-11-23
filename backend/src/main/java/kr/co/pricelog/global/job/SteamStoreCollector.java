package kr.co.pricelog.global.job;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    // TODO: 대상 게임 전체의 상점 응답을 수집하고, 전체 응답을 상품·가격 데이터로 변환한 뒤 청크별로 적재한다.
    /** 확인한 앱만 요청 순서대로 저장하고 그 결과를 반환한다. */
    public Map<Long, SteamPurchaseOptions> collectPurchaseOptions(List<Long> steamAppIds,
                                                                  Instant slotAt) {
        Map<Long, SteamPurchaseOptions> confirmed =
            steamStoreClient.getPurchaseOptions(steamAppIds);
        Instant observedAt = Instant.now();

        confirmed.forEach((id, options) ->
            gamePurchaseOptionService.applyPurchaseOptions(id, options, slotAt, observedAt));
        return confirmed;
    }
}
