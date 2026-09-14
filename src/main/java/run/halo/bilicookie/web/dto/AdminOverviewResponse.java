package run.halo.bilicookie.web.dto;

import java.util.List;

/**
 * GET /admin/status 系统总览 data。
 */
public record AdminOverviewResponse(
    boolean globalEnabled,
    int totalUsers,
    List<AdminUserStatus> usersStatus
) {
}