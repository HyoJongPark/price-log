package kr.co.pricelog.domain.review.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.time.LocalDate;
import kr.co.pricelog.domain.game.entity.Game;
import kr.co.pricelog.global.entity.BaseEntity;
import org.hibernate.annotations.SQLRestriction;

/** 본편과 한국 작성일별 Steam 구매자 평가 집계. */
@Entity
@SQLRestriction("deleted_at IS NULL")
public class ReviewHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Game game;

    private LocalDate reviewDate;

    private Integer positiveCount;

    private Integer totalCount;
}
