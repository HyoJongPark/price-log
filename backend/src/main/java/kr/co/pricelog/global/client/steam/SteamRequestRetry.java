package kr.co.pricelog.global.client.steam;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/** 단일 수집 스레드에서 목록·상점 요청이 같은 Steam 호스트의 다음 요청 가능 시각을 공유한다. */
@Component
public class SteamRequestRetry {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration REQUEST_INTERVAL = Duration.ofMillis(600);

    private Instant nextRequestAt = Instant.EPOCH;

    /** 429에만 제한적으로 재시도한다. 마지막 요청이 실패해도 다음 호출에 대기 시각을 남긴다. */
    public <T> T execute(Supplier<T> request) {
        for (int attempt = 1; ; attempt++) {
            awaitNextRequest();
            try {
                return request.get();
            } catch (HttpClientErrorException.TooManyRequests failure) {
                nextRequestAt = retryTime(failure.getResponseHeaders(), attempt);
                if (attempt == MAX_ATTEMPTS) {
                    throw failure;
                }
            } finally {
                Instant earliestNextRequest = Instant.now().plus(REQUEST_INTERVAL);
                if (earliestNextRequest.isAfter(nextRequestAt)) {
                    nextRequestAt = earliestNextRequest;
                }
            }
        }
    }

    private Instant retryTime(HttpHeaders headers, int attempt) {
        Instant now = Instant.now();
        Instant fallback = now.plusSeconds(1L << (attempt - 1));
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return fallback;
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
            return fallback;
        }
    }

    private void awaitNextRequest() {
        Duration delay = Duration.between(Instant.now(), nextRequestAt);
        if (!delay.isPositive()) {
            return;
        }

        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RestClientException("Steam 요청 대기가 중단되었습니다", interrupted);
        }
    }
}
