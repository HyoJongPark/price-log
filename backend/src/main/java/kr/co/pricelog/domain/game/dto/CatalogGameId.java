package kr.co.pricelog.domain.game.dto;

/** 앱별 태그를 조회할 Steam 앱 번호와 연결을 저장할 내부 게임 키. */
public record CatalogGameId(Long id, Long steamAppId) {}
