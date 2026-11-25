package kr.co.pricelog.domain.collection.repository;

import java.time.Instant;
import java.util.Optional;
import kr.co.pricelog.domain.collection.dto.ExecutionTime;
import kr.co.pricelog.domain.collection.entity.PipelineExecution;
import kr.co.pricelog.domain.collection.enums.PipelineExecutionStatus;
import kr.co.pricelog.domain.collection.enums.PipelineType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** 같은 종류와 예정 시각으로 실행된 수집은 한 회차를 공유한다. */
public interface PipelineExecutionRepository extends Repository<PipelineExecution, Long> {

    /** 최초 실행만 시작 행을 만들고 재실행은 기존 진행 위치를 보존한다. */
    @Modifying
    @Query("""
        INSERT INTO PipelineExecution (pipelineType, scheduledAt, createdAt, updatedAt)
        VALUES (:pipelineType, :scheduledAt, instant, instant)
        ON CONFLICT (pipelineType, scheduledAt) DO NOTHING
        """)
    void openIfAbsent(PipelineType pipelineType, Instant scheduledAt);

    /** 최초 실행에만 활성 본편 ID를 확정한다. 재개 시 카탈로그를 다시 읽지 않는다. */
    @Modifying
    @Query(value = """
        INSERT INTO pipeline_execution (pipeline, scheduled_at, target_ids, created_at, updated_at)
        SELECT 'STEAM_PRICE', :slotAt, ARRAY(
            SELECT steam_app_id FROM game
            WHERE app_type = 'GAME' AND deleted_at IS NULL ORDER BY steam_app_id
        ), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        WHERE NOT EXISTS (SELECT 1 FROM pipeline_execution
            WHERE pipeline = 'STEAM_PRICE' AND scheduled_at = :slotAt)
        ON CONFLICT (pipeline, scheduled_at) DO NOTHING
        """, nativeQuery = true)
    void openPriceCollection(Instant slotAt);

    /** 현재 회차보다 앞서 가격 대상을 확정한 마지막 회차. 빈 목록도 확정으로 센다. */
    Optional<ExecutionTime>
        findFirstByPipelineTypeAndTargetIdsNotNullAndScheduledAtBeforeOrderByScheduledAtDesc(
            PipelineType pipelineType, Instant slotAt);

    /** 최초 적재가 중단됐을 때 이어갈 회차를 찾는다. */
    Optional<ExecutionTime> findFirstByPipelineTypeOrderByScheduledAtDesc(
        PipelineType pipelineType);

    /** 해당 상태의 첫 회차 예정 시각을 찾는다. 첫 변경 조회는 최초 전수 완주 회차부터 따라잡는다. */
    Optional<ExecutionTime> findFirstByPipelineTypeAndStatusOrderByScheduledAtAsc(
        PipelineType pipelineType, PipelineExecutionStatus status);

    /** 직전 목록의 실패·미처리 ID를 DB 안에서 합쳐 활성 본편만 앱 번호순으로 확정한다. */
    @Modifying
    @Query(value = """
        WITH previous AS (
            SELECT failed_ids || target_ids[next_index + 1:] AS pending_ids
            FROM pipeline_execution
            WHERE pipeline = 'STEAM_CHANGED_APPS' AND scheduled_at < :slotAt
                AND target_ids IS NOT NULL
            ORDER BY scheduled_at DESC LIMIT 1
        )
        UPDATE pipeline_execution SET target_ids = ARRAY(
            SELECT steam_app_id FROM game WHERE app_type = 'GAME' AND deleted_at IS NULL
                AND steam_app_id IN (
                    SELECT unnest(CAST(:gameIds AS bigint[]))
                    UNION ALL SELECT unnest(pending_ids) FROM previous
                )
            ORDER BY steam_app_id
        ), updated_at = CURRENT_TIMESTAMP
        WHERE id = :executionId AND target_ids IS NULL
        """, nativeQuery = true)
    void setChangedPriceTargets(long executionId, Instant slotAt, Long[] gameIds);

    /** 각 묶음에서는 고정된 전체 대상 배열을 다시 쓰지 않고 진행 위치만 갱신한다. */
    @Modifying
    @Query("""
        UPDATE PipelineExecution e SET e.nextIndex = :nextIndex, e.failedIds = :failedIds,
            e.updatedAt = instant WHERE e.id = :executionId
        """)
    void updatePriceProgress(long executionId, int nextIndex, Long[] failedIds);

    /** 전체 대상 배열을 로드하지 않고 실행 중인 가격 회차의 종료 상태와 시각만 기록한다. */
    @Modifying
    @Query("""
        UPDATE PipelineExecution e SET e.status = :status, e.finishedAt = :finishedAt,
            e.updatedAt = :finishedAt WHERE e.id = :executionId AND e.status = RUNNING
        """)
    void finishPriceCollection(long executionId, PipelineExecutionStatus status,
                               Instant finishedAt);

    /** 변경 목록을 열 때 확보한 ID로 같은 회차를 다시 읽는다. */
    PipelineExecution findById(long executionId);

    /** 종류와 예정 시각에 해당하는 수집 회차를 찾는다. */
    PipelineExecution findByPipelineTypeAndScheduledAt(PipelineType pipelineType,
                                                       Instant scheduledAt);
}
