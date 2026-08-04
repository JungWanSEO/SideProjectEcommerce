"use client";

import { ReactNode, useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import Skeleton from "@/components/ui/Skeleton";

/**
 * 어드민(운영 콘솔) 레이아웃 — 스토어프론트와 분리된 사이드바 셸.
 *
 * 게이팅은 UX 레벨(비ADMIN은 리다이렉트)일 뿐, 진짜 접근 제어는 백엔드 hasRole("ADMIN")이 한다
 * (누구나 API를 직접 호출할 수 있으므로 프론트 숨김은 보안 경계가 아니다).
 *
 * 참고: 루트 레이아웃의 스토어 Header는 /admin 경로에서 자기 자신을 숨긴다(Header.tsx).
 */
const NAV = [
  { href: "/admin", label: "대시보드" },
  { href: "/admin/products", label: "상품" },
  { href: "/admin/categories", label: "카테고리" },
  { href: "/admin/brands", label: "브랜드" },
  { href: "/admin/orders", label: "주문" },
  { href: "/admin/returns", label: "반품 · 교환" },
  { href: "/admin/members", label: "회원" },
  { href: "/admin/settlements", label: "정산" },
  { href: "/admin/payouts", label: "지급" },
  { href: "/admin/reconciliations", label: "대사" },
  { href: "/admin/coupons", label: "쿠폰" },
  { href: "/admin/audit", label: "감사 로그" },
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

  // 인증 확인 중(CSR로 /me 조회) 또는 권한 없어 리다이렉트 중 — blank/raw 텍스트 대신 콘솔 형태 스켈레톤.
  if (loading || !user || user.role !== "ADMIN") return <AdminGateSkeleton />;

  return (
    <div className="flex min-h-screen bg-gray-50">
      {/* 사이드바 */}
      <aside className="w-56 shrink-0 border-r border-gray-200 bg-white">
        <div className="px-5 py-4 text-lg font-bold">
          commerce <span className="text-gray-400">admin</span>
        </div>
        <nav className="flex flex-col gap-1 px-3">
          {NAV.map((n) => {
            // 대시보드("/admin")는 정확히 일치할 때만 활성(다른 /admin/* 경로도 startsWith로 잡히는 것 방지).
            const active = n.href === "/admin" ? pathname === "/admin" : pathname.startsWith(n.href);
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

/**
 * 인증 확인/리다이렉트 중 보여줄 콘솔 형태 스켈레톤 — 실제 어드민 셸(사이드바 + 상단바 + KPI 그리드)을
 * 본떠 shimmer 골격을 깐다. 사이드바 라벨은 노출하지 않아(그냥 블록) 권한 확인 전 정보 누출이 없다.
 */
function AdminGateSkeleton() {
  return (
    <div className="flex min-h-screen bg-gray-50">
      {/* 사이드바 자리 */}
      <aside className="w-56 shrink-0 border-r border-gray-200 bg-white p-4">
        <Skeleton className="h-6 w-28" />
        <div className="mt-6 flex flex-col gap-2">
          {/* 네비 항목 수만큼 — NAV가 늘면 같이 늘려 스켈레톤이 실제 셸과 맞게 유지한다 */}
          {NAV.map((n) => (
            <Skeleton key={n.href} className="h-8 w-full rounded" />
          ))}
        </div>
      </aside>

      {/* 본문 자리 */}
      <div className="flex flex-1 flex-col">
        <header className="flex items-center justify-between border-b border-gray-200 bg-white px-6 py-3">
          <Skeleton className="h-4 w-20" />
          <Skeleton className="h-4 w-40" />
        </header>
        <main className="flex-1 p-6">
          <Skeleton className="h-7 w-40" />
          <div className="mt-6 grid grid-cols-2 gap-4 lg:grid-cols-5">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="rounded-2xl border border-gray-200 bg-white p-5">
                <Skeleton className="h-3 w-16" />
                <Skeleton className="mt-3 h-7 w-24" />
              </div>
            ))}
          </div>
        </main>
      </div>
    </div>
  );
}
