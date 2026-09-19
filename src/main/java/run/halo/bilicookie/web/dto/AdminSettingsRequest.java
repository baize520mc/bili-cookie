package run.halo.bilicookie.web.dto;

/**
 * PUT /admin/settings 请求体，字段均可选（null 表示不修改）。
 */
public record AdminSettingsRequest(
    Boolean globalEnabled,
    Double refreshIntervalMinutes,
    Integer cookieExpireDays
) {
}
