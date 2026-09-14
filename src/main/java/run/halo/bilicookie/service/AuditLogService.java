package run.halo.bilicookie.service;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.index.query.Queries;
import run.halo.bilicookie.extension.BiliCookieAuditLog;
import run.halo.bilicookie.extension.BiliCookieAuditLog.BiliCookieAuditLogSpec;

/**
 * 操作审计：追加式记录关键操作，写入失败仅记日志，绝不影响主业务链路。
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final ReactiveExtensionClient client;

    public AuditLogService(ReactiveExtensionClient client) {
        this.client = client;
    }

    /** 记录一条审计日志（幂等场景不重复记录由调用方控制）。 */
    public Mono<Void> record(String userId, String operator, String action,
        boolean success, String message) {
        BiliCookieAuditLog auditLog = new BiliCookieAuditLog();
        auditLog.setMetadata(new Metadata());
        auditLog.getMetadata().setName("audit-" + UUID.randomUUID());
        BiliCookieAuditLogSpec spec = auditLog.getSpec();
        spec.setUserId(userId);
        spec.setOperator(operator);
        spec.setAction(action);
        spec.setSuccess(success);
        spec.setMessage(message);
        spec.setCreatedAt(Instant.now().toString());
        return client.create(auditLog)
            .then()
            .onErrorResume(error -> {
                log.warn("写入审计日志失败 userId={} action={}: {}", userId, action,
                    error.getMessage());
                return Mono.empty();
            });
    }

    /**
     * 查询最近 N 条日志，可按 userId 过滤（管理员审计用）。
     *
     * <p>依据官方文档「查询或排序的字段必须注册为索引」，
     * spec.userId / spec.createdAt 已在插件启动时注册索引，此处可直接使用 fieldQuery 与 Sort。</p>
     */
    public Flux<BiliCookieAuditLog> list(String userId, int limit) {
        ListOptions listOptions = ListOptions.builder().build();
        if (userId != null && !userId.isBlank()) {
            listOptions = ListOptions.builder()
                .fieldQuery(Queries.equal("spec.userId", userId))
                .build();
        }
        log.info("[bili-cookie] 开始查询操作日志: userId={}, limit={}", userId, limit);
        return client.listAll(BiliCookieAuditLog.class, listOptions,
                Sort.by(Sort.Order.desc("spec.createdAt")))
            .take(limit)
            .doOnError(error -> log.error("[bili-cookie] 查询操作日志异常（常见原因："
                + "索引未注册或部署的 JAR 为旧版本）userId={}, limit={}", userId, limit, error));
    }
}
