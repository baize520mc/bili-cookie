package run.halo.bilicookie.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * B 站 Cookie 存储模型。
 *
 * <p>一个 Halo 用户仅一份 Cookie（spec.userId 唯一索引）。
 * sensitive 字段（sessdata/biliJct/refreshToken 等）为 AES-256-GCM 加密后的密文。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "bili-cookie.halo.run",
    version = "v1alpha1",
    kind = "BiliCookie",
    plural = "bilicookies",
    singular = "bilicookie")
public class BiliCookie extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private BiliCookieSpec spec = new BiliCookieSpec();

    @Data
    @Schema(name = "BiliCookieSpec")
    public static class BiliCookieSpec {

        /** Halo 用户 ID，唯一索引。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String userId;

        /* 敏感字段：加密后存储 */
        private String sessdata;
        private String biliJct;
        private String dedeUserId;
        private String sid;
        private String refreshToken;
        private String dedeUserIdCkMd5;
        private String buvid3;
        private String buvid4;

        /* 元信息 */
        private String savedAt;
        private String lastRefreshError;

        /* B 站账号信息（非敏感，明文）：验证时抓取写入 */
        private String biliUsername;
        private String biliUid;
        private String validatedAt;

        /* 用户级开关（null 视为 false，保守默认禁用） */
        private Boolean userEnabled;
        private Boolean clientEnabled;
        private Boolean autoRefreshEnabled;
    }
}