package kr.co.pricelog.domain.purchaseoption.service;

import static kr.co.pricelog.global.client.steam.enums.SteamPurchaseOptionType.BUNDLE;
import static kr.co.pricelog.global.client.steam.enums.SteamPurchaseOptionType.PACKAGE;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.co.pricelog.domain.game.entity.Game;
import kr.co.pricelog.domain.game.enums.StoreVisibility;
import kr.co.pricelog.domain.game.repository.GameRepository;
import kr.co.pricelog.domain.game.service.GameTagService;
import kr.co.pricelog.domain.price.repository.PriceHistoryRepository;
import kr.co.pricelog.domain.purchaseoption.dto.ClosedPurchaseOption;
import kr.co.pricelog.domain.purchaseoption.entity.PurchaseOption;
import kr.co.pricelog.domain.purchaseoption.repository.GamePurchaseOptionRepository;
import kr.co.pricelog.domain.purchaseoption.repository.PurchaseOptionRepository;
import kr.co.pricelog.global.client.steam.dto.SteamPrice;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOption;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOptions;
import kr.co.pricelog.global.client.steam.dto.SteamRelease;
import kr.co.pricelog.global.client.steam.enums.SteamPurchaseOptionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 한국 상점에서 확인한 게임을 배치 단위로 반영한다. 가격은 상품별로, 대표 옵션과 무료 여부는 게임별로 관리한다. */
@Service
@Transactional
@RequiredArgsConstructor
public class GamePurchaseOptionService {

    // IN 조건은 ID마다 바인딩한다. 큰 입력도 PostgreSQL의 문장당 파라미터 한도 안에서 읽는다.
    private static final int OPTIONS_PER_QUERY = 30_000;

    private final GameRepository gameRepository;
    private final GameTagService gameTagService;
    private final PurchaseOptionRepository purchaseOptionRepository;
    private final GamePurchaseOptionRepository gamePurchaseOptionRepository;
    private final PriceHistoryRepository priceHistoryRepository;

    /** 판매 종료가 확인된 내부 상품과 그 상품을 제공하는 게임 연결을 함께 닫는다. */
    public void endSale(long purchaseOptionId, Instant endedAt) {
        purchaseOptionRepository.findByIdAndSaleEndedAtIsNull(purchaseOptionId)
            .ifPresent(option -> {
                option.endSale(endedAt);
                gamePurchaseOptionRepository.findByPurchaseOption(option)
                    .forEach(link -> link.markDeleted(endedAt));
            });
    }

    /** 한 배치의 게임과 상품을 먼저 조회한 뒤, 요청 순서대로 각 게임의 구매 옵션을 반영한다. */
    public void applyPurchaseOptions(Map<Long, SteamPurchaseOptions> confirmed, Instant slotAt,
                                     Instant observedAt) {
        if (confirmed.isEmpty()) {
            return;
        }

        Map<Long, Game> gamesByAppId = new HashMap<>();
        for (Game game : gameRepository.findAllBySteamAppIdIn(confirmed.keySet())) {
            gamesByAppId.put(game.getSteamAppId(), game);
        }

        Map<PurchaseOptionKey, PurchaseOption> activeOptions =
            loadActiveOptions(confirmed.values());
        ClosedSales closedSales = loadClosedSales(slotAt);
        Map<Long, List<Long>> tagsByGame = new HashMap<>();
        List<GameState> changedGames = new ArrayList<>();
        Set<Long> confirmedGameIds = new HashSet<>();
        Map<PurchaseOptionKey, String> names = new HashMap<>();
        Map<Link, Boolean> links = new LinkedHashMap<>();
        Map<PurchaseOptionKey, Observation> prices = new LinkedHashMap<>();

        for (Map.Entry<Long, SteamPurchaseOptions> entry : confirmed.entrySet()) {
            Game game = gamesByAppId.get(entry.getKey());
            if (game == null) {
                throw new IllegalStateException("활성 카탈로그에 없는 Steam 앱: " + entry.getKey());
            }

            SteamPurchaseOptions result = entry.getValue();
            boolean visible = result.storeVisibility() == StoreVisibility.VISIBLE;
            // 태그는 재개한 회차에서도 지금 확인한 앱의 순서를 따른다.
            if (visible && result.tagIds() != null) {
                tagsByGame.put(game.getId(), result.tagIds());
            }

            boolean protectedSale = visible && isAffectedBySaleEnd(entry.getKey(), result,
                closedSales);
            boolean currentStoreState = visible && !protectedSale;
            Boolean free = currentStoreState && result.freeToPlay() != null
                ? result.freeToPlay() : game.getFreeToPlay();
            String imageUrl = currentStoreState && result.imageUrl() != null
                ? result.imageUrl() : game.getImageUrl();
            String description = currentStoreState && result.description() != null
                ? result.description() : game.getDescription();

            SteamRelease release = currentStoreState ? result.release() : null;
            // 출시 예정이 끝나면 예고 일정은 지우고, 이미 확인한 출시일은 날짜 생략 시 보존한다.
            boolean updateSchedule = release != null
                && (release.comingSoon() || release.date() != null || game.getComingSoon());
            String koreanSupport = currentStoreState && result.koreanSupportText() != null
                ? result.koreanSupportText() : game.getKoreanSupportText();
            GameState state = new GameState(game.getId(), result.storeVisibility(), free,
                imageUrl, description, updateSchedule ? release.date() : game.getReleaseDate(),
                updateSchedule ? release.text() : game.getReleaseText(),
                release == null ? game.getComingSoon() : Boolean.valueOf(release.comingSoon()),
                koreanSupport);
            if (state.differsFrom(game)) {
                changedGames.add(state);
            }
            if (!visible) {
                continue;
            }

            if (protectedSale) {
                collectHistoricalPrices(result, closedSales.endedOptions(), activeOptions.keySet(),
                    names.keySet(), prices);
                continue;
            }

            // 빈 목록도 정상 응답이면 이 게임의 기존 연결을 닫는다.
            confirmedGameIds.add(game.getId());
            for (SteamPurchaseOption option : result.purchaseOptions()) {
                PurchaseOptionKey key = new PurchaseOptionKey(option.type(), option.steamId());
                names.put(key, option.name());
                links.put(new Link(game.getId(), key), option.equals(result.bestPurchaseOption()));
                collectFirstPrice(prices, key, option.price(), true);
            }
        }

        saveGameStates(changedGames);
        gameTagService.saveGameTags(tagsByGame);
        saveChangedNames(names, activeOptions);
        saveLinks(confirmedGameIds, links, activeOptions, observedAt);
        savePrices(prices, activeOptions, slotAt, observedAt);
    }

