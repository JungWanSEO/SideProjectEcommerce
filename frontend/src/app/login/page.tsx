"use client";

import { useState, FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

export default function LoginPage() {
  const { login } = useAuth();
  const router = useRouter();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const u = await login(email, password);
      // 역할별 랜딩: 관리자=대시보드, 셀러=셀러 콘솔, 일반 사용자=상품 목록
      router.push(
        u.role === "ADMIN" ? "/admin" : u.role === "SELLER" ? "/seller" : "/products",
      );
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

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
      <p className="mt-5 text-center text-xs text-muted">테스트 계정: buyer@commerce.com / buyerpass1234</p>
    </main>
  );
}
