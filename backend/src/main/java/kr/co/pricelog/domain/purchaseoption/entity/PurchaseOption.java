package kr.co.pricelog.domain.purchaseoption.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import kr.co.pricelog.global.client.steam.enums.SteamPurchaseOptionType;
import kr.co.pricelog.global.entity.BaseEntity;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/** 한국 상점에서 여러 게임이 공유할 수 있는 패키지 또는 번들의 판매 구간. */
@Entity
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private SteamPurchaseOptionType type;

    private long steamId;

    private String name;

    /** Steam에서 확인한 판매 상품의 새 구간을 연다. */
    public PurchaseOption(SteamPurchaseOptionType type, long steamId, String name) {
        this.type = type;
        this.steamId = steamId;
        this.name = name;
    }

    /** 한국 상점의 최신 구매 옵션 표시명을 반영한다. */
    public void updateName(String name) {
        this.name = name;
    }
}
