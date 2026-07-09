"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { apiGet } from "@/lib/api";
import { PageResponse, Wishlist } from "@/lib/types";
import { useAuth } from "@/lib/auth";
import { useWishlist } from "@/lib/wishlist";
import ProductThumb from "@/components/ui/ProductThumb";
import Skeleton from "@/components/ui/Skeleton";
import ProductGridSkeleton from "@/components/ui/ProductGridSkeleton";
import Badge from "@/components/ui/Badge";
import { productImageSrc } from "@/lib/productImage";

/**
 * 내 위시리스트 (/account/wishlist). 찜한 상품을 카드 그리드로 보여주고, 카드에서 바로 해제한다.
 * 해제는 전역 찜 상태({@link useWishlist})를 토글해 다른 화면의 하트도 같이 갱신하고, 이 페이지 목록에선 제거한다.
 */
export default function WishlistPage() {
  const { user, loading: authLoading } = useAuth();
  const { toggle } = useWishlist();
  const router = useRouter();

  const [items, setItems] = useState<Wishlist[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  useEffect(() => {
    if (!authLoading && !user) router.replace("/login");
  }, [authLoading, user, router]);

  useEffect(() => {
    if (!user) return;
    apiGet<PageResponse<Wishlist>>("/api/wishlist/me")
      .then((page) => setItems(page.content))
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [user]);

  const remove = async (productId: number) => {
    setBusyId(productId);
    try {
      await toggle(productId); // 현재 찜 상태 → 해제(DELETE) + 전역 집합 갱신
      setItems((prev) => prev.filter((w) => w.productId !== productId)); // 이 화면 목록에서도 제거
    } catch {
      // 실패는 조용히 무시 — 다음 시도에 재시도
    } finally {
      setBusyId(null);
    }
  };

  if (authLoading || (user && loading))
    return (
      <main className="mx-auto max-w-6xl px-6 py-12">
        <header className="mb-8">
          <Skeleton className="h-3 w-20" />
          <Skeleton className="mt-2 h-9 w-40" />
        </header>
        <ProductGridSkeleton count={6} />
      </main>
    );
  if (!user) return null;

  return (
    <main className="mx-auto max-w-6xl px-6 py-12">
      <header className="mb-8">
        <span className="text-xs uppercase tracking-[0.3em] text-clay">Wishlist</span>
        <h1 className="mt-2 text-3xl font-bold text-ink">위시리스트</h1>
      </header>

      {error && <p className="mb-4 text-sm text-danger">{error}</p>}

      {items.length === 0 ? (
        <div className="rounded-2xl border border-line bg-paper p-12 text-center text-muted">
          아직 찜한 상품이 없습니다.{" "}
          <Link href="/products" className="text-clay hover:underline">
            상품 보러가기
          </Link>
        </div>
      ) : (
        <ul className="grid grid-cols-2 gap-x-5 gap-y-10 lg:grid-cols-3">
          {items.map((w) => (
            <li key={w.id} className="relative">
              {/* 해제 버튼(카드 코너) — 카드 <Link> 바깥 형제 */}
              <button
                type="button"
                onClick={() => remove(w.productId)}
                disabled={busyId === w.productId}
                aria-label="찜 해제"
                title="찜 해제"
                className="absolute right-3 top-3 z-10 grid h-9 w-9 place-items-center rounded-full bg-cream/85 backdrop-blur transition hover:bg-cream disabled:opacity-50"
              >
                <svg viewBox="0 0 24 24" className="h-5 w-5 text-clay" fill="currentColor" aria-hidden>
                  <path d="M12 20.5l-7.1-7.2a4.5 4.5 0 016.4-6.3l.7.7.7-.7a4.5 4.5 0 016.4 6.3L12 20.5z" />
                </svg>
              </button>

              {w.product ? (
                <Link href={`/products/${w.product.id}`} className="group block">
                  <div className="relative overflow-hidden rounded-2xl">
                    <ProductThumb
                      name={w.product.name}
                      src={productImageSrc(w.product)}
                      className="aspect-[4/5] w-full transition duration-500 group-hover:scale-[1.03]"
                    />
                    {w.product.status === "SOLD_OUT" && (
                      <span className="absolute left-3 top-3">
                        <Badge tone="dark">품절</Badge>
                      </span>
                    )}
                  </div>
                  <div className="mt-3 px-0.5">
                    {w.product.brandName && (
                      <p className="text-xs uppercase tracking-wider text-muted">{w.product.brandName}</p>
                    )}
                    <h2 className="mt-1 font-serif text-lg text-ink">{w.product.name}</h2>
                    <p className="mt-1 font-medium text-ink">{w.product.price.toLocaleString()}원</p>
                  </div>
                </Link>
              ) : (
                // 찜한 뒤 상품이 삭제된 경우 — 카드 자리는 두되 해제만 가능
                <div className="flex aspect-[4/5] w-full items-center justify-center rounded-2xl border border-line bg-paper text-sm text-muted">
                  삭제된 상품
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
