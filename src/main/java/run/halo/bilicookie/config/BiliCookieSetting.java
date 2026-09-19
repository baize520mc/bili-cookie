package run.halo.bilicookie.config;

/**
 * 插件设置值类，字段名与 settings.yaml 中 formSchema 的 name 一致。
 */
public record BiliCookieSetting(
    boolean globalEnabled,
    double refreshIntervalMinutes,
    int cookieExpireDays
) {
    public static final String GROUP = "basic";
}
