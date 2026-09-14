package run.halo.bilicookie.web.dto;

/**
 * PUT /cookie/preferences 请求体，字段均可选（null 表示不修改）。
 */
public record PreferencesRequest(
    Boolean userEnabled,
    Boolean clientEnabled,
    Boolean autoRefreshEnabled
) {
}
