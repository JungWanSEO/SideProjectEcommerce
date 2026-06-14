"use client";

import { ReactNode, useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

/**
 * 셀러 콘솔 레이아웃 — 스토어/어드민과 분리된 셸. (SELLER 전용)
 *
 * 게이팅은 UX 레벨(비SELLER는 리다이렉트)일 뿐, 진짜 접근 제어는 백엔드 hasRole("SELLER") +
 * 본인 sellerId 스코핑이 한다(셀러는 /api/seller/me/** 로 자기 것만 조회).
 */
export default function SellerLayout({ children }: { children: ReactNode }) {
  const { user, loading, logout } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (loading) return;
    if (!user) {
      router.replace("/login");
    } else if (user.role !== "SELLER") {
      router.replace("/"); // 권한 없으면 스토어로 (백엔드는 403)
    }
  }, [loading, user, router]);

  if (loading) return <div className="p-8 text-gray-500">불러오는 중…</div>;
  if (!user || user.role !== "SELLER") return null; // 리다이렉트 진행 중

  return (
    <div className="flex min-h-screen bg-gray-50">
      <aside className="w-56 shrink-0 border-r border-gray-200 bg-white">
        <div className="px-5 py-4 text-lg font-bold">
          commerce <span className="text-gray-400">seller</span>
        </div>
        <nav className="flex flex-col gap-1 px-3">
          <span className="rounded bg-gray-900 px-3 py-2 text-sm text-white">내 정산</span>
        </nav>
      </aside>

      <div className="flex flex-1 flex-col">
        <header className="flex items-center justify-between border-b border-gray-200 bg-white px-6 py-3">
          <span className="text-sm text-gray-500">셀러 콘솔</span>
          <div className="flex items-center gap-4 text-sm">
            <span className="text-gray-600">{user.email}</span>
            <button onClick={() => logout()} className="text-gray-500 hover:underline">
              로그아웃
            </button>
          </div>
        </header>
        <main className="flex-1 p-6">{children}</main>
      </div>
    </div>
  );
}
