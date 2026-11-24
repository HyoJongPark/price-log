package kr.co.pricelog.domain.purchaseoption.dto;

/** 종료 상품의 종류·Steam 번호와 이전 회차 재생에서 보호할 게임 ID. */
public record ClosedPurchaseOption(String type, Long steamId, Long steamAppId) {}
