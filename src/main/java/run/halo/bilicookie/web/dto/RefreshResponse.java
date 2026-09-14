package run.halo.bilicookie.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /cookie/refresh 响应 data。
 */
public record RefreshResponse(
    @JsonProperty("new_cookie") String newCookie
) {
}