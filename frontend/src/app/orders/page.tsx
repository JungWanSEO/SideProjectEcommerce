"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { apiGet } from "@/lib/api";
import { OrderSummary, PageResponse } from "@/lib/types";
import { ORDER_STATUS_LABEL, ORDER_STATUS_BADGE } from "@/lib/orderStatus";
import { useAuth } from "@/lib/auth";
import { loginHref } from "@/lib/useRequireAuth";
import Skeleton from "@/components/ui/Skeleton";

/** 내 주문 목록 (/orders). 인증 필요. 목록은 요약(대표상품명+항목수). */
export default function OrdersPage() {
  const { user, loading: authLoading } = useAuth();
  const router = useRouter();

  const [orders, setOrders] = useState<OrderSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!authLoading && !user) router.replace(loginHref(window.location.pathname + window.location.search));
  }, [authLoading, user, router]);

  useEffect(() => {
    if (!user) return;
    apiGet<PageResponse<OrderSummary>>("/api/orders")
      .then((page) => setOrders(page.content))
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [user]);

  if (authLoading || (user && loading))
    return (
      <main className="mx-auto max-w-3xl px-6 py-12">
        <Skeleton className="mb-8 h-9 w-40" />
        <ul className="flex flex-col gap-3">
          {Array.from({ length: 4 }).map((_, i) => (
            <li key={i} className="rounded-2xl border border-line bg-paper p-5">
              <div className="flex items-center justify-between">
                <Skeleton className="h-4 w-32" />
                <Skeleton className="h-5 w-16 rounded-full" />
              </div>
              <Skeleton className="mt-2 h-6 w-48" />
              <Skeleton className="mt-2 h-5 w-24" />
            </li>
          ))}
        </ul>
      </main>
    );
  if (!user) return null;
  if (error) return <p className="p-12 text-center text-danger">에러: {error}</p>;

  return (
    <main className="mx-auto max-w-3xl px-6 py-12">
      <h1 className="mb-8 text-3xl font-bold text-ink">주문 내역</h1>

      {orders.length === 0 ? (
        <div className="rounded-2xl border border-line bg-paper p-12 text-center">
          <p className="text-muted">주문 내역이 없습니다.</p>
          <Link href="/products" className="mt-3 inline-block text-clay hover:underline">
            상품 보러가기 →
          </Link>
        </div>
      ) : (
        <ul className="flex flex-col gap-3">
          {orders.map((o) => (
            <li key={o.id}>
              <Link
                href={`/orders/${o.id}`}
                className="block rounded-2xl border border-line bg-paper p-5 transition hover:border-clay hover:shadow-soft"
              >
                <div className="flex items-center justify-between">
                  <span className="text-sm text-muted">
                    #{o.id} · {new Date(o.createdAt).toLocaleDateString("ko-KR")}
                  </span>
                  <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${ORDER_STATUS_BADGE[o.status]}`}>
                    {ORDER_STATUS_LABEL[o.status]}
                  </span>
                </div>
                <p className="mt-2 font-serif text-lg text-ink">
                  {o.representativeProductName}
                  {o.itemCount > 1 && <span className="text-muted"> 외 {o.itemCount - 1}건</span>}
                </p>
                <p className="mt-1 font-medium text-ink">{o.totalPrice.toLocaleString()}원</p>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
