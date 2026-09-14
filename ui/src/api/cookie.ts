import { axiosInstance } from "@halo-dev/api-client";

const BASE = "/apis/api.bili-cookie.halo.run/v1alpha1";

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface StatusData {
  enabled: boolean;
  valid: boolean;
  expires_in: number;
  last_refresh: string | null;
  message: string;
  user_enabled: boolean;
  client_enabled: boolean;
  auto_refresh_enabled: boolean;
  bili_username: string | null;
  bili_uid: string | null;
  validated: boolean;
}

export interface CookieData {
  cookie: string;
}

export interface RefreshData {
  new_cookie: string;
}

export interface ValidateData {
  valid: boolean;
  bili_username: string | null;
  bili_uid: string | null;
  message: string;
}

export interface CookieSubmitBody {
  SESSDATA?: string;
  bili_jct?: string;
  refresh_token?: string;
  DedeUserID?: string;
  sid?: string;
}

export interface PreferencesBody {
  userEnabled?: boolean;
  clientEnabled?: boolean;
  autoRefreshEnabled?: boolean;
}

export interface AuditLogItem {
  name: string;
  userId: string;
  operator: string;
  action: string;
  success: boolean;
  message: string;
  createdAt: string;
}

export async function getStatus(): Promise<ApiResponse<StatusData>> {
  const { data } = await axiosInstance.get<ApiResponse<StatusData>>(`${BASE}/status`);
  return data;
}

export async function getCookie(): Promise<ApiResponse<CookieData>> {
  const { data } = await axiosInstance.get<ApiResponse<CookieData>>(`${BASE}/cookie`);
  return data;
}

export async function submitCookie(
  body: CookieSubmitBody
): Promise<ApiResponse<null>> {
  const { data } = await axiosInstance.post<ApiResponse<null>>(`${BASE}/cookie`, body);
  return data;
}

export async function refreshCookie(): Promise<ApiResponse<RefreshData>> {
  const { data } = await axiosInstance.post<ApiResponse<RefreshData>>(
    `${BASE}/cookie/refresh`
  );
  return data;
}

export async function validateCookie(): Promise<ApiResponse<ValidateData>> {
  const { data } = await axiosInstance.post<ApiResponse<ValidateData>>(
    `${BASE}/cookie/validate`
  );
  return data;
}

export async function updatePreferences(
  body: PreferencesBody
): Promise<ApiResponse<StatusData>> {
  const { data } = await axiosInstance.put<ApiResponse<StatusData>>(
    `${BASE}/cookie/preferences`,
    body
  );
  return data;
}

export async function clearAllData(): Promise<ApiResponse<null>> {
  const { data } = await axiosInstance.post<ApiResponse<null>>(`${BASE}/cookie/clear`);
  return data;
}

export async function getLogs(limit = 200): Promise<ApiResponse<AuditLogItem[]>> {
  const { data } = await axiosInstance.get<ApiResponse<AuditLogItem[]>>(
    `${BASE}/logs`,
    { params: { limit } }
  );
  return data;
}

export async function getAdminLogs(
  params: { limit?: number; userId?: string } = {}
): Promise<ApiResponse<AuditLogItem[]>> {
  const { data } = await axiosInstance.get<ApiResponse<AuditLogItem[]>>(
    `${BASE}/admin/logs`,
    { params: { limit: params.limit ?? 200, userId: params.userId || undefined } }
  );
  return data;
}

/** 操作类型 → 中文标签，未知类型原样返回 */
const ACTION_LABELS: Record<string, string> = {
  SAVE: "保存 Cookie",
  REFRESH: "手动刷新",
  AUTO_REFRESH: "自动刷新",
  VALIDATE: "验证 Cookie",
  PREF_UPDATE: "更新设置",
  CLEAR: "清除数据",
  CLIENT_CONNECT: "客户端连接",
  CLIENT_READ: "客户端读取",
  ADMIN_SETTINGS: "修改全局设置",
  ADMIN_SAVE: "管理员保存 Cookie",
  ADMIN_DELETE: "管理员删除 Cookie",
  ADMIN_REFRESH: "管理员刷新 Cookie",
};

export function actionLabel(action: string): string {
  return ACTION_LABELS[action] || action || "未知操作";
}
