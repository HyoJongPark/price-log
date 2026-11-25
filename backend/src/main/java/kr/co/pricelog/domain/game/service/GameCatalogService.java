package kr.co.pricelog.domain.game.service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.co.pricelog.domain.game.enums.SteamAppType;
import kr.co.pricelog.domain.game.repository.GameRepository;
import kr.co.pricelog.global.client.steam.dto.SteamApp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Steam에서 확인한 앱의 이름과 종류를 게임 카탈로그에 반영한다. */
@Service
@Transactional
@RequiredArgsConstructor
public class GameCatalogService {

    private final GameRepository gameRepository;

    /** 겹치는 변경 구간에서도 이미 연결된 DLC는 상점에 다시 요청하지 않는다. */
    @Transactional(readOnly = true)
    public List<Long> findUnlinkedDlcIds(List<Long> ids) {
        Set<Long> linked = gameRepository.findLinkedDlcAppIds(ids);
        return ids.stream().filter(id -> !linked.contains(id)).toList();
    }

    /** 게임 등록과 부모 연결을 페이지 단위로 함께 반영한다. */
    public void applyPage(SteamAppType type, List<SteamApp> apps, Map<Long, Long> parents,
                          Instant observedAt) {
        Long[] ids = apps.stream().map(SteamApp::appId).toArray(Long[]::new);
        String[] names = apps.stream().map(SteamApp::name).toArray(String[]::new);
        Long[] parentIds = Arrays.stream(ids).map(parents::get).toArray(Long[]::new);

        gameRepository.upsertPage(ids, names, parentIds, type, observedAt);
    }

    /** 목록에서 확인한 종류와 이름만 관측 시각 기준으로 갱신한다. */
    public void applyPage(SteamAppType type, List<SteamApp> apps, Instant observedAt) {
        applyPage(type, apps, Map.of(), observedAt);
    }
}
