package kr.co.pricelog.global.client.steam.dto;

/** Steam에서 지정한 작성 기간에 확인한 구매자 평가 건수. */
public record SteamReviewCounts(int positiveCount, int totalCount) {}
