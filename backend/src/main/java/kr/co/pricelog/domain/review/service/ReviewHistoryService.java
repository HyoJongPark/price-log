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

/** 요일별 수집 대상을 조회하고 작성일별 리뷰 집계를 일괄 저장한다. */
@Service
@Transactional
@RequiredArgsConstructor
public class ReviewHistoryService {

    private final ReviewHistoryRepository reviewHistoryRepository;

    /** 현재 회차에 처리할 본편과 DLC를 내부 키순으로 읽는다. */
    @Transactional(readOnly = true)
    public List<ReviewCollectionTarget> findDue(int weekday, long afterId, int limit) {
        return reviewHistoryRepository.findDue(weekday, afterId, Limit.of(limit));
    }

    /** 여러 앱의 작성일별 집계를 한 번에 저장한다. */
    public void saveBatch(List<CollectedReviews> batch) {
        List<Long> rowGames = new ArrayList<>();
        List<LocalDate> days = new ArrayList<>();
        List<Integer> positive = new ArrayList<>();
        List<Integer> totals = new ArrayList<>();
        for (CollectedReviews app : batch) {
            app.counts().forEach((day, counts) -> {
                rowGames.add(app.gameId());
                days.add(day);
                positive.add(counts.positiveCount());
                totals.add(counts.totalCount());
            });
        }

        reviewHistoryRepository.insertBatch(rowGames.toArray(Long[]::new),
            days.toArray(LocalDate[]::new), positive.toArray(Integer[]::new),
            totals.toArray(Integer[]::new));
    }
}
