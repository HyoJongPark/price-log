package kr.co.pricelog.domain.collection.dto;

import java.time.Instant;
import java.util.Optional;

/** 변경 목록을 읽을 회차. 가격 대상이 이미 확정됐으면 조회 시작 시각은 비어 있다. */
public record SteamChangeListPlan(long executionId, Optional<Instant> modifiedSince) {}
