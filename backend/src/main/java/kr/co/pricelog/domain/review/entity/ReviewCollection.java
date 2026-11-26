package kr.co.pricelog.domain.review.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import java.time.LocalDate;
import kr.co.pricelog.domain.game.entity.Game;
import org.hibernate.annotations.Immutable;

/** 게임별 리뷰 수집 일정을 읽는다. 진행 위치는 리뷰 이력과 함께 일괄 SQL로 갱신한다. */
@Entity
@Immutable
public class ReviewCollection {

    @Id
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    private Game game;

    private LocalDate firstReviewDate;

    private LocalDate collectedThrough;

    private LocalDate nextDueOn;
}
