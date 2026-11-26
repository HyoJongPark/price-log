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

    /** 미등록 앱만 첫 담당 요일에 배정한다. 일괄 등록도 한 트랜잭션의 행 수를 제한한다. */
    @Query(value = """
        WITH candidates AS (
            SELECT g.id, CAST(:today AS date) + mod(CAST(mod(g.steam_app_id, 7) AS integer)
                - :weekday + 7, 7) AS due
            FROM game g WHERE g.id > :afterId AND g.deleted_at IS NULL
                AND g.app_type IN ('GAME', 'DLC')
                AND NOT EXISTS (SELECT 1 FROM review_collection c WHERE c.game_id = g.id)
            ORDER BY g.id LIMIT :limit
        ), inserted AS (
            INSERT INTO review_collection(game_id, first_review_date, next_due_on)
            SELECT id, due - interval '1 year', due FROM candidates
            ON CONFLICT (game_id) DO NOTHING
        )
        SELECT max(id) FROM candidates
        """, nativeQuery = true)
    Long registerBatch(LocalDate today, int weekday, long afterId, int limit);

    /** 이전 담당일이 밀린 앱도 포함한다. 기존 추적 앱과 최초 적재를 따로 순회한다. */
    @Query("""
        SELECT new kr.co.pricelog.domain.review.dto.ReviewCollectionTarget(
            g.id, g.steamAppId, c.firstReviewDate, c.collectedThrough, c.nextDueOn)
        FROM ReviewCollection c JOIN c.game g
        WHERE c.nextDueOn <= :today AND g.id > :afterId
            AND g.appType IN (kr.co.pricelog.domain.game.enums.SteamAppType.GAME,
                              kr.co.pricelog.domain.game.enums.SteamAppType.DLC)
            AND ((:initial = true AND c.collectedThrough IS NULL)
                OR (:initial = false AND c.collectedThrough IS NOT NULL))
        ORDER BY g.id
        """)
    List<ReviewCollectionTarget> findDue(LocalDate today, boolean initial, long afterId,
                                         Limit limit);

    /** 직전 확인 위치가 같은 앱만 전진시키고, 해당 앱의 리뷰를 같은 명령으로 삽입한다. */
    @Modifying
    @Query(value = """
        WITH advanced AS (
            UPDATE review_collection c SET collected_through = x.until_day, next_due_on = x.due
            FROM unnest(CAST(:games AS bigint[]), CAST(:starts AS date[]), CAST(:ends AS date[]),
                        CAST(:nextDates AS date[])) AS x(game_id, since_day, until_day, due)
            JOIN game g ON g.id = x.game_id AND g.deleted_at IS NULL
                       AND g.app_type IN ('GAME', 'DLC')
            WHERE c.game_id = x.game_id
              AND coalesce(c.collected_through + 1, c.first_review_date) = x.since_day
            RETURNING c.game_id
        )
        INSERT INTO review_history(game_id, review_date, positive_count, total_count,
                                   created_at, updated_at)
        SELECT r.game_id, r.day, r.positive, r.total, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        FROM unnest(CAST(:rowGames AS bigint[]), CAST(:days AS date[]), CAST(:positive AS int[]),
                    CAST(:totals AS int[])) AS r(game_id, day, positive, total)
        JOIN advanced a ON a.game_id = r.game_id
        ON CONFLICT (game_id, review_date) DO NOTHING
        """, nativeQuery = true)
    void insertBatch(Long[] games, LocalDate[] starts, LocalDate[] ends, LocalDate[] nextDates,
                     Long[] rowGames, LocalDate[] days, Integer[] positive, Integer[] totals);
}
