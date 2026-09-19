package run.halo.bilicookie.service;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.bilicookie.config.SettingService;
import run.halo.bilicookie.extension.BiliCookie;

/**
 * Cookie 定时刷新任务。
 *
 * <p>每 60 秒扫描一次：全局开关开启 → 遍历所有已配置 refresh_token 的用户 →
 * 距离上次保存/刷新超过配置间隔则执行刷新；单用户失败仅记录日志并隔离，不影响其他用户。</p>
 *
 * <p>不使用 {@code @Scheduled}：Halo 插件的独立 context 不保证 Spring 调度基础设施可用。
 * 启动/停止由 {@link BiliCookiePlugin} 的插件生命周期方法显式调用（不依赖
 * {@code @PostConstruct}），调度由 Reactor 自带调度器（{@link Flux#interval}）驱动，
 * 行为可控且必然生效。</p>
 */
@Component
public class BiliCookieRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(BiliCookieRefreshScheduler.class);

    /** 扫描周期：60 秒。 */
    private static final Duration SCAN_INTERVAL = Duration.ofSeconds(60);

    /** 同一轮并发刷新的用户数上限，避免瞬时冲击 B 站接口。 */
    private static final int MAX_CONCURRENCY = 4;

    private final ReactiveExtensionClient client;
    private final SettingService settingService;
    private final BiliCookieService cookieService;
    private final BiliRefreshService refreshService;

    private Disposable disposable;

    public BiliCookieRefreshScheduler(ReactiveExtensionClient client,
        SettingService settingService, BiliCookieService cookieService,
        BiliRefreshService refreshService) {
        this.client = client;
        this.settingService = settingService;
        this.cookieService = cookieService;
        this.refreshService = refreshService;
    }

    /** 由插件 start() 调用：首次立即执行一轮，之后每 60 秒扫描一次。 */
    public void start() {
        if (disposable != null && !disposable.isDisposed()) {
            return;
        }
        disposable = Flux.interval(Duration.ZERO, SCAN_INTERVAL)
            .onBackpressureDrop()
            .flatMap(tick -> scanAndRefresh()
                .onErrorResume(error -> {
                    // 兜底：扫描本身异常（如扩展客户端瞬时故障）时仅记录，不影响下一次调度。
                    log.error("定时刷新任务执行异常", error);
                    return Mono.empty();
                }))
            .subscribe();
        log.info("[bili-cookie] 定时刷新任务已启动，扫描周期=60s");
    }

    /** 由插件 stop() 调用：停止调度并释放。 */
    public void stop() {
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
            log.info("[bili-cookie] 定时刷新任务已停止");
        }
    }

    private Mono<Void> scanAndRefresh() {
        return settingService.isEnabled()
            .filter(Boolean::booleanValue)
            .flatMapMany(enabled -> cleanOrphans()
                .thenMany(client
                    .listAll(BiliCookie.class, ListOptions.builder().build(), Sort.unsorted())))
            .flatMap(this::maybeRefreshUser, MAX_CONCURRENCY)
            .then();
    }

    /**
     * 清理孤儿 Cookie：Halo 2.26 不发布用户删除事件，改为每次扫描前核对
     * 对应 User 是否仍存在，不存在则删除其 Cookie 并记录审计，避免脏数据残留。
     */
    private Mono<Void> cleanOrphans() {
        return client.listAll(BiliCookie.class, ListOptions.builder().build(), Sort.unsorted())
            .flatMap(this::cleanIfOrphan, MAX_CONCURRENCY)
            .then();
    }

    private Mono<Void> cleanIfOrphan(BiliCookie cookie) {
        String userId = cookie.getSpec().getUserId();
        return client.fetch(User.class, userId)
            .hasElement()
            .flatMap(exists -> exists ? Mono.empty() : deleteOrphan(userId))
            .onErrorResume(error -> {
                log.warn("检查用户 {} 是否存在时失败: {}", userId, error.getMessage());
                return Mono.empty();
            });
    }

    private Mono<Void> deleteOrphan(String userId) {
        log.info("用户 {} 不存在，清理其孤儿 Cookie", userId);
        return cookieService.delete(userId, "system", "USER_DELETED")
            .then();
    }

    private Mono<Void> maybeRefreshUser(BiliCookie cookie) {
        String userId = cookie.getSpec().getUserId();
        return cookieService.isAutoRefreshEnabled(userId)
            .filter(Boolean::booleanValue)
            .flatMap(enabled -> cookieService.getPlainCookie(userId))
            .filter(plain -> !isBlank(plain.refreshToken()))
            .flatMap(plain -> settingService.getSetting()
                .flatMap(setting -> {
                    if (shouldRefresh(plain.savedAt(), setting.refreshIntervalMinutes())) {
                        log.info("定时刷新用户 Cookie: {}", userId);
                        return refreshService.refresh(userId, userId, "SCHEDULED_REFRESH")
                            .then();
                    }
                    return Mono.empty();
                }))
            .onErrorResume(error -> {
                // refresh() 内部已写入 last_refresh_error，此处仅记录日志并隔离失败用户。
                log.warn("定时刷新用户失败 userId={}: {}", userId, error.getMessage());
                return Mono.empty();
            })
            .then();
    }

    /** 距上次保存/刷新达到间隔分钟数才刷新；从未刷新过则立即刷新。 */
    private boolean shouldRefresh(String savedAt, double intervalMinutes) {
        if (isBlank(savedAt)) {
            return true;
        }
        try {
            Instant saved = Instant.parse(savedAt);
            return Duration.between(saved, Instant.now()).toMinutes() >= intervalMinutes;
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
