package run.halo.bilicookie.service;

import io.netty.channel.ChannelOption;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import run.halo.bilicookie.service.BiliCookieService.PlainCookie;
import run.halo.bilicookie.service.BiliCookieService.RefreshOutcome;
import run.halo.bilicookie.web.dto.RefreshResponse;
import run.halo.bilicookie.web.exception.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * B 站 Cookie 自动续期引擎，复刻官方原生 6 步刷新协议：
 *
 * <ol>
 *   <li>Step 0 预热：GET 首页携带完整 Cookie 下发 sid / buvid_fp 补齐会话；</li>
 *   <li>Step 1 检查：GET cookie/info 获取 data.timestamp（用服务端时间，非本地）；</li>
 *   <li>Step 2 生成 CorrespondPath：RSA-OAEP-SHA256 加密 {@code refresh_{timestamp}} 后 hex；</li>
 *   <li>Step 3 提取 refresh_csrf：GET correspond/1/{path}，正则抽取 {@code #1-name}（实时口令）；</li>
 *   <li>Step 4 刷新：POST cookie/refresh，从响应体 data.cookies_info 与 Set-Cookie 合并新 Cookie；</li>
 *   <li>Step 5 确认：POST confirm/refresh 用新 csrf + 旧 refresh_token 作废旧会话（失败不致命）。</li>
 * </ol>
 *
 * <p>整个流程使用同一个会话（Cookie 池）：初始放入用户字段 + 推导的
 * {@code DedeUserID__ckMd5 = md5(DedeUserID)[0:8]} + 自动生成的 buvid3/buvid4 设备标识，
 * 每一步响应的 Set-Cookie 合并进会话，后续请求持续携带。</p>
 *
 * <p>解析策略：B 站响应统一以 {@code String} 拉取，再使用插件自带 Jackson（tools.jackson）
 * 解析为 {@link JsonNode}，不依赖 WebClient 的 JSON codec，避免运行时 Jackson 版本类型冲突。</p>
 */
@Service
public class BiliRefreshService {

    private static final Logger log = LoggerFactory.getLogger(BiliRefreshService.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /* ---- B 站接口 ---- */
    private static final String HOME_URL = "https://www.bilibili.com/";
    private static final String INFO_URL =
        "https://passport.bilibili.com/x/passport-login/web/cookie/info";
    private static final String CORRESPOND_URL = "https://www.bilibili.com/correspond/1/";
    private static final String REFRESH_URL =
        "https://passport.bilibili.com/x/passport-login/web/cookie/refresh";
    private static final String CONFIRM_URL =
        "https://passport.bilibili.com/x/passport-login/web/confirm/refresh";

    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com/";
    private static final String ORIGIN = "https://www.bilibili.com";

    /** B 站 correspond 接口固定 RSA 公钥（OAEP-SHA256）。 */
    private static final String CORRESPOND_PUBLIC_KEY = """
        -----BEGIN PUBLIC KEY-----
        MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg
        Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71
        nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40
        JNrRuoEUXpabUzGB8QIDAQAB
        -----END PUBLIC KEY-----
        """;

    private static final Pattern REFRESH_CSRF_PATTERN =
        Pattern.compile("<div id=\"1-name\">([a-z0-9]+?)</div>");

    /** 86095：refresh_token 与 Cookie 不匹配，只能重新登录抓取。 */
    private static final int ERROR_REFRESH_CSRF_MISMATCH = 86095;
    private static final int ERROR_NOT_LOGIN = -101;
    private static final int ERROR_CSRF_FAILED = -111;

    /** correspond 接口对 RSA 路径有时间校验，生成后需等待数秒再访问。 */
    private static final Duration CORRESPOND_DELAY = Duration.ofSeconds(3);

    private final BiliCookieService cookieService;
    private final AuditLogService auditLogService;
    private final WebClient webClient;

    public BiliRefreshService(BiliCookieService cookieService,
        AuditLogService auditLogService) {
        this.cookieService = cookieService;
        this.auditLogService = auditLogService;
        this.webClient = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(
                HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                    .responseTimeout(Duration.ofSeconds(15))
                    // B 站对应接口等返回 gzip 压缩内容，需自动解压
                    .compress(true)))
            .build();
    }

    /**
     * 执行完整刷新流程并记录审计。action 区分 REFRESH（用户手动）/ ADMIN_REFRESH
     * （管理员代刷）/ SCHEDULED_REFRESH（定时任务）；operator 为执行者。
     * 任何一步失败都会写入 last_refresh_error 后原样抛出，
     * 由调用方（手动刷新接口 / 定时任务）决定如何展示或隔离。
     */
    public Mono<RefreshResponse> refresh(String userId, String operator, String action) {
        return cookieService.getPlainCookie(userId)
            .flatMap(plain -> doRefresh(userId, plain))
            .flatMap(resp -> auditLogService
                .record(userId, operator, action, true, "刷新成功")
                .thenReturn(resp))
            .onErrorResume(error -> failAndRethrow(userId, operator, action, error));
    }

    private Mono<RefreshResponse> doRefresh(String userId, PlainCookie plain) {
        if (isBlank(plain.refreshToken())) {
            return Mono.error(ApiException.biliRefreshFailed(
                "缺少 refresh_token（ac_time_value），无法自动刷新；请重新登录后一次性抓取全部 5 个字段"));
        }
        Session session = new Session(plain);
        return warmUp(session)
            .then(fetchTimestamp(session))
            .flatMap(timestamp -> fetchRefreshCsrf(session, timestamp))
            .flatMap(refreshCsrf -> refreshCookies(session, refreshCsrf))
            .flatMap(outcome -> confirmRefresh(session, plain.refreshToken())
                .onErrorResume(e -> {
                    // 确认失败不影响已刷新成功的结果，仅记录日志。
                    log.warn("confirm/refresh 确认失败（Cookie 已刷新）: {}", e.getMessage());
                    return Mono.empty();
                })
                .thenReturn(outcome))
            .flatMap(outcome -> cookieService.updateAfterRefresh(userId, outcome)
                .thenReturn(new RefreshResponse(
                    "SESSDATA=" + outcome.sessdata() + "; bili_jct=" + outcome.biliJct())));
    }

    /* ---- Step 0 预热：GET 首页补齐会话 ---- */

    private Mono<Void> warmUp(Session session) {
        return webClient.get()
            .uri(HOME_URL)
            .header(HttpHeaders.COOKIE, session.cookieHeader())
            .headers(this::commonHeaders)
            .exchangeToMono(resp -> {
                session.merge(resp.cookies());
                return resp.bodyToMono(String.class).then();
            });
    }

    /* ---- Step 1 检查：cookie/info 获取 timestamp ---- */

    private Mono<Long> fetchTimestamp(Session session) {
        return webClient.get()
            .uri(UriComponentsBuilder.fromUriString(INFO_URL)
                .queryParam("csrf", session.get("bili_jct"))
                .build(true)
                .toUri())
            .header(HttpHeaders.COOKIE, session.cookieHeader())
            .headers(this::commonHeaders)
            .exchangeToMono(resp -> {
                session.merge(resp.cookies());
                return resp.bodyToMono(String.class);
            })
            .map(json -> parseBiliInfo(json))
            .map(BiliRefreshService::resolveTimestamp)
            .flatMap(timestamp -> timestamp > 0
                ? Mono.just(timestamp)
                : Mono.error(ApiException.biliRefreshFailed("cookie/info 未返回有效 timestamp")));
    }

    /**
     * cookie/info 响应解析：code=-101（SESSDATA 已失效）不中断流程，
     * 因为 refresh_token 可能仍然有效，交由后续刷新步骤继续尝试。
     */
    private static JsonNode parseBiliInfo(String json) {
        JsonNode body;
        try {
            body = OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            throw ApiException.biliRefreshFailed(
                "B站预热检查 cookie/info：响应解析失败（" + e.getMessage() + "）");
        }
        int code = body.path("code").asInt(-1);
        if (code == ERROR_NOT_LOGIN) {
            log.warn("cookie/info 返回 -101（SESSDATA 已失效），改用当前时间戳继续尝试刷新");
            return null;
        }
        if (code != 0) {
            String message = body.path("message").asText("");
            throw ApiException.biliRefreshFailed(biliErrorMessage("预热检查 cookie/info", code, message));
        }
        return body;
    }

    /** -101 回退到当前时间戳；正常响应取 data.timestamp（毫秒）。 */
    private static long resolveTimestamp(JsonNode body) {
        if (body == null) {
            return System.currentTimeMillis();
        }
        return body.path("data").path("timestamp").asLong(0L);
    }

    /* ---- Step 2+3：生成 CorrespondPath 并提取 refresh_csrf ---- */

    private Mono<String> fetchRefreshCsrf(Session session, long timestampMs) {
        String correspondPath = buildCorrespondPath(timestampMs);
        return Mono.delay(CORRESPOND_DELAY)
            .flatMap(tick -> webClient.get()
                .uri(CORRESPOND_URL + correspondPath)
                .header(HttpHeaders.COOKIE, session.cookieHeader())
                .headers(this::commonHeaders)
                .exchangeToMono(resp -> {
                    session.merge(resp.cookies());
                    if (resp.statusCode().is2xxSuccessful()) {
                        return resp.bodyToMono(String.class);
                    }
                    return resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(ApiException.biliRefreshFailed(
                            "B站 correspond 页面请求失败（HTTP " + resp.statusCode().value() + "）："
                                + snippet(body))));
                }))
            .map(BiliRefreshService::extractRefreshCsrf);
    }

    /** 从 correspond 页面 HTML 提取 32 位 refresh_csrf（实时口令）。 */
    static String extractRefreshCsrf(String html) {
        Matcher matcher = REFRESH_CSRF_PATTERN.matcher(html);
        if (!matcher.find()) {
            throw ApiException.biliRefreshFailed(
                "无法从 B 站 correspond 页面提取 refresh_csrf（响应：" + snippet(html) + "）");
        }
        return matcher.group(1);
    }

    /** RSA-OAEP-SHA256 加密 {@code refresh_{timestamp}} 并 hex 编码。 */
    static String buildCorrespondPath(long timestampMs) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, loadPublicKey(),
                new OAEPParameterSpec("SHA-256", "MGF1",
                    MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            byte[] encrypted =
                cipher.doFinal(("refresh_" + timestampMs).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("RSA 加密 correspond 路径失败", e);
        }
    }

    private static PublicKey loadPublicKey() throws Exception {
        String pem = CORRESPOND_PUBLIC_KEY
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("RSA")
            .generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    /* ---- Step 4 刷新：cookie/refresh ---- */

    private Mono<RefreshOutcome> refreshCookies(Session session, String refreshCsrf) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("csrf", session.get("bili_jct"));
        form.add("refresh_csrf", refreshCsrf);
        form.add("source", "main_web");
        form.add("refresh_token", session.plain().refreshToken());
        return webClient.post()
            .uri(REFRESH_URL)
            .header(HttpHeaders.COOKIE, session.cookieHeader())
            .headers(this::postHeaders)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(form))
            .exchangeToMono(resp -> {
                session.merge(resp.cookies());
                return resp.bodyToMono(String.class);
            })
            .map(json -> {
                JsonNode body = parseBili(json, "刷新 cookie/refresh");
                // 新版接口在新 Cookie 位于响应体 data.cookies_info.cookies
                mergeCookiesInfo(session, body.path("data"));
                String newRefreshToken = body.path("data").path("refresh_token").asText("");
                return new RefreshOutcome(
                    session.get("SESSDATA"),
                    session.get("bili_jct"),
                    session.get("DedeUserID"),
                    session.get("DedeUserID__ckMd5"),
                    session.get("sid"),
                    isBlank(newRefreshToken) ? session.plain().refreshToken() : newRefreshToken);
            });
    }

    /** 合并响应体 {@code data.cookies_info.cookies} 中的新 Cookie 到会话。 */
    private static void mergeCookiesInfo(Session session, JsonNode data) {
        for (JsonNode domain : data.path("cookies_info").path("domains")) {
            for (JsonNode cookie : domain.path("cookies")) {
                String name = cookie.path("name").asText("");
                String value = cookie.path("value").asText("");
                if (!isBlank(name)) {
                    session.set(name, value);
                }
            }
        }
    }

    /* ---- Step 5 确认：confirm/refresh 作废旧 token ---- */

    private Mono<Void> confirmRefresh(Session session, String oldRefreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("csrf", session.get("bili_jct"));
        form.add("refresh_token", oldRefreshToken);
        return webClient.post()
            .uri(CONFIRM_URL)
            .headers(this::postHeaders)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(form))
            .exchangeToMono(resp -> {
                session.merge(resp.cookies());
                return resp.bodyToMono(String.class);
            })
            .map(json -> parseBili(json, "确认 confirm/refresh"))
            .then();
    }

    /* ---- 公共工具 ---- */

    private void commonHeaders(HttpHeaders headers) {
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        headers.set(HttpHeaders.REFERER, REFERER);
        headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en;q=0.8");
    }

    private void postHeaders(HttpHeaders headers) {
        commonHeaders(headers);
        headers.set(HttpHeaders.ORIGIN, ORIGIN);
        headers.set("X-Requested-With", "XMLHttpRequest");
    }

    /** 解析 B 站 JSON 响应并校验业务码，非 0 时抛 {@link ApiException}。 */
    private static JsonNode parseBili(String json, String step) {
        JsonNode body;
        try {
            body = OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            throw ApiException.biliRefreshFailed(
                "B站" + step + "：响应解析失败（" + e.getMessage() + "）");
        }
        int code = body.path("code").asInt(-1);
        if (code != 0) {
            String message = body.path("message").asText("");
            throw ApiException.biliRefreshFailed(biliErrorMessage(step, code, message));
        }
        return body;
    }

    static String biliErrorMessage(String step, int code, String message) {
        return switch (code) {
            case ERROR_REFRESH_CSRF_MISMATCH -> "B站刷新失败（86095）：refresh_token 与 Cookie 不匹配。"
                + "请重新登录，并在同一次会话中一次性抓取全部 5 个字段"
                + "（SESSDATA、bili_jct、DedeUserID、refresh_token、sid）";
            case ERROR_NOT_LOGIN -> "B站" + step + "失败（-101）：未登录 / SESSDATA 已失效，"
                + "请重新登录后重新抓取 Cookie";
            case ERROR_CSRF_FAILED -> "B站" + step + "失败（-111）：bili_jct（csrf）校验失败，"
                + "请重新复制完整 Cookie";
            default -> "B站" + step + "失败（code=" + code + "）：" + message;
        };
    }

    /** 截取响应片段用于错误提示/日志，避免完整响应过大。 */
    static String snippet(String text) {
        if (text == null || text.isEmpty()) {
            return "<空响应>";
        }
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private Mono<RefreshResponse> failAndRethrow(String userId, String operator, String action,
        Throwable error) {
        String message = error instanceof ApiException apiException
            ? apiException.getMessage()
            : (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
        log.warn("Cookie 刷新失败 userId={}: {}", userId, message);
        return cookieService.recordRefreshError(userId, message)
            .onErrorResume(ignore -> Mono.empty())
            .then(auditLogService.record(userId, operator, action, false, message))
            .then(Mono.error(error));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** md5 前 8 位，用于推导 DedeUserID__ckMd5。 */
    static String md5First8(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }

    private static String randomHex(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append("0123456789ABCDEF".charAt(random.nextInt(16)));
        }
        return sb.toString();
    }

    /** 自动生成设备标识 buvid3：UUID 大写 + 12 位随机 hex + infoc。 */
    private static String generateBuvid3() {
        return UUID.randomUUID().toString().toUpperCase() + randomHex(12) + "infoc";
    }

    /** 自动生成设备标识 buvid4：UUID 大写 + 12 位随机 hex + infoc。 */
    private static String generateBuvid4() {
        return UUID.randomUUID().toString().toUpperCase() + randomHex(12) + "infoc";
    }

    /**
     * 单次刷新流程的会话 Cookie 池：用户字段 + 推导字段 + 自动设备标识，
     * 逐步合并各响应 Set-Cookie，保证全程同一会话上下文。
     */
    private static final class Session {

        private final PlainCookie plain;
        private final Map<String, String> cookies = new LinkedHashMap<>();

        Session(PlainCookie plain) {
            this.plain = plain;
            set("SESSDATA", plain.sessdata());
            set("bili_jct", plain.biliJct());
            set("DedeUserID", plain.dedeUserId());
            set("sid", plain.sid());
            if (!isBlank(plain.dedeUserId())) {
                set("DedeUserID__ckMd5", md5First8(plain.dedeUserId()));
            }
            set("buvid3", isBlank(plain.buvid3()) ? generateBuvid3() : plain.buvid3());
            set("buvid4", isBlank(plain.buvid4()) ? generateBuvid4() : plain.buvid4());
        }

        PlainCookie plain() {
            return plain;
        }

        void set(String name, String value) {
            if (!isBlank(value)) {
                cookies.put(name, value);
            }
        }

        String get(String name) {
            return cookies.get(name);
        }

        void merge(MultiValueMap<String, ResponseCookie> setCookies) {
            if (setCookies == null) {
                return;
            }
            for (List<ResponseCookie> list : setCookies.values()) {
                if (list == null) {
                    continue;
                }
                for (ResponseCookie cookie : list) {
                    if (!isBlank(cookie.getName())) {
                        cookies.put(cookie.getName(), cookie.getValue());
                    }
                }
            }
        }

        String cookieHeader() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return sb.toString();
        }
    }
}
