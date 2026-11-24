package kr.co.pricelog.domain.collection.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import kr.co.pricelog.domain.collection.dto.SteamPriceCollectionPlan;
import kr.co.pricelog.domain.collection.enums.PipelineExecutionStatus;
import kr.co.pricelog.domain.collection.enums.PipelineType;
import kr.co.pricelog.global.entity.BaseEntity;

/** 파이프라인의 예정 시각 한 번에 대한 진행 상태. */
@Entity
public class PipelineExecution extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private PipelineType pipeline;

    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    private PipelineExecutionStatus status;

    private Long listCursor;

    private Instant finishedAt;

    @Column(updatable = false)
    private Long[] targetIds;

    private Integer nextIndex;

    private Long[] failedIds;

    /** 완료한 회차는 다시 요청하지 않고, 미완료 회차는 고정된 입력과 진행 위치를 복원한다. */
    public Optional<SteamPriceCollectionPlan> resumePriceCollection() {
        if (status == PipelineExecutionStatus.COMPLETED) {
            return Optional.empty();
        }
        resume();
        return Optional.of(new SteamPriceCollectionPlan(List.of(targetIds), nextIndex,
            List.of(failedIds)));
    }

    /** 이 회차의 모든 대상 처리를 마친 시각을 기록한다. */
    public void complete(Instant completedAt) {
        status = PipelineExecutionStatus.COMPLETED;
        finishedAt = completedAt;
    }

    /** 실패했던 목록을 마지막 커밋 위치에서 다시 연다. */
    public void resume() {
        if (status == PipelineExecutionStatus.FAILED) {
            status = PipelineExecutionStatus.RUNNING;
            finishedAt = null;
        }
    }

    /** 마지막으로 커밋한 커서를 보존하고 실패한 시각을 기록한다. */
    public void fail(Instant failedAt) {
        if (status == PipelineExecutionStatus.RUNNING) {
            status = PipelineExecutionStatus.FAILED;
            finishedAt = failedAt;
        }
    }

    /** 첫 요청은 0으로 시작하고 완료한 회차에는 다음 요청이 없다. */
    public OptionalLong nextCursor() {
        if (status == PipelineExecutionStatus.COMPLETED) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(listCursor == null ? 0L : listCursor);
    }

    /** 빈 마지막 페이지라면 직전 페이지에서 저장한 커서를 유지한다. */
    public void recordPage(OptionalLong lastAppId, boolean lastPage, Instant observedAt) {
        if (lastAppId.isPresent()) {
            listCursor = lastAppId.getAsLong();
        }
        if (lastPage) {
            complete(observedAt);
        }
    }
}
