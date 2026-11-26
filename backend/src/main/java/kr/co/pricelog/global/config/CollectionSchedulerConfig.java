package kr.co.pricelog.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 리뷰의 장시간 HTTP 대기가 기존 단일 카탈로그·가격 스레드를 점유하지 않게 한다. */
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

    private ThreadPoolTaskScheduler scheduler(String prefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix(prefix);
        return scheduler;
    }
}
