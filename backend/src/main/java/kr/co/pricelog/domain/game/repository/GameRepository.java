package kr.co.pricelog.domain.game.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import kr.co.pricelog.domain.game.entity.Game;
import kr.co.pricelog.domain.game.enums.SteamAppType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Steam 목록에서 확인한 항목을 한 페이지씩 저장한다. */
public interface GameRepository extends Repository<Game, Long> {

    /** 한 배치의 상점 응답에 대응하는 활성 카탈로그 항목을 조회한다. */
    List<Game> findAllBySteamAppIdIn(Collection<Long> steamAppIds);

    /** 한 배치에서 달라진 상점 상태만 갱신한다. */
    @Modifying
    @Query(value = """
        UPDATE game g SET store_visibility = s.visibility, is_free = s.free,
            image_url = s.image_url, description = s.description, release_date = s.release_date,
            release_text = s.release_text, coming_soon = s.coming_soon,
            korean_support_text = s.korean_support, updated_at = CURRENT_TIMESTAMP
        FROM unnest(CAST(:ids AS bigint[]), CAST(:visibility AS text[]),
                    CAST(:free AS boolean[]), CAST(:imageUrls AS text[]),
                    CAST(:descriptions AS text[]), CAST(:releaseDates AS date[]),
                    CAST(:releaseTexts AS text[]), CAST(:comingSoon AS boolean[]),
                    CAST(:koreanSupport AS text[]))
             AS s(id, visibility, free, image_url, description, release_date, release_text,
                  coming_soon, korean_support)
        WHERE g.id = s.id AND g.deleted_at IS NULL
        """, nativeQuery = true)
    void updateStoreStates(Long[] ids, String[] visibility, Boolean[] free, String[] imageUrls,
                           String[] descriptions, LocalDate[] releaseDates, String[] releaseTexts,
                           Boolean[] comingSoon, String[] koreanSupport);

    /** 해당 페이지에서 활성 본편에 이미 연결된 DLC 앱 번호만 조회한다. */
    @Query("""
        SELECT dlc.steamAppId FROM Game dlc JOIN Game parent ON parent.id = dlc.parentGameId
        WHERE dlc.steamAppId IN :ids AND dlc.appType = DLC AND parent.appType = GAME
            AND parent.steamAppId NOT IN :ids
        """)
    Set<Long> findLinkedDlcAppIds(Collection<Long> ids);

    /** 같은 페이지에 DLC로 나타난 앱은 부모 후보에서 제외한다. */
    @Modifying
    @Query(value = """
        WITH page AS (
            SELECT * FROM unnest(CAST(:ids AS bigint[]), CAST(:names AS text[]),
                                 CAST(:parentIds AS bigint[]))
                WITH ORDINALITY AS input(app_id, app_name, parent_app_id, position)
        )
        INSERT INTO game AS g
            (steam_app_id, name, app_type, parent_game_id,
             catalog_updated_at, created_at, updated_at)
        SELECT DISTINCT ON (app_id) app_id, app_name, :#{#appType.name()},
            CASE WHEN parent_app_id IS NOT NULL AND parent_app_id NOT IN (SELECT app_id FROM page)
                 THEN (SELECT p.id FROM game p WHERE p.steam_app_id = page.parent_app_id
                       AND p.app_type = 'GAME' AND p.deleted_at IS NULL) END,
            :now, :now, :now
        FROM page
        ORDER BY app_id, position DESC
        ON CONFLICT (steam_app_id) DO UPDATE SET
            name = EXCLUDED.name,
            app_type = EXCLUDED.app_type,
            search_aliases = CASE
                WHEN g.name <> EXCLUDED.name AND NOT (g.name = ANY(g.search_aliases))
                THEN array_append(g.search_aliases, g.name)
                ELSE g.search_aliases END,
            parent_game_id = CASE WHEN EXCLUDED.app_type = 'GAME' THEN NULL
                WHEN g.deleted_at IS NULL THEN coalesce(EXCLUDED.parent_game_id, g.parent_game_id)
                ELSE g.parent_game_id END,
            catalog_updated_at = EXCLUDED.catalog_updated_at,
            updated_at = GREATEST(g.updated_at, EXCLUDED.updated_at)
        WHERE g.catalog_updated_at <= EXCLUDED.catalog_updated_at
          AND ((g.name, g.app_type) IS DISTINCT FROM (EXCLUDED.name, EXCLUDED.app_type)
            OR (g.deleted_at IS NULL AND EXCLUDED.parent_game_id IS NOT NULL
                AND g.parent_game_id IS DISTINCT FROM EXCLUDED.parent_game_id))
        """, nativeQuery = true)
    void upsertPage(@Param("ids") Long[] ids, @Param("names") String[] names,
                    @Param("parentIds") Long[] parentIds, @Param("appType") SteamAppType appType,
                    @Param("now") Instant now);
}
