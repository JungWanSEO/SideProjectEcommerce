"use client";

import { useCallback, useEffect, useState } from "react";
import { apiGet, apiPatch } from "@/lib/api";
import { PageResponse, SellerShipment, ShipmentStatus } from "@/lib/types";

/**
 * 셀러 "출고 관리" (/seller/shipments, SELLER).
 *
 * 멀티셀러 주문에서 배송 단위는 주문이 아니라 <b>셀러별 shipment</b>다(#1). 셀러는 자기 shipment만 보고
 * 전진시킨다 — 목록 쿼리가 sellerId로 좁히고, 전이 API가 소유권을 다시 검증한다(이중 방어).
 *
 * 전이는 forward-only(PAID → SHIPPING → DELIVERED). 출고 시작할 때 택배사·운송장을 함께 보내면 저장된다.
 */

const STATUS_LABEL: Record<ShipmentStatus, string> = {
  PAID: "출고 대기",
  SHIPPING: "배송중",
  DELIVERED: "배송완료",
  CANCELLED: "취소됨",
};

const STATUS_BADGE: Record<ShipmentStatus, string> = {
  PAID: "bg-amber-50 text-amber-700",
  SHIPPING: "bg-blue-50 text-blue-700",
  DELIVERED: "bg-emerald-50 text-emerald-700",
  CANCELLED: "bg-gray-100 text-gray-500",
};

const FILTERS: { value: ShipmentStatus | "ALL"; label: string }[] = [
  { value: "ALL", label: "전체" },
  { value: "PAID", label: "출고 대기" },
  { value: "SHIPPING", label: "배송중" },
  { value: "DELIVERED", label: "배송완료" },
];

