package kr.co.pricelog.global.client.steam.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Steam이 반환한 앱 목록과 페이지 정보. */
public record SteamAppList(List<SteamApp> apps,
                           @JsonProperty("have_more_results") boolean hasMore,
                           @JsonProperty("last_appid") Long lastAppId) {

    /** 빈 마지막 페이지에서는 apps 필드가 생략된다. */
    public SteamAppList {
        apps = apps == null ? List.of() : List.copyOf(apps);
    }
}
