package com.ieum.ieum.community.photo;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 동네 이야기 사진 (COM-02)
 *
 * 흐름: 글쓰기 화면에서 사진을 고르면 한 장씩 먼저 올린다(upload → 번호). 글을 올리거나 고칠 때 그 번호들을 함께 보내면
 * 글에 붙는다(attach). 하루가 지나도록 글에 붙지 않은 사진은 새벽에 지운다(cleanupPending).
 *
 * 보여 주기: 보이는 글(VISIBLE)의 사진은 누구나 /api/posts/photos/{이름} 으로 받는다.
 * 가려진 글의 사진은 글쓴이와 관리자 화면에만, 1~2시간 쓸 수 있는 서명이 붙은 주소로 내보낸다
 * (사진은 <img> 로 불러서 로그인 토큰을 실을 수 없으므로).
 */
@Slf4j
@Service
public class PostPhotoService {

    public static final int MAX_PER_POST = 4;
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    static final int MAX_PENDING = 12;      // 글에 붙이지 않은 사진 (지난 하루)
    static final int DAILY_UPLOADS = 40;
    static final int ALT_MAX = 100;
    static final String URL = "/api/posts/photos/";

    public record PhotoRef(Long id, String alt) {
    }

    public record PhotoView(long id, String url, String thumbUrl, int width, int height, String alt) {
    }

    public record Uploaded(long id, int width, int height, int bytes) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final PhotoStorage storage;
    private final com.ieum.ieum.community.ProfanityFilter profanity;
    private final byte[] signKey;
    private final SecureRandom random = new SecureRandom();

