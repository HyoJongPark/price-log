package kr.co.pricelog.domain.price.repository;

import java.time.Instant;
import kr.co.pricelog.domain.price.entity.PriceHistory;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** 상품과 예정 슬롯별로 처음 성공한 가격을 보존한다. */
public interface PriceHistoryRepository extends Repository<PriceHistory, Long> {

    /** 새로 삽입한 회차의 관측만 현재가 후보가 된다. 과거 판매 구간 보충은 이력만 남긴다. */
    @Modifying
    @Query(value = """
        WITH incoming AS (
            SELECT * FROM unnest(CAST(:ids AS bigint[]), CAST(:prices AS bigint[]),
                CAST(:regular AS bigint[]), CAST(:ends AS timestamptz[]),
                CAST(:updateLatest AS boolean[])) AS i(id, price, regular, ends, update_latest)
        ), recorded AS (
            INSERT INTO price_history (purchase_option_id, slot_at, observed_at, current_price,
                regular_price, discount_ends_at, created_at, updated_at)
            SELECT id, :slotAt, :observedAt, price, regular, ends, CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP FROM incoming
            ON CONFLICT (purchase_option_id, slot_at) DO NOTHING
            RETURNING purchase_option_id
        )
        UPDATE purchase_option o SET current_price = i.price, regular_price = i.regular,
            discount_ends_at = i.ends, price_observed_at = :observedAt,
            updated_at = CURRENT_TIMESTAMP
        FROM incoming i JOIN recorded r ON r.purchase_option_id = i.id
        WHERE o.id = i.id AND i.update_latest
          AND (o.price_observed_at IS NULL OR o.price_observed_at < :observedAt)
        """, nativeQuery = true)
    void recordPrices(Long[] ids, Long[] prices, Long[] regular, Instant[] ends,
                      Boolean[] updateLatest, Instant slotAt, Instant observedAt);
}
