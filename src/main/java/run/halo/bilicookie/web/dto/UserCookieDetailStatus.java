package run.halo.bilicookie.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GET /admin/users/{userId}/cookie/status 响应 data（同 4.2 结构 + 开关与账号信息）。
 */
public record UserCookieDetailStatus(
    boolean enabled,
    boolean valid,
    @JsonProperty("expires_in") int expiresIn,
    @JsonProperty("last_refresh") String lastRefresh,
    String message,
    @JsonProperty("refresh_token_present") boolean refreshTokenPresent,
    @JsonProperty("user_enabled") boolean userEnabled,
    @JsonProperty("client_enabled") boolean clientEnabled,
    @JsonProperty("auto_refresh_enabled") boolean autoRefreshEnabled,
    @JsonProperty("bili_username") String biliUsername,
    @JsonProperty("bili_uid") String biliUid
) {
}
