package kr.co.pricelog.global.config;

import kr.co.pricelog.global.client.steam.SteamResponseException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 카탈로그·가격, 리뷰, 동접 차트가 각각의 일정에 따라 수집되도록 실행 스레드를 분리한다. */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "pricelog.collection.daily.enabled", havingValue = "true")
public class CollectionSchedulerConfig {

    @Bean
    @Primary
    ThreadPoolTaskScheduler taskScheduler() {
        return scheduler("collection-");
    }

    @Bean
    ThreadPoolTaskScheduler reviewTaskScheduler() {
        return scheduler("review-collection-");
    }

    @Bean
    ThreadPoolTaskScheduler playerChartTaskScheduler() {
        return scheduler("player-chart-");
    }

    private ThreadPoolTaskScheduler scheduler(String prefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix(prefix);
        // 같은 스레드가 다음 예약 작업을 실행할 때 이전 회차의 로그 문맥을 넘기지 않는다.
        scheduler.setTaskDecorator(task -> () -> {
            try {
                task.run();
            } finally {
                MDC.remove("collectionTask");
            }
        });
        scheduler.setErrorHandler(CollectionSchedulerConfig::logFailure);
        return scheduler;
    }

    private static void logFailure(Throwable failure) {
        String task = MDC.get("collectionTask");
        log.error("{} 실패. reason={}", task == null ? "수집 작업" : task,
            SteamResponseException.reasonForLog(failure));
    }
}
