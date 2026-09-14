package run.halo.bilicookie.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import run.halo.bilicookie.web.exception.ApiException;

/**
 * B 站刷新协议纯函数测试（RSA 路径、refresh_csrf 提取、错误码文案、MD5 推导）。
 */
class BiliRefreshServiceProtocolTest {

    @Test
    void shouldExtractRefreshCsrfFromHtml() {
        String html = "<div id=\"1-name\">a1b2c3d4e5f6789012345678abcdef01</div>";
        assertThat(BiliRefreshService.extractRefreshCsrf(html))
            .isEqualTo("a1b2c3d4e5f6789012345678abcdef01");
    }

    @Test
    void shouldFailWhenRefreshCsrfMissing() {
        assertThatThrownBy(() -> BiliRefreshService.extractRefreshCsrf("<html>no token</html>"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("无法从 B 站 correspond 页面提取 refresh_csrf");
    }

    @Test
    void shouldBuildHexCorrespondPath() {
        String path = BiliRefreshService.buildCorrespondPath(1_752_000_000_000L);
        // RSA-1024 加密 128 字节 → hex 256 字符
        assertThat(path).hasSize(256);
        assertThat(path.matches("[0-9a-f]+")).isTrue();
    }

    @Test
    void shouldBuildDifferentPathForDifferentTimestamp() {
        String path1 = BiliRefreshService.buildCorrespondPath(1_752_000_000_001L);
        String path2 = BiliRefreshService.buildCorrespondPath(1_752_000_000_002L);
        assertThat(path1).isNotEqualTo(path2);
    }

    @Test
    void shouldMap86095ErrorMessage() {
        String message = BiliRefreshService.biliErrorMessage("刷新 cookie/refresh", 86095, "xxx");
        assertThat(message).contains("86095").contains("一次性抓取全部 5 个字段");
    }

    @Test
    void shouldMap101ErrorMessage() {
        String message = BiliRefreshService.biliErrorMessage("预热检查 cookie/info", -101, "未登录");
        assertThat(message).contains("-101").contains("未登录");
    }

    @Test
    void shouldMap111ErrorMessage() {
        String message = BiliRefreshService.biliErrorMessage("刷新 cookie/refresh", -111, "csrf");
        assertThat(message).contains("-111").contains("bili_jct");
    }

    @Test
    void shouldFallbackToGenericMessage() {
        String message = BiliRefreshService.biliErrorMessage("某步骤", -400, "请求错误");
        assertThat(message).contains("code=-400").contains("请求错误");
    }

    @Test
    void shouldDeriveMd5First8() {
        // md5("123") = 202cb962ac59075b964b07152d234b70，前 8 位为 202cb962
        assertThat(BiliRefreshService.md5First8("123")).isEqualTo("202cb962");
    }

    @Test
    void snippetShouldTruncateLongText() {
        assertThat(BiliRefreshService.snippet("x".repeat(500))).hasSize(200);
        assertThat(BiliRefreshService.snippet("short")).isEqualTo("short");
        assertThat(BiliRefreshService.snippet("")).isEqualTo("<空响应>");
    }
}