    /** 과거 회차에서는 요청 순서상 이미 확인한 상품 중 판매가 이어진 상품의 이력만 보충한다. */
    private void collectHistoricalPrices(SteamPurchaseOptions result, Set<PurchaseOptionKey> ended,
                                         Set<PurchaseOptionKey> active,
                                         Set<PurchaseOptionKey> seenInBatch,
                                         Map<PurchaseOptionKey, Observation> prices) {
        for (SteamPurchaseOption option : result.purchaseOptions()) {
            PurchaseOptionKey key = new PurchaseOptionKey(option.type(), option.steamId());
            if (!ended.contains(key) && (active.contains(key) || seenInBatch.contains(key))) {
                collectFirstPrice(prices, key, option.price(), false);
            }
        }
    }

    /** 공유 상품은 요청 순서의 첫 유효 가격과 그 관측의 현재가 갱신 여부를 함께 보존한다. */
    private void collectFirstPrice(Map<PurchaseOptionKey, Observation> prices,
                                   PurchaseOptionKey key, SteamPrice price, boolean updateLatest) {
        if (price != null) {
            prices.putIfAbsent(key, new Observation(price, updateLatest));
        }
    }

    private Map<PurchaseOptionKey, PurchaseOption> loadActiveOptions(
            Collection<SteamPurchaseOptions> results) {
        List<PurchaseOptionKey> keys = results.stream()
            .flatMap(result -> result.purchaseOptions().stream())
            .map(option -> new PurchaseOptionKey(option.type(), option.steamId()))
            .distinct()
            .toList();
        Map<PurchaseOptionKey, PurchaseOption> activeOptions = new HashMap<>();

        for (int start = 0; start < keys.size(); start += OPTIONS_PER_QUERY) {
            List<PurchaseOptionKey> batch = keys.subList(start,
                Math.min(start + OPTIONS_PER_QUERY, keys.size()));
            List<PurchaseOption> options = purchaseOptionRepository.findActiveOptions(
                steamIds(batch, PACKAGE), steamIds(batch, BUNDLE));
            for (PurchaseOption option : options) {
                activeOptions.put(new PurchaseOptionKey(option.getType(), option.getSteamId()),
                    option);
            }
        }
        return activeOptions;
    }

    private List<Long> steamIds(List<PurchaseOptionKey> keys, SteamPurchaseOptionType type) {
        return keys.stream().filter(key -> key.type() == type)
            .map(PurchaseOptionKey::steamId).toList();
    }

    private ClosedSales loadClosedSales(Instant slotAt) {
        Set<Long> protectedAppIds = new HashSet<>();
        Set<PurchaseOptionKey> endedOptions = new HashSet<>();
        for (ClosedPurchaseOption row : purchaseOptionRepository.findClosedSince(slotAt)) {
            SteamPurchaseOptionType type = SteamPurchaseOptionType.valueOf(row.type());
            endedOptions.add(new PurchaseOptionKey(type, row.steamId()));
            if (row.steamAppId() != null) {
                protectedAppIds.add(row.steamAppId());
            }
        }
        return new ClosedSales(protectedAppIds, endedOptions);
    }

