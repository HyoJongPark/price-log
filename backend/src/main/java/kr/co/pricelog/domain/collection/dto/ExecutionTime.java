package kr.co.pricelog.domain.collection.dto;

import java.time.Instant;

/** 회차의 대상 배열을 읽지 않고 예정 시각만 조회한다. */
public record ExecutionTime(Instant scheduledAt) {}
