"use client";

import { useEffect, useState, FormEvent } from "react";
import { useRouter } from "next/navigation";
import { apiGet, apiPut } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { loginHref } from "@/lib/useRequireAuth";
import Skeleton from "@/components/ui/Skeleton";

/** 내 정보 응답(백엔드 MyProfileResponse) — provider·hasPassword로 로컬/소셜 UI 분기. */
interface MyProfile {
  id: number;
  email: string;
  nickname: string;
  role: string;
  provider: "LOCAL" | "GOOGLE" | "KAKAO" | "NAVER";
  hasPassword: boolean;
  createdAt: string;
}

const PROVIDER_LABEL: Record<string, string> = {
  LOCAL: "이메일",
  GOOGLE: "구글",
  KAKAO: "카카오",
  NAVER: "네이버",
};

const inputClass =
  "rounded-xl border border-line bg-paper px-4 py-3 text-ink outline-none transition placeholder:text-muted focus:border-clay";

export default function ProfilePage() {
  const { user, loading, refreshUser } = useAuth();
  const router = useRouter();

  const [profile, setProfile] = useState<MyProfile | null>(null);
  const [nickname, setNickname] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [pwMsg, setPwMsg] = useState<string | null>(null);
  const [pwErr, setPwErr] = useState<string | null>(null);

  useEffect(() => {
    if (loading) return;
    if (!user) {
      router.replace(loginHref(window.location.pathname + window.location.search));
      return;
    }
    apiGet<MyProfile>("/api/members/me")
      .then((p) => {
        setProfile(p);
        setNickname(p.nickname);
      })
      .catch((e) => setErr((e as Error).message));
  }, [loading, user, router]);

  const saveNickname = async (e: FormEvent) => {
    e.preventDefault();
    setMsg(null);
    setErr(null);
    try {
      const updated = await apiPut<MyProfile>("/api/members/me", { nickname });
      setProfile(updated);
      setMsg("저장되었습니다.");
      await refreshUser(); // 헤더 닉네임 즉시 반영
    } catch (e) {
      setErr((e as Error).message);
    }
  };

  const savePassword = async (e: FormEvent) => {
    e.preventDefault();
    setPwMsg(null);
    setPwErr(null);
    if (newPassword.length < 8) {
      setPwErr("비밀번호는 8자 이상이어야 합니다.");
      return;
    }
    try {
      await apiPut<void>("/api/members/me/password", {
        currentPassword: profile?.hasPassword ? currentPassword : null,
        newPassword,
      });
      setPwMsg(profile?.hasPassword ? "비밀번호가 변경되었습니다." : "비밀번호가 설정되었습니다.");
      setCurrentPassword("");
      setNewPassword("");
      setProfile((p) => (p ? { ...p, hasPassword: true } : p)); // 설정 후엔 '변경' 모드로
    } catch (e) {
      setPwErr((e as Error).message);
    }
  };

  if (loading || !profile) {
    return (
      <main className="mx-auto max-w-md px-6 py-12">
        <Skeleton className="h-9 w-32" />
        <section className="mt-8 rounded-2xl border border-line bg-paper/50 p-5">
          <div className="flex flex-col gap-3">
            {Array.from({ length: 3 }).map((_, i) => (
              <div key={i} className="flex justify-between">
                <Skeleton className="h-4 w-20" />
                <Skeleton className="h-4 w-32" />
              </div>
            ))}
          </div>
        </section>
        <div className="mt-6 flex flex-col gap-3">
          <Skeleton className="h-4 w-16" />
          <Skeleton className="h-12 w-full rounded-xl" />
          <Skeleton className="h-12 w-full rounded-full" />
        </div>
        <div className="mt-8 flex flex-col gap-3 border-t border-line pt-8">
          <Skeleton className="h-4 w-24" />
          <Skeleton className="h-12 w-full rounded-xl" />
          <Skeleton className="h-12 w-full rounded-xl" />
          <Skeleton className="h-12 w-full rounded-full" />
        </div>
      </main>
    );
  }

  return (
    <main className="mx-auto max-w-md px-6 py-12">
      <h1 className="font-serif text-3xl text-ink">내 정보</h1>

      <section className="mt-8 rounded-2xl border border-line bg-paper/50 p-5 text-sm">
        <div className="flex justify-between py-1">
          <span className="text-muted">이메일</span>
          <span className="text-ink">{profile.email}</span>
        </div>
        <div className="flex justify-between py-1">
          <span className="text-muted">로그인 방식</span>
          <span className="text-ink">{PROVIDER_LABEL[profile.provider] ?? profile.provider}</span>
        </div>
        <div className="flex justify-between py-1">
          <span className="text-muted">가입일</span>
          <span className="text-ink">{new Date(profile.createdAt).toLocaleDateString("ko-KR")}</span>
        </div>
      </section>

      <form onSubmit={saveNickname} className="mt-6 flex flex-col gap-3">
        <label className="text-sm font-medium text-ink">닉네임</label>
        <input
          value={nickname}
          onChange={(e) => setNickname(e.target.value)}
          required
          maxLength={30}
          className={inputClass}
        />
        {err && <p className="text-sm text-danger">{err}</p>}
        {msg && <p className="text-sm text-clay">{msg}</p>}
        <button
          type="submit"
          className="rounded-full bg-clay px-4 py-3 font-medium text-cream transition hover:bg-clay-600"
        >
          저장
        </button>
      </form>

      <form onSubmit={savePassword} className="mt-8 flex flex-col gap-3 border-t border-line pt-8">
        <h2 className="text-sm font-medium text-ink">{profile.hasPassword ? "비밀번호 변경" : "비밀번호 설정"}</h2>
        {!profile.hasPassword && (
          <p className="text-xs text-muted">
            소셜 로그인 계정입니다. 비밀번호를 설정하면 이메일로도 로그인할 수 있어요.
          </p>
        )}
        {profile.hasPassword && (
          <input
            type="password"
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
            placeholder="현재 비밀번호"
            required
            className={inputClass}
          />
        )}
        <input
          type="password"
          value={newPassword}
          onChange={(e) => setNewPassword(e.target.value)}
          placeholder="새 비밀번호 (8자 이상)"
          required
          minLength={8}
          className={inputClass}
        />
        {pwErr && <p className="text-sm text-danger">{pwErr}</p>}
        {pwMsg && <p className="text-sm text-clay">{pwMsg}</p>}
        <button
          type="submit"
          className="rounded-full border border-clay px-4 py-3 font-medium text-clay transition hover:bg-clay hover:text-cream"
        >
          {profile.hasPassword ? "비밀번호 변경" : "비밀번호 설정"}
        </button>
      </form>
    </main>
  );
}
