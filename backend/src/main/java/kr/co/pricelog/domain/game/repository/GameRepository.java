package kr.co.pricelog.domain.game.repository;

import java.time.Instant;
import java.util.Optional;
import kr.co.pricelog.domain.game.entity.Game;
import kr.co.pricelog.domain.game.enums.SteamAppType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Steam 목록에서 확인한 항목을 한 페이지씩 저장한다. */
public interface GameRepository extends Repository<Game, Long> {

    /** 상점 응답을 반영할 활성 카탈로그 항목을 조회한다. */
    Optional<Game> findBySteamAppId(long steamAppId);

    /** 같은 앱이 겹치면 목록에서 얻은 필드만 갱신한다. */
    @Modifying
    @Query(value = """
        INSERT INTO game AS g
            (steam_app_id, name, app_type, catalog_updated_at, created_at, updated_at)
        SELECT DISTINCT ON (app_id) app_id, app_name, :#{#appType.name()}, :now, :now, :now
        FROM unnest(CAST(:ids AS bigint[]), CAST(:names AS text[]))
             WITH ORDINALITY AS page(app_id, app_name, position)
        ORDER BY app_id, position DESC
        ON CONFLICT (steam_app_id) DO UPDATE SET
            name = EXCLUDED.name,
            app_type = EXCLUDED.app_type,
            search_aliases = CASE
                WHEN g.name <> EXCLUDED.name AND NOT (g.name = ANY(g.search_aliases))
                THEN array_append(g.search_aliases, g.name)
                ELSE g.search_aliases END,
            parent_game_id = CASE WHEN EXCLUDED.app_type = 'GAME' THEN NULL
                                  ELSE g.parent_game_id END,
            catalog_updated_at = EXCLUDED.catalog_updated_at,
            updated_at = GREATEST(g.updated_at, EXCLUDED.updated_at)
        WHERE g.catalog_updated_at <= EXCLUDED.catalog_updated_at
          AND (g.name, g.app_type) IS DISTINCT FROM (EXCLUDED.name, EXCLUDED.app_type)
        """, nativeQuery = true)
    void upsertPage(@Param("ids") Long[] ids, @Param("names") String[] names,
                    @Param("appType") SteamAppType appType, @Param("now") Instant now);
}
