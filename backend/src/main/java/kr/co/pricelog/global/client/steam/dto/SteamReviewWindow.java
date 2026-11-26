package kr.co.pricelog.global.client.steam.dto;

import java.time.LocalDate;
import java.util.Map;

/** 최신순 첫 페이지에서 완전히 확인한 날짜 구간. 건수가 없는 날짜는 0건이다. */
public record SteamReviewWindow(int totalMatching, LocalDate completeFrom,
                                Map<LocalDate, SteamReviewCounts> counts) {}
