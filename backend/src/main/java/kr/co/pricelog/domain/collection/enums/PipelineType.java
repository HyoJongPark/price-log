package kr.co.pricelog.domain.collection.enums;

import kr.co.pricelog.domain.game.enums.SteamAppType;

/** 실행 이력을 구분하는 수집 파이프라인 종류. */
public enum PipelineType {
    STEAM_GAME_LIST,
    STEAM_DLC_LIST,
    STEAM_PRICE,
    STEAM_CHANGED_APPS;

    /** 본편과 DLC는 각자의 커서로 전체 목록을 순회한다. */
    public static PipelineType forSteamList(SteamAppType type) {
        return switch (type) {
            case GAME -> STEAM_GAME_LIST;
            case DLC -> STEAM_DLC_LIST;
            case UNKNOWN -> throw new IllegalArgumentException("미확인 앱은 목록 수집 대상이 아닙니다");
        };
    }
}
