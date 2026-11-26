package kr.co.pricelog.domain.review.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.time.LocalDate;
import kr.co.pricelog.domain.game.entity.Game;
import org.hibernate.annotations.Immutable;

/** 게임별로 실제 확인한 연속 작성일 구간. 쉬었던 주는 미수집으로 남는다. */
@Entity
@Immutable
public class ReviewCollection {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Game game;

    private LocalDate firstReviewDate;

    private LocalDate collectedThrough;
}
