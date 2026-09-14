package run.halo.bilicookie.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.bilicookie.config.BiliCookieSetting;
import run.halo.bilicookie.config.SettingService;
import run.halo.bilicookie.extension.BiliCookie;
import run.halo.bilicookie.web.dto.AdminOverviewResponse;
import run.halo.bilicookie.web.dto.AdminSettingsRequest;
import run.halo.bilicookie.web.dto.AdminUserStatus;
import run.halo.bilicookie.web.dto.CookieSubmitRequest;
import run.halo.bilicookie.web.dto.RefreshResponse;
import run.halo.bilicookie.web.dto.UserCookieDetailStatus;
import run.halo.bilicookie.web.exception.ApiException;

/**
 * 管理员操作服务：设置读写、系统概览、用户列表、单用户详情，以及代用户保存/删除/刷新。
 */
@Service
public class AdminService {

    private static final String CONFIG_MAP_NAME = "bili-cookie-config";

    private final ReactiveExtensionClient client;
    private final SettingService settingService;
    private final BiliCookieService cookieService;
    private final BiliRefreshService refreshService;
    private final AuditLogService auditLogService;

    public AdminService(ReactiveExtensionClient client, SettingService settingService,
        BiliCookieService cookieService, BiliRefreshService refreshService,
        AuditLogService auditLogService) {
        this.client = client;
        this.settingService = settingService;
        this.cookieService = cookieService;
        this.refreshService = refreshService;
        this.auditLogService = auditLogService;
    }

    public Mono<BiliCookieSetting> getSetting() {
        return settingService.getSetting();
    }

    public Mono<BiliCookieSetting> updateSetting(String operator, AdminSettingsRequest request) {
        return settingService.getSetting()
            .map(current -> merge(current, request))
            .flatMap(this::persistSetting)
            .flatMap(setting -> auditLogService
                .record(null, operator, "ADMIN_SETTINGS", true,
                    "修改设置：全局开关=" + setting.globalEnabled()
                        + "，刷新间隔=" + setting.refreshIntervalHours()
                        + "h，预估有效期=" + setting.cookieExpireDays() + "天")
                .thenReturn(setting));
    }

    public Mono<AdminOverviewResponse> overview() {
        return settingService.isEnabled()
            .flatMap(enabled -> listUsers()
                .map(users -> new AdminOverviewResponse(enabled, users.size(), users)));
    }

    public Mono<List<AdminUserStatus>> listUsers() {
        return client.listAll(User.class, ListOptions.builder().build(), Sort.unsorted())
            .flatMap(this::toUserStatus)
            .collectList();
    }

    public Mono<UserCookieDetailStatus> userCookieStatus(String userId) {
        return settingService.isEnabled()
            .flatMap(enabled -> {
                if (!enabled) {
                    return Mono.just(new UserCookieDetailStatus(false, false, 0, null,
                        "全局已禁用", false, false, false, false, null, null));
                }
                return cookieService.find(userId)
                    .flatMap(this::toDetailStatus)
                    .defaultIfEmpty(new UserCookieDetailStatus(true, false, 0, null,
                        "未登录", false, false, false, false, null, null));
            });
    }

    public Mono<Void> saveForUser(String userId, CookieSubmitRequest request, String operator) {
        return requireUser(userId)
            .then(cookieService.save(userId, request, operator, "ADMIN_SAVE"));
    }

    public Mono<Void> deleteForUser(String userId, String operator) {
        return cookieService.delete(userId, operator, "ADMIN_DELETE");
    }

    public Mono<RefreshResponse> refreshForUser(String userId, String operator) {
        return requireUser(userId)
            .then(refreshService.refresh(userId, operator, "ADMIN_REFRESH"));
    }

    /* ---- 内部 ---- */

