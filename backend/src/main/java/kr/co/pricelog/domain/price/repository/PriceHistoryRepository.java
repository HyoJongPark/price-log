package kr.co.pricelog.domain.price.repository;

import java.time.Instant;
import kr.co.pricelog.domain.price.entity.PriceHistory;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** 상품과 예정 슬롯별로 처음 성공한 가격을 보존한다. */
public interface PriceHistoryRepository extends Repository<PriceHistory, Long> {

    /** 해당 상품·회차의 첫 관측이면 1, 이미 저장됐으면 0을 반환한다. */
    @Modifying
    @Query(value = """
        INSERT INTO price_history
            (purchase_option_id, slot_at, observed_at, current_price, regular_price,
             discount_ends_at, created_at, updated_at)
        VALUES (:purchaseOptionId, :slotAt, :observedAt, :currentPrice, :regularPrice,
               :discountEndsAt, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        ON CONFLICT (purchase_option_id, slot_at) DO NOTHING
        """, nativeQuery = true)
    int recordPrice(long purchaseOptionId, Instant slotAt, Instant observedAt, long currentPrice,
                     Long regularPrice, Instant discountEndsAt);
}
