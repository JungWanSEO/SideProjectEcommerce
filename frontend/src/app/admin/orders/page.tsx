"use client";

import { useCallback, useEffect, useState } from "react";
import { apiGet, apiPatch } from "@/lib/api";
import { Order, OrderStatus, OrderStatusUpdateInput, OrderSummary, PageResponse } from "@/lib/types";
import { ORDER_STATUS_BADGE, ORDER_STATUS_LABEL } from "@/lib/orderStatus";

/**
 * 주문 관리 화면 (/admin/orders, ADMIN).
 * 전체 주문을 상태별로 보고 배송 상태를 전진시킨다(PAID→SHIPPING→DELIVERED, forward-only).
 * 전이 가드·취소 차단은 백엔드가 강제하며, 위반 시 409 메시지를 띄운다.
 */
const FILTERS: { value: OrderStatus | "ALL"; label: string }[] = [
  { value: "ALL", label: "전체" },
  { value: "PENDING", label: "결제대기" },
  { value: "PAID", label: "결제완료" },
  { value: "SHIPPING", label: "배송중" },
  { value: "DELIVERED", label: "배송완료" },
  { value: "CANCELLED", label: "취소됨" },
];

// 현재 상태에서 누를 수 있는 "다음 단계" 버튼(없으면 액션 없음 — DELIVERED·CANCELLED·PENDING).
const NEXT_ACTION: Partial<Record<OrderStatus, { next: OrderStatus; label: string }>> = {
  PAID: { next: "SHIPPING", label: "배송 시작" },
  SHIPPING: { next: "DELIVERED", label: "배송 완료" },
};

export default function AdminOrdersPage() {
  const [orders, setOrders] = useState<OrderSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [filter, setFilter] = useState<OrderStatus | "ALL">("ALL");
  const [page, setPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [totalElements, setTotalElements] = useState(0);

  const load = useCallback(() => {
    setLoading(true);
    const q = new URLSearchParams({ page: String(page), size: "20" });
    if (filter !== "ALL") q.set("status", filter);
    apiGet<PageResponse<OrderSummary>>(`/api/orders/admin?${q.toString()}`)
      .then((p) => {
        setOrders(p.content);
        setHasNext(p.hasNext);
        setTotalElements(p.totalElements);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [page, filter]);

  useEffect(() => {
    load();
  }, [load]);

  // 필터 변경 시 첫 페이지로
  const changeFilter = (value: OrderStatus | "ALL") => {
    setFilter(value);
    setPage(0);
  };

  const advance = async (id: number, next: OrderStatus) => {
    if (!confirm(`주문 #${id}을(를) '${ORDER_STATUS_LABEL[next]}' 상태로 변경할까요?`)) return;
    setBusy(true);
    setError(null);
    const body: OrderStatusUpdateInput = { status: next };
    try {
      await apiPatch<Order>(`/api/orders/${id}/status`, body);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-xl font-bold">주문 관리</h1>
        <p className="text-sm text-gray-500">
          전체 주문을 상태별로 보고 배송을 진행합니다. 배송 상태는 결제완료 → 배송중 → 배송완료 순서로만 전진합니다(되돌리기 불가).
        </p>
      </div>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>
      )}

      {/* 상태 필터 */}
      <div className="mb-4 flex flex-wrap gap-2">
        {FILTERS.map((f) => (
          <button
            key={f.value}
            onClick={() => changeFilter(f.value)}
            className={`rounded-full border px-3 py-1 text-sm ${
              filter === f.value
                ? "border-gray-900 bg-gray-900 text-white"
                : "border-gray-300 bg-white text-gray-600 hover:bg-gray-100"
            }`}
          >
            {f.label}
          </button>
        ))}
      </div>

      {/* 목록 */}
      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
            <tr>
              <th className="px-4 py-3">주문</th>
              <th className="px-4 py-3">회원</th>
              <th className="px-4 py-3">상품</th>
              <th className="px-4 py-3">상태</th>
              <th className="px-4 py-3 text-right">금액</th>
              <th className="px-4 py-3">일시</th>
              <th className="px-4 py-3 text-right">처리</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading ? (
              <tr>
                <td colSpan={7} className="px-4 py-8 text-center text-gray-400">
                  불러오는 중…
                </td>
              </tr>
            ) : orders.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-4 py-8 text-center text-gray-400">
                  주문이 없습니다.
                </td>
              </tr>
            ) : (
              orders.map((o) => {
                const action = NEXT_ACTION[o.status];
                return (
                  <tr key={o.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 text-gray-400">#{o.id}</td>
                    <td className="px-4 py-3 text-gray-600">회원 #{o.memberId}</td>
                    <td className="px-4 py-3 font-medium text-gray-900">
                      {o.representativeProductName ?? <span className="text-gray-300">—</span>}
                      {o.itemCount > 1 && <span className="text-gray-400"> 외 {o.itemCount - 1}건</span>}
                    </td>
                    <td className="px-4 py-3">
                      <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${ORDER_STATUS_BADGE[o.status]}`}>
                        {ORDER_STATUS_LABEL[o.status]}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right text-gray-900">{o.totalPrice.toLocaleString()}원</td>
                    <td className="px-4 py-3 text-gray-500">
                      {new Date(o.createdAt).toLocaleString("ko-KR")}
                    </td>
                    <td className="px-4 py-3 text-right">
                      {action ? (
                        <button
                          onClick={() => advance(o.id, action.next)}
                          disabled={busy}
                          className="rounded bg-gray-900 px-3 py-1 text-xs text-white hover:bg-gray-700 disabled:opacity-50"
                        >
                          {action.label}
                        </button>
                      ) : (
                        <span className="text-xs text-gray-300">—</span>
                      )}
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {/* 페이지네이션 */}
      <div className="mt-4 flex items-center justify-between text-sm text-gray-500">
        <span>총 {totalElements.toLocaleString()}건</span>
        <div className="flex items-center gap-2">
          <button
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0 || loading}
            className="rounded border border-gray-300 px-3 py-1 hover:bg-gray-100 disabled:opacity-40"
          >
            이전
          </button>
          <span>{page + 1} 페이지</span>
          <button
            onClick={() => setPage((p) => p + 1)}
            disabled={!hasNext || loading}
            className="rounded border border-gray-300 px-3 py-1 hover:bg-gray-100 disabled:opacity-40"
          >
            다음
          </button>
        </div>
      </div>
    </div>
  );
}
