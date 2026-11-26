package kr.co.pricelog.domain.playercount.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import kr.co.pricelog.global.entity.BaseEntity;
import org.hibernate.annotations.SQLRestriction;

/** 카탈로그 등록 여부와 관계없이 Steam 차트에 실린 앱의 관측값. */
@Entity
@SQLRestriction("deleted_at IS NULL")
public class CcuHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long steamAppId;

    private Instant observedAt;

    private Integer sourceRank;

    private Integer playerCount;

    private Integer peakPlayerCount;
}
