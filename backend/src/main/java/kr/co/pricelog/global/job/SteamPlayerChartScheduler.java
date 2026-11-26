package kr.co.pricelog.global.job;

import kr.co.pricelog.domain.playercount.service.PlayerCountService;
import kr.co.pricelog.global.client.steam.SteamPlayerChartClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 기동 시와 매 15분에 Steam이 마지막으로 발표한 차트를 확인한다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pricelog.collection.daily.enabled", havingValue = "true")
public class SteamPlayerChartScheduler {

    private final SteamPlayerChartClient steamPlayerChartClient;
    private final PlayerCountService playerCountService;

    /** HTTP 응답 전체를 확인한 뒤 저장 트랜잭션을 연다. */
    @Scheduled(initialDelay = 0, scheduler = "playerChartTaskScheduler")
    @Scheduled(cron = "0 */15 * * * *", zone = "Asia/Seoul", scheduler = "playerChartTaskScheduler")
    public void collect() {
        MDC.put("collectionTask", "[STEAM_PLAYER_CHART] 동접 차트 수집");
        log.info("[STEAM_PLAYER_CHART] 동접 차트 수집 시작");
        playerCountService.saveChart(steamPlayerChartClient.getCurrentChart());
        log.info("[STEAM_PLAYER_CHART] 동접 차트 수집 종료");
    }
}
