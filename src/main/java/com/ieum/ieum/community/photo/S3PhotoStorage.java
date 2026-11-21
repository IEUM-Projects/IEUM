package com.ieum.ieum.community.photo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 사진을 AWS S3 에 둔다 (DEPLOY-01). ieum.upload.storage=s3 일 때만 쓴다 (기본은 서버 폴더 LocalPhotoStorage).
 *
 * - 버킷은 공개하지 않는다. 사진은 지금처럼 PostPhotoController 가 글 상태를 확인한 뒤 서버를 거쳐 보낸다.
 * - 열쇠(자격 증명)는 코드·설정 파일에 넣지 않는다. EC2 에 붙인 IAM 역할에서 받는다 (인스턴스 메타데이터 IMDSv2).
 *   내 컴퓨터에서 시험할 때만 환경 변수 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY (/ AWS_SESSION_TOKEN) 를 쓴다.
 * - AWS SDK 를 더하지 않고, 필요한 세 가지(넣기·꺼내기·지우기)만 AWS 서명 방식(Signature Version 4)으로 직접 요청한다.
 *   서명은 AWS 공식 라이브러리(botocore)가 만든 값과 같은지 시험으로 확인했다.
 * - 저장 위치: s3://{bucket}/{prefix}{앞 두 글자}/{이름}  (예: photos/ab/ab12….jpg)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ieum.upload.storage", havingValue = "s3")
public class S3PhotoStorage implements PhotoStorage {

    private static final Pattern NAME = Pattern.compile("[0-9a-f]{32}(_t)?\\.jpg");
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final String bucket;
    private final String region;
    private final String prefix;
    private final String endpoint;     // 비우면 https://{bucket}.s3.{region}.amazonaws.com
    private final boolean pathStyle;   // 시험용 가짜 S3: http://127.0.0.1:9997/{bucket}/...
    private final String imdsUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    record Credentials(String accessKey, String secretKey, String sessionToken, Instant expires) {
    }

    private volatile Credentials cached;

    public S3PhotoStorage(@Value("${ieum.upload.s3.bucket}") String bucket,
                          @Value("${ieum.upload.s3.region:ap-northeast-2}") String region,
                          @Value("${ieum.upload.s3.prefix:photos/}") String prefix,
                          @Value("${ieum.upload.s3.endpoint:}") String endpoint,
                          @Value("${ieum.upload.s3.imds-url:http://169.254.169.254}") String imdsUrl) {
        if (bucket == null || bucket.isBlank()) throw new IllegalStateException("ieum.upload.s3.bucket 을 넣어 주세요.");
        this.bucket = bucket.strip();
        this.region = region.strip();
        this.prefix = prefix == null ? "" : prefix.strip();
        this.endpoint = endpoint == null ? "" : endpoint.strip().replaceAll("/+$", "");
        this.pathStyle = !this.endpoint.isEmpty();
        this.imdsUrl = imdsUrl.replaceAll("/+$", "");
        log.info("동네 이야기 사진 저장소: S3 s3://{}/{} ({})", this.bucket, this.prefix, this.region);
    }

