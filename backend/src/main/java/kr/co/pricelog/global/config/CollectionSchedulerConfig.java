package kr.co.pricelog.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 카탈로그·가격, 리뷰, 동접 차트가 각각의 일정에 따라 수집되도록 실행 스레드를 분리한다. */
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
        return scheduler;
    }
}