    private BiliCookieSetting merge(BiliCookieSetting current, AdminSettingsRequest request) {
        boolean globalEnabled = request.globalEnabled() != null
            ? request.globalEnabled() : current.globalEnabled();
        int refreshIntervalHours = request.refreshIntervalHours() != null
            ? request.refreshIntervalHours() : current.refreshIntervalHours();
        int cookieExpireDays = request.cookieExpireDays() != null
            ? request.cookieExpireDays() : current.cookieExpireDays();
        return new BiliCookieSetting(globalEnabled, refreshIntervalHours, cookieExpireDays);
    }

    private Mono<BiliCookieSetting> persistSetting(BiliCookieSetting setting) {
        String json = toJson(setting);
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .flatMap(cm -> {
                cm.putDataItem(BiliCookieSetting.GROUP, json);
                return client.update(cm);
            })
            .switchIfEmpty(createConfigMap(json))
            .thenReturn(setting);
    }

    private Mono<ConfigMap> createConfigMap(String json) {
        ConfigMap configMap = new ConfigMap();
        Metadata metadata = new Metadata();
        metadata.setName(CONFIG_MAP_NAME);
        configMap.setMetadata(metadata);
        configMap.putDataItem(BiliCookieSetting.GROUP, json);
        return client.create(configMap);
    }

    private String toJson(BiliCookieSetting s) {
        return "{\"globalEnabled\":" + s.globalEnabled()
            + ",\"refreshIntervalHours\":" + s.refreshIntervalHours()
            + ",\"cookieExpireDays\":" + s.cookieExpireDays() + "}";
    }

    private Mono<AdminUserStatus> toUserStatus(User user) {
        String userId = user.getMetadata().getName();
        String username = displayName(user);
        return cookieService.find(userId)
            .flatMap(cookie -> settingService.cookieExpireDays().map(days -> {
                var spec = cookie.getSpec();
                boolean valid = isNotBlank(spec.getSessdata());
                int expiresIn = computeExpiresIn(spec.getSavedAt(), days);
                return new AdminUserStatus(userId, username, true, valid, expiresIn,
                    spec.getBiliUsername(), spec.getBiliUid(),
                    isTrue(spec.getUserEnabled()), isTrue(spec.getClientEnabled()),
                    isTrue(spec.getAutoRefreshEnabled()));
            }))
            .defaultIfEmpty(new AdminUserStatus(userId, username, false, false, 0,
                null, null, false, false, false));
    }

    private Mono<UserCookieDetailStatus> toDetailStatus(BiliCookie cookie) {
        return settingService.cookieExpireDays().map(days -> {
            var spec = cookie.getSpec();
            boolean valid = isNotBlank(spec.getSessdata());
            int expiresIn = computeExpiresIn(spec.getSavedAt(), days);
            boolean refreshTokenPresent = isNotBlank(spec.getRefreshToken());
            String message = valid ? "正常" : "未登录";
            return new UserCookieDetailStatus(true, valid, expiresIn, spec.getSavedAt(), message,
                refreshTokenPresent, isTrue(spec.getUserEnabled()),
                isTrue(spec.getClientEnabled()), isTrue(spec.getAutoRefreshEnabled()),
                spec.getBiliUsername(), spec.getBiliUid());
        });
    }

    private String displayName(User user) {
        String name = user.getSpec().getDisplayName();
        return isNotBlank(name) ? name : user.getMetadata().getName();
    }

    private Mono<Void> requireUser(String userId) {
        return client.fetch(User.class, userId)
            .hasElement()
            .flatMap(exists -> exists ? Mono.empty()
                : Mono.error(ApiException.notFound("用户不存在: " + userId)));
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean isTrue(Boolean b) {
        return Boolean.TRUE.equals(b);
    }

    private int computeExpiresIn(String savedAt, int expireDays) {
        if (!isNotBlank(savedAt)) {
            return 0;
        }
        try {
            Instant saved = Instant.parse(savedAt);
            Instant expiry = saved.plus(Duration.ofDays(expireDays));
            long days = Duration.between(Instant.now(), expiry).toDays();
            return (int) Math.max(0, days);
        } catch (Exception e) {
            return 0;
        }
    }
}