    /**
     * 종료·재판매 구간에 연결된 게임은 과거 회차의 빈 응답으로 현재 연결이 닫힐 수 있어 보호한다.
     * 연결 기록이 없는 게임도 응답에 종료된 상품이 있으면 같은 규칙을 적용한다.
     */
    private boolean isAffectedBySaleEnd(long appId, SteamPurchaseOptions options,
                                        ClosedSales closedSales) {
        if (closedSales.protectedAppIds().contains(appId)) {
            return true;
        }
        return !closedSales.endedOptions().isEmpty() && options.purchaseOptions().stream()
            .anyMatch(option -> closedSales.endedOptions().contains(
                new PurchaseOptionKey(option.type(), option.steamId())));
    }

    private void saveGameStates(List<GameState> changedGames) {
        if (!changedGames.isEmpty()) {
            gameRepository.updateStoreStates(
                changedGames.stream().map(GameState::id).toArray(Long[]::new),
                changedGames.stream().map(s -> s.visibility().name()).toArray(String[]::new),
                changedGames.stream().map(GameState::free).toArray(Boolean[]::new),
                changedGames.stream().map(GameState::imageUrl).toArray(String[]::new),
                changedGames.stream().map(GameState::description).toArray(String[]::new),
                changedGames.stream().map(GameState::releaseDate).toArray(LocalDate[]::new),
                changedGames.stream().map(GameState::releaseText).toArray(String[]::new),
                changedGames.stream().map(GameState::comingSoon).toArray(Boolean[]::new),
                changedGames.stream().map(GameState::koreanSupport).toArray(String[]::new));
        }
    }

    private void saveChangedNames(Map<PurchaseOptionKey, String> names,
                                  Map<PurchaseOptionKey, PurchaseOption> activeOptions) {
        List<PurchaseOptionKey> changed = names.keySet().stream().filter(key -> {
            PurchaseOption active = activeOptions.get(key);
            return active == null || !Objects.equals(active.getName(), names.get(key));
        }).toList();
        if (changed.isEmpty()) {
            return;
        }

        String[] types = changed.stream().map(key -> key.type().name()).toArray(String[]::new);
        Long[] ids = changed.stream().map(PurchaseOptionKey::steamId).toArray(Long[]::new);
        String[] changedNames = changed.stream().map(names::get).toArray(String[]::new);
        for (PurchaseOption option : purchaseOptionRepository.saveNames(types, ids, changedNames)) {
            activeOptions.put(new PurchaseOptionKey(option.getType(), option.getSteamId()), option);
        }
    }

    private void saveLinks(Set<Long> confirmedGames, Map<Link, Boolean> links,
                           Map<PurchaseOptionKey, PurchaseOption> active, Instant observedAt) {
        if (confirmedGames.isEmpty()) {
            return;
        }

        Long[] games = links.keySet().stream().map(Link::gameId).toArray(Long[]::new);
        Long[] options = links.keySet().stream().map(link -> active.get(link.option()).getId())
            .toArray(Long[]::new);
        Boolean[] best = links.values().toArray(Boolean[]::new);

        // 게임당 대표 한 건 제약을 위해 기존 대표 해제·종료 후 새 대표를 반영한다.
        gamePurchaseOptionRepository.closeOrRelease(confirmedGames.toArray(Long[]::new), games,
            options, best, observedAt);
        if (!links.isEmpty()) {
            gamePurchaseOptionRepository.saveLinks(games, options, best);
        }
    }

    private void savePrices(Map<PurchaseOptionKey, Observation> prices,
                            Map<PurchaseOptionKey, PurchaseOption> active, Instant slotAt,
                            Instant observedAt) {
        if (prices.isEmpty()) {
            return;
        }

        Long[] ids = prices.keySet().stream().map(key -> active.get(key).getId())
            .toArray(Long[]::new);
        List<SteamPrice> values = prices.values().stream().map(Observation::price).toList();
        priceHistoryRepository.recordPrices(ids,
            values.stream().map(SteamPrice::currentPrice).toArray(Long[]::new),
            values.stream().map(SteamPrice::regularPrice).toArray(Long[]::new),
            values.stream().map(SteamPrice::discountEndsAt).toArray(Instant[]::new),
            prices.values().stream().map(Observation::updateLatest).toArray(Boolean[]::new),
            slotAt, observedAt);
    }

    private record GameState(long id, StoreVisibility visibility, Boolean free, String imageUrl,
                             String description, LocalDate releaseDate, String releaseText,
                             Boolean comingSoon, String koreanSupport) {
        boolean differsFrom(Game game) {
            return visibility != game.getStoreVisibility()
                || !Objects.equals(free, game.getFreeToPlay())
                || !Objects.equals(imageUrl, game.getImageUrl())
                || !Objects.equals(description, game.getDescription())
                || !Objects.equals(releaseDate, game.getReleaseDate())
                || !Objects.equals(releaseText, game.getReleaseText())
                || !Objects.equals(comingSoon, game.getComingSoon())
                || !Objects.equals(koreanSupport, game.getKoreanSupportText());
        }
    }

    private record PurchaseOptionKey(SteamPurchaseOptionType type, long steamId) {}

    private record Link(long gameId, PurchaseOptionKey option) {}

    private record Observation(SteamPrice price, boolean updateLatest) {}

    private record ClosedSales(Set<Long> protectedAppIds, Set<PurchaseOptionKey> endedOptions) {}
}
