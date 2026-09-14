package run.halo.bilicookie.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * B 站 Cookie 操作审计日志。
 *
 * <p>记录用户提交/更新、管理员代操、手动/定时刷新结果等关键操作，
 * 一条记录一个不可变事件，仅追加不修改。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "bili-cookie.halo.run",
    version = "v1alpha1",
    kind = "BiliCookieAuditLog",
    plural = "bilicookieauditlogs",
    singular = "bilicookieauditlog")
public class BiliCookieAuditLog extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private BiliCookieAuditLogSpec spec = new BiliCookieAuditLogSpec();

    @Data
    @Schema(name = "BiliCookieAuditLogSpec")
    public static class BiliCookieAuditLogSpec {

        /** 数据归属用户（Halo 用户名）。 */
        private String userId;

        /** 操作者（Halo 用户名）；定时任务记 userId，系统事件记 system。 */
        private String operator;

        /** 动作：SAVE / DELETE / REFRESH / ADMIN_SAVE / ADMIN_DELETE / ADMIN_REFRESH / SCHEDULED_REFRESH / USER_DELETED。 */
        private String action;

        /** 是否成功。 */
        private boolean success;

        /** 结果或失败原因。 */
        private String message;

        /** 发生时间（ISO8601 UTC）。 */
        private String createdAt;
    }
}
