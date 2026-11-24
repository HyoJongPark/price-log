package kr.co.pricelog.global.client.steam;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/** 같은 Steam API 호스트의 목록·상점 요청이 429 대기 시각을 공유한다. */
@Component
public class SteamRequestRetry {

    private static final int MAX_ATTEMPTS = 3;

    private final AtomicReference<Instant> retryAt = new AtomicReference<>(Instant.EPOCH);

    /** 429에만 제한적으로 재시도한다. 마지막 요청이 실패해도 다음 호출에 대기 시각을 남긴다. */
    public <T> T execute(Supplier<T> request) {
        for (int attempt = 1; ; attempt++) {
            awaitRetryTime();
            try {
                return request.get();
            } catch (HttpClientErrorException.TooManyRequests failure) {
                Instant deadline = retryTime(failure.getResponseHeaders(), attempt);
                retryAt.accumulateAndGet(deadline, (a, b) -> a.isAfter(b) ? a : b);
                if (attempt == MAX_ATTEMPTS) {
                    throw failure;
                }
            }
        }
    }

    private Instant retryTime(HttpHeaders headers, int attempt) {
        Instant now = Instant.now();
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return now.plusSeconds(1L << (attempt - 1));
        }

        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds >= 0) {
                return now.plusSeconds(seconds);
            }
        } catch (NumberFormatException ignored) {
            // 다음으로 HTTP 날짜 형식을 확인한다.
        }
        try {
            return Instant.ofEpochMilli(headers.getFirstDate(HttpHeaders.RETRY_AFTER));
        } catch (IllegalArgumentException invalidDate) {
            return now.plusSeconds(1L << (attempt - 1));
        }
    }

    private void awaitRetryTime() {
        Duration delay = Duration.between(Instant.now(), retryAt.get());
        while (delay.isPositive()) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RestClientException("Steam 요청 대기가 중단되었습니다", interrupted);
            }
            delay = Duration.between(Instant.now(), retryAt.get());
        }
    }
}
