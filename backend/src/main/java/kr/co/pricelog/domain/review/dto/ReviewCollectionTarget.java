package kr.co.pricelog.domain.review.dto;

/** 담당 요일에 조회할 본편·DLC의 내부 키와 Steam 앱 번호. */
public record ReviewCollectionTarget(Long gameId, Long steamAppId) {}
