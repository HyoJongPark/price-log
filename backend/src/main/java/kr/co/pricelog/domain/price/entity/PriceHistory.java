package kr.co.pricelog.domain.price.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.time.Instant;
import kr.co.pricelog.domain.purchaseoption.entity.PurchaseOption;
import kr.co.pricelog.global.entity.BaseEntity;
import org.hibernate.annotations.SQLRestriction;

/** 한국 판매 구간에서 한 예정 슬롯에 처음 확인한 가격. */
@Entity
@SQLRestriction("deleted_at IS NULL")
public class PriceHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private PurchaseOption purchaseOption;

    private Instant slotAt;

    private Instant observedAt;

    @Column(nullable = false)
    private Long currentPrice;

    private Long regularPrice;

    private Instant discountEndsAt;
}
