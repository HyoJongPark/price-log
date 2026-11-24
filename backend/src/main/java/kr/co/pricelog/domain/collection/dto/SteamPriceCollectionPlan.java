package kr.co.pricelog.domain.collection.dto;

import java.util.List;

/** 한 회차의 고정된 본편 ID 순서와 마지막 커밋에서 복원한 위치·미해결 ID. */
public record SteamPriceCollectionPlan(List<Long> targetIds, int nextIndex, List<Long> failedIds) {}
