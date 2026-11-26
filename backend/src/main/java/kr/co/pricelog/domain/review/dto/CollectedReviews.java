package kr.co.pricelog.domain.review.dto;

import java.time.LocalDate;
import java.util.Map;
import kr.co.pricelog.global.client.steam.dto.SteamReviewCounts;

/** 연속된 기간을 끝까지 확인한 앱과 그 안에서 평가가 있었던 작성일. */
public record CollectedReviews(long gameId, Map<LocalDate, SteamReviewCounts> counts) {}
