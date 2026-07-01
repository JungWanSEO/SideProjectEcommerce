"use client";

import { createContext, useContext, useEffect, useState, ReactNode } from "react";
import { apiGet, apiPost } from "./api";

/** 로그인 사용자 (백엔드 MemberResponse) */
export interface User {
  id: number;
  email: string;
  nickname: string;
  role: "USER" | "ADMIN" | "SELLER";
  sellerId: number | null; // SELLER면 운영하는 셀러 ID (그 외 null)
  createdAt: string;
}

interface AuthContextType {
  user: User | null;
  loading: boolean; // 최초 /me 확인 중 여부
  login: (email: string, password: string) => Promise<User>; // 로그인한 유저 반환(역할 기반 분기용)
  logout: () => Promise<void>;
  refreshUser: () => Promise<void>; // /me 재조회로 전역 유저 갱신(예: 프로필 수정 후 헤더 닉네임 반영)
}

const AuthContext = createContext<AuthContextType | null>(null);

/**
 * 인증 상태 전역 관리.
 * 토큰은 httpOnly 쿠키라 JS가 못 읽으므로, "로그인 여부/누구인지"는 GET /api/auth/me 로 확인한다.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  // 최초 로드 시 쿠키로 현재 사용자 확인 (없으면 비로그인)
  useEffect(() => {
    apiGet<User>("/api/auth/me")
      .then(setUser)
      .catch(() => setUser(null)) // 401/403 등 → 비로그인
      .finally(() => setLoading(false));
  }, []);

  const login = async (email: string, password: string) => {
    // 로그인 성공 시 백엔드가 쿠키를 굽고 body로 유저 정보를 준다
    const u = await apiPost<User>("/api/auth/login", { email, password });
    setUser(u);
    return u; // 호출부가 역할(ADMIN/USER)에 따라 이동을 분기할 수 있도록 반환
  };

  const logout = async () => {
    await apiPost<void>("/api/auth/logout");
    setUser(null);
  };

  // 프로필 수정 등으로 서버 유저 정보가 바뀐 뒤 전역 상태를 최신화(쿠키의 access로 /me 재조회)
  const refreshUser = async () => {
    try {
      setUser(await apiGet<User>("/api/auth/me"));
    } catch {
      setUser(null);
    }
  };

  return (
    <AuthContext.Provider value={{ user, loading, login, logout, refreshUser }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth는 AuthProvider 안에서만 사용할 수 있습니다.");
  return ctx;
}
