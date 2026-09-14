package run.halo.bilicookie.web.exception;

import org.springframework.http.HttpStatus;
import run.halo.bilicookie.web.ErrorCode;

/**
 * 业务异常，携带 HTTP 状态码与业务 code，由全局异常处理器统一转换为统一响应体。
 *
 * <p>{@code data} 为可选负载：当某能力因开关未启用而拒绝时，携带当前状态
 * （如 {@code StatusResponse}），让客户端可直接展示「当前处于何种状态」。</p>
 */
public class ApiException extends RuntimeException {

    private final int code;
    private final HttpStatus httpStatus;
    private final Object data;

    public ApiException(HttpStatus httpStatus, int code, String message) {
        this(httpStatus, code, message, null);
    }

    public ApiException(HttpStatus httpStatus, int code, String message, Object data) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public Object getData() {
        return data;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, message);
    }

    public static ApiException pluginDisabled(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCode.PLUGIN_DISABLED, message);
    }

    /** 开关类拒绝：附带当前状态（data），便于客户端展示可读提示。 */
    public static ApiException pluginDisabled(String message, Object data) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCode.PLUGIN_DISABLED, message, data);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, message);
    }

    public static ApiException cookieNotConfigured() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.COOKIE_NOT_CONFIGURED,
            "该用户尚未配置 Cookie");
    }

    public static ApiException internalError(String message) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, message);
    }

    public static ApiException biliRefreshFailed(String message) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.BILI_REFRESH_FAILED,
            message);
    }
}