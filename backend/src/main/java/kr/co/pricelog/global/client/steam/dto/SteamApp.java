package kr.co.pricelog.global.client.steam.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import kr.co.pricelog.global.client.steam.constants.SteamApiConstants;

/** Steam 목록에 공개된 앱 번호와 이름. */
public record SteamApp(@JsonProperty("appid") long appId, String name) {

    /** 앱 ID와 이름이 있는 항목만 다음 수집 단계로 전달한다. */
    public SteamApp {
        if (appId <= 0 || appId > SteamApiConstants.MAX_APP_ID || name == null) {
            throw new IllegalArgumentException("Steam 앱 ID 또는 이름이 유효하지 않습니다");
        }
    }
}
