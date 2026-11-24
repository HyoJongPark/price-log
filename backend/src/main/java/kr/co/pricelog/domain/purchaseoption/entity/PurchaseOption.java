package kr.co.pricelog.domain.purchaseoption.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.Objects;
import kr.co.pricelog.global.client.steam.dto.SteamPrice;
import kr.co.pricelog.global.client.steam.enums.SteamPurchaseOptionType;
import kr.co.pricelog.global.entity.BaseEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/** 한국 상점에서 여러 게임이 공유할 수 있는 패키지 또는 번들의 판매 구간. */
@Entity
@Getter
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private SteamPurchaseOptionType type;

    @Column(nullable = false)
    private Long steamId;

    private String name;

    private Long currentPrice;

    private Long regularPrice;

    private Instant discountEndsAt;

    private Instant priceObservedAt;

    private Instant saleEndedAt;

    /** Steam에서 확인한 판매 상품의 새 구간을 연다. */
    public PurchaseOption(SteamPurchaseOptionType type, long steamId, String name) {
        this.type = type;
        this.steamId = steamId;
        this.name = name;
    }

    /** 이 판매 구간의 한국 판매 종료를 확인한 시각을 기록한다. */
    public void endSale(Instant endedAt) {
        saleEndedAt = Objects.requireNonNull(endedAt);
    }

    /** 한국 상점의 최신 구매 옵션 표시명을 반영한다. */
    public void updateName(String name) {
        this.name = name;
    }

    /** 새로 기록된 관측 중 가장 최근에 확인한 가격을 상품에 반영한다. */
    public void updatePrice(SteamPrice price, Instant observedAt) {
        if (priceObservedAt == null || observedAt.isAfter(priceObservedAt)) {
            currentPrice = price.currentPrice();
            regularPrice = price.regularPrice();
            discountEndsAt = price.discountEndsAt();
            priceObservedAt = observedAt;
        }
    }
}
