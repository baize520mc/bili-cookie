package run.halo.bilicookie.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 管理员视角的单个用户 Cookie 状态（列表项）。
 */
public record AdminUserStatus(
    String userId,
    String username,
    boolean cookieConfigured,
    boolean valid,
    int expiresIn,
    @JsonProperty("bili_username") String biliUsername,
    @JsonProperty("bili_uid") String biliUid,
    @JsonProperty("user_enabled") boolean userEnabled,
    @JsonProperty("client_enabled") boolean clientEnabled,
    @JsonProperty("auto_refresh_enabled") boolean autoRefreshEnabled
) {
}
