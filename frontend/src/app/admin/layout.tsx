"use client";

import { ReactNode, useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";
import Link from "next/link";
import { useAuth } from "@/lib/auth";

/**
 * 어드민(운영 콘솔) 레이아웃 — 스토어프론트와 분리된 사이드바 셸.
 *
 * 게이팅은 UX 레벨(비ADMIN은 리다이렉트)일 뿐, 진짜 접근 제어는 백엔드 hasRole("ADMIN")이 한다
 * (누구나 API를 직접 호출할 수 있으므로 프론트 숨김은 보안 경계가 아니다).
 *
 * 참고: 루트 레이아웃의 스토어 Header는 /admin 경로에서 자기 자신을 숨긴다(Header.tsx).
 */
const NAV = [
  { href: "/admin/settlements", label: "정산" },
  { href: "/admin/payouts", label: "지급" },
  { href: "/admin/reconciliations", label: "대사" },
];

export default function AdminLayout({ children }: { children: ReactNode }) {
  const { user, loading, logout } = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (loading) return;
    if (!user) {
      router.replace("/login");
    } else if (user.role !== "ADMIN") {
      router.replace("/"); // 권한 없으면 스토어로 (백엔드는 403)
    }
  }, [loading, user, router]);

  if (loading) return <div className="p-8 text-gray-500">불러오는 중…</div>;
  if (!user || user.role !== "ADMIN") return null; // 리다이렉트 진행 중

  return (
    <div className="flex min-h-screen bg-gray-50">
      {/* 사이드바 */}
      <aside className="w-56 shrink-0 border-r border-gray-200 bg-white">
        <div className="px-5 py-4 text-lg font-bold">
          commerce <span className="text-gray-400">admin</span>
        </div>
        <nav className="flex flex-col gap-1 px-3">
          {NAV.map((n) => {
            const active = pathname.startsWith(n.href);
            return (
              <Link
                key={n.href}
                href={n.href}
                className={`rounded px-3 py-2 text-sm ${
                  active ? "bg-gray-900 text-white" : "text-gray-700 hover:bg-gray-100"
                }`}
              >
                {n.label}
              </Link>
            );
          })}
        </nav>
      </aside>

      {/* 본문 */}
      <div className="flex flex-1 flex-col">
        <header className="flex items-center justify-between border-b border-gray-200 bg-white px-6 py-3">
          <span className="text-sm text-gray-500">운영 콘솔</span>
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
