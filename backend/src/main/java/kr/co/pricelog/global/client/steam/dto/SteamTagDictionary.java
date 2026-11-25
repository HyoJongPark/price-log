package kr.co.pricelog.global.client.steam.dto;

import java.util.Map;

/** 한국어 태그 사전 전체와 다음 변경 확인에 사용할 Steam 버전. */
public record SteamTagDictionary(String versionHash, Map<Long, String> names) {}
