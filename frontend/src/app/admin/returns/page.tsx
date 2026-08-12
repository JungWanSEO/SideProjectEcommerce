"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { apiGet, apiPatch } from "@/lib/api";
import { PageResponse, ReturnAction, ReturnRequest, ReturnStatus, ReturnType } from "@/lib/types";

/**
 * 어드민 반품·교환 (/admin/returns, ADMIN).
 *
 * 셀러 콘솔이 "내 셀러 것"만 보는 것과 달리 여기는 <b>셀러 경계를 넘어</b> 전부 본다(운영 대행). 그래서 스코프가
 * 쿼리에 없고 경로 인가(ADMIN)가 유일한 방어선 — 백엔드에서 HTTP 레벨 테스트로 못 박아 뒀다.
 *
 * 대행 전이는 주문 경로(`PATCH /api/orders/{orderId}/returns/{returnId}/status`)를 쓴다 — 셀러가 응답하지 않거나
 * CS로 처리해야 할 때 운영자가 대신 진행하는 용도.
 */

const STATUS_LABEL: Record<ReturnStatus, string> = {
  REQUESTED: "요청 접수",
  APPROVED: "승인됨",
  PICKED_UP: "수거 완료",
  INSPECTED: "검수 완료",
  REFUNDED: "환불 완료",
  COMPLETED: "교환 완료",
  REJECTED: "거부",
};

const STATUS_BADGE: Record<ReturnStatus, string> = {
  REQUESTED: "bg-amber-50 text-amber-700",
  APPROVED: "bg-blue-50 text-blue-700",
  PICKED_UP: "bg-blue-50 text-blue-700",
  INSPECTED: "bg-indigo-50 text-indigo-700",
  REFUNDED: "bg-emerald-50 text-emerald-700",
  COMPLETED: "bg-emerald-50 text-emerald-700",
  REJECTED: "bg-red-50 text-red-700",
};

const ACTION_LABEL: Record<ReturnAction, string> = {
  APPROVE: "승인",
  REJECT: "거부",
  PICK_UP: "수거 완료",
  INSPECT: "검수 완료",
  REFUND: "환불 확정",
  COMPLETE: "교환 확정",
  SET_FAULT: "귀책 재정",
};

/** 귀책 주체 라벨(#8 후속) — 어드민은 종료 전이면 셀러 판정을 뒤집을 수 있다. */
const FAULT_LABEL: Record<string, string> = {
  CUSTOMER: "고객 귀책",
  SELLER: "셀러 귀책",
  PLATFORM: "플랫폼 귀책",
  NONE: "귀책 없음",
};

const REASON_LABEL: Record<string, string> = {
  CHANGE_OF_MIND: "단순 변심",
  WRONG_ORDER: "주문 실수",
  DELIVERY_DELAY: "배송 지연",
  OUT_OF_STOCK: "품절",
  DEFECTIVE: "상품 불량",
  WRONG_DELIVERY: "오배송",
  OTHER: "기타",
};

const STATUS_FILTERS: (ReturnStatus | "ALL")[] = [
  "ALL",
  "REQUESTED",
  "APPROVED",
  "PICKED_UP",
  "INSPECTED",
  "REFUNDED",
  "COMPLETED",
  "REJECTED",
];

/** 현재 상태에서 가능한 전이 — 서버 상태머신과 같은 규칙(셀러 화면과 동일). 종료 상태는 액션 없음. */
function actionsFor(r: ReturnRequest): ReturnAction[] {
  switch (r.status) {
    case "REQUESTED":
      return ["APPROVE", "REJECT"];
    case "APPROVED":
      return ["PICK_UP"];
    case "PICKED_UP":
      return ["INSPECT"];
    case "INSPECTED":
      return [r.type === "RETURN" ? "REFUND" : "COMPLETE", "REJECT"];
    default:
      return [];
  }
}

