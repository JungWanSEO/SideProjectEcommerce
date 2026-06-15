"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useAuth } from "@/lib/auth";

/** 상단 네비게이션 — 로그인 상태에 따라 로그인 링크 / 유저+로그아웃 표시. */
export default function Header() {
  const { user, loading, logout } = useAuth();
  const pathname = usePathname();

  // 어드민·셀러 콘솔은 자체 사이드바 레이아웃을 쓰므로 스토어 헤더는 숨긴다.
  if (pathname?.startsWith("/admin") || pathname?.startsWith("/seller")) return null;

  const navLink = "text-sm text-ink/70 transition hover:text-clay";

  return (
    <header className="sticky top-0 z-30 border-b border-line bg-cream/85 backdrop-blur">
      <nav className="mx-auto flex max-w-6xl items-center justify-between px-6 py-4">
        <Link href="/" className="font-serif text-2xl font-extrabold tracking-tight text-ink">
          ATELIER
        </Link>
        <div className="flex items-center gap-6">
          <Link href="/products" className={navLink}>
            상품
          </Link>
          {loading ? null : user ? (
            <>
              <Link href="/orders" className={navLink}>
                주문내역
              </Link>
              <Link href="/account/addresses" className={navLink}>
                배송지
              </Link>
              <Link href="/account/coupons" className={navLink}>
                쿠폰함
              </Link>
              <Link href="/cart" className={navLink}>
                장바구니
              </Link>
              {user.role === "ADMIN" && (
                <Link href="/admin/settlements" className="text-sm font-medium text-clay hover:text-clay-600">
                  관리자
                </Link>
              )}
              {user.role === "SELLER" && (
                <Link href="/seller" className="text-sm font-medium text-clay hover:text-clay-600">
                  셀러 콘솔
                </Link>
              )}
              <span className="hidden text-sm text-muted sm:inline">{user.nickname}님</span>
              <button onClick={() => logout()} className="text-sm text-muted transition hover:text-ink">
                로그아웃
              </button>
            </>
          ) : (
            <Link href="/login" className={navLink}>
              로그인
            </Link>
          )}
        </div>
      </nav>
    </header>
  );
}
