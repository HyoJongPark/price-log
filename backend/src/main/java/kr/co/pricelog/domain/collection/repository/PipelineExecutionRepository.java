package kr.co.pricelog.domain.collection.repository;

import java.time.Instant;
import kr.co.pricelog.domain.collection.entity.PipelineExecution;
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
        VALUES (:#{#pipeline.name()}, :scheduledAt, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        ON CONFLICT (pipeline, scheduled_at) DO NOTHING
        """, nativeQuery = true)
    void openIfAbsent(PipelineType pipeline, Instant scheduledAt);

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

    /** 각 묶음에서는 고정된 전체 대상 배열을 다시 쓰지 않고 진행 위치만 갱신한다. */
    @Modifying
    @Query(value = """
        UPDATE pipeline_execution
        SET next_index = :nextIndex, failed_ids = :failedIds, updated_at = CURRENT_TIMESTAMP
        WHERE pipeline = 'STEAM_PRICE' AND scheduled_at = :slotAt
        """, nativeQuery = true)
    void updatePriceProgress(Instant slotAt, int nextIndex, Long[] failedIds);

    /** 종류와 예정 시각에 해당하는 수집 회차를 찾는다. */
    PipelineExecution findByPipelineAndScheduledAt(PipelineType pipeline, Instant scheduledAt);
}
