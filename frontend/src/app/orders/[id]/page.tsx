"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { apiGet, apiPost } from "@/lib/api";
import { Order } from "@/lib/types";
import { ORDER_STATUS_LABEL, ORDER_STATUS_BADGE } from "@/lib/orderStatus";
import { useAuth } from "@/lib/auth";
import { buttonClass } from "@/components/ui/Button";

/** 주문 상세 (/orders/[id]). 본인 주문만(서버가 403으로 차단). PENDING=결제/취소, PAID=취소(환불). */
export default function OrderDetailPage() {
  const params = useParams();
  const id = params.id as string;
  const { user, loading: authLoading } = useAuth();
  const router = useRouter();

  const [order, setOrder] = useState<Order | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);

  useEffect(() => {
    if (!authLoading && !user) router.replace("/login");
  }, [authLoading, user, router]);

  useEffect(() => {
    if (!user) return;
    apiGet<Order>(`/api/orders/${id}`)
      .then(setOrder)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [user, id]);

  const cancel = async () => {
    // PAID 주문 취소는 환불까지 동반 → 문구로 구분
    const msg = order?.status === "PAID" ? "결제를 취소하고 환불하시겠어요?" : "주문을 취소하시겠어요?";
    if (!confirm(msg)) return;
    setCancelling(true);
    setError(null);
    try {
      const updated = await apiPost<Order>(`/api/orders/${id}/cancel`);
      setOrder(updated); // 취소 API가 갱신된 주문(CANCELLED)을 돌려줌
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setCancelling(false);
    }
  };

  // 항목 부분취소(환불) — 그 항목만 취소·환불(PAID 주문)
  const cancelItem = async (itemId: number) => {
    if (!confirm("이 항목을 취소하고 환불하시겠어요?")) return;
    setCancelling(true);
    setError(null);
    try {
      const updated = await apiPost<Order>(`/api/orders/${id}/items/${itemId}/cancel`);
      setOrder(updated);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setCancelling(false);
    }
  };

  if (authLoading || (user && loading)) return <p className="p-12 text-center text-muted">불러오는 중…</p>;
  if (!user) return null;

  if (error && !order) {
    return (
      <main className="mx-auto max-w-3xl px-6 py-12">
        <p className="text-danger">에러: {error}</p>
        <Link href="/orders" className="mt-4 inline-block text-clay hover:underline">
          ← 주문 내역
        </Link>
      </main>
    );
  }
  if (!order) return null;

  return (
    <main className="mx-auto max-w-3xl px-6 py-10">
      <Link href="/orders" className="text-sm text-muted transition hover:text-clay">
        ← 주문 내역
      </Link>

      <div className="mt-5 flex items-center justify-between">
        <h1 className="font-serif text-3xl text-ink">주문 #{order.id}</h1>
        <span className={`rounded-full px-3 py-1 text-xs font-medium ${ORDER_STATUS_BADGE[order.status]}`}>
          {ORDER_STATUS_LABEL[order.status]}
        </span>
      </div>
      <p className="mt-1 text-sm text-muted">{new Date(order.createdAt).toLocaleString("ko-KR")}</p>

      <ul className="mt-6 overflow-hidden rounded-2xl border border-line bg-paper">
        {order.items.map((it, i) => (
          <li
            key={it.id}
            className={`flex items-center justify-between px-5 py-4 ${i > 0 ? "border-t border-line" : ""} ${
              it.status === "CANCELLED" ? "opacity-50" : ""
            }`}
          >
            <div>
              <p className="font-serif text-lg text-ink">
                {it.productName}
                {it.status === "CANCELLED" && (
                  <span className="ml-2 rounded bg-line px-1.5 py-0.5 text-xs text-muted">취소됨</span>
                )}
              </p>
              <p className="mt-0.5 text-sm text-muted">
                사이즈 {it.size} · {it.quantity}개 · {it.orderPrice.toLocaleString()}원
              </p>
            </div>
            <div className="flex items-center gap-3">
              <span className="font-medium text-ink">{it.subtotal.toLocaleString()}원</span>
              {/* PAID 주문의 활성 항목만 부분취소 가능 */}
              {order.status === "PAID" && it.status === "ACTIVE" && (
                <button
                  onClick={() => cancelItem(it.id)}
                  disabled={cancelling}
                  className="rounded-full border border-line px-3 py-1 text-xs text-muted transition hover:border-danger hover:text-danger disabled:opacity-50"
                >
                  취소
                </button>
              )}
            </div>
          </li>
        ))}
      </ul>

      <div className="mt-6 border-t border-line pt-5">
        {order.discountAmount > 0 && (
          <>
            <div className="flex justify-between text-sm">
              <span className="text-muted">상품 합계</span>
              <span className="text-ink">{order.totalPrice.toLocaleString()}원</span>
            </div>
            <div className="mt-1 flex justify-between text-sm">
              <span className="text-sage-600">
                쿠폰 할인{order.couponCode ? ` (${order.couponCode})` : ""}
              </span>
              <span className="font-medium text-sage-600">−{order.discountAmount.toLocaleString()}원</span>
            </div>
          </>
        )}
        <div className="mt-2 flex items-center justify-between">
          <span className="text-muted">{order.discountAmount > 0 ? "결제 금액" : "합계"}</span>
          <span className="text-2xl font-bold text-ink">{order.payableAmount.toLocaleString()}원</span>
        </div>
      </div>

      {/* 배송지 (주문 시점 스냅샷). 배송지 없이 만든 주문이면 표시 안 함. */}
      {order.shipping && (
        <section className="mt-6 rounded-2xl border border-line bg-paper p-5">
          <h2 className="mb-2 font-serif text-lg text-ink">배송지</h2>
          <p className="font-medium text-ink">
            {order.shipping.recipient}
            <span className="ml-2 text-sm font-normal text-muted">{order.shipping.phone}</span>
          </p>
          <p className="mt-1 text-sm text-ink/80">
            [{order.shipping.zipcode}] {order.shipping.address1}
            {order.shipping.address2 ? ` ${order.shipping.address2}` : ""}
          </p>
          {order.shipping.deliveryMemo && (
            <p className="mt-1 text-sm text-muted">요청사항: {order.shipping.deliveryMemo}</p>
          )}
        </section>
      )}

      {error && <p className="mt-4 text-sm text-danger">{error}</p>}

      <div className="mt-7 flex gap-3">
        {/* PENDING(결제대기): 결제하기 + 취소 / PAID(결제완료): 취소=환불 / CANCELLED: 버튼 없음 */}
        {order.status === "PENDING" && (
          <Link href={`/orders/${order.id}/pay`} className={buttonClass("primary", "md")}>
            결제하기
          </Link>
        )}
        {(order.status === "PENDING" || order.status === "PAID") && (
          <button
            onClick={cancel}
            disabled={cancelling}
            className="rounded-full border border-line px-5 py-2.5 text-sm text-muted transition hover:border-danger hover:text-danger disabled:opacity-50"
          >
            {cancelling ? "취소 처리 중…" : "주문 취소"}
          </button>
        )}
      </div>
    </main>
  );
}
