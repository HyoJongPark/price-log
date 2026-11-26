package kr.co.pricelog.domain.review.repository;

import java.time.LocalDate;
import java.util.List;
import kr.co.pricelog.domain.review.dto.ReviewCollectionTarget;
import kr.co.pricelog.domain.review.entity.ReviewHistory;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** 구매자 평가의 수집 대상과 작성일 이력을 일괄 처리한다. */
public interface ReviewHistoryRepository extends Repository<ReviewHistory, Long> {

    /** 같은 주를 이미 확인한 앱은 Steam에 다시 요청하지 않는다. */
    @Query("""
        SELECT new kr.co.pricelog.domain.review.dto.ReviewCollectionTarget(g.id, g.steamAppId)
        FROM Game g WHERE g.id > :afterId AND mod(g.steamAppId, 7) = :weekday
            AND g.appType IN (kr.co.pricelog.domain.game.enums.SteamAppType.GAME,
                              kr.co.pricelog.domain.game.enums.SteamAppType.DLC)
            AND NOT EXISTS (SELECT c.id FROM ReviewCollection c WHERE c.game = g
                AND c.firstReviewDate <= :start AND c.collectedThrough >= :end)
        ORDER BY g.id
        """)
    List<ReviewCollectionTarget> findDue(int weekday, LocalDate start, LocalDate end,
                                         long afterId, Limit limit);

    /** 연속된 주는 확인 구간을 연장하고, 공백 뒤에는 새 구간을 연다. 0건인 날도 구분된다. */
    @Modifying
    @Query(value = """
        WITH covered AS (
            INSERT INTO review_collection(game_id, first_review_date, collected_through)
            SELECT g.id, coalesce(c.first_review_date, CAST(:start AS date)), CAST(:end AS date)
            FROM game g LEFT JOIN review_collection c
                ON c.game_id = g.id AND c.first_review_date <= :start
               AND c.collected_through >= CAST(:start AS date) - 1
            WHERE g.id = ANY(CAST(:games AS bigint[])) AND g.deleted_at IS NULL
              AND g.app_type IN ('GAME', 'DLC')
              AND NOT EXISTS (SELECT 1 FROM review_collection done WHERE done.game_id = g.id
                  AND done.first_review_date <= :start AND done.collected_through >= :end)
            ON CONFLICT (game_id, first_review_date) DO UPDATE
                SET collected_through = greatest(review_collection.collected_through,
                                                 excluded.collected_through)
            RETURNING game_id
        )
        INSERT INTO review_history(game_id, review_date, positive_count, total_count,
                                   created_at, updated_at)
        SELECT r.game_id, r.day, r.positive, r.total, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        FROM unnest(CAST(:rowGames AS bigint[]), CAST(:days AS date[]), CAST(:positive AS int[]),
                    CAST(:totals AS int[])) AS r(game_id, day, positive, total)
        JOIN covered a ON a.game_id = r.game_id
        ON CONFLICT (game_id, review_date) DO NOTHING
        """, nativeQuery = true)
    void insertBatch(Long[] games, LocalDate start, LocalDate end,
                     Long[] rowGames, LocalDate[] days, Integer[] positive, Integer[] totals);
}
