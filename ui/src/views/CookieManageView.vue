<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  Toast,
  VAlert,
  VButton,
  VCard,
  VLoading,
  VModal,
  VPageHeader,
  VStatusDot,
  VSwitch,
  VTabbar,
} from "@halo-dev/components";
import BiliCookieIcon from "../icons/BiliCookieIcon";
import UserLogsPanel from "./UserLogsPanel.vue";
import {
  clearAllData,
  getCookie,
  getStatus,
  refreshCookie,
  submitCookie,
  updatePreferences,
  validateCookie,
  type StatusData,
} from "../api/cookie";

type StatusState = "success" | "warning" | "error" | "secondary" | "default";

/** 页面内横向切换：Cookie 设置 / 操作日志 */
const activeTab = ref<"cookie" | "logs">("cookie");
const tabItems = [
  { id: "cookie", label: "Cookie 设置" },
  { id: "logs", label: "操作日志" },
];
const logsPanelRef = ref<InstanceType<typeof UserLogsPanel> | null>(null);

function onTabChange(id: string | number) {
  if (id === "logs") {
    // 切到日志面板时刷新一次，保证看到最新记录
    logsPanelRef.value?.load();
  }
}

const loading = ref(false);
const saving = ref(false);
const refreshing = ref(false);
const validating = ref(false);
const error = ref("");

const status = ref<StatusData | null>(null);

const sessdata = ref("");
const biliJct = ref("");
const dedeUserID = ref("");
const refreshToken = ref("");
const sid = ref("");

const showCookie = ref(false);
const maskedCookie = ref("");

const uploadVisible = ref(false);
const confirmVisible = ref(false);
const clearVisible = ref(false);
const clearing = ref(false);
const tipVisible = ref(true);
const tipClientVisible = ref(true);

/** 总开关未启用时，功能按钮一律置灰并展示免责声明 */
const userEnabled = computed(() => status.value?.user_enabled === true);
const hasCookie = computed(() => status.value?.valid === true);
/** 缺少 refresh_token 时无法自动刷新，开关禁用并提示 */
const hasRefreshToken = computed(() => status.value?.has_refresh_token === true);

const statusState = computed<StatusState>(() => {
  const s = status.value;
  if (!s || !s.enabled) return "default";
  if (!s.valid) return "error";
  const exp = s.expires_in;
  if (exp != null && exp <= 7) return "warning";
  return "success";
});

const statusText = computed(() => {
  const s = status.value;
  if (!s) return "状态未知";
  if (!s.enabled) return "插件已禁用";
  if (!s.valid) return s.message || "未配置 / 已失效";
  const exp = s.expires_in;
  if (exp != null && exp <= 7) return exp <= 0 ? "已过期" : `即将过期（剩 ${exp} 天）`;
  return "状态正常";
});

const expiresText = computed(() => {
  const s = status.value;
  if (!s || !s.valid || s.expires_in == null) return "—";
  if (s.expires_in <= 0) return "已过期";
  return `${s.expires_in}`;
});

const accountText = computed(() => {
  const s = status.value;
  if (!s?.validated) return "未验证";
  return `${s.bili_username || "未知用户"}（UID ${s.bili_uid || "—"}）`;
});

