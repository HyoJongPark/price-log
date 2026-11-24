package kr.co.pricelog.domain.purchaseoption.service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.co.pricelog.domain.game.entity.Game;
import kr.co.pricelog.domain.game.enums.StoreVisibility;
import kr.co.pricelog.domain.game.repository.GameRepository;
import kr.co.pricelog.domain.price.repository.PriceHistoryRepository;
import kr.co.pricelog.domain.purchaseoption.dto.ClosedPurchaseOption;
import kr.co.pricelog.domain.purchaseoption.entity.GamePurchaseOption;
import kr.co.pricelog.domain.purchaseoption.entity.PurchaseOption;
import kr.co.pricelog.domain.purchaseoption.repository.GamePurchaseOptionRepository;
import kr.co.pricelog.domain.purchaseoption.repository.PurchaseOptionRepository;
import kr.co.pricelog.global.client.steam.dto.SteamPrice;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOption;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOptions;
import kr.co.pricelog.global.client.steam.enums.SteamPurchaseOptionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 한국 상점에서 확인한 게임을 배치 단위로 반영한다. 가격은 상품별로, 대표 옵션과 무료 여부는 게임별로 관리한다. */
@Service
@Transactional
@RequiredArgsConstructor
public class GamePurchaseOptionService {

    private final GameRepository gameRepository;
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
        confirmed.forEach((appId, options) -> {
            Game game = gamesByAppId.get(appId);
            if (game == null) {
                throw new IllegalStateException("활성 카탈로그에 없는 Steam 앱: " + appId);
            }

            game.updateStoreVisibility(options.storeVisibility());
            if (options.storeVisibility() != StoreVisibility.VISIBLE) {
                // 상점 비노출 응답으로는 구매 옵션이나 무료 여부의 변화를 판정할 수 없다.
                return;
            }

            if (isAffectedBySaleEnd(appId, options, closedSales)) {
                recordUnchangedOptionHistory(options, activeOptions, closedSales.endedOptions(),
                    slotAt, observedAt);
            } else {
                updateGameOptions(game, options, activeOptions, slotAt, observedAt);
            }
        });
    }

    private Map<PurchaseOptionKey, PurchaseOption> loadActiveOptions(
            Collection<SteamPurchaseOptions> results) {
        List<PurchaseOptionKey> keys = results.stream()
            .flatMap(result -> result.purchaseOptions().stream())
            .map(option -> new PurchaseOptionKey(option.type(), option.steamId()))
            .distinct()
            .toList();
        Map<PurchaseOptionKey, PurchaseOption> activeOptions = new HashMap<>();
        if (keys.isEmpty()) {
            return activeOptions;
        }

        // SQL에서 두 배열의 같은 위치를 한 상품으로 묶으므로 둘 다 같은 키 목록에서 꺼낸다.
        String[] types = keys.stream().map(key -> key.type().name()).toArray(String[]::new);
        Long[] steamIds = keys.stream().map(PurchaseOptionKey::steamId).toArray(Long[]::new);
        for (PurchaseOption option : purchaseOptionRepository.findActiveByKeys(types, steamIds)) {
            activeOptions.put(new PurchaseOptionKey(option.getType(), option.getSteamId()), option);
        }
        return activeOptions;
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

    /** 과거 회차를 재생할 때는 판매가 이어진 기존 상품의 가격 이력만 보충한다. 현재가와 게임 연결은 유지한다. */
    private void recordUnchangedOptionHistory(SteamPurchaseOptions result,
                                              Map<PurchaseOptionKey, PurchaseOption> activeOptions,
                                              Set<PurchaseOptionKey> endedOptions,
                                              Instant slotAt, Instant observedAt) {
        for (SteamPurchaseOption option : result.purchaseOptions()) {
            SteamPrice price = option.price();
            PurchaseOptionKey key = new PurchaseOptionKey(option.type(), option.steamId());
            if (price == null || endedOptions.contains(key)) {
                continue;
            }

            PurchaseOption active = activeOptions.get(key);
            if (active != null) {
                insertPriceHistory(active, price, slotAt, observedAt);
            }
        }
    }

    private void updateGameOptions(Game game, SteamPurchaseOptions result,
                                   Map<PurchaseOptionKey, PurchaseOption> activeOptions,
                                   Instant slotAt, Instant observedAt) {
        game.updateFreeToPlay(result.freeToPlay());

        Map<PurchaseOption, Boolean> incomingOptions = new LinkedHashMap<>();
        for (SteamPurchaseOption steamOption : result.purchaseOptions()) {
            PurchaseOption purchaseOption = resolvePurchaseOption(steamOption, activeOptions);
            SteamPrice price = steamOption.price();
            // 여러 게임에서 같은 상품을 팔아도 해당 회차의 첫 가격만 현재가 갱신에 사용한다.
            if (price != null && insertPriceHistory(purchaseOption, price, slotAt, observedAt)) {
                purchaseOption.updatePrice(price, observedAt);
            }
            incomingOptions.put(purchaseOption, steamOption.equals(result.bestPurchaseOption()));
        }
        updateGameLinks(game, incomingOptions, observedAt);
    }

    private boolean insertPriceHistory(PurchaseOption option, SteamPrice price, Instant slotAt,
                                       Instant observedAt) {
        return priceHistoryRepository.recordPrice(option.getId(), slotAt, observedAt,
            price.currentPrice(), price.regularPrice(), price.discountEndsAt()) == 1;
    }

    private PurchaseOption resolvePurchaseOption(
            SteamPurchaseOption option, Map<PurchaseOptionKey, PurchaseOption> activeOptions) {
        PurchaseOptionKey key = new PurchaseOptionKey(option.type(), option.steamId());
        // 새 상품도 이 배치의 조회 결과에 넣어 다음 게임이 같은 상품을 판매하면 같은 행에 연결한다.
        PurchaseOption purchaseOption = activeOptions.computeIfAbsent(key,
            missing -> purchaseOptionRepository.save(
                new PurchaseOption(missing.type(), missing.steamId(), option.name())));
        purchaseOption.updateName(option.name());
        return purchaseOption;
    }

    /** 응답에서 빠진 상품은 이 게임과의 연결만 닫는다. 기존 연결과 대조하고 맵에 남은 상품은 새로 연결한다. */
    private void updateGameLinks(Game game, Map<PurchaseOption, Boolean> incomingOptions,
                                 Instant observedAt) {
        GamePurchaseOption retainedBest = null;

        List<GamePurchaseOption> activeLinks = gamePurchaseOptionRepository.findByGame(game);
        for (GamePurchaseOption link : activeLinks) {
            Boolean selected = incomingOptions.remove(link.getPurchaseOption());
            if (selected == null) {
                link.markDeleted(observedAt);
            } else if (selected) {
                retainedBest = link;
            } else {
                link.setBestOption(false);
            }
        }

        // 기존 대표의 해제·종료를 먼저 반영해야 게임당 대표 한 건 제약을 지킬 수 있다.
        gamePurchaseOptionRepository.flush();
        if (retainedBest != null) {
            retainedBest.setBestOption(true);
        }
        incomingOptions.forEach((option, selected) ->
            gamePurchaseOptionRepository.save(new GamePurchaseOption(game, option, selected)));
    }

    private record PurchaseOptionKey(SteamPurchaseOptionType type, long steamId) {}

    private record ClosedSales(Set<Long> protectedAppIds, Set<PurchaseOptionKey> endedOptions) {}
}
