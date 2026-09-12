import { ref } from "vue";

/**
 * 控制台 API 封装：与 Halo Console 前端一致，从 localStorage 读取访问令牌。
 */
export function useConsoleApi() {
  async function apiGet(path: string, params: Record<string, string | number | undefined> = {}) {
    const query = Object.entries(params)
      .filter(([, value]) => value !== undefined && value !== null && value !== "")
      .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
      .join("&");
    return request("GET", `/apis/console.api.uni.uhalo.pro/v1alpha1${path}${query ? `?${query}` : ""}`);
  }

  async function apiPost(path: string, body: Record<string, unknown> = {}) {
    return request("POST", `/apis/console.api.uni.uhalo.pro/v1alpha1${path}`, body);
  }

  async function apiPut(path: string, body: Record<string, unknown> = {}) {
    return request("PUT", `/apis/console.api.uni.uhalo.pro/v1alpha1${path}`, body);
  }

  async function apiDelete(path: string) {
    return request("DELETE", `/apis/console.api.uni.uhalo.pro/v1alpha1${path}`);
  }

  async function request(method: string, url: string, body?: Record<string, unknown>) {
    let token = "";
    try {
      token = localStorage.getItem("authenticated-token") || "";
    } catch (e) {
      // ignore
    }
    const res = await fetch(url, {
      method,
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(body ? { "Content-Type": "application/json" } : {}),
      },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
    if (res.status === 401 || res.status === 403) {
      throw new Error("鉴权失败：当前登录用户无该数据权限");
    }
    if (!res.ok) {
      let detail = await res.text().catch(() => "");
      try {
        detail = JSON.parse(detail)?.error || detail;
      } catch (e) {
        // keep raw
      }
      throw new Error(detail || `请求失败：HTTP ${res.status}`);
    }
    return res.json();
  }

  return { apiGet, apiPost, apiPut, apiDelete };
}

export function useTableState<T>() {
  const keyword = ref("");
  const status = ref("");
  const page = ref(0);
  const size = ref(20);
  const items = ref<T[]>([]);
  const summary = ref<any[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const error = ref("");

  return { keyword, status, page, size, items, summary, total, loading, error };
}

export function formatTime(value: string | null | undefined): string {
  if (!value) return "-";
  const date = new Date(value);
  if (isNaN(date.getTime())) return String(value);
  const pad = (n: number) => (n < 10 ? "0" : "") + n;
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
