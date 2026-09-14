package run.halo.bilicookie.config;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 插件设置读取服务。字段默认值与 settings.yaml 保持一致，避免首次安装未保存设置时为空。
 */
@Component
public class SettingService {

    private static final BiliCookieSetting DEFAULT = new BiliCookieSetting(true, 6, 30);

    private final ReactiveSettingFetcher settingFetcher;

    public SettingService(ReactiveSettingFetcher settingFetcher) {
        this.settingFetcher = settingFetcher;
    }

    public Mono<BiliCookieSetting> getSetting() {
        return settingFetcher.fetch(BiliCookieSetting.GROUP, BiliCookieSetting.class)
            .switchIfEmpty(Mono.just(DEFAULT));
    }

    public Mono<Boolean> isEnabled() {
        return getSetting().map(BiliCookieSetting::globalEnabled);
    }

    public Mono<Integer> cookieExpireDays() {
        return getSetting().map(BiliCookieSetting::cookieExpireDays);
    }
}