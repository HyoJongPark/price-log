package kr.co.pricelog.global.job;

import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매일 한국 시각 00:15에 담당 앱의 지난 7일을 집계한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pricelog.collection.daily.enabled", havingValue = "true")
public class SteamReviewScheduler {

    private final SteamReviewCollector steamReviewCollector;

    /** 자정 직후의 리뷰 집계는 한국 시각 00:15부터 시작한다. */
    @Scheduled(cron = "0 15 0 * * *", zone = "Asia/Seoul", scheduler = "reviewTaskScheduler")
    public void collect() {
        steamReviewCollector.collectDue(LocalDate.now(ZoneId.of("Asia/Seoul")));
    }
}
