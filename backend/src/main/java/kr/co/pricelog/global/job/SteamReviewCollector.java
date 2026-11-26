package kr.co.pricelog.global.job;

import io.github.resilience4j.retry.annotation.Retry;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.co.pricelog.domain.review.dto.CollectedReviews;
import kr.co.pricelog.domain.review.dto.ReviewCollectionTarget;
import kr.co.pricelog.domain.review.service.ReviewHistoryService;
import kr.co.pricelog.global.client.steam.SteamReviewClient;
import kr.co.pricelog.global.client.steam.dto.SteamReviewCounts;
import kr.co.pricelog.global.client.steam.dto.SteamReviewWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/** 담당 요일의 본편·DLC에서 지난 7일의 구매자 평가를 적재한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SteamReviewCollector {

    private static final int TARGET_PAGE_SIZE = 1_000;
    private static final int APPS_PER_BATCH = 250;
    private static final int ROWS_PER_BATCH = 1_000;

    private final SteamReviewClient steamReviewClient;
    private final ReviewHistoryService reviewHistoryService;

    /** 월요일을 0으로 잡고 앱 번호를 7로 나눈 나머지에 해당하는 본편·DLC만 읽는다. */
    @Retry(name = "steamCollection")
    public void collectDue(LocalDate today) {
        LocalDate start = today.minusWeeks(1);
        LocalDate end = today.minusDays(1);
        int weekday = today.getDayOfWeek().getValue() - 1;

        long afterId = 0;
        List<ReviewCollectionTarget> targets;
        while (!(targets = reviewHistoryService.findDue(weekday, start, end, afterId,
            TARGET_PAGE_SIZE)).isEmpty()) {
            collectBatch(targets, start, end);
            afterId = targets.getLast().gameId();
        }
    }

    /** HTTP 대기 중에는 연결을 빌리지 않고, 한 주를 끝까지 확인한 앱만 묶어서 저장한다. */
    private void collectBatch(List<ReviewCollectionTarget> targets, LocalDate start,
                              LocalDate end) {
        List<CollectedReviews> batch = new ArrayList<>();
        int rows = 0;
        for (ReviewCollectionTarget target : targets) {
            Map<LocalDate, SteamReviewCounts> counts;
            try {
                counts = readPeriod(target.steamAppId(), start, end);
            } catch (HttpClientErrorException.TooManyRequests limited) {
                savePending(batch, start, end);
                throw limited;
            } catch (RestClientException failure) {
                log.warn("Steam 구매자 평가 미수집. 앱 {}, 시작일 {}, 사유 {}",
                    target.steamAppId(), start, failure.getMessage());
                continue;
            }

            if (rows + counts.size() > ROWS_PER_BATCH || batch.size() == APPS_PER_BATCH) {
                savePending(batch, start, end);
                batch.clear();
                rows = 0;
            }
            batch.add(new CollectedReviews(target.gameId(), counts));
            rows += counts.size();
        }
        savePending(batch, start, end);
    }

    private void savePending(List<CollectedReviews> batch, LocalDate start, LocalDate end) {
        if (!batch.isEmpty()) {
            reviewHistoryService.saveBatch(batch, start, end);
        }
    }

    /** 날짜 경계에서 잘린 페이지는 완결된 뒷부분만 사용하고 남은 날짜를 다시 조회한다. */
    private Map<LocalDate, SteamReviewCounts> readPeriod(long appId, LocalDate start,
                                                         LocalDate end) {
        Map<LocalDate, SteamReviewCounts> counts = new HashMap<>();
        LocalDate until = end;
        while (until.isAfter(start)) {
            SteamReviewWindow window = steamReviewClient.getReviewWindow(appId, start, until);
            counts.putAll(window.counts());
            LocalDate remainingEnd = window.completeFrom().minusDays(1);
            if (remainingEnd.isBefore(start)) {
                return counts;
            }

            long days = ChronoUnit.DAYS.between(start, until) + 1;
            if (window.totalMatching() >= days * SteamReviewClient.REVIEWS_PER_PAGE) {
                until = remainingEnd;
                break;
            }
            if (remainingEnd.equals(until)) {
                readDay(appId, until, counts);
                remainingEnd = until.minusDays(1);
            }
            until = remainingEnd;
        }

        for (LocalDate day = start; !day.isAfter(until); day = day.plusDays(1)) {
            readDay(appId, day, counts);
        }
        return counts;
    }

    private void readDay(long appId, LocalDate day, Map<LocalDate, SteamReviewCounts> counts) {
        SteamReviewCounts daily = steamReviewClient.getDailyCounts(appId, day);
        if (daily.totalCount() > 0) {
            counts.put(day, daily);
        }
    }
}
