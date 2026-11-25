package kr.co.pricelog.global.client.steam.dto;

import java.time.LocalDate;

/** 한국 시간 기준 공개 출시일 또는 상점의 출시 예정 문구. */
public record SteamRelease(LocalDate date, String text, boolean comingSoon) {}
