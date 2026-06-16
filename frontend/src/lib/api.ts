import { ApiResponse } from "./types";

// API 베이스 URL. .env.local 의 NEXT_PUBLIC_API_BASE_URL 사용, 없으면 로컬 기본값.
const BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

// 401이 나도 refresh를 시도하지 않을 인증 엔드포인트 (무한루프·무의미한 refresh 방지)
const NO_REFRESH = ["/api/auth/login", "/api/auth/refresh", "/api/auth/logout"];

function rawFetch(path: string, method: string, body?: unknown): Promise<Response> {
  return fetch(`${BASE_URL}${path}`, {
    method,
    credentials: "include", // httpOnly 인증 쿠키 송수신
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

/** access 쿠키 만료 추정 시 1회 refresh 시도. 성공하면 백엔드가 새 access 쿠키를 셋한다. */
async function tryRefresh(): Promise<boolean> {
  try {
    const res = await rawFetch("/api/auth/refresh", "POST");
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * 백엔드 호출 공통 헬퍼.
 * - credentials:"include"로 httpOnly 쿠키 송수신.
 * - 401(access 만료 추정)이면 refresh 1회 후 원요청 재시도 → 만료돼도 끊김 없는 UX.
 * - 공통 응답(ApiResponse<T>)을 풀어 data만 반환. 실패 시 서버 메시지로 Error.
 */
async function request<T>(path: string, method: string, body?: unknown): Promise<T> {
  let res = await rawFetch(path, method, body);

  if (res.status === 401 && !NO_REFRESH.includes(path)) {
    if (await tryRefresh()) {
      res = await rawFetch(path, method, body); // 새 access 쿠키로 1회 재시도
    }
  }

  const parsed = (await res.json().catch(() => null)) as ApiResponse<T> | null;
  if (!res.ok || !parsed?.success) {
    throw new Error(parsed?.message ?? `요청 실패 (HTTP ${res.status})`);
  }
  return parsed.data;
}

export const apiGet = <T>(path: string): Promise<T> => request<T>(path, "GET");

export const apiPost = <T>(path: string, body?: unknown): Promise<T> =>
  request<T>(path, "POST", body);

export const apiPut = <T>(path: string, body?: unknown): Promise<T> =>
  request<T>(path, "PUT", body);

export const apiPatch = <T>(path: string, body?: unknown): Promise<T> =>
  request<T>(path, "PATCH", body);

export const apiDelete = <T>(path: string): Promise<T> => request<T>(path, "DELETE");
