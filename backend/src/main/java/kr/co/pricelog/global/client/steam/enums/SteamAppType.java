package kr.co.pricelog.global.client.steam.enums;

/** GetAppList에서 따로 순회하는 앱 종류. */
public enum SteamAppType {
    GAME("game"),
    DLC("dlc");

    private final String value;

    SteamAppType(String value) {
        this.value = value;
    }

    /** 카탈로그에 저장하는 앱 종류의 문자열 표현을 반환한다. */
    @Override
    public String toString() {
        return value;
    }
}
