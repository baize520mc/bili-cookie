package run.halo.bilicookie.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GET /status 响应 data。
 */
public record StatusResponse(
    boolean enabled,
    boolean valid,
    @JsonProperty("expires_in") int expiresIn,
    @JsonProperty("last_refresh") String lastRefresh,
    String message,
    @JsonProperty("user_enabled") boolean userEnabled,
    @JsonProperty("client_enabled") boolean clientEnabled,
    @JsonProperty("auto_refresh_enabled") boolean autoRefreshEnabled,
    @JsonProperty("bili_username") String biliUsername,
    @JsonProperty("bili_uid") String biliUid,
    boolean validated,
    @JsonProperty("has_refresh_token") boolean hasRefreshToken,
    @JsonProperty("plugin_version") String pluginVersion
) {

    public static StatusResponse disabled(String pluginVersion) {
        return new StatusResponse(false, false, 0, null, "全局已禁用",
            false, false, false, null, null, false, false, pluginVersion);
    }

    public static StatusResponse notConfigured(String pluginVersion) {
        return new StatusResponse(true, false, 0, null, "未登录",
            false, false, false, null, null, false, false, pluginVersion);
    }
}
