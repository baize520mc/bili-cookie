package run.halo.bilicookie.web;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ApiVersion;
import run.halo.bilicookie.config.SettingService;
import run.halo.bilicookie.security.AuthSupport;
import run.halo.bilicookie.service.AuditLogService;
import run.halo.bilicookie.service.BiliAccountService;
import run.halo.bilicookie.service.BiliCookieService;
import run.halo.bilicookie.service.BiliRefreshService;
import run.halo.bilicookie.web.dto.AuditLogResponse;
import run.halo.bilicookie.web.dto.CookieResponse;
import run.halo.bilicookie.web.dto.CookieSubmitRequest;
import run.halo.bilicookie.web.dto.PreferencesRequest;
import run.halo.bilicookie.web.dto.RefreshResponse;
import run.halo.bilicookie.web.dto.StatusResponse;
import run.halo.bilicookie.web.dto.ValidateResponse;
import run.halo.bilicookie.web.exception.ApiException;

/**
 * 普通用户接口（Electron / 前台页面调用）。
 *
 * <p>实际路径前缀由 {@link ApiVersion} 提供：{@code /apis/api.bili-cookie.halo.run/v1alpha1}。
 * 所有操作都以 Token 所属用户（本人）为数据边界。</p>
 *
 * <p>用户级开关（阶段 6）：总开关 userEnabled 控制存储/更新/读取/验证等全部功能；
 * clientEnabled 仅约束客户端专用路径（/cookie/connect、/cookie/client），不影响 UI 读取。</p>
 */
@ApiVersion("api.bili-cookie.halo.run/v1alpha1")
@RestController
public class BiliCookieController {

    private static final Logger log = LoggerFactory.getLogger(BiliCookieController.class);

    private final AuthSupport authSupport;
    private final SettingService settingService;
    private final BiliCookieService cookieService;
    private final BiliRefreshService refreshService;
    private final BiliAccountService accountService;
    private final AuditLogService auditLogService;

    public BiliCookieController(AuthSupport authSupport, SettingService settingService,
        BiliCookieService cookieService, BiliRefreshService refreshService,
        BiliAccountService accountService, AuditLogService auditLogService) {
        this.authSupport = authSupport;
        this.settingService = settingService;
        this.cookieService = cookieService;
        this.refreshService = refreshService;
        this.accountService = accountService;
        this.auditLogService = auditLogService;
    }

    /** UI 读取本人 Cookie（仅校验总开关，不受 clientEnabled 限制）；返回脱敏串，不输出明文。 */
    @GetMapping("/cookie")
    public Mono<ApiResponse<CookieResponse>> getCookie() {
        return ensureEnabled()
            .then(authSupport.currentUserName())
            .flatMap(this::requireUserEnabled)
            .flatMap(cookieService::getMaskedCookie)
            .map(data -> ApiResponse.ok(data));
    }

    /** 客户端读取本人 Cookie（需总开关 + 客户端连接开关均开启），并记录 CLIENT_READ 日志。 */
    @GetMapping("/cookie/client")
    public Mono<ApiResponse<CookieResponse>> getCookieForClient() {
        return ensureEnabled()
            .then(authSupport.currentUserName())
            .flatMap(this::requireClientAllowed)
            .flatMap(userId -> cookieService.getCookie(userId)
                .flatMap(resp -> auditLogService
                    .record(userId, userId, "CLIENT_READ", true, "客户端读取 Cookie")
                    .thenReturn(resp)))
            .map(data -> ApiResponse.ok(data));
    }

    /** 客户端连接握手：仅登记连接并记录 CLIENT_CONNECT 日志，不读取任何数据。 */
    @PostMapping("/cookie/connect")
    public Mono<ApiResponse<Void>> connect() {
        return ensureEnabled()
            .then(authSupport.currentUserName())
            .flatMap(this::requireClientAllowed)
            .flatMap(userId -> auditLogService
                .record(userId, userId, "CLIENT_CONNECT", true, "客户端连接成功")
                .thenReturn(userId))
            .thenReturn(ApiResponse.<Void>ok("客户端连接成功", null));
    }

    @GetMapping("/status")
    public Mono<ApiResponse<StatusResponse>> status() {
        return authSupport.currentUserName()
            .flatMap(cookieService::status)
            .map(data -> ApiResponse.ok(data));
    }

    /** 保存/覆盖本人 Cookie；保存成功后非阻塞抓取一次 B 站账号信息（失败不影响保存）。 */
    @PostMapping("/cookie")
    public Mono<ApiResponse<Void>> submit(@RequestBody Mono<CookieSubmitRequest> requestBody) {
        return ensureEnabled()
            .then(authSupport.currentUserName())
            .flatMap(this::requireUserEnabled)
            .zipWith(requestBody)
            .flatMap(tuple -> cookieService.save(tuple.getT1(), tuple.getT2(), tuple.getT1(),
                "SAVE")
                .then(tryFetchAccountInfo(tuple.getT1())))
            .thenReturn(ApiResponse.<Void>ok("Cookie 保存成功", null));
    }

