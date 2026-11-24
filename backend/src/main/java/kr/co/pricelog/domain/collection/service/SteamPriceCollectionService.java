package kr.co.pricelog.domain.collection.service;

import static kr.co.pricelog.domain.collection.enums.PipelineType.STEAM_PRICE;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kr.co.pricelog.domain.collection.dto.SteamPriceCollectionPlan;
import kr.co.pricelog.domain.collection.entity.PipelineExecution;
import kr.co.pricelog.domain.collection.repository.PipelineExecutionRepository;
import kr.co.pricelog.domain.purchaseoption.service.GamePurchaseOptionService;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 가격 수집 회차를 열고 상품 변경과 재개 위치를 묶음 단위로 커밋한다. */
@Service
@Transactional
@RequiredArgsConstructor
public class SteamPriceCollectionService {

    private final PipelineExecutionRepository pipelineExecutionRepository;
    private final GamePurchaseOptionService gamePurchaseOptionService;

    /** 처음 실행하면 본편 목록을 고정하고 재실행하면 DB의 마지막 커밋을 읽는다. */
    public Optional<SteamPriceCollectionPlan> resume(Instant slotAt) {
        pipelineExecutionRepository.openPriceCollection(slotAt);
        return execution(slotAt).resumePriceCollection();
    }

    /** 한 HTTP 응답으로 확인한 모든 게임에 같은 관측 시각을 부여한다. */
    public void saveOptions(Map<Long, SteamPurchaseOptions> confirmed, Instant slotAt,
                            Instant observedAt) {
        confirmed.forEach((id, options) ->
            gamePurchaseOptionService.applyPurchaseOptions(id, options, slotAt, observedAt));
    }

    /** 옵션·가격·이력과 다음 위치·미해결 ID를 함께 확정한다. */
    public void saveBatch(Map<Long, SteamPurchaseOptions> confirmed, Instant slotAt,
                          Instant observedAt, int nextIndex, Set<Long> failedIds) {
        saveOptions(confirmed, slotAt, observedAt);
        pipelineExecutionRepository.updatePriceProgress(slotAt, nextIndex,
            failedIds.toArray(Long[]::new));
    }

    /** 모든 본편의 가격 조회가 해결된 회차를 마친다. */
    public void complete(Instant slotAt) {
        execution(slotAt).complete(Instant.now());
    }

    /** 마지막으로 커밋한 위치를 유지한 채 실행 실패를 기록한다. */
    public void markFailed(Instant slotAt) {
        execution(slotAt).fail(Instant.now());
    }

    private PipelineExecution execution(Instant slotAt) {
        return pipelineExecutionRepository.findByPipelineAndScheduledAt(STEAM_PRICE, slotAt);
    }
}
