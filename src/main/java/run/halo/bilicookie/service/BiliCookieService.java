package run.halo.bilicookie.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.bilicookie.config.SettingService;
import run.halo.bilicookie.crypto.AesGcmCipher;
import run.halo.bilicookie.crypto.EncryptionKeyStore;
import run.halo.bilicookie.extension.BiliCookie;
import run.halo.bilicookie.extension.BiliCookie.BiliCookieSpec;
import run.halo.bilicookie.web.dto.CookieResponse;
import run.halo.bilicookie.web.dto.CookieSubmitRequest;
import run.halo.bilicookie.web.dto.PreferencesRequest;
import run.halo.bilicookie.web.dto.StatusResponse;
import run.halo.bilicookie.web.exception.ApiException;

/**
 * 普通用户 Cookie 业务：查询、自助提交/覆盖、状态计算，以及密钥加解密编排。
 *
 * <p>userId 即 Halo 用户名（User 的 metadata.name），同时用作 {@link BiliCookie} 资源的 name，
 * 保证一个用户仅一份 Cookie。</p>
 */
@Service
public class BiliCookieService {

    private final ReactiveExtensionClient client;
    private final EncryptionKeyStore keyStore;
    private final SettingService settingService;
    private final AuditLogService auditLogService;

    public BiliCookieService(ReactiveExtensionClient client,
        EncryptionKeyStore keyStore, SettingService settingService,
        AuditLogService auditLogService) {
        this.client = client;
        this.keyStore = keyStore;
        this.settingService = settingService;
        this.auditLogService = auditLogService;
    }

    /** 按 userId 查询，不存在则返回空 Mono。 */
    public Mono<BiliCookie> find(String userId) {
        return client.fetch(BiliCookie.class, userId);
    }

    /** 查询本人 Cookie，不存在时抛 40402。 */
    public Mono<BiliCookie> require(String userId) {
        return find(userId).switchIfEmpty(Mono.error(ApiException.cookieNotConfigured()));
    }

    /** 拼接返回 Cookie 串：{@code SESSDATA=xxx; bili_jct=xxx}。 */
    public Mono<CookieResponse> getCookie(String userId) {
        return require(userId)
            .flatMap(cookie -> keyStore.getOrCreateCipher()
                .map(cipher -> toCookieResponse(cookie, cipher)));
    }

    /**
     * UI 查看用：返回**脱敏后**的 Cookie 串（每个值仅首尾 4 位），
     * 不再输出完整明文。完整明文仅客户端专用接口（/cookie/client）与刷新接口返回。
     */
    public Mono<CookieResponse> getMaskedCookie(String userId) {
        return getCookie(userId)
            .map(resp -> new CookieResponse(maskCookie(resp.cookie())));
    }

    /**
     * 保存/覆盖 Cookie 并记录审计。operator 为执行者（本人或管理员），
     * action 区分 SAVE（用户本人）与 ADMIN_SAVE（管理员代存）。
     */
    public Mono<Void> save(String userId, CookieSubmitRequest request, String operator,
        String action) {
        if (isBlank(request.sessdata()) || isBlank(request.biliJct())) {
            return Mono.error(ApiException.badRequest("SESSDATA 和 bili_jct 为必填项"));
        }
        return keyStore.getOrCreateCipher()
            .map(cipher -> buildSpec(userId, request, cipher))
            .flatMap(spec -> upsert(userId, spec))
            .then(auditLogService.record(userId, operator, action, true, "保存/更新 Cookie"));
    }

    /** 删除某用户 Cookie（幂等，不存在也视为成功）并记录审计。 */
    public Mono<Void> delete(String userId, String operator, String action) {
        return find(userId)
            .flatMap(cookie -> client.delete(cookie)
                .then(auditLogService.record(userId, operator, action, true, "删除 Cookie")))
            .then();
    }

    /** 读取明文 Cookie（供刷新协议构建 B 站请求头），不存在时抛 40402。 */
    public Mono<PlainCookie> getPlainCookie(String userId) {
        return require(userId)
            .flatMap(cookie -> keyStore.getOrCreateCipher()
                .map(cipher -> {
                    BiliCookieSpec spec = cookie.getSpec();
                    return new PlainCookie(
                        decrypt(cipher, spec.getSessdata()),
                        decrypt(cipher, spec.getBiliJct()),
                        decrypt(cipher, spec.getDedeUserId()),
                        decrypt(cipher, spec.getSid()),
                        decrypt(cipher, spec.getRefreshToken()),
                        decrypt(cipher, spec.getBuvid3()),
                        decrypt(cipher, spec.getBuvid4()),
                        decrypt(cipher, spec.getDedeUserIdCkMd5()),
                        spec.getSavedAt());
                }));
    }

