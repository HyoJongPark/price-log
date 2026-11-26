package kr.co.pricelog.global.job;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 담당 요일의 전날까지 집계하고 매시 미처리 앱을 재개한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pricelog.collection.daily.enabled", havingValue = "true")
public class SteamReviewScheduler {

    private final SteamReviewCollector steamReviewCollector;

    /** 자정 직후의 리뷰 집계는 한국 시각 00:15부터 시작한다. */
    @Scheduled(initialDelay = 0, scheduler = "reviewTaskScheduler")
    @Scheduled(cron = "0 15 * * * *", zone = "Asia/Seoul", scheduler = "reviewTaskScheduler")
    public void collect() {
        LocalDate slotDay = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
            .minusMinutes(15).toLocalDate();
        steamReviewCollector.collectDue(slotDay);
    }
}
