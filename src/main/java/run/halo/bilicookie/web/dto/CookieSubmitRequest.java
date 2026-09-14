package run.halo.bilicookie.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /cookie 请求体，字段名对齐 B 站 Cookie 原始命名。
 */
public record CookieSubmitRequest(
    @JsonProperty("SESSDATA") String sessdata,
    @JsonProperty("bili_jct") String biliJct,
    @JsonProperty("refresh_token") String refreshToken,
    @JsonProperty("DedeUserID") String dedeUserID,
    @JsonProperty("sid") String sid
) {
}