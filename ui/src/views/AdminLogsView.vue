<script setup lang="ts">
import { onMounted, ref } from "vue";
import {
  VAlert,
  VButton,
  VCard,
  VEmpty,
  VLoading,
  VPageHeader,
  VStatusDot,
} from "@halo-dev/components";
import BiliCookieIcon from "../icons/BiliCookieIcon";
import { actionLabel, getAdminLogs, type AuditLogItem } from "../api/cookie";

const loading = ref(false);
const error = ref("");
const logs = ref<AuditLogItem[]>([]);
const userIdFilter = ref("");

type DotState = "success" | "error" | "secondary";

function dotState(item: AuditLogItem): DotState {
  return item.success ? "success" : "error";
}

function formatTime(v: string | null | undefined): string {
  if (!v) return "—";
  const d = new Date(v);
  if (Number.isNaN(d.getTime())) return v;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(
    d.getHours()
  )}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function errMsg(e: unknown, fallback: string): string {
  const anyErr = e as {
    response?: { data?: { message?: string } };
    message?: string;
  };
  return anyErr?.response?.data?.message || anyErr?.message || fallback;
}

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const res = await getAdminLogs({
      limit: 200,
      userId: userIdFilter.value.trim() || undefined,
    });
    logs.value = res.data || [];
  } catch (e) {
    error.value = errMsg(e, "获取日志失败");
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div>
    <VPageHeader title="Cookie 管理日志">
      <template #icon>
        <span class="bc-page-icon"><BiliCookieIcon /></span>
      </template>
      <template #actions>
        <VButton type="secondary" :loading="loading" @click="load">刷新</VButton>
      </template>
    </VPageHeader>

    <div class="bc-page">
      <VCard :body-class="['!p-0']">
        <div class="bc-toolbar">
          <div class="bc-toolbar__title">
            <span>全部操作日志</span>
            <span class="bc-toolbar__hint">包含用户操作（保存/刷新/验证/客户端连接）与管理员操作（设置/代管/刷新任务）</span>
          </div>
          <div class="bc-toolbar__filter">
            <input
              v-model="userIdFilter"
              type="text"
              placeholder="按用户 ID 筛选（留空为全部）"
              class="bc-toolbar__input"
              @keyup.enter="load"
            />
            <VButton type="secondary" size="sm" @click="load">查询</VButton>
          </div>
        </div>

        <div v-if="loading && !logs.length" class="bc-loading">
          <VLoading />
        </div>

        <VAlert
          v-else-if="error"
          type="error"
          title="出错了"
          :closable="true"
          class="bc-alert"
          @close="error = ''"
        >
          {{ error }}
        </VAlert>

        <VEmpty
          v-else-if="!logs.length"
          title="暂无日志"
          message="没有符合条件的操作记录"
        />

        <div v-else class="bc-table">
          <table>
            <thead>
              <tr>
                <th class="bc-col-time">时间</th>
                <th class="bc-col-user">用户</th>
                <th>操作</th>
                <th class="bc-col-result">结果</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in logs" :key="item.name">
                <td class="bc-col-time">{{ formatTime(item.createdAt) }}</td>
                <td class="bc-col-user">{{ item.userId || "—" }}</td>
                <td>{{ actionLabel(item.action) }}</td>
                <td class="bc-col-result">
                  <VStatusDot :state="dotState(item)" :text="item.success ? '成功' : '失败'" />
                </td>
                <td class="bc-col-message">{{ item.message || "—" }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </VCard>
    </div>
  </div>
</template>

<style scoped>
.bc-page {
  --bc-text-primary: #1f2937;
  --bc-text-muted: #6b7280;
  --bc-text-faint: #9ca3af;
  --bc-border: #e5e7eb;
  --bc-border-light: #f3f4f6;
  --bc-bg-subtle: #f9fafb;
  --bc-font-caption: 12px;

  margin: 16px;
}
.bc-page-icon {
  font-size: 18px;
  color: var(--bc-text-muted);
  display: inline-flex;
}
.bc-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  width: 100%;
  background: var(--bc-bg-subtle);
  padding: 12px 16px;
}
.bc-toolbar__title {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13px;
  font-weight: 600;
  color: var(--bc-text-primary);
}
.bc-toolbar__hint {
  font-size: var(--bc-font-caption);
  font-weight: 400;
  color: var(--bc-text-faint);
}
.bc-toolbar__filter {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bc-toolbar__input {
  width: 220px;
  height: 32px;
  padding: 0 10px;
  font-size: 13px;
  color: var(--bc-text-primary);
  background: #fff;
  border: 1px solid var(--bc-border);
  border-radius: 4px;
  outline: none;
}
.bc-toolbar__input::placeholder {
  color: var(--bc-text-faint);
}
.bc-toolbar__input:focus {
  border-color: var(--bc-text-muted);
}
.bc-loading {
  display: flex;
  justify-content: center;
  padding: 64px 0;
}
.bc-alert {
  margin: 16px;
}
.bc-table {
  overflow-x: auto;
}
.bc-table table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.bc-table th {
  text-align: left;
  font-size: var(--bc-font-caption);
  font-weight: 600;
  color: var(--bc-text-muted);
  background: var(--bc-bg-subtle);
  padding: 8px 16px;
  border-bottom: 1px solid var(--bc-border);
  white-space: nowrap;
}
.bc-table td {
  padding: 10px 16px;
  border-bottom: 1px solid var(--bc-border-light);
  color: var(--bc-text-primary);
  vertical-align: middle;
}
.bc-table tbody tr:last-child td {
  border-bottom: none;
}
.bc-table tbody tr:hover {
  background: var(--bc-bg-subtle);
}
.bc-col-time {
  white-space: nowrap;
  color: var(--bc-text-muted);
  font-variant-numeric: tabular-nums;
}
.bc-col-user {
  white-space: nowrap;
  color: var(--bc-text-muted);
}
.bc-col-result {
  white-space: nowrap;
}
.bc-col-message {
  color: var(--bc-text-muted);
  word-break: break-all;
}
</style>
