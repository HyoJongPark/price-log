package kr.co.pricelog.domain.game.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.co.pricelog.domain.game.dto.CatalogGameId;
import kr.co.pricelog.domain.game.enums.SteamAppType;
import kr.co.pricelog.domain.game.repository.GameTagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 한국 상점에서 확인한 사전 또는 게임별 표시 순서를 한 배치로 저장한다. */
@Service
@Transactional
@RequiredArgsConstructor
public class GameTagService {

    private final GameTagRepository gameTagRepository;

    /** 기존 설치의 태그만 보완할 때 다음 활성 본편·DLC 배치를 읽는다. */
    @Transactional(readOnly = true)
    public List<CatalogGameId> findGamesAfter(long steamAppId, int batchSize) {
        return gameTagRepository.findByAppTypeInAndSteamAppIdGreaterThanOrderBySteamAppId(
            List.of(SteamAppType.GAME, SteamAppType.DLC), steamAppId, Limit.of(batchSize));
    }

    /** 같은 Steam 번호의 이름만 최신 한국어 이름으로 갱신한다. */
    public void saveDictionary(Map<Long, String> names) {
        Long[] ids = names.keySet().toArray(Long[]::new);
        gameTagRepository.upsertTags(ids, names.values().toArray(String[]::new));
    }

    /** 키는 내부 게임 ID다. 빈 목록을 받은 게임도 기존 연결 정리에 포함한다. */
    public void saveGameTags(Map<Long, List<Long>> tagsByGame) {
        if (tagsByGame.isEmpty()) {
            return;
        }

        List<Long> gameIds = new ArrayList<>();
        List<Long> steamIds = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        tagsByGame.forEach((gameId, tags) -> {
            for (int index = 0; index < tags.size(); index++) {
                gameIds.add(gameId);
                steamIds.add(tags.get(index));
                positions.add(index + 1);
            }
        });

        Set<Long> distinctIds = new LinkedHashSet<>(steamIds);
        if (!distinctIds.isEmpty()) {
            gameTagRepository.upsertTags(distinctIds.toArray(Long[]::new),
                new String[distinctIds.size()]);
        }
        gameTagRepository.syncLinks(tagsByGame.keySet().toArray(Long[]::new),
            gameIds.toArray(Long[]::new), steamIds.toArray(Long[]::new),
            positions.toArray(Integer[]::new));
    }
}