    public PostPhotoService(NamedParameterJdbcTemplate jdbc, PhotoStorage storage, com.ieum.ieum.community.ProfanityFilter profanity,
                            @Value("${ieum.upload.sign-key:${jwt.secret:}}") String key) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.profanity = profanity;
        if (key == null || key.isBlank()) throw new IllegalStateException("사진 주소 서명 키(ieum.upload.sign-key 또는 jwt.secret)가 없어요.");
        this.signKey = ("photo:" + key).getBytes(StandardCharsets.UTF_8);
    }

    // ================================================================ 올리기

    /** uid 는 CommunityService.requireWriter 로 확인한 회원 */
    public Uploaded upload(long uid, byte[] data) {
        if (data.length > MAX_BYTES) throw new BusinessException(ErrorCode.PHOTO_TOO_LARGE);
        MapSqlParameterSource p = new MapSqlParameterSource("uid", uid).addValue("since", LocalDateTime.now().minusDays(1));
        Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM post_photos WHERE user_id = :uid AND post_id IS NULL AND created_at >= :since", p, Long.class);
        if (pending != null && pending >= MAX_PENDING) throw new BusinessException(ErrorCode.PHOTO_PENDING_LIMIT);
        Long today = jdbc.queryForObject("SELECT COUNT(*) FROM post_photos WHERE user_id = :uid AND created_at >= CURDATE()", p, Long.class);
        if (today != null && today >= DAILY_UPLOADS) throw new BusinessException(ErrorCode.PHOTO_DAILY_LIMIT);

        PhotoProcessor.Result r = PhotoProcessor.process(data);
        String key = newKey();
        try {
            storage.save(key + ".jpg", r.photo());
            storage.save(key + "_t.jpg", r.thumb());
        } catch (IOException e) {
            removeFiles(key);
            log.error("사진을 저장하지 못했어요", e);
            throw new IllegalStateException("사진을 저장하지 못했어요", e);
        }
        GeneratedKeyHolder id = new GeneratedKeyHolder();
        try {
            jdbc.update("""
                    INSERT INTO post_photos (user_id, file_key, width, height, bytes) VALUES (:uid, :key, :w, :h, :b)""",
                    new MapSqlParameterSource("uid", uid).addValue("key", key).addValue("w", r.width()).addValue("h", r.height())
                            .addValue("b", r.photo().length), id, new String[]{"id"});
        } catch (RuntimeException e) {
            removeFiles(key);
            throw e;
        }
        return new Uploaded(id.getKey().longValue(), r.width(), r.height(), r.photo().length);
    }

    private String newKey() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    // ================================================================ 글에 붙이기

    /**
     * 글의 사진을 refs 순서대로 맞춘다. 목록에서 빠진 사진은 지운다(트랜잭션이 끝난 뒤 파일도).
     * refs 가 null 이면(사진을 모르는 예전 화면) 그대로 둔다.
     */
    public void attach(long uid, long postId, List<PhotoRef> refs) {
        if (refs == null) return;
        if (refs.size() > MAX_PER_POST) throw new BusinessException(ErrorCode.PHOTO_TOO_MANY);
        Set<Long> ids = new LinkedHashSet<>();
        for (PhotoRef ref : refs) {
            if (ref == null || ref.id() == null || !ids.add(ref.id())) throw new BusinessException(ErrorCode.PHOTO_NOT_FOUND);
            if (ref.alt() != null && ref.alt().strip().length() > ALT_MAX) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("사진 설명은 " + ALT_MAX + "자까지 쓸 수 있어요."));
            }
            profanity.check("사진 설명", ref.alt());   // 화면 낭독기가 읽는 공개 글이라 같이 거름 (COM-03)
        }
        MapSqlParameterSource p = new MapSqlParameterSource("uid", uid).addValue("post", postId);
        if (!ids.isEmpty()) {
            p.addValue("ids", ids);
            // 다른 사람 사진, 다른 글에 붙은 사진은 붙일 수 없다. 줄을 잠가 두 글에 동시에 붙는 일을 막는다
            List<Long> ok = jdbc.queryForList("""
                    SELECT id FROM post_photos WHERE id IN (:ids) AND user_id = :uid AND (post_id IS NULL OR post_id = :post) FOR UPDATE""", p, Long.class);
            if (ok.size() != ids.size()) throw new BusinessException(ErrorCode.PHOTO_NOT_FOUND);
        }
        List<String> removed = jdbc.queryForList(ids.isEmpty()
                ? "SELECT file_key FROM post_photos WHERE post_id = :post"
                : "SELECT file_key FROM post_photos WHERE post_id = :post AND id NOT IN (:ids)", p, String.class);
        if (!removed.isEmpty()) {
            jdbc.update("DELETE FROM post_photos WHERE file_key IN (:keys)", new MapSqlParameterSource("keys", removed));
            afterCommit(removed);
        }
        int order = 0;
        for (PhotoRef ref : refs) {
            String alt = ref.alt() == null || ref.alt().isBlank() ? null : ref.alt().strip().replaceAll("\\s+", " ");
            jdbc.update("UPDATE post_photos SET post_id = :post, sort_order = :o, alt = :alt WHERE id = :id",
                    new MapSqlParameterSource("post", postId).addValue("o", order++).addValue("alt", alt).addValue("id", ref.id()));
        }
    }

    /** 글을 지울 때: 사진 줄과 파일을 모두 지운다 */
    public void deleteForPost(long postId) {
        MapSqlParameterSource p = new MapSqlParameterSource("post", postId);
        List<String> keys = jdbc.queryForList("SELECT file_key FROM post_photos WHERE post_id = :post", p, String.class);
        if (keys.isEmpty()) return;
        jdbc.update("DELETE FROM post_photos WHERE post_id = :post", p);
        afterCommit(keys);
    }

    private void afterCommit(List<String> keys) {
        List<String> copy = List.copyOf(keys);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    copy.forEach(PostPhotoService.this::removeFiles);
                }
            });
        } else {
            copy.forEach(this::removeFiles);
        }
    }

    private void removeFiles(String key) {
        storage.delete(key + ".jpg");
        storage.delete(key + "_t.jpg");
    }

    /** 하루가 지나도록 글에 붙지 않은 사진 지우기 (새벽 정리, TokenCleanupService) */
    public int cleanupPending(LocalDateTime now) {
        MapSqlParameterSource p = new MapSqlParameterSource("before", now.minusDays(1));
        List<String> keys = jdbc.queryForList("SELECT file_key FROM post_photos WHERE post_id IS NULL AND created_at < :before LIMIT 5000", p, String.class);
        if (keys.isEmpty()) return 0;
        jdbc.update("DELETE FROM post_photos WHERE post_id IS NULL AND file_key IN (:keys)", new MapSqlParameterSource("keys", keys));
        keys.forEach(this::removeFiles);
        return keys.size();
    }

    // ================================================================ 보여 주기

    /** 글의 사진들. visible=false 면 서명 붙은 주소 (글쓴이·관리자만 이 목록을 받는다) */
    public List<PhotoView> photosOf(long postId, boolean visible) {
        return jdbc.query("SELECT id, file_key, width, height, alt FROM post_photos WHERE post_id = :post ORDER BY sort_order, id",
                new MapSqlParameterSource("post", postId), (rs, i) -> {
                    String key = rs.getString("file_key");
                    return new PhotoView(rs.getLong("id"), url(key + ".jpg", visible), url(key + "_t.jpg", visible),
                            rs.getInt("width"), rs.getInt("height"), rs.getString("alt"));
                });
    }

    /** 목록용 첫 사진 작은 주소 (보이는 글만) */
    public static String thumbUrl(String key) {
        return key == null ? null : URL + key + "_t.jpg";
    }

    String url(String name, boolean visible) {
        if (visible) return URL + name;
        // 지금 시각을 1시간 단위로 올려서 1~2시간 쓰게 한다 (같은 시간 안에서는 주소가 같아 브라우저가 다시 받지 않음)
        long exp = (Instant.now().getEpochSecond() / 3600 + 2) * 3600;
        return URL + name + "?e=" + exp + "&s=" + sign(name, exp);
    }

    private String sign(String name, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signKey, "HmacSHA256"));
            byte[] d = mac.doFinal((name + ":" + exp).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d).substring(0, 22);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record Served(byte[] data, boolean signed) {
    }

    /**
     * 사진 파일 보내기. 보이는 글에 붙은 사진이거나, 서명이 맞고 시간이 남은 주소일 때만.
     * 그 밖(가려진 글, 지운 글, 아직 안 붙인 사진, 없는 이름)은 모두 '없음'.
     */
    public Optional<Served> serve(String name, Long exp, String sig) {
        if (name == null || !name.matches("[0-9a-f]{32}(_t)?\\.jpg")) return Optional.empty();
        boolean signed = false;
        if (exp != null && sig != null) {
            if (exp < Instant.now().getEpochSecond()) return Optional.empty();
            if (!MessageDigest.isEqual(sign(name, exp).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8))) return Optional.empty();
            signed = true;
        }
        String key = name.substring(0, 32);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT ph.post_id, p.status FROM post_photos ph LEFT JOIN posts p ON p.id = ph.post_id WHERE ph.file_key = :key""",
                new MapSqlParameterSource("key", key));
        if (rows.isEmpty()) return Optional.empty();
        Object status = rows.getFirst().get("status");
        boolean ok = signed ? status != null && !"DELETED".equals(status) : "VISIBLE".equals(status);
        if (!ok) return Optional.empty();
        try {
            boolean viaSignature = signed;
            return storage.load(name).map(d -> new Served(d, viaSignature));
        } catch (IOException e) {
            log.warn("사진 파일을 읽지 못했어요: {}", name);
            return Optional.empty();
        }
    }
}
