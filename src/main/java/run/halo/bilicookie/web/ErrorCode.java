package run.halo.bilicookie.web;

/**
 * 统一业务错误码，与《需求文档》§3.3 固定表一致。
 */
public final class ErrorCode {

    public static final int SUCCESS = 0;
    public static final int BAD_REQUEST = 40001;
    public static final int UNAUTHORIZED = 40101;
    public static final int FORBIDDEN = 40301;
    public static final int PLUGIN_DISABLED = 40302;
    public static final int NOT_FOUND = 40401;
    public static final int COOKIE_NOT_CONFIGURED = 40402;
    public static final int INTERNAL_ERROR = 50001;
    public static final int BILI_REFRESH_FAILED = 50002;

    private ErrorCode() {
    }
}