"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { apiGet, apiDelete, apiPut } from "@/lib/api";
import { Cart, CartItem } from "@/lib/types";
import { useAuth } from "@/lib/auth";
import { loginHref } from "@/lib/useRequireAuth";
import Badge from "@/components/ui/Badge";
import Skeleton from "@/components/ui/Skeleton";

/**
 * 장바구니 페이지 (/cart). 인증 필요(httpOnly 쿠키 자동 전송).
 * 비로그인 시 로그인 페이지로 보낸다.
 */
export default function CartPage() {
  const { user, loading: authLoading } = useAuth();
  const router = useRouter();

  const [cart, setCart] = useState<Cart | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [updatingOptionId, setUpdatingOptionId] = useState<number | null>(null); // 수량 변경 중인 항목

  // 비로그인 → 로그인으로
  useEffect(() => {
    if (!authLoading && !user) router.replace(loginHref(window.location.pathname + window.location.search));
  }, [authLoading, user, router]);

  useEffect(() => {
    if (!user) return;
    apiGet<Cart>("/api/carts")
      .then(setCart)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [user]);

  const remove = async (optionId: number) => {
    try {
      const updated = await apiDelete<Cart>(`/api/carts/items/${optionId}`);
      setCart(updated); // 삭제 API가 갱신된 장바구니를 돌려줌
    } catch (e) {
      setError((e as Error).message);
    }
  };

  // 수량 변경(절대값): PUT이 갱신된 장바구니를 돌려줌(소계·총합 재계산까지 서버가 함).
  // 최소 1(0은 삭제 버튼으로), 재고 초과는 클램프(백엔드는 막지 않지만 UX 가드).
  const changeQty = async (it: CartItem, next: number) => {
    if (next < 1 || next > it.stock || next === it.quantity) return;
    setUpdatingOptionId(it.optionId);
    setError(null);
    try {
      const updated = await apiPut<Cart>(`/api/carts/items/${it.optionId}`, { quantity: next });
      setCart(updated);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setUpdatingOptionId(null);
    }
  };

  if (authLoading || (user && loading))
    return (
      <main className="mx-auto max-w-3xl px-6 py-12">
        {/* 제목 */}
        <Skeleton className="mb-8 h-9 w-40" />

        {/* 항목 목록 */}
        <ul className="overflow-hidden rounded-2xl border border-line bg-paper">
          {Array.from({ length: 3 }).map((_, i) => (
            <li
              key={i}
              className={`flex items-center justify-between gap-4 px-5 py-4 ${i > 0 ? "border-t border-line" : ""}`}
            >
              <div>
                <Skeleton className="h-5 w-40" />
                <Skeleton className="mt-2 h-4 w-24" />
              </div>
              <div className="flex items-center gap-4">
                <Skeleton className="h-9 w-28 rounded-full" />
                <Skeleton className="h-5 w-20" />
                <Skeleton className="h-4 w-8" />
              </div>
            </li>
          ))}
        </ul>

        {/* 요약 행 */}
        <div className="mt-6 flex items-center justify-between">
          <Skeleton className="h-5 w-16" />
          <Skeleton className="h-8 w-28" />
        </div>

        {/* 주문하기 버튼 */}
        <Skeleton className="mt-6 h-[3.25rem] w-full rounded-full" />
      </main>
    );
  if (!user) return null; // 리다이렉트 중

  const items = cart?.items ?? [];
  const totalPrice = items.reduce((sum, it) => sum + it.subtotal, 0);

  return (
    <main className="mx-auto max-w-3xl px-6 py-12">
      <h1 className="mb-8 text-3xl font-bold text-ink">장바구니</h1>

      {items.length === 0 ? (
        <div className="rounded-2xl border border-line bg-paper p-12 text-center">
          <p className="text-muted">장바구니가 비어 있습니다.</p>
          <Link href="/products" className="mt-3 inline-block text-clay hover:underline">
            상품 보러가기 →
          </Link>
        </div>
      ) : (
        <>
          <ul className="overflow-hidden rounded-2xl border border-line bg-paper">
            {items.map((it, i) => (
              <li
                key={it.optionId}
                className={`flex items-center justify-between gap-4 px-5 py-4 ${i > 0 ? "border-t border-line" : ""}`}
              >
                <div>
                  <p className="font-serif text-lg text-ink">{it.productName}</p>
                  <p className="mt-0.5 text-sm text-muted">
                    사이즈 {it.size}
                    {it.soldOut && (
                      <span className="ml-2">
                        <Badge tone="danger">품절</Badge>
                      </span>
                    )}
                  </p>
                </div>
                <div className="flex items-center gap-4">
                  {/* 수량 스테퍼: −는 1에서 멈추고(0은 '삭제'), +는 재고에서 멈춘다. 변경 중엔 비활성 */}
                  <div className="flex items-center rounded-full border border-line">
                    <button
                      onClick={() => changeQty(it, it.quantity - 1)}
                      disabled={updatingOptionId === it.optionId || it.quantity <= 1}
                      aria-label="수량 줄이기"
                      className="px-3 py-1.5 text-muted transition hover:text-ink disabled:opacity-40"
                    >
                      −
                    </button>
                    <span className="min-w-[2rem] text-center text-sm font-medium text-ink">
                      {it.quantity}
                    </span>
                    <button
                      onClick={() => changeQty(it, it.quantity + 1)}
                      disabled={updatingOptionId === it.optionId || it.soldOut || it.quantity >= it.stock}
                      aria-label="수량 늘리기"
                      className="px-3 py-1.5 text-muted transition hover:text-ink disabled:opacity-40"
                    >
                      +
                    </button>
                  </div>
                  <span className="w-24 text-right font-medium text-ink">
                    {it.subtotal.toLocaleString()}원
                  </span>
                  <button
                    onClick={() => remove(it.optionId)}
                    className="text-sm text-muted transition hover:text-danger"
                  >
                    삭제
                  </button>
                </div>
              </li>
            ))}
          </ul>

          <div className="mt-6 flex items-center justify-between">
            <span className="text-muted">총 {cart?.totalQuantity ?? 0}개</span>
            <span className="text-2xl font-bold text-ink">{totalPrice.toLocaleString()}원</span>
          </div>

          {error && <p className="mt-4 text-sm text-danger">{error}</p>}

          <button
            onClick={() => router.push("/checkout")}
            className="mt-6 w-full rounded-full bg-clay px-4 py-3.5 font-medium text-cream transition hover:bg-clay-600"
          >
            주문하기
          </button>
        </>
      )}
    </main>
  );
}
