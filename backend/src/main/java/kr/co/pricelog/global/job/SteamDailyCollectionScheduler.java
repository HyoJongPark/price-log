package kr.co.pricelog.global.job;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** 한국 날짜의 카탈로그를 완주한 뒤 오전·오후에 전체 본편 가격을 관측한다. */
@Component
@ConditionalOnProperty(name = "pricelog.collection.daily.enabled", havingValue = "true")
public class SteamDailyCollectionScheduler {

    private static final String SEOUL = "Asia/Seoul";
    private static final LocalTime MORNING = LocalTime.of(3, 10);
    private static final LocalTime AFTERNOON = LocalTime.of(15, 10);

    private final SteamCatalogCollector steamCatalogCollector;
    private final SteamStoreCollector steamStoreCollector;
    private final SteamAppChangeCollector steamAppChangeCollector;
    private final int batchSize;

    // 단일 스케줄러 스레드에서 기동 호출과 정기 호출이 같은 회차에 연달아 들어오는 경우를 구분한다.
    private Instant lastStartedSlot;

    /** 한 회차에서 순서대로 사용할 수집기와 상점 요청 크기를 받는다. */
    public SteamDailyCollectionScheduler(SteamCatalogCollector steamCatalogCollector,
                                        SteamStoreCollector steamStoreCollector,
                                        SteamAppChangeCollector steamAppChangeCollector,
                                        @Value("${pricelog.collection.daily.batch-size}")
                                            int batchSize) {
        Assert.isTrue(batchSize > 0, "일별 수집 묶음 크기는 양수여야 합니다");

        this.steamCatalogCollector = steamCatalogCollector;
        this.steamStoreCollector = steamStoreCollector;
        this.steamAppChangeCollector = steamAppChangeCollector;
        this.batchSize = batchSize;
    }

    /** 서버 기동 시 한 번, 이후 매일 한국 시각 03:10과 15:10에 같은 수집 경로를 호출한다. */
    @Scheduled(initialDelay = 0)
    @Scheduled(cron = "0 10 3,15 * * *", zone = SEOUL)
    public void collect() {
        collectToday(Instant.now());
    }

    /** 변경 목록은 전수 회차 사이에 여섯 시간마다 확인한다. */
    @Scheduled(cron = "0 0 0,6,12,18 * * *", zone = SEOUL)
    public void collectChanges() {
        collectRecentChanges(Instant.now());
    }

    void collectRecentChanges(Instant now) {
        ZonedDateTime seoulNow = now.atZone(ZoneId.of(SEOUL));
        Instant slotAt = seoulNow.withHour(seoulNow.getHour() / 6 * 6)
            .truncatedTo(ChronoUnit.HOURS).toInstant();
        steamAppChangeCollector.collect(slotAt, batchSize);
    }

    void collectToday(Instant now) {
        ZonedDateTime seoulNow = now.atZone(ZoneId.of(SEOUL));
        if (seoulNow.toLocalTime().isBefore(MORNING)) {
            return;
        }

        Instant catalogSlot = seoulNow.with(MORNING).toInstant();
        Instant priceSlot = seoulNow.toLocalTime().isBefore(AFTERNOON)
            ? catalogSlot : seoulNow.with(AFTERNOON).toInstant();
        if (priceSlot.equals(lastStartedSlot)) {
            return;
        }

        lastStartedSlot = priceSlot;
        // 오후에도 오늘 목록이 완주됐는지 확인한다. 완료한 목록은 다시 요청하지 않는다.
        steamCatalogCollector.collectAll(catalogSlot);
        steamStoreCollector.collectAll(priceSlot, batchSize);
    }
}
