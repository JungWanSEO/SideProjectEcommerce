"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

/**
 * 로그인 후 되돌아올 경로를 실어 로그인 URL을 만든다. `/login?returnTo=<원래 경로>`.
 *
 * <p>같은 인증 가드가 10여 개 페이지에 복붙돼 있고 전부 그냥 `/login`으로만 보내, 로그인하면 어디로든
 * 랜딩해 사용자가 하던 일(장바구니·주소 입력 등)을 잃었다. returnTo를 실으면 원래 자리로 돌아온다.
 */
export function loginHref(returnTo: string): string {
  // 외부 URL 주입(오픈 리다이렉트) 방지 — 내부 절대경로("/...")만 허용.
  const safe = returnTo.startsWith("/") && !returnTo.startsWith("//") ? returnTo : "/";
  return `/login?returnTo=${encodeURIComponent(safe)}`;
}

/**
 * "로그인 필요" 페이지 가드 훅 — 비로그인이면 현재 경로를 returnTo로 실어 로그인으로 보낸다.
 * 페이지마다 복붙하던 `useEffect(() => { if (!user) router.replace("/login") })`를 한 줄로 대체한다.
 *
 * @returns { user, loading } — 렌더 게이팅에 그대로 쓴다(loading이거나 user 없으면 콘텐츠 대신 스켈레톤/빈 화면).
 */
export function useRequireAuth() {
  const { user, loading } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (loading) return; // 최초 /me 확인 중엔 판단 보류(깜빡임 방지)
    if (!user) {
      router.replace(loginHref(window.location.pathname + window.location.search));
    }
  }, [loading, user, router]);

  return { user, loading };
}
