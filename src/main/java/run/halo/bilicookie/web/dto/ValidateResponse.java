package run.halo.bilicookie.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /cookie/validate 响应 data。
 */
public record ValidateResponse(
    boolean valid,
    @JsonProperty("bili_username") String biliUsername,
    @JsonProperty("bili_uid") String biliUid,
    String message
) {
}
