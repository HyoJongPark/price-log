package kr.co.pricelog.domain.collection.service;

import static kr.co.pricelog.domain.collection.enums.PipelineExecutionStatus.COMPLETED;
import static kr.co.pricelog.domain.collection.enums.PipelineExecutionStatus.FAILED;
import static kr.co.pricelog.domain.collection.enums.PipelineType.STEAM_CHANGED_APPS;
import static kr.co.pricelog.domain.collection.enums.PipelineType.STEAM_DLC_LIST;
import static kr.co.pricelog.domain.collection.enums.PipelineType.STEAM_PRICE;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kr.co.pricelog.domain.collection.dto.ExecutionTime;
import kr.co.pricelog.domain.collection.dto.SteamChangeListPlan;
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

    /** 대상이 확정된 회차는 목록 재요청을 건너뛴다. 마지막 목록 회차보다 한 시간 앞에서부터 다시 읽는다. */
    public SteamChangeListPlan startChangeList(Instant slotAt) {
        pipelineExecutionRepository.openIfAbsent(STEAM_CHANGED_APPS, slotAt);
        PipelineExecution execution = pipelineExecutionRepository.findByPipelineTypeAndScheduledAt(
            STEAM_CHANGED_APPS, slotAt);
        if (execution.hasPriceTargets()) {
            return new SteamChangeListPlan(execution.getId(), Optional.empty());
        }

        execution.resume();
        Instant start = pipelineExecutionRepository
            .findFirstByPipelineTypeAndTargetIdsNotNullAndScheduledAtBeforeOrderByScheduledAtDesc(
                STEAM_CHANGED_APPS, slotAt)
            .or(() -> pipelineExecutionRepository
                .findFirstByPipelineTypeAndStatusOrderByScheduledAtAsc(STEAM_DLC_LIST, COMPLETED))
            .orElseThrow().scheduledAt();

        Instant modifiedSince = start.minus(1, ChronoUnit.HOURS);
        return new SteamChangeListPlan(execution.getId(), Optional.of(modifiedSince));
    }

    /** 모든 변경 목록 페이지가 성공한 뒤에만 상점 조회 대상을 확정한다. */
    public void setChangedPriceTargets(long executionId, Instant slotAt, Collection<Long> gameIds) {
        pipelineExecutionRepository.setChangedPriceTargets(executionId, slotAt,
            gameIds.toArray(Long[]::new));
    }

    /** 처음 실행하면 본편 목록을 고정하고 재실행하면 DB의 마지막 커밋을 읽는다. */
    public Optional<SteamPriceCollectionPlan> openFullCollection(Instant now) {
        if (pipelineExecutionRepository.findFirstByPipelineTypeAndStatusOrderByScheduledAtAsc(
            STEAM_PRICE, COMPLETED).isPresent()) {
            return Optional.empty();
        }

        Instant slotAt = pipelineExecutionRepository.findFirstByPipelineTypeOrderByScheduledAtDesc(
            STEAM_PRICE).map(ExecutionTime::scheduledAt)
            .orElse(now);
        pipelineExecutionRepository.openPriceCollection(slotAt);
        return pipelineExecutionRepository.findByPipelineTypeAndScheduledAt(STEAM_PRICE, slotAt)
            .resumePriceCollection();
    }

    /** 변경 목록에서 확정한 대상과 마지막 커밋 위치를 읽는다. */
    public Optional<SteamPriceCollectionPlan> resumePriceCollection(long executionId) {
        return pipelineExecutionRepository.findById(executionId).resumePriceCollection();
    }

    /** 한 HTTP 응답으로 확인한 모든 게임에 같은 관측 시각을 부여한다. */
    public void saveOptions(Map<Long, SteamPurchaseOptions> confirmed, Instant slotAt,
                            Instant observedAt) {
        gamePurchaseOptionService.applyPurchaseOptions(confirmed, slotAt, observedAt);
    }

    /** 옵션·가격·이력과 다음 위치·미해결 ID를 함께 확정한다. */
    public void saveBatch(SteamPriceCollectionPlan plan, Map<Long, SteamPurchaseOptions> confirmed,
                          Instant observedAt, int nextIndex, Set<Long> failedIds) {
        saveOptions(confirmed, plan.slotAt(), observedAt);
        pipelineExecutionRepository.updatePriceProgress(plan.executionId(), nextIndex,
            failedIds.toArray(Long[]::new));
    }

    /** 모든 본편의 가격 조회가 해결된 회차를 마친다. */
    public void complete(long executionId) {
        pipelineExecutionRepository.finishPriceCollection(executionId, COMPLETED, Instant.now());
    }

    /** 마지막으로 커밋한 위치를 유지한 채 실행 실패를 기록한다. */
    public void markFailed(long executionId) {
        pipelineExecutionRepository.finishPriceCollection(executionId, FAILED, Instant.now());
    }
}
