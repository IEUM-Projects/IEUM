package com.ieum.ieum.auth;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.mail.MailService;
import com.ieum.ieum.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 가입 이메일 인증 (AUTH-02): 가입 화면에서 6자리 번호를 메일로 보내고, 맞으면 30분 안에 가입할 수 있다.
 * 비밀번호 찾기(AUTH-03, PasswordResetService)도 issue·check·consume 을 함께 쓴다 (purpose = PASSWORD_RESET).
 *
 *   보내기   : 같은 이메일은 1분에 한 번·하루 5번, 같은 IP는 1시간에 20번까지. 이미 가입된 이메일이면 409
 *   확인     : 번호는 10분 동안만, 5번 틀리면 그 번호는 끝 (다시 받아야 함)
 *   가입     : 30분 안에 확인된 번호가 있어야 가입되고, 그 번호는 한 번 쓰면 끝
 *
 * ieum.auth.email-verification=false 이면 이 확인을 건너뛴다 (메일 서버 없는 개발·시험용. 운영은 true).
 * 번호는 BCrypt 로 저장하고, 하루 지난 기록은 TokenCleanupService 가 지운다.
 */
@Slf4j
@Service
public class EmailVerificationService {

    public static final int CODE_MINUTES = 10;
    public static final int VERIFIED_MINUTES = 30;
    public static final int RESEND_SECONDS = 60;
    public static final int DAILY_PER_EMAIL = 5;
    public static final int HOURLY_PER_IP = 20;
    public static final int MAX_ATTEMPTS = 5;
    static final String PURPOSE = "SIGNUP";

    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final UserRepository userRepository;
    private final boolean required;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(NamedParameterJdbcTemplate jdbc, PasswordEncoder passwordEncoder, MailService mailService,
                                    UserRepository userRepository,
                                    @Value("${ieum.auth.email-verification:true}") boolean required) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.userRepository = userRepository;
        this.required = required;
        if (!required) log.warn("가입 이메일 인증을 끈 상태예요 (ieum.auth.email-verification=false). 운영 서버에서는 켜 주세요.");
    }

    public boolean required() {
        return required;
    }

    /** 인증번호 보내기 (가입) */
    @Transactional
    public AuthDtos.VerificationSent send(String rawEmail, String ip) {
        String email = AuthService.normalizeEmail(rawEmail);
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        return issue(email, PURPOSE, ip, code -> new Mail("[이음] 이메일 인증번호 " + code, mailText(code)));
    }

    /** 보낼 메일 (제목, 본문) */
    record Mail(String subject, String text) {
    }

    /**
     * 번호를 만들어 메일로 보내고 저장한다 (가입·비밀번호 찾기가 함께 씀).
     * compose 가 null 이면 메일은 보내지 않고 기록만 남긴다: 비밀번호 찾기에서 가입하지 않은 이메일일 때,
     * 가입된 이메일과 똑같이 '1분에 한 번·하루 5번'이 걸리게 하려고 (가입 여부를 알아내지 못하게).
     */
    AuthDtos.VerificationSent issue(String email, String purpose, String ip, java.util.function.Function<String, Mail> compose) {
        LocalDateTime now = LocalDateTime.now();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("email", email).addValue("purpose", purpose).addValue("ip", ip)
                .addValue("now", Timestamp.valueOf(now))
                .addValue("minute", Timestamp.valueOf(now.minusSeconds(RESEND_SECONDS)))
                .addValue("day", Timestamp.valueOf(now.minusDays(1)))
                .addValue("hour", Timestamp.valueOf(now.minusHours(1)));
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT SUM(created_at > :minute) AS last_minute, SUM(created_at > :day) AS last_day
                  FROM email_verifications WHERE email = :email AND purpose = :purpose AND created_at > :day""", p);
        if (number(counts.get("last_minute")) > 0) throw new BusinessException(ErrorCode.VERIFICATION_TOO_SOON);
        if (number(counts.get("last_day")) >= DAILY_PER_EMAIL) throw new BusinessException(ErrorCode.VERIFICATION_DAILY_LIMIT);
        if (ip != null) {
            Integer byIp = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM email_verifications WHERE request_ip = :ip AND created_at > :hour", p, Integer.class);
            if (byIp != null && byIp >= HOURLY_PER_IP) throw new BusinessException(ErrorCode.VERIFICATION_DAILY_LIMIT);
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        if (compose != null) {
            Mail mail = compose.apply(code);
            try {
                mailService.send(email, mail.subject(), mail.text());
            } catch (MailService.MailException e) {
                throw new BusinessException(ErrorCode.MAIL_SEND_FAILED);
            }
        } else {
            code = java.util.UUID.randomUUID().toString();   // 아무도 모르는 값: 이 기록으로는 확인할 수 없다
        }
        // 새 번호를 보내면 앞서 보낸 번호는 못 쓰게 (헷갈리지 않게, 마지막 번호만)
        jdbc.update("""
                UPDATE email_verifications SET expires_at = :now
                 WHERE email = :email AND purpose = :purpose AND verified_at IS NULL AND expires_at > :now""", p);
        jdbc.update("""
                INSERT INTO email_verifications (email, purpose, code_hash, request_ip, expires_at, created_at)
                VALUES (:email, :purpose, :hash, :ip, :expires, :now)""",
                p.addValue("hash", passwordEncoder.encode(code))
                        .addValue("expires", Timestamp.valueOf(now.plusMinutes(CODE_MINUTES))));
        return new AuthDtos.VerificationSent(email, CODE_MINUTES * 60, RESEND_SECONDS);
    }

    /** 번호 확인 (가입) */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthDtos.VerificationConfirmed confirm(String rawEmail, String rawCode) {
        return check(AuthService.normalizeEmail(rawEmail), PURPOSE, rawCode, VERIFIED_MINUTES);
    }

    /** 번호 확인 (가입·비밀번호 찾기가 함께 씀). 맞으면 validMinutes 안에 다음 단계를 마쳐야 한다 */
    AuthDtos.VerificationConfirmed check(String email, String purpose, String rawCode, int validMinutes) {
        String code = rawCode == null ? "" : rawCode.replaceAll("\\s", "");
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, code_hash, attempts, expires_at, verified_at FROM email_verifications
                 WHERE email = :email AND purpose = :purpose AND used_at IS NULL
                 ORDER BY id DESC LIMIT 1 FOR UPDATE""",
                new MapSqlParameterSource("email", email).addValue("purpose", purpose));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.VERIFICATION_EXPIRED);
        Map<String, Object> row = rows.get(0);
        long id = ((Number) row.get("id")).longValue();
        if (row.get("verified_at") != null) {
            return new AuthDtos.VerificationConfirmed(email, validMinutes * 60);   // 이미 맞힘 (두 번 누름)
        }
        int attempts = ((Number) row.get("attempts")).intValue();
        if (toTime(row.get("expires_at")).isBefore(now) || attempts >= MAX_ATTEMPTS) {
            throw new BusinessException(ErrorCode.VERIFICATION_EXPIRED);
        }
        if (!code.matches("\\d{6}") || !passwordEncoder.matches(code, (String) row.get("code_hash"))) {
            jdbc.update("UPDATE email_verifications SET attempts = attempts + 1 WHERE id = :id", new MapSqlParameterSource("id", id));
            int left = MAX_ATTEMPTS - attempts - 1;
            if (left <= 0) throw new BusinessException(ErrorCode.VERIFICATION_EXPIRED);
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_MISMATCH, List.of("인증번호가 맞지 않아요. " + left + "번 더 입력할 수 있어요."));
        }
        jdbc.update("UPDATE email_verifications SET verified_at = :now WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("now", Timestamp.valueOf(now)));
        return new AuthDtos.VerificationConfirmed(email, validMinutes * 60);
    }

    /** 가입할 때: 30분 안에 맞힌 번호가 있는지 보고, 있으면 다 쓴 것으로 표시. 인증을 끈 상태면 그냥 통과 */
    @Transactional
    public LocalDateTime consumeForSignup(String email) {
        LocalDateTime now = LocalDateTime.now();
        if (!required) return null;
        if (!consume(email, PURPOSE, VERIFIED_MINUTES, now)) throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
        return now;
    }

    /** withinMinutes 안에 맞힌 번호 하나를 '다 씀'으로 표시. 없으면 false (한 번 쓴 번호는 다시 못 씀) */
    boolean consume(String email, String purpose, int withinMinutes, LocalDateTime now) {
        int updated = jdbc.update("""
                UPDATE email_verifications SET used_at = :now
                 WHERE email = :email AND purpose = :purpose AND used_at IS NULL
                   AND verified_at IS NOT NULL AND verified_at > :since
                 ORDER BY id DESC LIMIT 1""",
                new MapSqlParameterSource("email", email).addValue("purpose", purpose)
                        .addValue("now", Timestamp.valueOf(now))
                        .addValue("since", Timestamp.valueOf(now.minusMinutes(withinMinutes))));
        return updated > 0;
    }

    /** 하루 지난 기록 지우기 (TokenCleanupService 가 매일 새벽 부름) */
    public int cleanup(LocalDateTime now) {
        return jdbc.update("DELETE FROM email_verifications WHERE created_at < :before",
                new MapSqlParameterSource("before", Timestamp.valueOf(now.minusDays(1))));
    }

    private static String mailText(String code) {
        return """
                안녕하세요, 이음입니다.

                가입 화면에 아래 인증번호 6자리를 입력해 주세요.

                    %s

                - 인증번호는 %d분 동안만 쓸 수 있어요.
                - 이음에 가입하려던 적이 없다면 이 메일은 지워 주세요. 다른 사람이 이메일을 잘못 적은 것일 수 있어요.
                - 이음은 메일이나 전화로 인증번호를 묻지 않아요. 누구에게도 알려 주지 마세요.

                이음 — 우리 동네의 오래된 이야기를 잇습니다
                """.formatted(code, CODE_MINUTES);
    }

    private static long number(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static LocalDateTime toTime(Object o) {
        if (o instanceof LocalDateTime t) return t;
        if (o instanceof Timestamp t) return t.toLocalDateTime();
        return LocalDateTime.parse(o.toString().replace(' ', 'T'));
    }
}
