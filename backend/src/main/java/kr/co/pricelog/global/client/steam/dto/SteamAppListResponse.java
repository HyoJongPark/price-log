package kr.co.pricelog.global.client.steam.dto;

/** Steam GetAppList의 최상위 JSON 객체. 목록은 response 필드에 들어 있다. */
public record SteamAppListResponse(SteamAppList response) {}
