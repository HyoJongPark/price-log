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

    /** 종류와 예정 시각에 해당하는 수집 회차를 찾는다. */
    PipelineExecution findByPipelineAndScheduledAt(PipelineType pipeline, Instant scheduledAt);
}
