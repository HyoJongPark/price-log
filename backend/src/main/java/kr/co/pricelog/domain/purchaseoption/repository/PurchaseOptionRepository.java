package kr.co.pricelog.domain.purchaseoption.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.co.pricelog.domain.purchaseoption.dto.ClosedPurchaseOption;
import kr.co.pricelog.domain.purchaseoption.entity.PurchaseOption;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** 패키지·번들의 종류와 Steam 번호로 한국 판매 상품을 식별한다. */
public interface PurchaseOptionRepository extends Repository<PurchaseOption, Long> {

    /** 두 배열의 같은 위치를 하나의 상품 키로 묶어 현재 판매 구간을 조회한다. */
    @Query(value = """
        SELECT o.* FROM purchase_option o
        JOIN unnest(CAST(:types AS text[]), CAST(:steamIds AS bigint[])) AS k(type, steam_id)
          ON o.type = k.type AND o.steam_id = k.steam_id
        WHERE o.deleted_at IS NULL AND o.sale_ended_at IS NULL
        """, nativeQuery = true)
    List<PurchaseOption> findActiveByKeys(String[] types, Long[] steamIds);

    /** 종료 확인 대상인 내부 판매 구간이 아직 열려 있는지 확인한다. */
    Optional<PurchaseOption> findByIdAndSaleEndedAtIsNull(long id);

    /** 회차 이후 종료된 상품의 당시 게임 연결과 재판매 구간의 현재 게임 연결을 조회한다. */
    @Query(value = """
        WITH ended AS (
            SELECT id, type, steam_id FROM purchase_option
            WHERE deleted_at IS NULL AND sale_ended_at >= :slotAt
        )
        SELECT o.type, o.steam_id, g.steam_app_id
        FROM ended o
        LEFT JOIN game_purchase_option l ON l.purchase_option_id = o.id
            AND (l.deleted_at IS NULL OR l.deleted_at >= :slotAt)
        LEFT JOIN game g ON g.id = l.game_id
        UNION
        SELECT active.type, active.steam_id, g.steam_app_id
        FROM (SELECT DISTINCT type, steam_id FROM ended) o
        JOIN purchase_option active ON active.type = o.type AND active.steam_id = o.steam_id
            AND active.deleted_at IS NULL AND active.sale_ended_at IS NULL
        JOIN game_purchase_option l ON l.purchase_option_id = active.id AND l.deleted_at IS NULL
        JOIN game g ON g.id = l.game_id
        """, nativeQuery = true)
    List<ClosedPurchaseOption> findClosedSince(Instant slotAt);

    /** 새 상품과 이름이 달라진 상품을 쓰고, 이번 배치의 연결과 가격에 사용할 내부 ID를 반환한다. */
    @Query(value = """
        WITH saved AS (
            INSERT INTO purchase_option (type, steam_id, name, created_at, updated_at)
            SELECT type, steam_id, name, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            FROM unnest(CAST(:types AS text[]), CAST(:steamIds AS bigint[]),
                        CAST(:names AS text[])) AS s(type, steam_id, name)
            ON CONFLICT (type, steam_id) WHERE deleted_at IS NULL AND sale_ended_at IS NULL
            DO UPDATE SET name = EXCLUDED.name, updated_at = CURRENT_TIMESTAMP
            RETURNING *
        )
        SELECT * FROM saved
        """, nativeQuery = true)
    List<PurchaseOption> saveNames(String[] types, Long[] steamIds, String[] names);
}
