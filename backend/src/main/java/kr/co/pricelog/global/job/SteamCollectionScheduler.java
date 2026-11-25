package kr.co.pricelog.global.job;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** 기동 시 카탈로그와 가격을 최초 적재하고 이후 매시간 변경분을 수집한다. */
@Component
@ConditionalOnProperty(name = "pricelog.collection.daily.enabled", havingValue = "true")
public class SteamCollectionScheduler {

    private static final String SEOUL = "Asia/Seoul";

    private final SteamCatalogCollector steamCatalogCollector;
    private final SteamStoreCollector steamStoreCollector;
    private final SteamAppChangeCollector steamAppChangeCollector;
    private final int batchSize;

    private boolean catalogInitialized;

    /** 한 회차에서 순서대로 사용할 수집기와 상점 요청 크기를 받는다. */
    public SteamCollectionScheduler(SteamCatalogCollector steamCatalogCollector,
                                   SteamStoreCollector steamStoreCollector,
                                   SteamAppChangeCollector steamAppChangeCollector,
                                   @Value("${pricelog.collection.daily.batch-size}")
                                       int batchSize) {
        Assert.isTrue(batchSize > 0, "수집 묶음 크기는 양수여야 합니다");

        this.steamCatalogCollector = steamCatalogCollector;
        this.steamStoreCollector = steamStoreCollector;
        this.steamAppChangeCollector = steamAppChangeCollector;
        this.batchSize = batchSize;
    }

    /** 기동 시 최초 카탈로그와 가격 적재 상태를 확인한 뒤 변경분을 따라잡는다. */
    @Scheduled(initialDelay = 0)
    public void initialize() {
        initialize(Instant.now());
    }

    void initialize(Instant now) {
        steamCatalogCollector.initialize(now);
        catalogInitialized = true;
        // 가격 이력은 상품과 회차 시각으로 식별한다. 매시 정각은 변경분 회차에 남겨 둔다.
        Instant priceSlot = now.truncatedTo(ChronoUnit.MICROS);
        if (priceSlot.equals(now.truncatedTo(ChronoUnit.HOURS))) {
            priceSlot = priceSlot.plus(1, ChronoUnit.MICROS);
        }
        steamStoreCollector.collectAll(priceSlot, batchSize);
        collectRecentChanges(now);
    }

    /** 매시 정각에는 변경된 본편·DLC와 해당 본편의 가격을 확인한다. */
    @Scheduled(cron = "0 0 * * * *", zone = SEOUL)
    public void collectChanges() {
        collectRecentChanges(Instant.now());
    }

    void collectRecentChanges(Instant now) {
        if (catalogInitialized) {
            steamAppChangeCollector.collect(now.truncatedTo(ChronoUnit.HOURS), batchSize);
        }
    }
}
