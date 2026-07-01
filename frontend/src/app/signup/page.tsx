"use client";

import { useState, FormEvent } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { apiPost } from "@/lib/api";
import { useAuth } from "@/lib/auth";

export default function SignupPage() {
  const { login } = useAuth();
  const router = useRouter();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [passwordConfirm, setPasswordConfirm] = useState("");
  const [nickname, setNickname] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    // 클라이언트 선검증 — 백엔드와 같은 규칙으로 즉시 피드백(서버 왕복 전에)
    if (password.length < 8) {
      setError("비밀번호는 8자 이상이어야 합니다.");
      return;
    }
    if (password !== passwordConfirm) {
      setError("비밀번호가 일치하지 않습니다.");
      return;
    }
    setSubmitting(true);
    try {
      // 가입(POST /api/members). 이메일 중복이면 서버가 409 + 메시지.
      await apiPost("/api/members", { email, password, nickname });
      // 가입 성공 → 바로 로그인시켜 매끄럽게 진입(데모 UX)
      await login(email, password);
      router.push("/products");
    } catch (err) {
      setError((err as Error).message); // 중복 이메일(409)·검증(400) 등 서버 메시지 노출
    } finally {
      setSubmitting(false);
    }
  };

  const inputClass =
    "rounded-xl border border-line bg-paper px-4 py-3 text-ink outline-none transition placeholder:text-muted focus:border-clay";

  return (
    <main className="mx-auto flex min-h-[calc(100vh-65px)] max-w-sm flex-col justify-center px-6 py-12">
      <h1 className="text-center font-serif text-3xl text-ink">회원가입</h1>
      <p className="mt-2 text-center text-sm text-muted">ATELIER 계정을 만들어 보세요.</p>

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
          placeholder="비밀번호 (8자 이상)"
          required
          minLength={8}
          className={inputClass}
        />
        <input
          type="password"
          value={passwordConfirm}
          onChange={(e) => setPasswordConfirm(e.target.value)}
          placeholder="비밀번호 확인"
          required
          className={inputClass}
        />
        <input
          type="text"
          value={nickname}
          onChange={(e) => setNickname(e.target.value)}
          placeholder="닉네임"
          required
          maxLength={30}
          className={inputClass}
        />
        {error && <p className="text-sm text-danger">{error}</p>}
        <button
          type="submit"
          disabled={submitting}
          className="mt-1 rounded-full bg-clay px-4 py-3 font-medium text-cream transition hover:bg-clay-600 disabled:opacity-50"
        >
          {submitting ? "가입 중…" : "회원가입"}
        </button>
      </form>
      <p className="mt-5 text-center text-sm text-muted">
        이미 계정이 있으신가요?{" "}
        <Link href="/login" className="text-clay hover:underline">
          로그인
        </Link>
      </p>
    </main>
  );
}
