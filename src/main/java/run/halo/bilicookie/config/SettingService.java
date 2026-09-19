package run.halo.bilicookie.config;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 插件设置读取服务。字段默认值与 settings.yaml 保持一致，避免首次安装未保存设置时为空。
 */
@Component
public class SettingService {

    /** 默认值：全局开关开、刷新间隔 360 分钟（6 小时）、有效期 30 天。 */
    private static final BiliCookieSetting DEFAULT = new BiliCookieSetting(true, 360, 30);

    private final ReactiveSettingFetcher settingFetcher;

    public SettingService(ReactiveSettingFetcher settingFetcher) {
        this.settingFetcher = settingFetcher;
    }

    public Mono<BiliCookieSetting> getSetting() {
        return settingFetcher.fetch(BiliCookieSetting.GROUP, BiliCookieSetting.class)
            // 兼容 v1.0.0 旧字段名 refreshIntervalHours：旧配置读不到新字段时
            // 分钟数为 0，回退默认间隔，避免出现"每分钟刷新"。
            .map(setting -> setting.refreshIntervalMinutes() > 0
                ? setting
                : new BiliCookieSetting(setting.globalEnabled(),
                    DEFAULT.refreshIntervalMinutes(), setting.cookieExpireDays()))
            .switchIfEmpty(Mono.just(DEFAULT));
    }

    public Mono<Boolean> isEnabled() {
        return getSetting().map(BiliCookieSetting::globalEnabled);
    }

    public Mono<Integer> cookieExpireDays() {
        return getSetting().map(BiliCookieSetting::cookieExpireDays);
    }
}
