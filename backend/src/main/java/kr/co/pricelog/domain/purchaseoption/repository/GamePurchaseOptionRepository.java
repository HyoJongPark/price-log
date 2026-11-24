package kr.co.pricelog.domain.purchaseoption.repository;

import java.time.Instant;
import java.util.List;
import kr.co.pricelog.domain.purchaseoption.entity.GamePurchaseOption;
import kr.co.pricelog.domain.purchaseoption.entity.PurchaseOption;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** 게임별 활성 구매 옵션을 조회하고 연결 변경을 저장한다. */
public interface GamePurchaseOptionRepository extends Repository<GamePurchaseOption, Long> {

    /** 해당 판매 구간을 아직 제공 중인 모든 게임의 연결을 찾는다. */
    List<GamePurchaseOption> findByPurchaseOption(PurchaseOption purchaseOption);

    /** 정상 확인한 게임에 한해, 사라진 연결을 닫고 이전 대표를 먼저 해제한다. */
    @Modifying
    @Query(value = """
        WITH incoming AS (
            SELECT * FROM unnest(CAST(:gameIds AS bigint[]), CAST(:optionIds AS bigint[]),
                                 CAST(:best AS boolean[])) AS i(game_id, option_id, best)
        ), changes AS (
            SELECT l.id, i.option_id IS NULL AS removed
            FROM game_purchase_option l LEFT JOIN incoming i
                ON i.game_id = l.game_id AND i.option_id = l.purchase_option_id
            WHERE l.game_id = ANY(CAST(:confirmedGames AS bigint[])) AND l.deleted_at IS NULL
              AND (i.option_id IS NULL OR (l.is_best_option AND NOT i.best))
        )
        UPDATE game_purchase_option l
        SET deleted_at = CASE WHEN c.removed THEN CAST(:observedAt AS timestamptz) END,
            is_best_option = c.removed AND l.is_best_option, updated_at = CURRENT_TIMESTAMP
        FROM changes c WHERE l.id = c.id
        """, nativeQuery = true)
    void closeOrRelease(Long[] confirmedGames, Long[] gameIds, Long[] optionIds, Boolean[] best,
                        Instant observedAt);

    /** 누락된 연결과 대표로 승격할 연결만 반영한다. 그대로인 연결의 감사 시각은 보존한다. */
    @Modifying
    @Query(value = """
        INSERT INTO game_purchase_option
            (game_id, purchase_option_id, is_best_option, created_at, updated_at)
        SELECT i.game_id, i.option_id, i.best, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        FROM unnest(CAST(:gameIds AS bigint[]), CAST(:optionIds AS bigint[]),
                    CAST(:best AS boolean[])) AS i(game_id, option_id, best)
        LEFT JOIN game_purchase_option l ON l.game_id = i.game_id
            AND l.purchase_option_id = i.option_id AND l.deleted_at IS NULL
        WHERE l.id IS NULL OR l.is_best_option <> i.best
        ON CONFLICT (game_id, purchase_option_id) WHERE deleted_at IS NULL
        DO UPDATE SET is_best_option = EXCLUDED.is_best_option, updated_at = CURRENT_TIMESTAMP
        """, nativeQuery = true)
    void saveLinks(Long[] gameIds, Long[] optionIds, Boolean[] best);
}
