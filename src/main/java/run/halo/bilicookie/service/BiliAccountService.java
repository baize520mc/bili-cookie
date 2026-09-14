package run.halo.bilicookie.service;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import run.halo.bilicookie.service.BiliCookieService.PlainCookie;
import run.halo.bilicookie.web.dto.ValidateResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * B 站账号信息服务：通过 {@code x/web-interface/nav} 校验 Cookie 有效性并抓取用户名/UID。
 *
 * <p>与刷新协议共用同一套 WebClient 配置（自动解压 gzip、超时 15s），
 * 响应统一以 String 拉取后用插件自带 Jackson（tools.jackson）解析。</p>
 */
@Service
public class BiliAccountService {

    private static final Logger log = LoggerFactory.getLogger(BiliAccountService.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";
    private static final String REFERER = "https://www.bilibili.com/";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    private static final int ERROR_NOT_LOGIN = -101;
    private static final int ERROR_CSRF_FAILED = -111;

    private final BiliCookieService cookieService;
    private final AuditLogService auditLogService;
    private final WebClient webClient;

    public BiliAccountService(BiliCookieService cookieService,
        AuditLogService auditLogService) {
        this.cookieService = cookieService;
        this.auditLogService = auditLogService;
        this.webClient = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(
                HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                    .responseTimeout(Duration.ofSeconds(15))
                    .compress(true)))
            .build();
    }

    /**
     * 验证 Cookie 有效性并抓取账号信息。有效时写入 biliUsername/biliUid/validatedAt 并记录审计；
     * 无效时返回 valid=false + 可读 message（不抛异常，便于客户端直接展示）。
     */
    public Mono<ValidateResponse> validate(String userId, String operator) {
        return cookieService.getPlainCookie(userId)
            .flatMap(plain -> fetchNav(plain))
            .flatMap(info -> {
                if (!info.isLogin()) {
                    log.warn("Cookie 验证失败 userId={}: {}", userId, info.message());
                    return auditLogService
                        .record(userId, operator, "VALIDATE", false, info.message())
                        .thenReturn(new ValidateResponse(false, null, null, info.message()));
                }
                return cookieService.updateAccountInfo(userId, info.uname(), info.mid())
                    .then(auditLogService.record(userId, operator, "VALIDATE", true,
                        "验证通过，账号：" + info.uname()))
                    .thenReturn(new ValidateResponse(true, info.uname(), info.mid(), "正常"));
            });
    }

    /* ---- 内部 ---- */

    private Mono<NavInfo> fetchNav(PlainCookie plain) {
        return webClient.get()
            .uri(NAV_URL)
            .header(HttpHeaders.COOKIE, buildCookieHeader(plain))
            .headers(headers -> {
                headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
                headers.set(HttpHeaders.REFERER, REFERER);
                headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
                headers.set(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en;q=0.8");
            })
            .exchangeToMono(resp -> resp.bodyToMono(String.class))
            .map(BiliAccountService::parseNav);
    }

    private static String buildCookieHeader(PlainCookie plain) {
        StringBuilder sb = new StringBuilder();
        append(sb, "SESSDATA", plain.sessdata());
        append(sb, "bili_jct", plain.biliJct());
        append(sb, "DedeUserID", plain.dedeUserId());
        append(sb, "sid", plain.sid());
        append(sb, "buvid3", plain.buvid3());
        append(sb, "buvid4", plain.buvid4());
        append(sb, "DedeUserID__ckMd5", plain.dedeUserIdCkMd5());
        return sb.toString();
    }

    private static void append(StringBuilder sb, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("; ");
        }
        sb.append(name).append('=').append(value);
    }

    /** 解析 nav 响应：code=0 且 data.isLogin=true 视为有效，提取 uname/mid。 */
    private static NavInfo parseNav(String json) {
        JsonNode body;
        try {
            body = OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            return new NavInfo(false, null, null, "B站验证：响应解析失败（" + e.getMessage() + "）");
        }
        int code = body.path("code").asInt(-1);
        if (code != 0) {
            String message = body.path("message").asText("");
            String text = switch (code) {
                case ERROR_NOT_LOGIN -> "未登录 / SESSDATA 已失效，请重新登录后抓取 Cookie";
                case ERROR_CSRF_FAILED -> "bili_jct（csrf）校验失败，请重新复制完整 Cookie";
                default -> "B站验证失败（code=" + code + "）：" + message;
            };
            return new NavInfo(false, null, null, text);
        }
        JsonNode data = body.path("data");
        if (!data.path("isLogin").asBoolean(false)) {
            return new NavInfo(false, null, null, "Cookie 已失效，请重新登录后抓取");
        }
        String mid = data.path("mid").asText("");
        return new NavInfo(true, data.path("uname").asText(""), mid, "正常");
    }

    /** nav 解析结果。 */
    private record NavInfo(boolean isLogin, String uname, String mid, String message) {
    }
}
