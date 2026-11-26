package kr.co.pricelog.global.client.steam.dto;

/** Steam 차트에 실린 앱의 원천 순위와 현재·최고 동시 접속자 수. */
public record SteamPlayerCount(long steamAppId, int sourceRank, int playerCount,
                               int peakPlayerCount) {}
