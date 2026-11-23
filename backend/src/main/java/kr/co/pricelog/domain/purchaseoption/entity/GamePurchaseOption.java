package kr.co.pricelog.domain.purchaseoption.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import kr.co.pricelog.domain.game.entity.Game;
import kr.co.pricelog.global.entity.BaseEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/** 한 게임의 한국 상점에서 노출된 판매 상품과 Steam의 기본 선택 여부. */
@Entity
@Getter
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GamePurchaseOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Game game;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_option_id")
    private PurchaseOption purchaseOption;

    @Column(name = "is_best_option", nullable = false)
    private boolean bestOption;

    /** 게임의 구매 옵션 목록에 새 상품을 연결한다. */
    public GamePurchaseOption(Game game, PurchaseOption purchaseOption, boolean bestOption) {
        this.game = game;
        this.purchaseOption = purchaseOption;
        this.bestOption = bestOption;
    }

    /** Steam이 이 게임에서 지정한 기본 옵션인지 반영한다. */
    public void setBestOption(boolean bestOption) {
        this.bestOption = bestOption;
    }
}
