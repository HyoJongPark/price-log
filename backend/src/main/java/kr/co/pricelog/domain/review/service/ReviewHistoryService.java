package kr.co.pricelog.domain.review.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kr.co.pricelog.domain.review.dto.CollectedReviews;
import kr.co.pricelog.domain.review.dto.ReviewCollectionTarget;
import kr.co.pricelog.domain.review.repository.ReviewHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 수집 완료 범위와 해당 범위의 양수 집계를 한 번에 커밋한다. */
@Service
@Transactional
@RequiredArgsConstructor
public class ReviewHistoryService {

    private final ReviewHistoryRepository reviewHistoryRepository;

    /** 최초 등록은 앱 번호의 나머지에 따라 오늘부터 7일에 분산한다. */
    public Long registerBatch(LocalDate today, long afterId, int limit) {
        return reviewHistoryRepository.registerBatch(today, today.getDayOfWeek().getValue() - 1,
            afterId, limit);
    }

    /** 현재 회차에 처리할 본편과 DLC를 내부 키순으로 읽는다. */
    @Transactional(readOnly = true)
    public List<ReviewCollectionTarget> findDue(LocalDate today, boolean initial, long afterId,
                                                int limit) {
        return reviewHistoryRepository.findDue(today, initial, afterId, Limit.of(limit));
    }

    /** 리뷰가 없었던 앱도 확인 범위는 다른 앱과 함께 갱신한다. */
    public void saveBatch(List<CollectedReviews> batch) {
        int size = batch.size();
        Long[] gameIds = new Long[size];
        LocalDate[] starts = new LocalDate[size];
        LocalDate[] ends = new LocalDate[size];
        LocalDate[] nextDates = new LocalDate[size];

        List<Long> rowGames = new ArrayList<>();
        List<LocalDate> days = new ArrayList<>();
        List<Integer> positive = new ArrayList<>();
        List<Integer> totals = new ArrayList<>();
        int index = 0;
        for (CollectedReviews app : batch) {
            gameIds[index] = app.gameId();
            starts[index] = app.start();
            ends[index] = app.end();
            nextDates[index] = app.nextDueOn();
            index++;

            app.counts().forEach((day, counts) -> {
                rowGames.add(app.gameId());
                days.add(day);
                positive.add(counts.positiveCount());
                totals.add(counts.totalCount());
            });
        }

        reviewHistoryRepository.insertBatch(gameIds, starts, ends, nextDates,
            rowGames.toArray(Long[]::new), days.toArray(LocalDate[]::new),
            positive.toArray(Integer[]::new), totals.toArray(Integer[]::new));
    }
}