    /** 刷新成功后原子更新 Cookie 各字段并重置 saved_at / last_refresh_error。 */
    public Mono<Void> updateAfterRefresh(String userId, RefreshOutcome outcome) {
        return keyStore.getOrCreateCipher()
            .flatMap(cipher -> find(userId)
                .flatMap(existing -> {
                    BiliCookieSpec spec = existing.getSpec();
                    if (!isBlank(outcome.sessdata())) {
                        spec.setSessdata(encrypt(cipher, outcome.sessdata()));
                    }
                    if (!isBlank(outcome.biliJct())) {
                        spec.setBiliJct(encrypt(cipher, outcome.biliJct()));
                    }
                    if (!isBlank(outcome.dedeUserId())) {
                        spec.setDedeUserId(encrypt(cipher, outcome.dedeUserId()));
                    }
                    if (!isBlank(outcome.dedeUserIdCkMd5())) {
                        spec.setDedeUserIdCkMd5(encrypt(cipher, outcome.dedeUserIdCkMd5()));
                    }
                    if (!isBlank(outcome.sid())) {
                        spec.setSid(encrypt(cipher, outcome.sid()));
                    }
                    if (!isBlank(outcome.refreshToken())) {
                        spec.setRefreshToken(encrypt(cipher, outcome.refreshToken()));
                    }
                    spec.setSavedAt(Instant.now().toString());
                    spec.setLastRefreshError(null);
                    return client.update(existing);
                }))
            .then();
    }

    /** 记录最近一次刷新失败原因（用户存在才记录，幂等）。 */
    public Mono<Void> recordRefreshError(String userId, String error) {
        return find(userId)
            .flatMap(existing -> {
                existing.getSpec().setLastRefreshError(error);
                return client.update(existing);
            })
            .then();
    }

    /** 状态查询：全局开关、是否配置、预估剩余天数、最后刷新时间、用户级开关与账号信息。 */
    public Mono<StatusResponse> status(String userId) {
        return settingService.isEnabled()
            .flatMap(enabled -> {
                if (!enabled) {
                    return Mono.just(StatusResponse.disabled());
                }
                return find(userId)
                    .flatMap(this::toStatus)
                    .defaultIfEmpty(StatusResponse.notConfigured());
            });
    }

    /** 读取用户级开关与账号信息（不依赖全局开关，供偏好/客户端判断用）。 */
    public Mono<StatusResponse> getPreferences(String userId) {
        return find(userId)
            .flatMap(this::toStatus)
            .defaultIfEmpty(StatusResponse.notConfigured());
    }

    /**
     * 更新用户级开关（userEnabled/clientEnabled/autoRefreshEnabled，null 不修改）。
     * 记录不存在时创建仅含开关的占位记录，保证开启总开关可先于 Cookie 保存。
     */
    public Mono<StatusResponse> updatePreferences(String userId, PreferencesRequest request,
        String operator) {
        return find(userId)
            // 更新分支必须发射非空值：applyPreferences 返回 Mono<Void> 只完成不发射，
            // 若直接 switchIfEmpty 会误判为「记录不存在」而重建记录、重置其他开关。
            .flatMap(existing -> applyPreferences(existing, request).thenReturn(existing))
            .switchIfEmpty(Mono.defer(() -> createPrefsOnly(userId, request)))
            .then(auditLogService.record(userId, operator, "PREF_UPDATE", true,
                describePreferenceChange(request)))
            .then(getPreferences(userId));
    }

    /** 用户总开关是否启用（记录缺失视为 false）。 */
    public Mono<Boolean> isUserEnabled(String userId) {
        return find(userId)
            .map(cookie -> isTrue(cookie.getSpec().getUserEnabled()))
            .defaultIfEmpty(false);
    }

    /** 是否允许客户端连接（总开关启用且客户端开关开启）。 */
    public Mono<Boolean> isClientAllowed(String userId) {
        return find(userId)
            .map(cookie -> isTrue(cookie.getSpec().getUserEnabled())
                && isTrue(cookie.getSpec().getClientEnabled()))
            .defaultIfEmpty(false);
    }

    /** 是否允许定时自动续期（总开关启用且自动刷新开关开启）。 */
    public Mono<Boolean> isAutoRefreshEnabled(String userId) {
        return find(userId)
            .map(cookie -> isTrue(cookie.getSpec().getUserEnabled())
                && isTrue(cookie.getSpec().getAutoRefreshEnabled()))
            .defaultIfEmpty(false);
    }

