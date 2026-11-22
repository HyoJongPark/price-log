package kr.co.pricelog.domain.game.service;

import java.time.Instant;
import java.util.List;
import kr.co.pricelog.domain.game.repository.GameRepository;
import kr.co.pricelog.global.client.steam.dto.SteamApp;
import kr.co.pricelog.global.client.steam.enums.SteamAppType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 한 페이지에서 관측한 앱을 카탈로그에 함께 반영한다. */
@Service
@Transactional
public class GameCatalogService {

    private final GameRepository gameRepository;

    /** 카탈로그 저장소를 받는다. */
    public GameCatalogService(GameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }

    /** 목록에서 확인한 종류와 이름만 관측 시각 기준으로 갱신한다. */
    public void applyPage(SteamAppType type, List<SteamApp> apps, Instant observedAt) {
        Long[] ids = apps.stream().map(SteamApp::appId).toArray(Long[]::new);
        String[] names = apps.stream().map(SteamApp::name).toArray(String[]::new);

        gameRepository.upsertPage(ids, names, type.toString(), observedAt);
    }
}
