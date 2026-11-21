package com.ieum.ieum.favorite;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 찜한 곳 (FAV-01): 회원이 가게·시장을 저장해 두고 내 정보에서 모아 본다.
 *
 * - 같은 곳을 두 번 찜해도 한 줄 (찜하기·풀기는 몇 번 눌러도 결과가 같다)
 * - 한 사람 최대 MAX 곳 (너무 많이 쌓이지 않게)
 * - 보이지 않게 된 가게(숨김)는 목록에 '지금은 볼 수 없어요'로 남기고, 풀 수 있게 한다
 */
@Service
@RequiredArgsConstructor
public class FavoriteService {

    public static final int MAX = 300;

    public enum Type {
        STORE, MARKET;

        /** 주소의 store / market → Type. 그 밖이면 400 */
        static Type of(String raw) {
            try {
                return Type.valueOf(raw == null ? "" : raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("가게(store)나 시장(market)만 찜할 수 있어요."));
            }
        }
    }

    public record Saved(boolean saved) {
    }

    /** 내 정보 목록 한 줄. available=false 면 지금은 볼 수 없는 곳 (숨겨진 가게 등) */
    public record Item(String type, long id, String name, String label, String address, LocalDateTime savedAt, boolean available) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public Saved state(long userId, String type, long targetId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM favorites WHERE user_id = :u AND target_type = :t AND target_id = :id""",
                params(userId, Type.of(type), targetId), Integer.class);
        return new Saved(n != null && n > 0);
    }

    @Transactional
    public Saved save(long userId, String type, long targetId) {
        Type t = Type.of(type);
        MapSqlParameterSource p = params(userId, t, targetId);
        if (state(userId, type, targetId).saved()) return new Saved(true);
        boolean exists = t == Type.STORE
                ? count("SELECT COUNT(*) FROM stores WHERE id = :id AND is_visible = 1", p) > 0
                : count("SELECT COUNT(*) FROM markets WHERE id = :id", p) > 0;
        if (!exists) throw new BusinessException(t == Type.STORE ? ErrorCode.STORE_NOT_FOUND : ErrorCode.MARKET_NOT_FOUND);
        // 회원 줄을 잠가서 동시에 여러 번 눌러도 MAX 를 넘지 않게
        jdbc.queryForObject("SELECT id FROM users WHERE id = :u FOR UPDATE", p, Long.class);
        if (count("SELECT COUNT(*) FROM favorites WHERE user_id = :u", p) >= MAX) {
            throw new BusinessException(ErrorCode.FAVORITE_LIMIT);
        }
        jdbc.update("""
                INSERT IGNORE INTO favorites (user_id, target_type, target_id, created_at)
                VALUES (:u, :t, :id, :now)""", p.addValue("now", Timestamp.valueOf(LocalDateTime.now())));
        return new Saved(true);
    }

    @Transactional
    public Saved remove(long userId, String type, long targetId) {
        jdbc.update("DELETE FROM favorites WHERE user_id = :u AND target_type = :t AND target_id = :id",
                params(userId, Type.of(type), targetId));
        return new Saved(false);
    }

    /** 내 정보: 최근에 찜한 순 */
    @Transactional(readOnly = true)
    public List<Item> list(long userId) {
        return jdbc.query("""
                SELECT f.target_type, f.target_id, f.created_at,
                       COALESCE(s.name, m.name) AS name,
                       CASE WHEN f.target_type = 'STORE' THEN s.category ELSE m.market_type END AS label,
                       COALESCE(s.road_address, s.jibun_address, m.road_address, m.jibun_address) AS address,
                       CASE WHEN f.target_type = 'STORE' THEN (s.id IS NOT NULL AND s.is_visible = 1) ELSE m.id IS NOT NULL END AS available
                  FROM favorites f
                  LEFT JOIN stores s ON f.target_type = 'STORE' AND s.id = f.target_id
                  LEFT JOIN markets m ON f.target_type = 'MARKET' AND m.id = f.target_id
                 WHERE f.user_id = :u
                 ORDER BY f.created_at DESC, f.id DESC""",
                new MapSqlParameterSource("u", userId),
                (rs, i) -> {
                    boolean available = rs.getBoolean("available");
                    return new Item(rs.getString("target_type"), rs.getLong("target_id"),
                            available ? rs.getString("name") : null, available ? rs.getString("label") : null,
                            available ? rs.getString("address") : null,
                            rs.getTimestamp("created_at").toLocalDateTime(), available);
                });
    }

    /** 탈퇴할 때 그 회원의 찜을 모두 지운다 */
    public int deleteAll(long userId) {
        return jdbc.update("DELETE FROM favorites WHERE user_id = :u", new MapSqlParameterSource("u", userId));
    }

    private int count(String sql, MapSqlParameterSource p) {
        Integer n = jdbc.queryForObject(sql, p, Integer.class);
        return n == null ? 0 : n;
    }

    private static MapSqlParameterSource params(long userId, Type t, long targetId) {
        return new MapSqlParameterSource("u", userId).addValue("t", t.name()).addValue("id", targetId);
    }
}
