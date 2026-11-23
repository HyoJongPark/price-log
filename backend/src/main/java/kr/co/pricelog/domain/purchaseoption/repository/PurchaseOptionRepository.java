package kr.co.pricelog.domain.purchaseoption.repository;

import java.util.Optional;
import kr.co.pricelog.domain.purchaseoption.entity.PurchaseOption;
import kr.co.pricelog.global.client.steam.enums.SteamPurchaseOptionType;
import org.springframework.data.repository.Repository;

/** 패키지·번들의 종류와 Steam 번호로 한국 판매 상품을 식별한다. */
public interface PurchaseOptionRepository extends Repository<PurchaseOption, Long> {

    /** 같은 종류와 번호로 현재 판매 중인 상품을 찾는다. */
    Optional<PurchaseOption> findByTypeAndSteamId(
        SteamPurchaseOptionType type, long steamId);

    /** 새 판매 구간을 저장한다. */
    PurchaseOption save(PurchaseOption purchaseOption);
}