function formatTime(v: string | null | undefined): string {
  if (!v) return "—";
  const d = new Date(v);
  if (Number.isNaN(d.getTime())) return v;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(
    d.getHours()
  )}:${pad(d.getMinutes())}`;
}

function errMsg(e: unknown, fallback: string): string {
  const anyErr = e as {
    response?: { data?: { message?: string } };
    message?: string;
  };
  return anyErr?.response?.data?.message || anyErr?.message || fallback;
}

async function loadStatus() {
  loading.value = true;
  error.value = "";
  try {
    const res = await getStatus();
    status.value = res.data;
  } catch (e) {
    error.value = errMsg(e, "获取状态失败");
  } finally {
    loading.value = false;
  }
}

/** 切换用户级开关（总开关/客户端连接/自动刷新） */
async function togglePref(patch: {
  userEnabled?: boolean;
  clientEnabled?: boolean;
  autoRefreshEnabled?: boolean;
}) {
  try {
    const res = await updatePreferences(patch);
    status.value = res.data;
    Toast.success("设置已更新");
  } catch (e) {
    Toast.error(errMsg(e, "更新设置失败"));
  }
}

async function save() {
  saving.value = true;
  try {
    const res = await submitCookie({
      SESSDATA: sessdata.value,
      bili_jct: biliJct.value,
      refresh_token: refreshToken.value,
      DedeUserID: dedeUserID.value,
      sid: sid.value,
    });
    Toast.success(res.message || "Cookie 保存成功");
    uploadVisible.value = false;
    await loadStatus();
  } catch (e) {
    Toast.error(errMsg(e, "保存失败"));
  } finally {
    saving.value = false;
  }
}

async function refresh() {
  refreshing.value = true;
  try {
    const res = await refreshCookie();
    if (res.data?.new_cookie) {
      // 刷新成功后同步回填字段（Cookie 已被服务端续期）
      const parts: Record<string, string> = {};
      res.data.new_cookie
        .split(/[;\n]+/)
        .forEach((part) => {
          const i = part.indexOf("=");
          if (i > 0) parts[part.slice(0, i).trim()] = part.slice(i + 1).trim();
        });
      if (parts["SESSDATA"] !== undefined) sessdata.value = parts["SESSDATA"];
      if (parts["bili_jct"] !== undefined) biliJct.value = parts["bili_jct"];
      if (parts["DedeUserID"] !== undefined) dedeUserID.value = parts["DedeUserID"];
      if (parts["refresh_token"] !== undefined) refreshToken.value = parts["refresh_token"];
      if (parts["sid"] !== undefined) sid.value = parts["sid"];
    }
    Toast.success(res.message || "刷新成功");
    await loadStatus();
  } catch (e) {
    Toast.error(errMsg(e, "刷新失败"));
  } finally {
    refreshing.value = false;
  }
}

async function validate() {
  validating.value = true;
  try {
    const res = await validateCookie();
    if (res.data?.valid) {
      Toast.success(
        `验证通过：${res.data.bili_username || "未知用户"}（UID ${res.data.bili_uid || "—"}）`
      );
    } else {
      Toast.warning(res.data?.message || "Cookie 验证未通过");
    }
    await loadStatus();
  } catch (e) {
    Toast.error(errMsg(e, "验证失败"));
  } finally {
    validating.value = false;
  }
}

async function confirmClear() {
  clearing.value = true;
  try {
    await clearAllData();
    Toast.success("已清除所有数据并禁用");
    clearVisible.value = false;
    // 重置本地表单与展示
    sessdata.value = "";
    biliJct.value = "";
    dedeUserID.value = "";
    refreshToken.value = "";
    sid.value = "";
    showCookie.value = false;
    maskedCookie.value = "";
    await loadStatus();
  } catch (e) {
    Toast.error(errMsg(e, "清除失败"));
  } finally {
    clearing.value = false;
  }
}

async function toggleShowCookie() {
  if (showCookie.value) {
    showCookie.value = false;
    return;
  }
  try {
    const res = await getCookie();
    if (res.data?.cookie) {
      // 后端已脱敏（每个值仅首尾 4 位），直接展示
      maskedCookie.value = res.data.cookie;
      showCookie.value = true;
    } else {
      Toast.warning("尚未配置 Cookie");
    }
  } catch (e) {
    Toast.error(errMsg(e, "获取 Cookie 失败"));
  }
}

/** 点击「更新 Cookie」：先弹出确认窗口，展示当前填写的字段 */
function openConfirm() {
  confirmVisible.value = true;
}

/** 确认窗口「确认更新」：关闭确认窗口后执行保存 */
async function confirmSave() {
  confirmVisible.value = false;
  await save();
}

onMounted(loadStatus);
</script>

<template>
  <div>
    <VPageHeader title="B站 Cookie">
      <template #icon>
        <span class="bc-page-icon"><BiliCookieIcon /></span>
      </template>
      <template #actions>
        <VButton type="secondary" :disabled="!userEnabled" @click="uploadVisible = true">
          更新 Cookie
        </VButton>
        <VButton
          :loading="refreshing"
          type="secondary"
          :disabled="!userEnabled || !hasCookie"
          @click="refresh"
        >
          手动刷新
        </VButton>
      </template>
    </VPageHeader>

    <div class="bc-page">
      <!-- 横拉菜单：同页面切换 Cookie 设置 / 操作日志 -->
      <div class="bc-tabs">
        <VTabbar v-model:active-id="activeTab" :items="tabItems" @change="onTabChange" />
      </div>

      <!-- 面板一：Cookie 设置 -->
      <div v-show="activeTab === 'cookie'" class="bc-tab-panel">
      <VCard :body-class="['!p-0']">
        <!-- 状态条：灰底工具栏，与官方 UC 页面一致 -->
        <template #header>
          <div class="bc-toolbar">
            <div class="bc-toolbar__status">
              <VStatusDot :state="statusState" :text="statusText" />
              <span v-if="status?.plugin_version" class="bc-toolbar__version">
                v{{ status.plugin_version }}
              </span>
            </div>
            <span class="bc-toolbar__hint">
              Cookie 加密保存于本站，仅你本人可见
            </span>
          </div>
        </template>

        <div v-if="loading && !status" class="bc-loading">
          <VLoading />
        </div>

        <div v-else class="bc-body">
          <!-- 免责声明：总开关关闭时展示，需用户手动开启 -->
          <VAlert
            v-if="!userEnabled"
            type="error"
            :closable="false"
            title="功能未启用"
            description="本功能默认关闭。启用后将由本站加密保存你的 B 站 Cookie，并可按你的设置自动续期。请勿将 Cookie 提供给任何人，泄露可能导致账号被盗。开启即表示你已知晓并同意以上风险。"
            class="bc-alert"
          />

          <!-- 无法自动刷新：已有 Cookie 但缺少 refresh_token，自动续期不可用 -->
          <VAlert
            v-if="userEnabled && hasCookie && !hasRefreshToken"
            type="error"
            :closable="false"
            title="无法自动刷新"
            description="当前 Cookie 缺少 refresh_token（ac_time_value），自动续期不可用，自动刷新开关已禁用。请点击右上角「更新 Cookie」重新上传完整 Cookie（含 refresh_token）。"
            class="bc-alert"
          />

          <!-- 获取方式提示：自绘提示条，完整显示不截断 -->
          <div v-if="tipVisible" class="bc-tip">
            <span class="bc-tip__icon">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden="true">
                <path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z" />
              </svg>
            </span>
            <div class="bc-tip__body">
              <span class="bc-tip__title">Cookie 获取方式</span>
              <span class="bc-tip__text">
                <span class="bc-tip__method">方法一：可在客户端一键登录后自动获取并上传 Cookie。</span>
                <span class="bc-tip__method">方法二：如您能自己获取 Cookie 信息，可点击此页面右上角「更新 Cookie」按钮手动填写上传。</span>
              </span>
            </div>
            <button
              class="bc-tip__close"
              type="button"
              aria-label="关闭提示"
              @click="tipVisible = false"
            >
              ×
            </button>
          </div>

          <!-- 连接客户端提示 -->
          <div v-if="tipClientVisible" class="bc-tip">
            <span class="bc-tip__icon">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden="true">
                <path d="M13.341 4A6 6 0 0 0 13 6H5v14h14v-8a6 6 0 0 0 2-.341V21a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM19 8a2 2 0 1 0 0-4a2 2 0 0 0 0 4m0 2a4 4 0 1 1 0-8a4 4 0 0 1 0 8" />
              </svg>
            </span>
            <div class="bc-tip__body">
              <span class="bc-tip__title">连接客户端</span>
              <span class="bc-tip__text">
                此页面用于配置你的 B 站 Cookie。如需通过客户端连接，请先前往
                <a
                  class="bc-tip__link"
                  href="/uc/profile?tab=pat"
                  target="_blank"
                  rel="noopener"
                >「我的 - 个人令牌」</a>
                页面生成个人访问令牌，然后在下方开启「接受客户端连接」。
              </span>
            </div>
            <button
              class="bc-tip__close"
              type="button"
              aria-label="关闭提示"
              @click="tipClientVisible = false"
            >
              ×
            </button>
          </div>

          <VAlert
            v-if="error"
            type="error"
            title="出错了"
            :closable="true"
            class="bc-alert"
            @close="error = ''"
          >
            {{ error }}
          </VAlert>

          <!-- 我的 Cookie：标题统领下方状态概览与查看功能 -->
          <section class="bc-section">
            <div class="bc-section__head">
              <h2 class="bc-section__title">我的 Cookie</h2>
              <span class="bc-section__hint">
                出于安全考虑仅显示部分内容，完整内容加密保存在本站
              </span>
            </div>

            <!-- 账号信息 -->
            <div class="bc-account">
              <div class="bc-account__item">
                <span class="bc-account__label">B 站账号</span>
                <span class="bc-account__value">{{ accountText }}</span>
              </div>
              <VButton
                type="secondary"
                size="sm"
                :loading="validating"
                :disabled="!userEnabled || !hasCookie"
                @click="validate"
              >
                验证 Cookie 有效性
              </VButton>
            </div>

            <!-- 状态概览：剩余有效期、上次刷新等 -->
            <div class="bc-status">
              <div class="bc-status__main">
                <div class="bc-status__item">
                  <span class="bc-status__label">剩余有效期</span>
                  <span class="bc-status__value-wrap">
                    <span class="bc-status__value">{{ expiresText }}</span>
                    <span v-if="status?.valid && status.expires_in != null" class="bc-status__unit">天</span>
                  </span>
                </div>
                <div class="bc-status__item">
                  <span class="bc-status__label">上次刷新</span>
                  <span class="bc-status__value bc-status__value--sm">
                    {{ formatTime(status?.last_refresh) }}
                  </span>
                </div>
                <div class="bc-status__item">
                  <span class="bc-status__label">全局开关</span>
                  <span class="bc-status__value bc-status__value--sm">
                    {{ status?.enabled ? "已开启" : "已关闭" }}
                  </span>
                </div>
                <div class="bc-status__item">
                  <span class="bc-status__label">自动续期</span>
                  <span class="bc-status__value bc-status__value--sm">
                    {{ !hasRefreshToken ? "无法自动刷新" : (status?.auto_refresh_enabled ? "已启用" : "已停用") }}
                  </span>
                </div>
              </div>
              <p v-if="status?.message" class="bc-message">{{ status.message }}</p>
            </div>

            <!-- 用户级开关 -->
            <div class="bc-switches">
              <div class="bc-switch-row">
                <div class="bc-switch-row__text">
                  <span class="bc-switch-row__title">插件功能总开关</span>
                  <span class="bc-switch-row__desc">控制 Cookie 的保存、更新、读取与验证等全部功能</span>
                </div>
                <VSwitch
                  :model-value="userEnabled"
                  @update:model-value="(v: boolean) => togglePref({ userEnabled: v })"
                />
              </div>
              <div class="bc-switch-row">
                <div class="bc-switch-row__text">
                  <span class="bc-switch-row__title">接受客户端连接</span>
                  <span class="bc-switch-row__desc">允许专属客户端连接并读取你的 Cookie（不影响本站页面使用）</span>
                </div>
                <VSwitch
                  :model-value="userEnabled && status?.client_enabled === true"
                  :disabled="!userEnabled"
                  @update:model-value="(v: boolean) => togglePref({ clientEnabled: v })"
                />
              </div>
              <div class="bc-switch-row">
                <div class="bc-switch-row__text">
                  <span class="bc-switch-row__title">自动刷新 Cookie</span>
                  <span class="bc-switch-row__desc">
                    {{ hasRefreshToken
                      ? "到达刷新间隔后由本站自动续期，避免 Cookie 过期"
                      : "缺少 refresh_token，无法自动刷新；请重新上传完整 Cookie" }}
                  </span>
                </div>
                <VSwitch
                  :model-value="hasRefreshToken && userEnabled && status?.auto_refresh_enabled === true"
                  :disabled="!userEnabled || !hasRefreshToken"
                  @update:model-value="(v: boolean) => togglePref({ autoRefreshEnabled: v })"
                />
              </div>
            </div>

            <div class="bc-actions">
              <VButton type="default" :disabled="!hasCookie" @click="toggleShowCookie">
                {{ showCookie ? "隐藏 Cookie" : "查看我的 Cookie" }}
              </VButton>
            </div>
            <textarea
              v-if="showCookie"
              readonly
              class="bc-cookie-view"
              :value="maskedCookie"
              rows="6"
            ></textarea>
          </section>

          <!-- 危险操作区 -->
          <section class="bc-danger">
            <div class="bc-danger__text">
              <span class="bc-danger__title">清除所有数据</span>
              <span class="bc-danger__desc">删除本站保存的全部 Cookie 数据并关闭本功能，该操作不可恢复</span>
            </div>
            <VButton type="danger" @click="clearVisible = true">
              清除所有数据并禁用
            </VButton>
          </section>
        </div>
      </VCard>
      </div>

      <!-- 面板二：操作日志 -->
      <div v-show="activeTab === 'logs'" class="bc-tab-panel">
        <UserLogsPanel ref="logsPanelRef" />
      </div>
    </div>

    <!-- 更新 Cookie：二级窗口，按 Halo 官方规范（VModal + FormKit） -->
    <VModal v-model:visible="uploadVisible" title="更新 Cookie" :width="560">
      <div class="bc-upd">
        <VAlert
          type="info"
          :closable="false"
          title="上传注意事项"
          description="请一次性填写全部 5 个字段并保存。为保证自动续期可用，请从浏览器登录态中同时抓取 refresh_token；sid 为可选项。"
        />

        <FormKit id="update-form" type="form" @submit="save">
          <div class="bc-upd__fields">
            <FormKit
              v-model="sessdata"
              name="SESSDATA"
              type="text"
              label="SESSDATA"
              placeholder="登录凭证"
            />
            <FormKit
              v-model="biliJct"
              name="bili_jct"
              type="text"
              label="bili_jct"
              placeholder="CSRF Token"
            />
            <FormKit
              v-model="dedeUserID"
              name="DedeUserID"
              type="text"
              label="DedeUserID"
              placeholder="用户 UID"
            />
            <FormKit
              v-model="refreshToken"
              name="refresh_token"
              type="text"
              label="refresh_token"
              placeholder="刷新令牌（用于自动续期）"
            />
            <FormKit
              v-model="sid"
              name="sid"
              class="bc-upd__full"
              type="text"
              label="sid"
              placeholder="可选"
            />
          </div>
        </FormKit>
      </div>
      <template #footer>
        <div class="bc-upd__actions">
          <VButton type="default" @click="uploadVisible = false">取消</VButton>
          <VButton
            type="primary"
            @click="openConfirm"
          >
            更新 Cookie
          </VButton>
        </div>
      </template>
    </VModal>

    <!-- 确认更新：展示当前填写的字段，确认后提交 -->
    <VModal v-model:visible="confirmVisible" title="确认更新 Cookie" :width="480">
      <div class="bc-confirm">
        <p class="bc-confirm__question">是否要手动上传并更新你的 Cookie？</p>
        <dl class="bc-confirm__list">
          <div class="bc-confirm__row">
            <dt>SESSDATA</dt>
            <dd :class="{ 'bc-confirm__empty': !sessdata }">{{ sessdata || "（未填写）" }}</dd>
          </div>
          <div class="bc-confirm__row">
            <dt>bili_jct</dt>
            <dd :class="{ 'bc-confirm__empty': !biliJct }">{{ biliJct || "（未填写）" }}</dd>
          </div>
          <div class="bc-confirm__row">
            <dt>DedeUserID</dt>
            <dd :class="{ 'bc-confirm__empty': !dedeUserID }">{{ dedeUserID || "（未填写）" }}</dd>
          </div>
          <div class="bc-confirm__row">
            <dt>refresh_token</dt>
            <dd :class="{ 'bc-confirm__empty': !refreshToken }">{{ refreshToken || "（未填写）" }}</dd>
          </div>
          <div class="bc-confirm__row">
            <dt>sid</dt>
            <dd :class="{ 'bc-confirm__empty': !sid }">{{ sid || "（未填写）" }}</dd>
          </div>
        </dl>
      </div>
      <template #footer>
        <div class="bc-upd__actions">
          <VButton type="default" @click="confirmVisible = false">取消</VButton>
          <VButton type="primary" :loading="saving" @click="confirmSave">
            确认更新
          </VButton>
        </div>
      </template>
    </VModal>

    <!-- 清除所有数据：危险操作确认弹窗 -->
    <VModal v-model:visible="clearVisible" title="清除所有数据" :width="480">
      <div class="bc-clear">
        <VAlert
          type="error"
          :closable="false"
          title="危险操作"
          description="此操作将删除本站保存的全部 B 站 Cookie 数据，并关闭本功能。清除后如需使用需重新上传 Cookie，且不可恢复。"
        />
        <p class="bc-clear__hint">确认要继续吗？</p>
      </div>
      <template #footer>
        <div class="bc-upd__actions">
          <VButton type="default" @click="clearVisible = false">取消</VButton>
          <VButton type="danger" :loading="clearing" @click="confirmClear">
            确认清除并禁用
          </VButton>
        </div>
      </template>
    </VModal>
  </div>
</template>

<style scoped>
/* 设计令牌：灰阶分层对齐 Halo 官方组件体系 */
.bc-page {
  --bc-text-primary: #1f2937;
  --bc-text-secondary: #4b5563;
  --bc-text-muted: #6b7280;
  --bc-text-faint: #9ca3af;
  --bc-border: #e5e7eb;
  --bc-border-light: #f3f4f6;
  --bc-bg-subtle: #f9fafb;
  --bc-radius-card: 10px;
  --bc-font-caption: 12px;
  --bc-gap-sm: 8px;
  --bc-gap-md: 12px;
  --bc-gap-lg: 16px;

  margin: var(--bc-gap-lg);
}
@media (max-width: 640px) {
  .bc-page {
    margin: 0;
  }
}
.bc-page-icon {
  font-size: 18px;
  color: var(--bc-text-secondary);
  display: inline-flex;
}

/* 横拉菜单：与页面内容间距 */
.bc-tabs {
  margin-bottom: var(--bc-gap-lg);
}
.bc-tab-panel {
  min-width: 0;
}

/* 工具栏：灰底信息条 */
.bc-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--bc-gap-md);
  width: 100%;
  background: var(--bc-bg-subtle);
  padding: 12px 16px;
  flex-wrap: wrap;
}
.bc-toolbar__status {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bc-toolbar__version {
  font-size: var(--bc-font-caption);
  color: var(--bc-text-faint);
}
.bc-toolbar__hint {
  font-size: var(--bc-font-caption);
  color: var(--bc-text-faint);
}

.bc-loading {
  display: flex;
  justify-content: center;
  padding: 64px 0;
}

.bc-body {
  padding: var(--bc-gap-lg);
}

.bc-alert {
  margin-bottom: var(--bc-gap-lg);
}

/* 提示条：自绘，文字完整换行显示不截断 */
.bc-tip {
  display: flex;
  align-items: flex-start;
  gap: var(--bc-gap-sm);
  background: #e6f4ff;
  border: 1px solid #91caff;
  border-left: 4px solid #1677ff;
  border-radius: var(--bc-radius-card);
  padding: var(--bc-gap-md);
  margin-bottom: var(--bc-gap-lg);
}
.bc-tip__icon {
  color: #1677ff;
  flex-shrink: 0;
  margin-top: 1px;
}
.bc-tip__body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}
.bc-tip__title {
  font-size: 13px;
  font-weight: 600;
  color: #0958d9;
}
.bc-tip__text {
  font-size: var(--bc-font-caption);
  line-height: 1.6;
  color: #3f5a8c;
  white-space: normal;
  word-break: break-word;
}
.bc-tip__close {
  flex-shrink: 0;
  border: none;
  background: transparent;
  color: #8ab4ff;
  font-size: 16px;
  line-height: 1;
  padding: 0 2px;
  cursor: pointer;
  transition: color var(--bc-transition, 150ms ease);
}
.bc-tip__close:hover {
  color: #1677ff;
}
/* 提示条内链接 */
.bc-tip__link {
  color: #1677ff;
  text-decoration: none;
  font-weight: 500;
}
.bc-tip__link:hover {
  text-decoration: underline;
}
/* 提示条内方法列表：每行一条 */
.bc-tip__method {
  display: block;
}

/* 账号信息条 */
.bc-account {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--bc-gap-md);
  flex-wrap: wrap;
  background: var(--bc-bg-subtle);
  border: 1px solid var(--bc-border-light);
  border-radius: var(--bc-radius-card);
  padding: 12px 16px;
  margin-bottom: var(--bc-gap-lg);
}
.bc-account__item {
  display: flex;
  align-items: baseline;
  gap: var(--bc-gap-sm);
  min-width: 0;
}
.bc-account__label {
  font-size: var(--bc-font-caption);
  color: var(--bc-text-muted);
}
.bc-account__value {
  font-size: 14px;
  font-weight: 600;
  color: var(--bc-text-primary);
  overflow-wrap: anywhere;
}

/* 状态概览：顶部通栏，横向铺满 */
.bc-status {
  display: flex;
  flex-direction: column;
  gap: var(--bc-gap-md);
}
.bc-status__main {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--bc-gap-lg);
}
@media (min-width: 768px) {
  .bc-status__main {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}
.bc-status__item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.bc-status__label {
  font-size: var(--bc-font-caption);
  color: var(--bc-text-muted);
}
.bc-status__value-wrap {
  display: flex;
  align-items: baseline;
  gap: 4px;
}
.bc-status__value {
  font-size: 24px;
  font-weight: 700;
  line-height: 1.2;
  color: var(--bc-text-primary);
}
.bc-status__value--sm {
  font-size: 14px;
  font-weight: 600;
}
.bc-status__unit {
  font-size: var(--bc-font-caption);
  color: var(--bc-text-muted);
}
.bc-message {
  margin: 0;
  font-size: var(--bc-font-caption);
  color: var(--bc-text-muted);
}

/* 用户级开关区 */
.bc-switches {
  display: flex;
  flex-direction: column;
  gap: 0;
  border: 1px solid var(--bc-border);
  border-radius: var(--bc-radius-card);
  overflow: hidden;
  margin-top: var(--bc-gap-lg);
}
.bc-switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--bc-gap-lg);
  padding: 12px 16px;
}
.bc-switch-row + .bc-switch-row {
  border-top: 1px solid var(--bc-border-light);
}
.bc-switch-row__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.bc-switch-row__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--bc-text-primary);
}
.bc-switch-row__desc {
  font-size: var(--bc-font-caption);
  color: var(--bc-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 危险操作区 */
.bc-danger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--bc-gap-lg);
  flex-wrap: wrap;
  margin-top: var(--bc-gap-lg);
  border: 1px solid #fecaca;
  border-radius: var(--bc-radius-card);
  background: #fef2f2;
  padding: 12px 16px;
}
.bc-danger__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.bc-danger__title {
  font-size: 13px;
  font-weight: 600;
  color: #b91c1c;
}
.bc-danger__desc {
  font-size: var(--bc-font-caption);
  color: #9f1239;
}

.bc-section__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--bc-gap-md);
  flex-wrap: wrap;
  margin-bottom: var(--bc-gap-lg);
}
.bc-section__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--bc-text-primary);
}
.bc-section__hint {
  font-size: var(--bc-font-caption);
  color: var(--bc-text-muted);
}

.bc-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--bc-gap-md);
  margin-top: var(--bc-gap-lg);
}

.bc-cookie-view {
  margin-top: var(--bc-gap-md);
  width: 100%;
  resize: vertical;
  border: 1px solid var(--bc-border);
  border-radius: var(--bc-radius-card);
  background: var(--bc-bg-subtle);
  padding: var(--bc-gap-md);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: var(--bc-font-caption);
  line-height: 1.6;
  color: var(--bc-text-primary);
  outline: none;
}
</style>

<!-- 弹窗样式：VModal 通过 teleport 渲染，scoped 选择器无法稳定命中，故使用全局块，类名唯一前缀 bc-upd- -->
<style>
.bc-upd {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.bc-upd .alert {
  margin-bottom: 0;
}
.bc-upd__fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  align-items: start;
}
.bc-upd .formkit-outer {
  min-width: 0;
  margin-bottom: 0;
  /* FormKit 自带 py-4/first:pt-0 在 grid 两列下会导致第二列垂直错位，全部归零，间距交给 grid gap */
  padding: 0 !important;
}
.bc-upd__full {
  grid-column: 1 / -1;
}
.bc-upd__actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
/* 确认更新弹窗 */
.bc-confirm {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.bc-confirm__question {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  color: #1f2937;
}
.bc-confirm__list {
  display: flex;
  flex-direction: column;
  gap: 0;
  margin: 0;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  overflow: hidden;
}
.bc-confirm__row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 8px 12px;
}
.bc-confirm__row + .bc-confirm__row {
  border-top: 1px solid #f3f4f6;
}
.bc-confirm__row dt {
  flex: 0 0 120px;
  margin: 0;
  font-size: 13px;
  font-weight: 500;
  color: #6b7280;
  line-height: 1.6;
  overflow-wrap: break-word;
}
.bc-confirm__row dd {
  flex: 1;
  min-width: 0;
  margin: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  color: #111827;
  line-height: 1.6;
  overflow-wrap: break-word;
  word-break: break-all;
}
.bc-confirm__row dd.bc-confirm__empty {
  color: #9ca3af;
}
/* 清除数据弹窗 */
.bc-clear {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.bc-clear .alert {
  margin-bottom: 0;
}
.bc-clear__hint {
  margin: 0;
  font-size: 14px;
  color: #1f2937;
}
</style>
