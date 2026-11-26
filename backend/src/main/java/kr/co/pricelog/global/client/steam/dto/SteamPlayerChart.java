package kr.co.pricelog.global.client.steam.dto;

import java.time.Instant;
import java.util.List;

/** Steam이 같은 시각에 발표한 동시 접속자 순위표. */
public record SteamPlayerChart(Instant observedAt, List<SteamPlayerCount> entries) {}
