package kr.co.pricelog.domain.purchaseoption.repository;

import java.util.List;
import kr.co.pricelog.domain.game.entity.Game;
import kr.co.pricelog.domain.purchaseoption.entity.GamePurchaseOption;
import org.springframework.data.repository.Repository;

/** 게임별 활성 구매 옵션을 조회하고 연결 변경을 저장한다. */
public interface GamePurchaseOptionRepository extends Repository<GamePurchaseOption, Long> {

    /** 해당 게임에서 마지막으로 확인한 활성 연결을 읽는다. */
    List<GamePurchaseOption> findByGame(Game game);

    /** 새 구매 옵션 연결을 저장한다. */
    GamePurchaseOption save(GamePurchaseOption link);

    /** 새 대표 옵션을 지정하기 전에 기존 대표 연결의 변경을 DB에 반영한다. */
    void flush();
}
