package kr.co.pricelog.global.client.steam.constants;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Steam 목록·상점 API에서 공통으로 사용하는 값. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SteamApiConstants {

    public static final String BASE_URL = "https://api.steampowered.com";

    /** Steam의 uint32 앱 ID 상한. */
    public static final long MAX_APP_ID = 0xffff_ffffL;
}
