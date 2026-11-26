package kr.co.pricelog.domain.playercount.repository;

import java.time.Instant;
import kr.co.pricelog.domain.playercount.entity.CcuHistory;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** 새 원천 시각의 차트 한 판을 일괄 적재한다. */
public interface CcuHistoryRepository extends Repository<CcuHistory, Long> {

    /** 같은 판의 100개 관측과 중복 판정을 단일 PostgreSQL 명령으로 처리한다. */
    @Modifying
    @Query(value = """
        INSERT INTO ccu_history
            (steam_app_id, observed_at, source_rank, player_count, peak_player_count,
             created_at, updated_at)
        SELECT e.app_id, :observedAt, e.rank, e.players, e.peak_players,
               CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        FROM unnest(CAST(:appIds AS bigint[]), CAST(:ranks AS integer[]),
                    CAST(:players AS integer[]), CAST(:peakPlayers AS integer[]))
            AS e(app_id, rank, players, peak_players)
        WHERE NOT EXISTS (SELECT 1 FROM ccu_history WHERE observed_at >= :observedAt)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    void insertNewChart(Instant observedAt, Long[] appIds, Integer[] ranks, Integer[] players,
                        Integer[] peakPlayers);
}