export default function AdminReturnsPage() {
  const [items, setItems] = useState<ReturnRequest[]>([]);
  const [status, setStatus] = useState<ReturnStatus | "ALL">("ALL");
  const [type, setType] = useState<ReturnType | "ALL">("ALL");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [totalElements, setTotalElements] = useState(0);

  const load = useCallback(() => {
    setLoading(true);
    const q = new URLSearchParams({ page: "0", size: "20" });
    if (status !== "ALL") q.set("status", status);
    if (type !== "ALL") q.set("type", type);
    apiGet<PageResponse<ReturnRequest>>(`/api/returns/admin?${q.toString()}`)
      .then((p) => {
        setItems(p.content);
        setTotalElements(p.totalElements);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [status, type]);

  useEffect(() => {
    load();
  }, [load]);

  const advance = async (r: ReturnRequest, action: ReturnAction, faultParty?: string) => {
    // 귀책 재정(SET_FAULT)은 상태를 바꾸지 않으므로 "되돌릴 수 없습니다" 경고가 맞지 않는다.
    if (action !== "SET_FAULT"
        && !confirm(`[대행] ${ACTION_LABEL[action]} 처리하시겠어요? 되돌릴 수 없습니다.`)) return;
    const memo = action === "REJECT" ? prompt("거부 사유(선택)") ?? undefined : undefined;
    setBusyId(r.id);
    setError(null);
    try {
      // 대행 전이는 주문 경로 — 반품이 그 주문 소속인지 서버가 검증한다(경로 불일치면 404).
      const updated = await apiPatch<ReturnRequest>(
        `/api/orders/${r.orderId}/returns/${r.id}/status`,
        { action, memo, faultParty }
      );
      setItems((prev) => prev.map((it) => (it.id === updated.id ? updated : it)));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div>
      <div className="mb-6 flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-bold text-ink">반품 · 교환</h1>
          <p className="mt-1 text-sm text-gray-500">
            전체 셀러의 반품·교환입니다. 셀러가 처리하지 못한 건을 운영자가 대행할 수 있습니다.
          </p>
        </div>
        <span className="text-sm text-gray-500">{totalElements.toLocaleString()}건</span>
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-3">
        <div className="flex flex-wrap gap-1">
          {STATUS_FILTERS.map((s) => (
            <button
              key={s}
              onClick={() => setStatus(s)}
              className={`rounded-full px-3 py-1.5 text-sm ${
                status === s ? "bg-gray-900 text-white" : "text-gray-600 hover:bg-gray-100"
              }`}
            >
              {s === "ALL" ? "전체" : STATUS_LABEL[s]}
            </button>
          ))}
        </div>
        <select
          value={type}
          onChange={(e) => setType(e.target.value as ReturnType | "ALL")}
          aria-label="유형"
          className="rounded border border-gray-300 px-2 py-1.5 text-sm"
        >
          <option value="ALL">유형 전체</option>
          <option value="RETURN">반품</option>
          <option value="EXCHANGE">교환</option>
        </select>
      </div>

      {error && <p className="mb-4 rounded-lg bg-red-50 px-4 py-2 text-sm text-red-700">{error}</p>}

      {loading ? (
        <p className="py-16 text-center text-sm text-gray-400">불러오는 중…</p>
      ) : items.length === 0 ? (
        <p className="py-16 text-center text-sm text-gray-400">해당 조건의 반품·교환이 없습니다.</p>
      ) : (
        <div className="overflow-x-auto rounded-2xl border border-gray-200 bg-white">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-100 text-left text-xs text-gray-500">
                <th className="px-4 py-3">상태</th>
                <th className="px-4 py-3">유형</th>
                <th className="px-4 py-3">주문 / 셀러</th>
                <th className="px-4 py-3">사유</th>
                <th className="px-4 py-3 text-right">환불액</th>
                <th className="px-4 py-3">신청일</th>
                <th className="px-4 py-3 text-right">대행 처리</th>
              </tr>
            </thead>
            <tbody>
              {items.map((r) => (
                <tr key={r.id} className="border-b border-gray-50 last:border-0">
                  <td className="px-4 py-3">
                    <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${STATUS_BADGE[r.status]}`}>
                      {STATUS_LABEL[r.status]}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-600">{r.type === "RETURN" ? "반품" : "교환"}</td>
                  <td className="px-4 py-3">
                    <Link href={`/admin/orders?keyword=${r.orderId}`} className="text-gray-700 underline">
                      #{r.orderId}
                    </Link>
                    <span className="ml-2 text-xs text-gray-400">
                      셀러 {r.sellerId ?? "플랫폼"}
                    </span>
                  </td>
                  <td className="max-w-xs px-4 py-3">
                    {r.reasonCode && (
                      <span className="mr-1 font-medium text-ink">
                        {REASON_LABEL[r.reasonCode] ?? r.reasonCode}
                      </span>
                    )}
                    <span className="text-gray-500">{r.reason}</span>
                    {/*
                      귀책 재정(#8 후속) — 셀러가 검수에서 확정하지만 자기 이익 방향으로 판정할 수 있어
                      어드민이 종료 전까지 뒤집을 수 있다. 모든 재정은 이력에 남는다.
                    */}
                    <div className="mt-1 flex items-center gap-1">
                      <span className="text-xs text-gray-400">
                        {FAULT_LABEL[r.effectiveFault] ?? r.effectiveFault}
                        {r.faultParty == null && " (신고 기준)"}
                      </span>
                      {actionsFor(r).length > 0 && (
                        <select
                          value=""
                          onChange={(e) => e.target.value && advance(r, "SET_FAULT", e.target.value)}
                          disabled={busyId === r.id}
                          className="rounded border border-gray-200 px-1 py-0.5 text-xs disabled:opacity-50"
                          aria-label="귀책 재정"
                        >
                          <option value="">재정…</option>
                          <option value="CUSTOMER">고객 귀책</option>
                          <option value="SELLER">셀러 귀책</option>
                          <option value="PLATFORM">플랫폼 귀책</option>
                          <option value="NONE">귀책 없음</option>
                        </select>
                      )}
                    </div>
                  </td>
                  <td className="px-4 py-3 text-right text-gray-700">
                    {r.refundAmount != null ? `${r.refundAmount.toLocaleString()}원` : "—"}
                    {r.returnShippingCharged != null && r.returnShippingCharged > 0 && (
                      <div className="text-xs text-amber-700">
                        회수비 −{r.returnShippingCharged.toLocaleString()}
                      </div>
                    )}
                  </td>
                  <td className="px-4 py-3 text-xs text-gray-500">
                    {new Date(r.createdAt).toLocaleDateString("ko-KR")}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex justify-end gap-1">
                      {actionsFor(r).length === 0 ? (
                        <span className="text-xs text-gray-400">완료</span>
                      ) : (
                        actionsFor(r).filter((a) => a !== "SET_FAULT").map((a) => (
                          <button
                            key={a}
                            onClick={() => advance(r, a)}
                            disabled={busyId === r.id}
                            className={`rounded px-2 py-1 text-xs font-medium transition disabled:opacity-50 ${
                              a === "REJECT"
                                ? "border border-red-200 text-red-600 hover:bg-red-50"
                                : "bg-gray-900 text-white hover:bg-gray-700"
                            }`}
                          >
                            {ACTION_LABEL[a]}
                          </button>
                        ))
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
