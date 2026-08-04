"use client";

import { useCallback, useEffect, useState } from "react";
import { apiGet } from "@/lib/api";
import { OrderStatus, OrderSummary, PageResponse } from "@/lib/types";
import { ORDER_STATUS_BADGE, ORDER_STATUS_LABEL } from "@/lib/orderStatus";

/**
 * 셀러 "내 주문" (/seller/orders, SELLER).
 * 내 셀러 상품이 하나라도 든 주문을 본다 — 무엇을 포장해 보낼지. 셀러 스코프는 서버가 강제한다.
 *
 * 조회 전용: 출고는 주문 단위가 아니라 <b>셀러별 배송 단위</b>(shipment)로 하므로 "출고 관리"(/seller/shipments)에서
 * 처리한다. 한 주문에 여러 셀러가 섞여도 각자 자기 몫만 전진시킨다(#1).
 */
const FILTERS: { value: OrderStatus | "ALL"; label: string }[] = [
  { value: "ALL", label: "전체" },
  { value: "PAID", label: "결제완료" },
  { value: "SHIPPING", label: "배송중" },
  { value: "DELIVERED", label: "배송완료" },
  { value: "CANCELLED", label: "취소됨" },
];

export default function SellerOrdersPage() {
  const [orders, setOrders] = useState<OrderSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [filter, setFilter] = useState<OrderStatus | "ALL">("ALL");
  const [keyword, setKeyword] = useState("");
  const [page, setPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [totalElements, setTotalElements] = useState(0);

  const load = useCallback(() => {
    setLoading(true);
    const q = new URLSearchParams({ page: String(page), size: "20" });
    if (filter !== "ALL") q.set("status", filter);
    if (keyword.trim()) q.set("keyword", keyword.trim());
    apiGet<PageResponse<OrderSummary>>(`/api/seller/me/orders?${q.toString()}`)
      .then((p) => {
        setOrders(p.content);
        setHasNext(p.hasNext);
        setTotalElements(p.totalElements);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [page, filter, keyword]);

  useEffect(() => {
    load();
  }, [load]);

  const changeFilter = (value: OrderStatus | "ALL") => {
    setFilter(value);
    setPage(0);
  };

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-xl font-bold">내 주문</h1>
        <p className="text-sm text-gray-500">
          내 셀러 상품이 포함된 주문입니다. 출고는 <b>출고 관리</b>에서 내 배송 건만 따로 처리합니다.
        </p>
      </div>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      <div className="mb-4 flex flex-wrap items-center gap-2">
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
        <span className="mx-1 h-4 w-px bg-gray-200" />
        <input
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") setPage(0);
          }}
          placeholder="수령인 · 주문번호"
          className="w-56 rounded border border-gray-300 bg-white px-3 py-1 text-sm text-gray-700"
        />
      </div>

      <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
            <tr>
              <th className="px-4 py-3">주문번호</th>
              <th className="px-4 py-3">주문일</th>
              <th className="px-4 py-3">대표상품</th>
              <th className="px-4 py-3 text-right">항목수</th>
              <th className="px-4 py-3 text-right">주문금액</th>
              <th className="px-4 py-3">상태</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading ? (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-gray-400">
                  불러오는 중…
                </td>
              </tr>
            ) : orders.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-gray-400">
                  주문이 없습니다.
                </td>
              </tr>
            ) : (
              orders.map((o) => (
                <tr key={o.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 font-medium text-gray-900">#{o.id}</td>
                  <td className="whitespace-nowrap px-4 py-3 text-gray-500">
                    {new Date(o.createdAt).toLocaleDateString("ko-KR")}
                  </td>
                  <td className="px-4 py-3 text-gray-600">{o.representativeProductName ?? "—"}</td>
                  <td className="px-4 py-3 text-right text-gray-500">{o.itemCount}</td>
                  <td className="px-4 py-3 text-right text-gray-800">
                    {o.totalPrice.toLocaleString()}원
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${ORDER_STATUS_BADGE[o.status]}`}
                    >
                      {ORDER_STATUS_LABEL[o.status]}
                    </span>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

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
