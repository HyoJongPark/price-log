package kr.co.pricelog.domain.game.enums;

/** 카탈로그에서 식별하는 Steam 앱 종류. */
public enum SteamAppType {
    GAME("game"),
    DLC("dlc"),
    UNKNOWN("unknown");

    private final String value;

    SteamAppType(String value) {
        this.value = value;
    }

    /** 앱 종류의 소문자 표기를 반환한다. */
    @Override
    public String toString() {
        return value;
    }
}
