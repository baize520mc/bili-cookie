package run.halo.bilicookie.web;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ApiVersion;
import run.halo.bilicookie.config.BiliCookieSetting;
import run.halo.bilicookie.security.AuthSupport;
import run.halo.bilicookie.service.AdminService;
import run.halo.bilicookie.service.AuditLogService;
import run.halo.bilicookie.web.dto.AdminOverviewResponse;
import run.halo.bilicookie.web.dto.AdminSettingsRequest;
import run.halo.bilicookie.web.dto.AdminUserStatus;
import run.halo.bilicookie.web.dto.AuditLogResponse;
import run.halo.bilicookie.web.dto.CookieSubmitRequest;
import run.halo.bilicookie.web.dto.RefreshResponse;
import run.halo.bilicookie.web.dto.UserCookieDetailStatus;
import run.halo.bilicookie.web.exception.ApiException;

/**
 * 管理员接口。仅超级管理员（角色 {@code super-role}）或已分配插件 manage 角色模板的用户可访问。
 *
 * <p>实际路径前缀：{@code /apis/api.bili-cookie.halo.run/v1alpha1/admin}。</p>
 */
@ApiVersion("api.bili-cookie.halo.run/v1alpha1")
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AuthSupport authSupport;
    private final AdminService adminService;
    private final AuditLogService auditLogService;

    public AdminController(AuthSupport authSupport, AdminService adminService,
        AuditLogService auditLogService) {
        this.authSupport = authSupport;
        this.adminService = adminService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/settings")
    public Mono<ApiResponse<BiliCookieSetting>> getSetting() {
        return requireAdmin()
            .then(adminService.getSetting())
            .map(data -> ApiResponse.ok(data));
    }

    @PutMapping("/settings")
    public Mono<ApiResponse<BiliCookieSetting>> updateSetting(
        @RequestBody Mono<AdminSettingsRequest> body) {
        return requireAdmin()
            .then(authSupport.currentUserName())
            .flatMap(operator -> Mono.zip(Mono.just(operator), body)
                .flatMap(tuple -> adminService.updateSetting(tuple.getT1(), tuple.getT2())))
            .map(data -> ApiResponse.ok("设置已更新", data));
    }

    @GetMapping("/status")
    public Mono<ApiResponse<AdminOverviewResponse>> overview() {
        return requireAdmin()
            .then(adminService.overview())
            .map(data -> ApiResponse.ok(data));
    }

    @GetMapping("/users")
    public Mono<ApiResponse<List<AdminUserStatus>>> listUsers() {
        return requireAdmin()
            .then(adminService.listUsers())
            .map(data -> ApiResponse.ok(data));
    }

    @GetMapping("/users/{userId}/cookie/status")
    public Mono<ApiResponse<UserCookieDetailStatus>> userCookieStatus(
        @PathVariable String userId) {
        return requireAdmin()
            .then(adminService.userCookieStatus(userId))
            .map(data -> ApiResponse.ok(data));
    }

    @PostMapping("/users/{userId}/cookie")
    public Mono<ApiResponse<Void>> saveForUser(@PathVariable String userId,
        @RequestBody Mono<CookieSubmitRequest> body) {
        return requireAdmin()
            .then(authSupport.currentUserName())
            .flatMap(operator -> Mono.zip(Mono.just(operator), body)
                .flatMap(tuple -> adminService.saveForUser(userId, tuple.getT2(), tuple.getT1())))
            .thenReturn(ApiResponse.<Void>ok("Cookie 保存成功", null));
    }

    @DeleteMapping("/users/{userId}/cookie")
    public Mono<ResponseEntity<Void>> deleteForUser(@PathVariable String userId) {
        return requireAdmin()
            .then(authSupport.currentUserName())
            .flatMap(operator -> adminService.deleteForUser(userId, operator))
            .thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping("/users/{userId}/cookie/refresh")
    public Mono<ApiResponse<RefreshResponse>> refreshForUser(@PathVariable String userId) {
        return requireAdmin()
            .then(authSupport.currentUserName())
            .flatMap(operator -> adminService.refreshForUser(userId, operator))
            .map(data -> ApiResponse.ok("刷新成功", data));
    }

    @GetMapping("/logs")
    public Mono<ApiResponse<List<AuditLogResponse>>> listLogs(
        @RequestParam(name = "limit", defaultValue = "50") int limit,
        @RequestParam(name = "userId", required = false) String userId) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return requireAdmin()
            .then(auditLogService.list(userId, safeLimit)
                .map(AuditLogResponse::from)
                .collectList())
            .map(data -> ApiResponse.ok(data));
    }

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private Mono<Void> requireAdmin() {
        return authSupport.isAdmin()
            .flatMap(admin -> admin ? Mono.empty()
                : authSupport.currentAuthorities()
                    .doOnNext(roles -> log.warn(
                        "[bili-cookie] 管理员权限校验失败，当前 authorities={}", roles))
                    .then(Mono.error(ApiException.forbidden("需要管理员权限"))));
    }
}