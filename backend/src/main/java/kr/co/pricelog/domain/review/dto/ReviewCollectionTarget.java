package kr.co.pricelog.domain.review.dto;

import java.time.LocalDate;

/** 수집할 게임의 내부 키·Steam 앱 번호와 첫 미수집 작성일·담당일. */
public record ReviewCollectionTarget(Long gameId, Long steamAppId, LocalDate firstReviewDate,
                                     LocalDate collectedThrough, LocalDate dueOn) {

    /** 첫 수집은 배정된 시작일부터, 이후 수집은 마지막으로 확인한 날의 다음 날부터 읽는다. */
    public LocalDate startDate() {
        return collectedThrough == null ? firstReviewDate : collectedThrough.plusDays(1);
    }
}
