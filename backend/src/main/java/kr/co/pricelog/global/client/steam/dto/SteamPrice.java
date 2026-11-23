package kr.co.pricelog.global.client.steam.dto;

import java.time.Instant;

/** 한국 비로그인 상점의 한 판매 상품에 대해 확인한 원화 가격. */
public record SteamPrice(long currentPrice, Long regularPrice, Instant discountEndsAt) {}
