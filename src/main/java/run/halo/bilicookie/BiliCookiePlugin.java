package run.halo.bilicookie;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.bilicookie.extension.BiliCookie;
import run.halo.bilicookie.extension.BiliCookieAuditLog;

/**
 * 插件入口。
 */
@Component
@EnableScheduling
public class BiliCookiePlugin extends BasePlugin {

    private static final Logger log = LoggerFactory.getLogger(BiliCookiePlugin.class);

    private final SchemeManager schemeManager;

    public BiliCookiePlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        // 注册自定义模型，并为 spec.userId 建立唯一索引（一个用户一份 Cookie）。
        schemeManager.register(BiliCookie.class, indexSpecs ->
            indexSpecs.add(IndexSpecs.<BiliCookie, String>single("spec.userId", String.class)
                .indexFunc(cookie -> cookie.getSpec().getUserId()))
        );
        // 注册审计日志模型；按官方文档「查询或排序的字段必须注册为索引」：
        // spec.userId 供按用户过滤、spec.createdAt 供时间倒序排序。
        schemeManager.register(BiliCookieAuditLog.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<BiliCookieAuditLog, String>single("spec.userId", String.class)
                .indexFunc(logEntry -> logEntry.getSpec().getUserId()));
            indexSpecs.add(IndexSpecs.<BiliCookieAuditLog, String>single("spec.createdAt", String.class)
                .indexFunc(logEntry -> logEntry.getSpec().getCreatedAt()));
        });
        log.info("[bili-cookie] 插件启动完成：已注册模型 BiliCookie(spec.userId 索引)、"
            + "BiliCookieAuditLog(spec.userId、spec.createdAt 索引)。若 Halo 日志无此行，说明部署的 JAR 为旧版本。");
    }

    @Override
    public void stop() {
        schemeManager.unregister(Scheme.buildFromType(BiliCookie.class));
        schemeManager.unregister(Scheme.buildFromType(BiliCookieAuditLog.class));
    }
}