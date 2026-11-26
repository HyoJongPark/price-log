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

/** 본편·DLC의 최초 1년과 담당 요일까지의 미수집 작성일을 같은 경로로 적재한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SteamReviewCollector {

    private static final int TARGET_PAGE_SIZE = 1_000;
    private static final int APPS_PER_BATCH = 50;
    private static final int ROWS_PER_BATCH = 1_000;
    private static final int TARGET_REVIEWS_PER_CHECKPOINT = 7 * SteamReviewClient.REVIEWS_PER_PAGE;
    private static final int MIN_DAYS_PER_CHECKPOINT = 7;

    private final SteamReviewClient steamReviewClient;
    private final ReviewHistoryService reviewHistoryService;

    /** 이미 추적 중인 앱의 밀린 날짜를 먼저 처리한 뒤 새 앱의 1년치를 채운다. */
    @Retry(name = "steamCollection")
    public void collectDue(LocalDate today) {
        long afterId = 0;
        Long lastId;
        while ((lastId = reviewHistoryService.registerBatch(today, afterId, TARGET_PAGE_SIZE))
            != null) {
            afterId = lastId;
        }

        collectDue(today, false);
        collectDue(today, true);
    }

    private void collectDue(LocalDate today, boolean initial) {
        long afterId = 0;
        List<ReviewCollectionTarget> targets;
        while (!(targets = reviewHistoryService.findDue(today, initial, afterId, TARGET_PAGE_SIZE))
            .isEmpty()) {
            collectBatch(targets, today);
            afterId = targets.getLast().gameId();
        }
    }

    /** 앱 수와 실제 리뷰 행 수 모두 제한한다. HTTP 대기 중에는 DB 연결을 점유하지 않는다. */
    void collectBatch(List<ReviewCollectionTarget> targets, LocalDate today) {
        List<CollectedReviews> batch = new ArrayList<>();
        for (ReviewCollectionTarget target : targets) {
            try {
                collectTarget(target, today, batch);
            } catch (HttpClientErrorException.TooManyRequests limited) {
                savePending(batch);
                throw limited;
            } catch (RestClientException failure) {
                log.warn("Steam 구매자 평가 미수집. 앱 {}, 시작일 {}, 사유 {}",
                    target.steamAppId(), target.startDate(), failure.getMessage());
            }
        }
        savePending(batch);
    }

    private void addToBatch(List<CollectedReviews> batch, CollectedReviews result) {
        int rows = batch.stream().mapToInt(app -> app.counts().size()).sum();
        if (rows + result.counts().size() > ROWS_PER_BATCH || batch.size() == APPS_PER_BATCH) {
            savePending(batch);
        }
        batch.add(result);
    }

    private void savePending(List<CollectedReviews> batch) {
        if (!batch.isEmpty()) {
            reviewHistoryService.saveBatch(batch);
            batch.clear();
        }
    }

    private void collectTarget(ReviewCollectionTarget target, LocalDate today,
                               List<CollectedReviews> batch) {
        LocalDate due = target.dueOn();
        LocalDate latestSlot = due.plusWeeks(ChronoUnit.WEEKS.between(due, today));
        LocalDate start = target.startDate();
        LocalDate lastDay = latestSlot.minusDays(1);
        LocalDate end = lastDay.isAfter(start.plusDays(365)) ? start.plusYears(1).minusDays(1)
            : lastDay;
        long appId = target.steamAppId();
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        SteamReviewWindow first = days == 1 ? null : steamReviewClient.getReviewWindow(appId,
            start, end);

        long span = daysPerCheckpoint(start, days, first);
        while (!start.isAfter(end)) {
            LocalDate until = start.plusDays(span - 1);
            if (until.isAfter(end)) {
                until = end;
            }
            Map<LocalDate, SteamReviewCounts> counts = readPeriod(appId, start, until,
                span == days ? first : null);
            LocalDate nextDue = until.equals(lastDay) ? latestSlot.plusWeeks(1) : due;
            addToBatch(batch, new CollectedReviews(target.gameId(), start, until, nextDue,
                counts));
            if (until.isBefore(end)) {
                savePending(batch);
            }
            start = until.plusDays(1);
        }
    }

    /** 밀집 구간은 목표 리뷰 수에 맞춰 나누되 짧은 조회 구간은 한 번에 처리한다. */
    private static long daysPerCheckpoint(LocalDate start, long days, SteamReviewWindow first) {
        if (days <= MIN_DAYS_PER_CHECKPOINT || first == null || !first.completeFrom().isAfter(start)
            || first.totalMatching() <= TARGET_REVIEWS_PER_CHECKPOINT) {
            return days;
        }

        long estimatedDays = days * TARGET_REVIEWS_PER_CHECKPOINT / first.totalMatching();
        return Math.max(MIN_DAYS_PER_CHECKPOINT, estimatedDays);
    }

    /** 날짜 경계에서 잘린 페이지는 완결된 뒷부분만 사용하고 남은 날짜를 다시 조회한다. */
    private Map<LocalDate, SteamReviewCounts> readPeriod(long appId, LocalDate start,
                                                         LocalDate end, SteamReviewWindow first) {
        Map<LocalDate, SteamReviewCounts> counts = new HashMap<>();
        LocalDate until = end;
        while (until.isAfter(start)) {
            SteamReviewWindow window = first != null ? first
                : steamReviewClient.getReviewWindow(appId, start, until);
            first = null;
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
