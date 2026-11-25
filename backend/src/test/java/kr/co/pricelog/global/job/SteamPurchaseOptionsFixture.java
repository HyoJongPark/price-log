package kr.co.pricelog.global.job;

import java.util.List;
import kr.co.pricelog.domain.game.enums.StoreVisibility;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOption;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOptions;

/** 수집기 테스트에 사용하는 이미지와 설명이 없는 정상 상점 응답. */
final class SteamPurchaseOptionsFixture {

    private SteamPurchaseOptionsFixture() {}

    static SteamPurchaseOptions visiblePurchaseOptions(List<SteamPurchaseOption> options,
                                                      SteamPurchaseOption bestPurchaseOption,
                                                      Boolean freeToPlay) {
        return new SteamPurchaseOptions(options, bestPurchaseOption, freeToPlay,
            StoreVisibility.VISIBLE, null, null, null, null, null);
    }
}
