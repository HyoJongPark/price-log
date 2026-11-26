package kr.co.pricelog.global.job;

import kr.co.pricelog.domain.game.service.GameTagService;
import kr.co.pricelog.global.client.steam.SteamTagDictionaryClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 기동 시 한국어 사전을 읽고 매일 같은 버전인지 확인한다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pricelog.collection.daily.enabled", havingValue = "true")
public class SteamTagDictionaryScheduler {

    private final SteamTagDictionaryClient steamTagDictionaryClient;
    private final GameTagService gameTagService;

    private String versionHash;

    /** DB 반영이 끝난 버전만 다음 요청에 전달한다. */
    @Scheduled(initialDelay = 0, fixedDelayString = "P1D")
    public void refresh() {
        MDC.put("collectionTask", "[STEAM_TAG_DICTIONARY] 태그 사전 확인");
        log.info("[STEAM_TAG_DICTIONARY] 태그 사전 확인 시작");
        steamTagDictionaryClient.getTagDictionary(versionHash).ifPresent(dictionary -> {
            gameTagService.saveDictionary(dictionary.names());
            versionHash = dictionary.versionHash();
        });
        log.info("[STEAM_TAG_DICTIONARY] 태그 사전 확인 종료");
    }
}
