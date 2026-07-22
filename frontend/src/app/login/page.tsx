"use client";

import { useState, FormEvent } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import SocialLoginButtons from "@/components/SocialLoginButtons";

export default function LoginPage() {
  const { login } = useAuth();
  const router = useRouter();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [redirecting, setRedirecting] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const u = await login(email, password);
      // 로그인 성공 → setUser로 전역 헤더가 즉시 로그인 상태로 바뀌고, 곧 라우팅한다.
      // 그 "헤더 flip + 클라 네비 공백"을 전체화면 오버레이로 덮어 로딩을 명확히 보여준다(아래 redirecting 분기).
      setRedirecting(true);
      // returnTo(가드가 실어 보낸 원래 경로)가 있으면 그리로. 내부 절대경로만 허용(오픈 리다이렉트 방지).
      // useSearchParams 대신 핸들러에서 window.location을 읽는다 — 렌더 시 Suspense 바일아웃을 피하려고.
      const returnTo = new URLSearchParams(window.location.search).get("returnTo");
      const safeReturn = returnTo && returnTo.startsWith("/") && !returnTo.startsWith("//") ? returnTo : null;
      // returnTo가 없으면 역할별 랜딩: 관리자=대시보드, 셀러=셀러 콘솔, 일반 사용자=상품 목록
      router.push(
        safeReturn ?? (u.role === "ADMIN" ? "/admin" : u.role === "SELLER" ? "/seller" : "/products"),
      );
    } catch (err) {
      setError((err as Error).message);
      setSubmitting(false); // 실패 시에만 폼 복구(성공 시엔 오버레이가 덮고 곧 언마운트)
    }
  };

  // 로그인 성공 후 이동 중 — 헤더(sticky z-30)까지 덮는 fixed z-50 전체화면 로딩.
  if (redirecting) {
    return (
      <div
        role="status"
        aria-live="polite"
        className="fixed inset-0 z-50 flex flex-col items-center justify-center gap-6 bg-cream"
      >
        <span
          aria-hidden
          className="h-14 w-14 animate-spin rounded-full border-[3px] border-line border-t-clay"
        />
        <p className="font-serif text-2xl tracking-[0.3em] text-ink">ATELIER</p>
        {/* 상태 문구는 스피너로 충분 — 화면엔 안 띄우고 스크린리더용으로만 */}
        <span className="sr-only">이동 중</span>
      </div>
    );
  }

  const inputClass =
    "rounded-xl border border-line bg-paper px-4 py-3 text-ink outline-none transition placeholder:text-muted focus:border-clay";

  return (
    <main className="mx-auto flex min-h-[calc(100vh-65px)] max-w-sm flex-col justify-center px-6 py-12">
      <h1 className="text-center font-serif text-3xl text-ink">로그인</h1>
      <p className="mt-2 text-center text-sm text-muted">ATELIER에 오신 걸 환영합니다.</p>

      <form onSubmit={onSubmit} className="mt-8 flex flex-col gap-3">
        <input
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="이메일"
          required
          className={inputClass}
        />
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder="비밀번호"
          required
          className={inputClass}
        />
        {error && <p className="text-sm text-danger">{error}</p>}
        <button
          type="submit"
          disabled={submitting}
          className="mt-1 rounded-full bg-clay px-4 py-3 font-medium text-cream transition hover:bg-clay-600 disabled:opacity-50"
        >
          {submitting ? "로그인 중…" : "로그인"}
        </button>
      </form>
      <SocialLoginButtons />
      <p className="mt-5 text-center text-sm text-muted">
        계정이 없으신가요?{" "}
        <Link href="/signup" className="text-clay hover:underline">
          회원가입
        </Link>
      </p>
      <p className="mt-2 text-center text-xs text-muted">테스트 계정: buyer@commerce.com / buyerpass1234</p>
    </main>
  );
}