export default function SellerShipmentsPage() {
  const [items, setItems] = useState<SellerShipment[]>([]);
  const [filter, setFilter] = useState<ShipmentStatus | "ALL">("PAID"); // 기본은 "지금 보내야 할 것"
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  // 출고 시작 입력(배송 건별) — 운송장은 건마다 다르므로 shipmentId로 보관한다.
  const [shipInput, setShipInput] = useState<{ id: number; courier: string; tracking: string } | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    const q = new URLSearchParams({ page: "0", size: "20" });
    if (filter !== "ALL") q.set("status", filter);
    apiGet<PageResponse<SellerShipment>>(`/api/seller/me/shipments?${q.toString()}`)
      .then((p) => setItems(p.content))
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [filter]);

  useEffect(() => {
    load();
  }, [load]);

  const advance = async (s: SellerShipment, next: ShipmentStatus, courier?: string, tracking?: string) => {
    setBusyId(s.shipmentId);
    setError(null);
    try {
      await apiPatch<SellerShipment>(`/api/seller/me/shipments/${s.shipmentId}/status`, {
        status: next,
        courier: courier?.trim() || null,
        trackingNumber: tracking?.trim() || null,
      });
      setShipInput(null);
      load(); // 상태 필터가 걸려 있으면 목록에서 빠질 수 있어 다시 불러온다
    } catch (e) {
      setError((e as Error).message); // forward-only 위반(409)·소유권(403) 등 서버 문구 그대로
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-ink">출고 관리</h1>
        <p className="mt-1 text-sm text-gray-500">
          내 셀러의 배송 건입니다. 한 주문에 여러 셀러가 섞여 있어도 <b>내 몫만</b> 따로 출고합니다.
        </p>
      </div>

      <div className="mb-4 flex flex-wrap gap-1">
        {FILTERS.map((f) => (
          <button
            key={f.value}
            onClick={() => setFilter(f.value)}
            className={`rounded-full px-3 py-1.5 text-sm ${
              filter === f.value ? "bg-gray-900 text-white" : "text-gray-600 hover:bg-gray-100"
            }`}
          >
            {f.label}
          </button>
        ))}
      </div>

      {error && <p className="mb-4 rounded-lg bg-red-50 px-4 py-2 text-sm text-red-700">{error}</p>}

      {loading ? (
        <p className="py-16 text-center text-sm text-gray-400">불러오는 중…</p>
      ) : items.length === 0 ? (
        <p className="py-16 text-center text-sm text-gray-400">
          {filter === "PAID" ? "출고할 배송 건이 없습니다." : "해당 상태의 배송 건이 없습니다."}
        </p>
      ) : (
        <ul className="flex flex-col gap-3">
          {items.map((s) => (
            <li key={s.shipmentId} className="rounded-2xl border border-gray-200 bg-white p-5">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${STATUS_BADGE[s.status]}`}>
                      {STATUS_LABEL[s.status]}
                    </span>
                    {s.kind === "EXCHANGE" && (
                      <span className="rounded-full bg-purple-50 px-2.5 py-0.5 text-xs font-medium text-purple-700">
                        교환 재출고
                      </span>
                    )}
                    <span className="text-sm text-gray-500">
                      주문 #{s.orderId} · 배송 #{s.shipmentId}
                    </span>
                  </div>

                  {/* 보낼 품목 — 내 셀러 항목만 담겨 온다(응답 스코프) */}
                  <ul className="mt-2 flex flex-col gap-0.5 text-sm text-ink">
                    {s.items.map((line) => (
                      <li key={line.orderItemId} className={line.status !== "ACTIVE" ? "text-gray-400 line-through" : ""}>
                        {line.productName} · {line.size} · {line.quantity}개
                      </li>
                    ))}
                  </ul>

                  {s.shipping && (
                    <p className="mt-2 text-xs text-gray-500">
                      {s.shipping.recipient} · {s.shipping.phone} · ({s.shipping.zipcode}){" "}
                      {s.shipping.address1} {s.shipping.address2}
                      {s.shipping.deliveryMemo && (
                        <span className="ml-1 text-gray-400">“{s.shipping.deliveryMemo}”</span>
                      )}
                    </p>
                  )}
                  {s.trackingNumber && (
                    <p className="mt-1 text-xs text-gray-500">
                      {s.courier} {s.trackingNumber}
                    </p>
                  )}
                </div>

                <div className="flex shrink-0 flex-col items-end gap-2">
                  {s.status === "PAID" &&
                    (shipInput?.id === s.shipmentId ? (
                      <div className="flex flex-wrap items-center justify-end gap-2">
                        <input
                          value={shipInput.courier}
                          onChange={(e) => setShipInput({ ...shipInput, courier: e.target.value })}
                          placeholder="택배사"
                          className="w-24 rounded border border-gray-300 px-2 py-1 text-sm"
                        />
                        <input
                          value={shipInput.tracking}
                          onChange={(e) => setShipInput({ ...shipInput, tracking: e.target.value })}
                          placeholder="운송장 번호"
                          className="w-36 rounded border border-gray-300 px-2 py-1 text-sm"
                        />
                        <button
                          onClick={() => advance(s, "SHIPPING", shipInput.courier, shipInput.tracking)}
                          disabled={busyId === s.shipmentId}
                          className="rounded-lg bg-gray-900 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
                        >
                          출고
                        </button>
                        <button
                          onClick={() => setShipInput(null)}
                          className="text-sm text-gray-500 hover:underline"
                        >
                          취소
                        </button>
                      </div>
                    ) : (
                      <button
                        onClick={() => setShipInput({ id: s.shipmentId, courier: "", tracking: "" })}
                        className="rounded-lg bg-gray-900 px-3 py-1.5 text-sm font-medium text-white"
                      >
                        출고 시작
                      </button>
                    ))}

                  {s.status === "SHIPPING" && (
                    <button
                      onClick={() => advance(s, "DELIVERED")}
                      disabled={busyId === s.shipmentId}
                      className="rounded-lg bg-gray-900 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
                    >
                      배송완료 처리
                    </button>
                  )}

                  {(s.status === "DELIVERED" || s.status === "CANCELLED") && (
                    <span className="text-xs text-gray-400">
                      {s.status === "DELIVERED" && s.deliveredAt
                        ? `${new Date(s.deliveredAt).toLocaleDateString("ko-KR")} 완료`
                        : "처리 완료"}
                    </span>
                  )}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
