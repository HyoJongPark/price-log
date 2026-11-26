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

    /** 담당 요일의 본편과 DLC를 내부 키순으로 읽는다. */
    @Query("""
        SELECT new kr.co.pricelog.domain.review.dto.ReviewCollectionTarget(g.id, g.steamAppId)
        FROM Game g WHERE g.id > :afterId AND mod(g.steamAppId, 7) = :weekday
            AND g.appType IN (kr.co.pricelog.domain.game.enums.SteamAppType.GAME,
                              kr.co.pricelog.domain.game.enums.SteamAppType.DLC)
        ORDER BY g.id
        """)
    List<ReviewCollectionTarget> findDue(int weekday, long afterId, Limit limit);

    /** 같은 게임의 작성일별 집계는 다시 조회해도 한 번만 저장한다. */
    @Modifying
    @Query(value = """
        INSERT INTO review_history(game_id, review_date, positive_count, total_count,
                                   created_at, updated_at)
        SELECT r.game_id, r.day, r.positive, r.total, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        FROM unnest(CAST(:rowGames AS bigint[]), CAST(:days AS date[]), CAST(:positive AS int[]),
                    CAST(:totals AS int[])) AS r(game_id, day, positive, total)
        JOIN game g ON g.id = r.game_id AND g.deleted_at IS NULL
            AND g.app_type IN ('GAME', 'DLC')
        ON CONFLICT (game_id, review_date) DO NOTHING
        """, nativeQuery = true)
    void insertBatch(Long[] rowGames, LocalDate[] days, Integer[] positive, Integer[] totals);
}
