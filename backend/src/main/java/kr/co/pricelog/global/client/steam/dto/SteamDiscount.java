package kr.co.pricelog.global.client.steam.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 상점이 활성 할인에 명시한 Unix 초 단위 종료 시각. */
public record SteamDiscount(@JsonProperty("discount_end_date") Long endDate) {}
