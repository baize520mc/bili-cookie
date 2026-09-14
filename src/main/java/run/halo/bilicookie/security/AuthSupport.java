package run.halo.bilicookie.security;

import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 鉴权基建：从 Halo 的 ReactiveSecurityContextHolder 中获取当前用户并判断角色。
 */
@Component
public class AuthSupport {

    /**
     * 管理员角色名集合。
     *
     * <p>Halo 的 authority 格式为 {@code ROLE_}<角色名>（见 Halo 源码
     * {@code run.halo.app.security.authorization.AuthorityUtils}：{@code ROLE_PREFIX = "ROLE_"}、
     * {@code SUPER_ROLE_NAME = "super-role"}）。超级管理员角色名为 {@code super-role}。
     * 非超级管理员若被分配了插件的 manage 角色模板（bili-cookie-role-manage），也视为管理员。</p>
     */
    private static final Set<String> ADMIN_ROLE_NAMES = Set.of("super-role", "bili-cookie-role-manage");

    /** 获取当前已认证用户的用户名。 */
    public Mono<String> currentUserName() {
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .map(Authentication::getName);
    }

    /** 获取当前用户全部 authority 字符串（调试用，便于排查权限问题）。 */
    public Mono<List<String>> currentAuthorities() {
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .map(auth -> auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList());
    }

    /** 判断当前用户是否为管理员。 */
    public Mono<Boolean> isAdmin() {
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .map(this::isAdmin);
    }

    private boolean isAdmin(Authentication auth) {
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .map(AuthSupport::stripRolePrefix)
            .anyMatch(ADMIN_ROLE_NAMES::contains);
    }

    private static String stripRolePrefix(String authority) {
        return authority != null && authority.startsWith("ROLE_")
            ? authority.substring("ROLE_".length())
            : authority;
    }
}