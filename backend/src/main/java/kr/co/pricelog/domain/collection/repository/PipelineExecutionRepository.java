package kr.co.pricelog.domain.collection.repository;

import java.time.Instant;
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
    @Query(value = """
        INSERT INTO pipeline_execution (pipeline, scheduled_at, created_at, updated_at)
        VALUES (:#{#pipelineType.name()}, :scheduledAt, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        ON CONFLICT (pipeline, scheduled_at) DO NOTHING
        """, nativeQuery = true)
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

    /** 첫 실패 이후에도 최초 조회 범위를 보존하고, 완주한 목록의 시작 시각을 기준으로 잇는다. */
    @Query(value = """
        SELECT coalesce(max(scheduled_at) FILTER (WHERE target_ids IS NOT NULL),
                        min(scheduled_at) - interval '6 hours')
        FROM pipeline_execution
        WHERE pipeline = 'STEAM_CHANGED_APPS' AND scheduled_at <= :slotAt
        """, nativeQuery = true)
    Instant changeWindowStart(Instant slotAt);

    /** 직전 목록의 미확인·미처리 ID와 새로 읽은 본편을 합쳐 이번 회차의 입력을 고정한다. */
    @Modifying
    @Query(value = """
        WITH previous AS (
            SELECT target_ids, next_index, failed_ids FROM pipeline_execution
            WHERE pipeline = 'STEAM_CHANGED_APPS' AND scheduled_at < :slotAt
              AND target_ids IS NOT NULL ORDER BY scheduled_at DESC LIMIT 1
        ), candidates AS (
            SELECT unnest(CAST(:gameIds AS bigint[])) AS app_id
            UNION ALL
            SELECT unnest(failed_ids) FROM previous
            UNION ALL
            SELECT pending.app_id FROM previous,
                unnest(target_ids) WITH ORDINALITY pending(app_id, position)
            WHERE pending.position > previous.next_index
        )
        UPDATE pipeline_execution SET target_ids = ARRAY(
            SELECT steam_app_id FROM game WHERE app_type = 'GAME' AND deleted_at IS NULL
            AND steam_app_id IN (SELECT app_id FROM candidates) ORDER BY steam_app_id
        ), updated_at = CURRENT_TIMESTAMP
        WHERE id = :executionId AND target_ids IS NULL
        """, nativeQuery = true)
    void setChangedPriceTargets(long executionId, Instant slotAt, Long[] gameIds);

    /** 각 묶음에서는 고정된 전체 대상 배열을 다시 쓰지 않고 진행 위치만 갱신한다. */
    @Modifying
    @Query(value = """
        UPDATE pipeline_execution
        SET next_index = :nextIndex, failed_ids = :failedIds, updated_at = CURRENT_TIMESTAMP
        WHERE id = :executionId
        """, nativeQuery = true)
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
