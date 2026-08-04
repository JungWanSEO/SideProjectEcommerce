"use client";

import { ReactNode, useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import Skeleton from "@/components/ui/Skeleton";
import NotificationBell from "@/components/NotificationBell";

// 셀러 콘솔 내비게이션. /seller = 정산(랜딩) · orders = 내 주문 · shipments = 출고 관리 · returns = 반품/교환.
const NAV = [
  { href: "/seller", label: "내 정산" },
  { href: "/seller/orders", label: "내 주문" },
  { href: "/seller/shipments", label: "출고 관리" },
  { href: "/seller/returns", label: "반품 · 교환" },
];

/**
 * 셀러 콘솔 레이아웃 — 스토어/어드민과 분리된 셸. (SELLER 전용)
 *
 * 게이팅은 UX 레벨(비SELLER는 리다이렉트)일 뿐, 진짜 접근 제어는 백엔드 hasRole("SELLER") +
 * 본인 sellerId 스코핑이 한다(셀러는 /api/seller/me/** 로 자기 것만 조회).
 */
export default function SellerLayout({ children }: { children: ReactNode }) {
  const { user, loading, logout } = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (loading) return;
    if (!user) {
      router.replace("/login");
    } else if (user.role !== "SELLER") {
      router.replace("/"); // 권한 없으면 스토어로 (백엔드는 403)
    }
  }, [loading, user, router]);

  // 인증 확인 중(CSR로 /me 조회) 또는 권한 없어 리다이렉트 중 — blank/raw 텍스트 대신 콘솔 형태 스켈레톤.
  if (loading || !user || user.role !== "SELLER") return <SellerGateSkeleton />;

  return (
    <div className="flex min-h-screen bg-gray-50">
      <aside className="w-56 shrink-0 border-r border-gray-200 bg-white">
        <div className="px-5 py-4 text-lg font-bold">
          commerce <span className="text-gray-400">seller</span>
        </div>
        <nav className="flex flex-col gap-1 px-3">
          {NAV.map((n) => {
            // "/seller"(정산 랜딩)는 정확히 일치할 때만 활성(하위 경로가 startsWith로 잡히지 않게).
            const active = n.href === "/seller" ? pathname === "/seller" : pathname.startsWith(n.href);
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

      <div className="flex flex-1 flex-col">
        <header className="flex items-center justify-between border-b border-gray-200 bg-white px-6 py-3">
          <span className="text-sm text-gray-500">셀러 콘솔</span>
          <div className="flex items-center gap-4 text-sm">
            {/* 셀러 알림 벨(#6) — 새 주문·반품 요청이 여기로 온다. 스코핑은 백엔드가 sellerId로 강제. */}
            <NotificationBell basePath="/api/seller/me/notifications" tone="console" />
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
 * 인증 확인/리다이렉트 중 보여줄 콘솔 형태 스켈레톤 — 실제 셀러 셸(사이드바 + 상단바 + 콘텐츠)을
 * 본떠 shimmer 골격을 깐다. 사이드바 라벨은 노출하지 않아 권한 확인 전 정보 누출이 없다.
 */
function SellerGateSkeleton() {
  return (
    <div className="flex min-h-screen bg-gray-50">
      {/* 사이드바 자리 */}
      <aside className="w-56 shrink-0 border-r border-gray-200 bg-white p-4">
        <Skeleton className="h-6 w-28" />
        <div className="mt-6 flex flex-col gap-2">
          <Skeleton className="h-8 w-full rounded" />
        </div>
      </aside>

      {/* 본문 자리 */}
      <div className="flex flex-1 flex-col">
        <header className="flex items-center justify-between border-b border-gray-200 bg-white px-6 py-3">
          <Skeleton className="h-4 w-20" />
          <Skeleton className="h-4 w-40" />
        </header>
        <main className="flex-1 p-6">
          <Skeleton className="h-6 w-48" />
          <Skeleton className="mt-2 h-4 w-72" />
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