    /** 手动刷新本人 Cookie。 */
    @PostMapping("/cookie/refresh")
    public Mono<ApiResponse<RefreshResponse>> refresh() {
        return ensureEnabled()
            .then(authSupport.currentUserName())
            .flatMap(this::requireUserEnabled)
            .flatMap(userName -> refreshService.refresh(userName, userName, "REFRESH"))
            .map(resp -> ApiResponse.ok("刷新成功", resp));
    }

    /** 验证 Cookie 有效性并抓取 B 站用户名/UID（写入后前端可展示）。 */
    @PostMapping("/cookie/validate")
    public Mono<ApiResponse<ValidateResponse>> validate() {
        return ensureEnabled()
            .then(authSupport.currentUserName())
            .flatMap(this::requireUserEnabled)
            .flatMap(userId -> accountService.validate(userId, userId))
            .map(resp -> ApiResponse.ok("验证完成", resp));
    }

    /** 更新用户级开关（userEnabled/clientEnabled/autoRefreshEnabled），返回更新后状态。 */
    @PutMapping("/cookie/preferences")
    public Mono<ApiResponse<StatusResponse>> updatePreferences(
        @RequestBody Mono<PreferencesRequest> requestBody) {
        return authSupport.currentUserName()
            .zipWith(requestBody)
            .flatMap(tuple -> cookieService
                .updatePreferences(tuple.getT1(), tuple.getT2(), tuple.getT1()))
            .map(data -> ApiResponse.ok("偏好设置已更新", data));
    }

    /** 清除所有数据并禁用（红色危险操作，前端需二次确认）。 */
    @PostMapping("/cookie/clear")
    public Mono<ApiResponse<Void>> clear() {
        return authSupport.currentUserName()
            .flatMap(userId -> cookieService.clearAll(userId, userId))
            .thenReturn(ApiResponse.<Void>ok("已清除所有数据并禁用", null));
    }

    /** 查询当前用户自己的操作日志（倒序）。 */
    @GetMapping("/logs")
    public Mono<ApiResponse<List<AuditLogResponse>>> logs(
        @RequestParam(name = "limit", defaultValue = "50") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return authSupport.currentUserName()
            .doOnNext(userId -> log.info("[bili-cookie] 收到日志查询请求: userId={}, limit={}",
                userId, safeLimit))
            .flatMapMany(userId -> auditLogService.list(userId, safeLimit))
            .map(AuditLogResponse::from)
            .collectList()
            .doOnNext(list -> log.info("[bili-cookie] 日志查询成功: 返回 {} 条", list.size()))
            .map(list -> ApiResponse.ok(list));
    }

    /* ---- 内部 ---- */

    /** 全局开关校验：关闭时返回 40302 并携带当前状态（data）。 */
    private Mono<Void> ensureEnabled() {
        return settingService.isEnabled()
            .flatMap(enabled -> enabled ? Mono.empty()
                : Mono.error(ApiException.pluginDisabled("插件全局开关已关闭",
                    StatusResponse.disabled())));
    }

    /** 用户总开关校验：未启用返回 40302 + 可读提示 + 当前状态（data）。 */
    private Mono<String> requireUserEnabled(String userId) {
        return cookieService.isUserEnabled(userId)
            .flatMap(enabled -> enabled ? Mono.just(userId)
                : cookieService.status(userId)
                    .flatMap(status -> Mono.error(ApiException.pluginDisabled(
                        "用户总开关未启用，请先在页面开启插件功能", status))));
    }

    /** 客户端连接校验：总开关 + clientEnabled 均开启，否则返回 40302 + 当前状态（data）。 */
    private Mono<String> requireClientAllowed(String userId) {
        return cookieService.isClientAllowed(userId)
            .flatMap(allowed -> allowed ? Mono.just(userId)
                : cookieService.status(userId)
                    .flatMap(status -> Mono.error(ApiException.pluginDisabled(
                        "未开放客户端连接，请先在页面开启「接受客户端连接」", status))));
    }

    /** 保存后非阻塞抓取账号信息：失败仅记录日志，不影响保存成功响应。 */
    private Mono<Void> tryFetchAccountInfo(String userId) {
        return accountService.validate(userId, "system")
            .onErrorResume(error -> {
                log.warn("保存后抓取 B 站账号信息失败 userId={}: {}", userId, error.getMessage());
                return Mono.empty();
            })
            .then();
    }
}
