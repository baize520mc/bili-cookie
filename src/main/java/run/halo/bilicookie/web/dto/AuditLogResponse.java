package run.halo.bilicookie.web.dto;

import run.halo.bilicookie.extension.BiliCookieAuditLog;

/**
 * 审计日志响应项（用户 / 管理员查询用）。
 */
public record AuditLogResponse(String name, String userId, String operator, String action,
    boolean success, String message, String createdAt) {

    public static AuditLogResponse from(BiliCookieAuditLog log) {
        var spec = log.getSpec();
        return new AuditLogResponse(
            log.getMetadata().getName(),
            spec.getUserId(),
            spec.getOperator(),
            spec.getAction(),
            spec.isSuccess(),
            spec.getMessage(),
            spec.getCreatedAt());
    }
}
