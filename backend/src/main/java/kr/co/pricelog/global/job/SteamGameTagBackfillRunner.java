package kr.co.pricelog.global.job;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.co.pricelog.domain.game.dto.CatalogGameId;
import kr.co.pricelog.domain.game.service.GameTagService;
import kr.co.pricelog.global.client.steam.SteamStoreClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** 이미 가격을 적재한 설치에서 기존 회차와 가격 이력을 건드리지 않고 태그만 채운다. */
@Slf4j
@Component
@ConditionalOnProperty(name = "pricelog.collection.tag-backfill.after-app-id")
public class SteamGameTagBackfillRunner implements CommandLineRunner {

    private final SteamStoreClient steamStoreClient;
    private final GameTagService gameTagService;
    private final long afterAppId;
    private final int batchSize;

    /** 저장이 끝난 배치의 마지막 앱 번호를 지정하면 그 다음 앱부터 시작한다. */
    public SteamGameTagBackfillRunner(SteamStoreClient steamStoreClient,
                                      GameTagService gameTagService,
                                      @Value("${pricelog.collection.tag-backfill.after-app-id}")
                                          long afterAppId,
                                      @Value("${pricelog.collection.daily.batch-size}")
                                          int batchSize,
                                      @Value("${pricelog.collection.daily.enabled}")
                                          boolean scheduled) {
        Assert.isTrue(!scheduled, "태그 단독 보완 시 정기 수집을 끄세요");
        Assert.isTrue(afterAppId >= 0 && batchSize > 0, "시작 앱 번호 또는 배치 크기가 유효하지 않습니다");
        this.steamStoreClient = steamStoreClient;
        this.gameTagService = gameTagService;
        this.afterAppId = afterAppId;
        this.batchSize = batchSize;
    }

    @Override
    public void run(String... args) {
        long cursor = afterAppId;
        while (true) {
            List<CatalogGameId> games = gameTagService.findGamesAfter(cursor, batchSize);
            if (games.isEmpty()) {
                return;
            }

            List<Long> ids = games.stream().map(CatalogGameId::steamAppId).toList();
            Map<Long, List<Long>> confirmed = steamStoreClient.getGameTags(ids);
            Map<Long, List<Long>> tagsByGame = new HashMap<>();
            for (CatalogGameId game : games) {
                List<Long> tags = confirmed.get(game.steamAppId());
                if (tags != null) {
                    tagsByGame.put(game.id(), tags);
                }
            }
            gameTagService.saveGameTags(tagsByGame);
            cursor = ids.getLast();
            log.info("태그 보완 배치 반영. 마지막 Steam 앱 {}, 확인 {}/{}", cursor, confirmed.size(),
                ids.size());
        }
    }
}
