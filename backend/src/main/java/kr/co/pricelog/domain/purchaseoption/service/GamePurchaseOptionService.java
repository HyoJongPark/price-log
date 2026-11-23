package kr.co.pricelog.domain.purchaseoption.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.co.pricelog.domain.game.entity.Game;
import kr.co.pricelog.domain.game.repository.GameRepository;
import kr.co.pricelog.domain.price.repository.PriceHistoryRepository;
import kr.co.pricelog.domain.purchaseoption.entity.GamePurchaseOption;
import kr.co.pricelog.domain.purchaseoption.entity.PurchaseOption;
import kr.co.pricelog.domain.purchaseoption.repository.GamePurchaseOptionRepository;
import kr.co.pricelog.domain.purchaseoption.repository.PurchaseOptionRepository;
import kr.co.pricelog.global.client.steam.dto.SteamPrice;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOption;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 한 게임의 한국 상점 응답을 상품 목록·대표 옵션·무료 표기에 함께 반영한다. */
@Service
@Transactional
@RequiredArgsConstructor
public class GamePurchaseOptionService {

    private final GameRepository gameRepository;
    private final PurchaseOptionRepository purchaseOptionRepository;
    private final GamePurchaseOptionRepository gamePurchaseOptionRepository;
    private final PriceHistoryRepository priceHistoryRepository;

    /** 성공한 응답에 있는 상품은 연결하고 목록에서 사라진 연결은 닫는다. */
    public void applyPurchaseOptions(long steamAppId, SteamPurchaseOptions result,
                                     Instant slotAt, Instant observedAt) {
        Game game = gameRepository.findBySteamAppId(steamAppId)
            .orElseThrow(() -> new IllegalStateException("활성 카탈로그에 없는 Steam 앱: " + steamAppId));
        game.updateFreeToPlay(result.freeToPlay());

        Map<PurchaseOption, Boolean> incomingOptions = new LinkedHashMap<>();
        for (SteamPurchaseOption steamOption : result.purchaseOptions()) {
            PurchaseOption purchaseOption = resolvePurchaseOption(steamOption);
            recordPrice(purchaseOption, steamOption.price(), slotAt, observedAt);
            incomingOptions.put(purchaseOption, steamOption.equals(result.bestPurchaseOption()));
        }
        updateGameLinks(game, incomingOptions, observedAt);
    }

    private void recordPrice(PurchaseOption option, SteamPrice price, Instant slotAt,
                             Instant observedAt) {
        if (price == null) {
            return;
        }
        int inserted = priceHistoryRepository.recordPrice(option.getId(), slotAt, observedAt,
            price.currentPrice(), price.regularPrice(), price.discountEndsAt());
        if (inserted == 1) {
            option.updatePrice(price, observedAt);
        }
    }

    private PurchaseOption resolvePurchaseOption(SteamPurchaseOption option) {
        PurchaseOption purchaseOption = purchaseOptionRepository
            .findByTypeAndSteamId(option.type(), option.steamId())
            .orElseGet(() -> purchaseOptionRepository.save(
                new PurchaseOption(option.type(), option.steamId(), option.name())));
        purchaseOption.updateName(option.name());
        return purchaseOption;
    }

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
}
