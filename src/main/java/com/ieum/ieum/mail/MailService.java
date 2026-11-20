package com.ieum.ieum.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;

/**
 * 메일 보내기 (AUTH-02). 라이브러리 없이 SMTP를 직접 말한다 (보내는 메일이 인증번호 한 종류뿐이라 충분).
 *
 * ieum.mail.mode
 *   log  : 메일을 보내지 않고 서버 로그에 내용을 남긴다 (개발용, 기본값). 운영 서버에서는 smtp 로!
 *   smtp : 아래 SMTP 서버로 보낸다
 * ieum.mail.host / port / username / password / from / from-name
 * ieum.mail.security : ssl (465, 기본) | starttls (587) | none (시험용)
 *
 * 비밀번호(앱 비밀번호)는 application-local.yml 에만 둔다.
 */
@Slf4j
@Service
public class MailService {

    private static final int TIMEOUT_MS = 10_000;

    private final String mode;
    private final String host;
    private final int port;
    private final String security;
    private final String username;
    private final String password;
    private final String from;
    private final String fromName;

    public MailService(@Value("${ieum.mail.mode:log}") String mode,
                       @Value("${ieum.mail.host:}") String host,
                       @Value("${ieum.mail.port:465}") int port,
                       @Value("${ieum.mail.security:ssl}") String security,
                       @Value("${ieum.mail.username:}") String username,
                       @Value("${ieum.mail.password:}") String password,
                       @Value("${ieum.mail.from:}") String from,
                       @Value("${ieum.mail.from-name:이음}") String fromName) {
        this.mode = mode.strip().toLowerCase();
        this.host = host.strip();
        this.port = port;
        this.security = security.strip().toLowerCase();
        this.username = username.strip();
        this.password = password;
        this.from = from.strip().isEmpty() ? username.strip() : from.strip();
        this.fromName = fromName;
        if (logOnly()) {
            log.warn("메일을 보내지 않고 로그에만 남기는 모드예요 (ieum.mail.mode=log). 운영 서버에서는 smtp 로 바꿔 주세요.");
        } else if (this.host.isEmpty() || this.from.isEmpty()) {
            log.warn("ieum.mail.mode=smtp 인데 host 나 from 이 비어 있어요. 메일을 보낼 수 없어요.");
        }
    }

    public boolean logOnly() {
        return !"smtp".equals(mode);
    }

    /** 글자만 있는 메일 한 통. 실패하면 MailException */
    public void send(String to, String subject, String text) {
        if (to == null || to.isBlank() || to.chars().anyMatch(c -> c == '\r' || c == '\n' || c == '<' || c == '>')) {
            throw new MailException("받는 주소가 올바르지 않아요.");
        }
        if (logOnly()) {
            log.info("[메일 보내기 대신 로그] 받는 사람={} 제목={}\n{}", to, subject, text);
            return;
        }
        try {
            smtp(to, subject, text);
            log.info("메일을 보냈어요: 받는 사람={} 제목={}", mask(to), subject);
        } catch (IOException | RuntimeException e) {
            log.warn("메일을 보내지 못했어요: 받는 사람={} 까닭={}", mask(to), e.getMessage());
            throw new MailException("메일을 보내지 못했어요.", e);
        }
    }

    // ------------------------------------------------------------------ SMTP

    private void smtp(String to, String subject, String text) throws IOException {
        Socket socket = "ssl".equals(security) ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        if (socket instanceof SSLSocket ssl) verifyHost(ssl);
        socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
        socket.setSoTimeout(TIMEOUT_MS);
        try {
            Conn c = new Conn(socket);
            c.expect(220);
            c.command("EHLO ieum", 250);
            if ("starttls".equals(security)) {
                c.command("STARTTLS", 220);
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, port, true);
                verifyHost(ssl);
                ssl.startHandshake();
                c = new Conn(ssl);
                socket = ssl;
                c.command("EHLO ieum", 250);
            }
            if (!username.isEmpty()) {
                c.command("AUTH LOGIN", 334);
                c.command(b64(username), 334);
                c.command(b64(password), 235);
            }
            c.command("MAIL FROM:<" + from + ">", 250);
            c.command("RCPT TO:<" + to + ">", 250, 251);
            c.command("DATA", 354);
            c.write(message(to, subject, text) + "\r\n.");
            c.expect(250);
            c.command("QUIT", 221);
        } finally {
            socket.close();
        }
    }

    /** 인증서의 이름이 접속한 서버 이름과 맞는지도 확인 (가짜 서버에 비밀번호를 보내지 않게) */
    private static void verifyHost(SSLSocket ssl) {
        SSLParameters params = ssl.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(params);
    }

    private String message(String to, String subject, String text) {
        String body = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(text.replace("\r\n", "\n").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        String domain = from.contains("@") ? from.substring(from.indexOf('@') + 1) : "ieum";
        return String.join("\r\n",
                "From: " + encoded(fromName) + " <" + from + ">",
                "To: <" + to + ">",
                "Subject: " + encoded(subject),
                "Date: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()),
                "Message-ID: <" + UUID.randomUUID() + "@" + domain + ">",
                "MIME-Version: 1.0",
                "Content-Type: text/plain; charset=UTF-8",
                "Content-Transfer-Encoding: base64",
                "",
                body);
    }

    private static String encoded(String s) {
        return "=?UTF-8?B?" + b64(s) + "?=";
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** 로그에 주소를 다 남기지 않음: ab***@example.com */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at < 0) return "***";
        return email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
    }

    /** 한 줄 보내고 응답 번호 확인 */
    private static final class Conn {
        private final BufferedReader in;
        private final OutputStream out;

        Conn(Socket socket) throws IOException {
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = socket.getOutputStream();
        }

        void write(String line) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /** 오류 문구에는 서버 응답만 담고 보낸 줄(비밀번호 등)은 담지 않는다 */
        void command(String line, int... ok) throws IOException {
            write(line);
            expect(ok);
        }

        /** 여러 줄 응답("250-...", "250 ...")의 마지막 줄까지 읽고 번호 확인 */
        void expect(int... ok) throws IOException {
            String line;
            do {
                line = in.readLine();
                if (line == null) throw new IOException("메일 서버가 연결을 끊었어요.");
            } while (line.length() > 3 && line.charAt(3) == '-');
            int code;
            try {
                code = Integer.parseInt(line.substring(0, 3));
            } catch (RuntimeException e) {
                throw new IOException("메일 서버 응답을 알 수 없어요: " + line);
            }
            for (int o : ok) if (o == code) return;
            throw new IOException("메일 서버가 거절했어요: " + line);
        }
    }

    public static class MailException extends RuntimeException {
        public MailException(String message) {
            super(message);
        }

        public MailException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