    /** 验证成功后写入 B 站账号信息（用户名/UID）并刷新 validatedAt。 */
    public Mono<Void> updateAccountInfo(String userId, String biliUsername, String biliUid) {
        return find(userId)
            .flatMap(existing -> {
                BiliCookieSpec spec = existing.getSpec();
                spec.setBiliUsername(biliUsername);
                spec.setBiliUid(biliUid);
                spec.setValidatedAt(Instant.now().toString());
                return client.update(existing);
            })
            .then();
    }

    /** 清除所有数据并禁用：删除 Cookie 记录并记录审计（幂等）。 */
    public Mono<Void> clearAll(String userId, String operator) {
        return find(userId)
            .flatMap(cookie -> client.delete(cookie)
                .then(auditLogService.record(userId, operator, "CLEAR", true,
                    "清除所有数据并禁用")))
            .then();
    }

    private CookieResponse toCookieResponse(BiliCookie cookie, AesGcmCipher cipher) {
        BiliCookieSpec spec = cookie.getSpec();
        String sessdata = decrypt(cipher, spec.getSessdata());
        String biliJct = decrypt(cipher, spec.getBiliJct());
        return new CookieResponse("SESSDATA=" + sessdata + "; bili_jct=" + biliJct);
    }

    private Mono<StatusResponse> toStatus(BiliCookie cookie) {
        return settingService.cookieExpireDays()
            .map(days -> {
                BiliCookieSpec spec = cookie.getSpec();
                boolean valid = !isBlank(spec.getSessdata());
                int expiresIn = computeExpiresIn(spec.getSavedAt(), days);
                String message = valid ? "正常" : "未登录";
                return new StatusResponse(true, valid, expiresIn, spec.getSavedAt(), message,
                    isTrue(spec.getUserEnabled()), isTrue(spec.getClientEnabled()),
                    isTrue(spec.getAutoRefreshEnabled()), spec.getBiliUsername(),
                    spec.getBiliUid(), !isBlank(spec.getValidatedAt()));
            });
    }

    private BiliCookieSpec buildSpec(String userId, CookieSubmitRequest request,
        AesGcmCipher cipher) {
        BiliCookieSpec spec = new BiliCookieSpec();
        spec.setUserId(userId);
        spec.setSessdata(encrypt(cipher, request.sessdata()));
        spec.setBiliJct(encrypt(cipher, request.biliJct()));
        spec.setRefreshToken(encrypt(cipher, request.refreshToken()));
        spec.setDedeUserId(encrypt(cipher, request.dedeUserID()));
        spec.setSid(encrypt(cipher, request.sid()));
        // 自动补齐：buvid3/buvid4（占位生成，阶段 4 对齐 B 站真实格式），
        // DedeUserID__ckMd5 由 DedeUserID 推导（同样阶段 4 校正算法）。
        spec.setBuvid3(encrypt(cipher, newBuvid3()));
        spec.setBuvid4(encrypt(cipher, newBuvid4()));
        spec.setDedeUserIdCkMd5(encrypt(cipher, dedeUserIDCkMd5(request.dedeUserID())));
        spec.setSavedAt(Instant.now().toString());
        spec.setLastRefreshError(null);
        return spec;
    }

    private Mono<Void> upsert(String userId, BiliCookieSpec newSpec) {
        return find(userId)
            .flatMap(existing -> {
                BiliCookieSpec old = existing.getSpec();
                // 保存 Cookie 不覆盖用户开关与账号信息
                newSpec.setUserEnabled(old.getUserEnabled());
                newSpec.setClientEnabled(old.getClientEnabled());
                newSpec.setAutoRefreshEnabled(old.getAutoRefreshEnabled());
                newSpec.setBiliUsername(old.getBiliUsername());
                newSpec.setBiliUid(old.getBiliUid());
                newSpec.setValidatedAt(old.getValidatedAt());
                existing.setSpec(newSpec);
                return client.update(existing);
            })
            .switchIfEmpty(createNew(userId, newSpec))
            .then();
    }

    /** 在已存在记录上应用开关修改（null 不修改）。 */
    private Mono<Void> applyPreferences(BiliCookie cookie, PreferencesRequest request) {
        BiliCookieSpec spec = cookie.getSpec();
        if (request.userEnabled() != null) {
            spec.setUserEnabled(request.userEnabled());
        }
        if (request.clientEnabled() != null) {
            spec.setClientEnabled(request.clientEnabled());
        }
        if (request.autoRefreshEnabled() != null) {
            spec.setAutoRefreshEnabled(request.autoRefreshEnabled());
        }
        return client.update(cookie).then();
    }

