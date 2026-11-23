package kr.co.pricelog.domain.game.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import kr.co.pricelog.domain.game.enums.SteamAppType;
import kr.co.pricelog.global.entity.BaseEntity;
import org.hibernate.annotations.SQLRestriction;

/** Steam 앱 번호로 식별하는 카탈로그 항목. */
@Entity
@SQLRestriction("deleted_at IS NULL")
public class Game extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private long steamAppId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SteamAppType appType;

    @Column(nullable = false)
    private Instant catalogUpdatedAt;

    @Column(name = "is_free")
    private Boolean freeToPlay;

    /** 한국 상점 앱 응답의 무료 표기를 반영한다. 필드가 생략되면 미확인으로 둔다. */
    public void updateFreeToPlay(Boolean freeToPlay) {
        this.freeToPlay = freeToPlay;
    }
}
