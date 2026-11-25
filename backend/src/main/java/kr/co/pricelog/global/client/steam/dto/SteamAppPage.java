package kr.co.pricelog.global.client.steam.dto;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** 한 종류의 앱 목록. 마지막 페이지에서는 다음 요청에 쓸 커서가 비어 있다. */
public record SteamAppPage(List<SteamApp> apps, OptionalLong nextCursor) {

    /** 응답 목록을 복사해 호출자가 관측 결과를 변경할 수 없게 한다. */
    public SteamAppPage {
        apps = List.copyOf(apps);
        Objects.requireNonNull(nextCursor);
    }

    /** 이 페이지의 앱 번호를 목록 순서로 반환한다. */
    public List<Long> appIds() {
        return apps.stream().map(SteamApp::appId).toList();
    }
}
