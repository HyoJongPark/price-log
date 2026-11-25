package kr.co.pricelog.domain.game.repository;

import java.util.List;
import kr.co.pricelog.domain.game.dto.CatalogGameId;
import kr.co.pricelog.domain.game.entity.Game;
import kr.co.pricelog.domain.game.enums.SteamAppType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** 한국어 태그 사전과 본편의 상위 태그를 묶음 단위로 반영한다. */
public interface GameTagRepository extends Repository<Game, Long> {

    /** 기존 설치 보완에 필요한 두 식별자만 앱 번호 순으로 읽는다. */
    List<CatalogGameId> findByAppTypeAndSteamAppIdGreaterThanOrderBySteamAppId(
        SteamAppType appType, long afterAppId, Limit limit);

    /** 이름이 없는 새 ID도 먼저 연결할 수 있다. 이름을 알게 되면 기존 행을 갱신한다. */
    @Modifying
    @Query(value = """
        INSERT INTO tag AS t (steam_tag_id, name_ko)
        SELECT i.steam_id, i.name FROM unnest(CAST(:steamIds AS bigint[]),
            CAST(:names AS text[])) AS i(steam_id, name)
        WHERE NOT EXISTS (SELECT 1 FROM tag old WHERE old.steam_tag_id = i.steam_id
                          AND (i.name IS NULL OR old.name_ko = i.name))
        ON CONFLICT (steam_tag_id) DO UPDATE SET name_ko = EXCLUDED.name_ko
        WHERE EXCLUDED.name_ko IS NOT NULL AND t.name_ko IS DISTINCT FROM EXCLUDED.name_ko
        """, nativeQuery = true)
    void upsertTags(Long[] steamIds, String[] names);

    /** 명시적으로 확인한 게임에서만 순위를 바꾸거나 사라진 연결을 지운다. */
    @Modifying
    @Query(value = """
        WITH incoming AS (
            SELECT rows.game_id, t.id AS tag_id, rows.position
            FROM unnest(CAST(:gameIds AS bigint[]), CAST(:steamIds AS bigint[]),
                        CAST(:positions AS integer[])) AS rows(game_id, steam_id, position)
            JOIN tag t ON t.steam_tag_id = rows.steam_id
        ), saved AS (
            INSERT INTO game_tag AS gt (game_id, tag_id, display_order)
            SELECT i.game_id, i.tag_id, i.position FROM incoming i
            WHERE NOT EXISTS (SELECT 1 FROM game_tag old WHERE old.game_id = i.game_id
                              AND old.tag_id = i.tag_id AND old.display_order = i.position)
            ON CONFLICT (game_id, tag_id) DO UPDATE SET display_order = EXCLUDED.display_order
            WHERE gt.display_order <> EXCLUDED.display_order
        )
        DELETE FROM game_tag gt WHERE gt.game_id = ANY(:confirmedGameIds)
          AND NOT EXISTS (SELECT 1 FROM incoming i
                          WHERE i.game_id = gt.game_id AND i.tag_id = gt.tag_id)
        """, nativeQuery = true)
    void syncLinks(Long[] confirmedGameIds, Long[] gameIds, Long[] steamIds, Integer[] positions);
}