    /** 创建仅含开关的占位记录（用于开启总开关先于 Cookie 保存的场景）。 */
    private Mono<BiliCookie> createPrefsOnly(String userId, PreferencesRequest request) {
        BiliCookieSpec spec = new BiliCookieSpec();
        spec.setUserId(userId);
        spec.setUserEnabled(Boolean.TRUE.equals(request.userEnabled()));
        spec.setClientEnabled(Boolean.TRUE.equals(request.clientEnabled()));
        spec.setAutoRefreshEnabled(Boolean.TRUE.equals(request.autoRefreshEnabled()));
        spec.setSavedAt(Instant.now().toString());
        return createNew(userId, spec);
    }

    private static String describePreferenceChange(PreferencesRequest request) {
        StringBuilder sb = new StringBuilder("更新偏好设置");
        if (request.userEnabled() != null) {
            sb.append("，总开关=").append(request.userEnabled() ? "开" : "关");
        }
        if (request.clientEnabled() != null) {
            sb.append("，客户端连接=").append(request.clientEnabled() ? "开" : "关");
        }
        if (request.autoRefreshEnabled() != null) {
            sb.append("，自动刷新=").append(request.autoRefreshEnabled() ? "开" : "关");
        }
        return sb.toString();
    }

    private Mono<BiliCookie> createNew(String userId, BiliCookieSpec spec) {
        BiliCookie cookie = new BiliCookie();
        cookie.setSpec(spec);
        Metadata metadata = new Metadata();
        metadata.setName(userId);
        cookie.setMetadata(metadata);
        // 幂等写入：先查再写，避免 find→create 之间 name 已被占用；
        // create 因 name 冲突失败时兜底回退到 update，杜绝「检测到有相同名称」。
        return client.fetch(BiliCookie.class, userId)
            .flatMap(existing -> {
                existing.setSpec(spec);
                return client.update(existing);
            })
            .switchIfEmpty(Mono.defer(() -> client.create(cookie)
                .onErrorResume(error -> client.fetch(BiliCookie.class, userId)
                    .flatMap(existing -> {
                        existing.setSpec(spec);
                        return client.update(existing);
                    }))));
    }

    /* ---- 加解密 / 工具 ---- */

    private static String encrypt(AesGcmCipher cipher, String plain) {
        return isBlank(plain) ? null : cipher.encrypt(plain);
    }

    private static String decrypt(AesGcmCipher cipher, String cipherText) {
        return isBlank(cipherText) ? null : cipher.decrypt(cipherText);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 对完整 Cookie 串脱敏：每个 {@code key=value} 的值仅保留首尾 4 位，键名与分隔保持原样。 */
    private static String maskCookie(String cookie) {
        StringBuilder sb = new StringBuilder(cookie.length());
        String[] parts = cookie.split(";", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(';');
            }
            String part = parts[i];
            int idx = part.indexOf('=');
            if (idx < 0) {
                sb.append(part);
            } else {
                sb.append(part, 0, idx + 1).append(maskValue(part.substring(idx + 1)));
            }
        }
        return sb.toString();
    }

    /** 值脱敏：长度 ≤8 显示 6 个星号，否则仅保留首尾 4 位。 */
    private static String maskValue(String value) {
        if (isBlank(value)) {
            return "";
        }
        if (value.length() <= 8) {
            return "******";
        }
        return value.substring(0, 4) + "******" + value.substring(value.length() - 4);
    }

    private static boolean isTrue(Boolean b) {
        return Boolean.TRUE.equals(b);
    }

    private static String newBuvid3() {
        return UUID.randomUUID().toString().toUpperCase();
    }

    private static String newBuvid4() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private static String dedeUserIDCkMd5(String dedeUserId) {
        return isBlank(dedeUserId) ? null : md5Hex(dedeUserId);
    }

    private static String md5Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("MD5 计算失败", e);
        }
    }

    private int computeExpiresIn(String savedAt, int expireDays) {
        if (isBlank(savedAt)) {
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

    /** 解密后的明文 Cookie（仅供刷新协议等内部链路使用，禁止返回前端）。 */
    public record PlainCookie(String sessdata, String biliJct, String dedeUserId, String sid,
        String refreshToken, String buvid3, String buvid4, String dedeUserIdCkMd5, String savedAt) {
    }

    /** 刷新成功后的新凭据（B 站 Set-Cookie 中的对应字段，缺失字段保留旧值）。 */
    public record RefreshOutcome(String sessdata, String biliJct, String dedeUserId,
        String dedeUserIdCkMd5, String sid, String refreshToken) {
    }
}