    private String key(String name) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("사진 이름이 올바르지 않아요: " + name);
        return prefix + name.substring(0, 2) + "/" + name;
    }

    private URI uri(String key) {
        String path = "/" + encodePath(key);
        if (pathStyle) return URI.create(endpoint + "/" + bucket + path);
        return URI.create("https://" + bucket + ".s3." + region + ".amazonaws.com" + path);
    }

    @Override
    public void save(String name, byte[] data) throws IOException {
        HttpResponse<String> r = send("PUT", key(name), data, "image/jpeg", HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IOException("S3 에 사진을 넣지 못했어요: " + r.statusCode() + " " + brief(r.body()));
    }

    @Override
    public Optional<byte[]> load(String name) throws IOException {
        HttpResponse<byte[]> r = send("GET", key(name), null, null, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() == 404) return Optional.empty();
        if (r.statusCode() / 100 != 2) throw new IOException("S3 에서 사진을 꺼내지 못했어요: " + r.statusCode() + " " + brief(new String(r.body(), StandardCharsets.UTF_8)));
        return Optional.of(r.body());
    }

    @Override
    public void delete(String name) {
        try {
            HttpResponse<String> r = send("DELETE", key(name), null, null, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2 && r.statusCode() != 404) log.warn("S3 사진을 지우지 못했어요: {} ({})", name, r.statusCode());
        } catch (IOException | RuntimeException e) {
            log.warn("S3 사진을 지우지 못했어요: {} ({})", name, e.getMessage());
        }
    }

    private static String brief(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
    }

    private <T> HttpResponse<T> send(String method, String key, byte[] body, String contentType, HttpResponse.BodyHandler<T> handler) throws IOException {
        Credentials c = credentials();
        URI u = uri(key);
        Instant now = Instant.now();
        String payloadHash = body == null ? EMPTY_SHA256 : sha256Hex(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(30));
        String auth = authorization(method, u, now, payloadHash, c);
        b.header("x-amz-date", AMZ_DATE.format(now)).header("x-amz-content-sha256", payloadHash).header("Authorization", auth);
        if (c.sessionToken() != null) b.header("x-amz-security-token", c.sessionToken());
        if (contentType != null) b.header("Content-Type", contentType);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            return http.send(b.build(), handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("S3 요청이 끊겼어요", e);
        }
    }

    // ---------------------------------------------------------------- 서명 (Signature Version 4)

    /** Authorization 머리말. 서명에 넣는 머리말: host, x-amz-content-sha256, x-amz-date (+ x-amz-security-token) */
    String authorization(String method, URI u, Instant now, String payloadHash, Credentials c) {
        String amzDate = AMZ_DATE.format(now);
        String day = amzDate.substring(0, 8);
        String host = u.getPort() == -1 ? u.getHost() : u.getHost() + ":" + u.getPort();
        StringBuilder headers = new StringBuilder()
                .append("host:").append(host).append('\n')
                .append("x-amz-content-sha256:").append(payloadHash).append('\n')
                .append("x-amz-date:").append(amzDate).append('\n');
        String signed = "host;x-amz-content-sha256;x-amz-date";
        if (c.sessionToken() != null) {
            headers.append("x-amz-security-token:").append(c.sessionToken()).append('\n');
            signed += ";x-amz-security-token";
        }
        String canonical = method + "\n" + u.getRawPath() + "\n" + "" + "\n" + headers + "\n" + signed + "\n" + payloadHash;
        String scope = day + "/" + region + "/s3/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] k = hmac(("AWS4" + c.secretKey()).getBytes(StandardCharsets.UTF_8), day);
        k = hmac(k, region);
        k = hmac(k, "s3");
        k = hmac(k, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(k, toSign));
        return "AWS4-HMAC-SHA256 Credential=" + c.accessKey() + "/" + scope + ", SignedHeaders=" + signed + ", Signature=" + signature;
    }

    /** S3 경로 인코딩: 영문·숫자·-_.~ 와 / 는 그대로, 나머지는 %XX */
    static String encodePath(String key) {
        StringBuilder b = new StringBuilder();
        for (byte by : key.getBytes(StandardCharsets.UTF_8)) {
            char ch = (char) (by & 0xFF);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || "-_.~/".indexOf(ch) >= 0) b.append(ch);
            else b.append('%').append(String.format("%02X", by & 0xFF));
        }
        return b.toString();
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- 자격 증명

    /** 환경 변수가 있으면 그것, 없으면 EC2 IAM 역할 (만료 5분 전에 새로 받음) */
    Credentials credentials() throws IOException {
        String ak = System.getenv("AWS_ACCESS_KEY_ID");
        String sk = System.getenv("AWS_SECRET_ACCESS_KEY");
        if (ak != null && !ak.isBlank() && sk != null && !sk.isBlank()) {
            String token = System.getenv("AWS_SESSION_TOKEN");
            return new Credentials(ak, sk, token == null || token.isBlank() ? null : token, Instant.MAX);
        }
        Credentials c = cached;
        if (c != null && Instant.now().isBefore(c.expires().minus(Duration.ofMinutes(5)))) return c;
        synchronized (this) {
            c = cached;
            if (c != null && Instant.now().isBefore(c.expires().minus(Duration.ofMinutes(5)))) return c;
            cached = fetchInstanceCredentials();
            return cached;
        }
    }

    private Credentials fetchInstanceCredentials() throws IOException {
        try {
            HttpClient imds = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            String token = imds.send(HttpRequest.newBuilder(URI.create(imdsUrl + "/latest/api/token")).timeout(Duration.ofSeconds(2))
                    .header("X-aws-ec2-metadata-token-ttl-seconds", "21600").PUT(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString()).body().strip();
            String base = imdsUrl + "/latest/meta-data/iam/security-credentials/";
            HttpResponse<String> roleRes = imds.send(HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofSeconds(2))
                    .header("X-aws-ec2-metadata-token", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (roleRes.statusCode() != 200 || roleRes.body().isBlank()) {
                throw new IOException("EC2 에 IAM 역할이 붙어 있지 않아요 (S3 에 사진을 넣으려면 역할이 필요해요)");
            }
            String role = roleRes.body().strip().split("\\R")[0];
            HttpResponse<String> credRes = imds.send(HttpRequest.newBuilder(URI.create(base + role)).timeout(Duration.ofSeconds(2))
                    .header("X-aws-ec2-metadata-token", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonNode n = json.readTree(credRes.body());
            return new Credentials(n.get("AccessKeyId").asString(), n.get("SecretAccessKey").asString(), n.get("Token").asString(),
                    Instant.parse(n.get("Expiration").asString()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("EC2 자격 증명을 받지 못했어요", e);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("EC2 자격 증명을 받지 못했어요: " + e.getMessage(), e);
        }
    }
}
