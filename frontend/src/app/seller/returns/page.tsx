"use client";

import { useCallback, useEffect, useState } from "react";
import { apiGet, apiPatch } from "@/lib/api";
import {
  PageResponse,
  ReturnAction,
  ReturnRequest,
  ReturnStatus,
} from "@/lib/types";

/**
 * 셀러 "반품/교환 처리" (/seller/returns, SELLER).
 *
 * 백엔드(#3)는 요청→승인→수거→검수→환불/교환의 상태머신을 이미 강제한다. 이 화면은 그 전이를 <b>버튼으로</b>
 * 노출할 뿐이고, 무엇이 가능한지의 진실은 서버에 있다(잘못된 순서면 409). 그래서 버튼은 현재 상태에서
 * 가능한 액션만 그리고, 실패 메시지는 서버 문구를 그대로 보여준다.
 *
 * 스코프는 서버가 강제한다 — 셀러는 자기 sellerId의 반품만 조회·처리(남의 것은 403).
 */

/** 상태 라벨·색 — 진행 중(파랑)·종료(회색/초록)·거부(빨강)로 한눈에 구분. */
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
  SET_FAULT: "귀책 재정",   // 셀러에겐 노출되지 않는다(ADMIN 전용)
};

/** 귀책 주체 라벨(#8 후속) — 검수에서 확정한다. 회수비를 누가 무는지가 여기서 갈린다. */
const FAULT_LABEL: Record<string, string> = {
  CUSTOMER: "고객 귀책",
  SELLER: "셀러 귀책",
  PLATFORM: "플랫폼 귀책",
  NONE: "귀책 없음",
};

/** 사유 코드 → 한글(백엔드 CancelReason과 1:1). 자유텍스트 reason과 별개인 구조화 사유(#8). */
const REASON_LABEL: Record<string, string> = {
  CHANGE_OF_MIND: "단순 변심",
  WRONG_ORDER: "주문 실수",
  DELIVERY_DELAY: "배송 지연",
  OUT_OF_STOCK: "품절",
  DEFECTIVE: "상품 불량",
  WRONG_DELIVERY: "오배송",
  OTHER: "기타",
};

/**
 * 현재 상태에서 가능한 액션 — 서버 상태머신과 같은 규칙을 화면에도 둔다(버튼을 눌러야 알 수 있게 하지 않는다).
 * 종료 상태(REFUNDED/COMPLETED/REJECTED)는 빈 배열 → 액션 없음.
 */
function actionsFor(r: ReturnRequest): ReturnAction[] {
  switch (r.status) {
    case "REQUESTED":
      return ["APPROVE", "REJECT"];
    case "APPROVED":
      return ["PICK_UP"];
    case "PICKED_UP":
      return ["INSPECT"];
    case "INSPECTED":
      // 검수 후 갈래가 나뉜다: 반품이면 환불 확정, 교환이면 대체품 재출고. 불합격이면 거부.
      return [r.type === "RETURN" ? "REFUND" : "COMPLETE", "REJECT"];
    default:
      return [];
  }
}

export default function SellerReturnsPage() {
  const [items, setItems] = useState<ReturnRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  // 검수 시 고를 귀책(반품별). 비워 두면 구매자 신고 사유에서 파생된다(#8 후속).
  const [faultChoice, setFaultChoice] = useState<Record<number, string | undefined>>({});
  const [page, setPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [totalElements, setTotalElements] = useState(0);

  const load = useCallback(() => {
    setLoading(true);
    apiGet<PageResponse<ReturnRequest>>(`/api/seller/me/returns?page=${page}&size=20`)
      .then((p) => {
        setItems(p.content);
        setHasNext(p.hasNext);
        setTotalElements(p.totalElements);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [page]);

  useEffect(() => {
    load();
  }, [load]);

  const advance = async (r: ReturnRequest, action: ReturnAction) => {
    // 환불·교환 확정은 돈·재고가 움직인다 → 한 번 더 확인(되돌릴 수 없는 전이).
    if ((action === "REFUND" || action === "COMPLETE" || action === "REJECT") &&
        !confirm(`${ACTION_LABEL[action]} 처리하시겠어요? 되돌릴 수 없습니다.`)) {
      return;
    }
    const memo = action === "REJECT" ? prompt("거부 사유(선택)") ?? undefined : undefined;
    // 검수에서 귀책을 확정한다(#8 후속). 비워 두면 구매자가 신고한 사유에서 파생되므로,
    // 이견이 있을 때만 고르면 된다. 이 판정이 회수비를 누가 무는지를 결정한다.
    const faultParty = action === "INSPECT" ? faultChoice[r.id] : undefined;
    setBusyId(r.id);
    setError(null);
    try {
      const updated = await apiPatch<ReturnRequest>(`/api/seller/me/returns/${r.id}/status`, {
        action,
        memo,
        faultParty,
      });
      setItems((prev) => prev.map((it) => (it.id === updated.id ? updated : it)));
    } catch (e) {
      setError((e as Error).message); // 상태머신 위반(409)·소유권(403) 등 서버 문구 그대로
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
            내 셀러 상품의 반품/교환 요청입니다. 승인 → 수거 → 검수 → 환불(또는 교환 재출고) 순으로 처리합니다.
          </p>
        </div>
        <span className="text-sm text-gray-500">{totalElements.toLocaleString()}건</span>
      </div>

      {error && <p className="mb-4 rounded-lg bg-red-50 px-4 py-2 text-sm text-red-700">{error}</p>}

      {loading ? (
        <p className="py-16 text-center text-sm text-gray-400">불러오는 중…</p>
      ) : items.length === 0 ? (
        <p className="py-16 text-center text-sm text-gray-400">처리할 반품/교환 요청이 없습니다.</p>
      ) : (
        <ul className="flex flex-col gap-3">
          {items.map((r) => {
            const actions = actionsFor(r);
            return (
              <li key={r.id} className="rounded-2xl border border-gray-200 bg-white p-5">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${STATUS_BADGE[r.status]}`}>
                        {STATUS_LABEL[r.status]}
                      </span>
                      <span className="rounded-full bg-gray-100 px-2.5 py-0.5 text-xs font-medium text-gray-700">
                        {r.type === "RETURN" ? "반품" : "교환"}
                      </span>
                      <span className="text-sm text-gray-500">
                        주문 #{r.orderId} · 항목 #{r.orderItemId} · {r.quantity}개
                      </span>
                    </div>
                    <p className="mt-2 text-sm text-ink">
                      {r.reasonCode && (
                        <span className="mr-2 font-medium">
                          {REASON_LABEL[r.reasonCode] ?? r.reasonCode}
                        </span>
                      )}
                      {r.reason || <span className="text-gray-400">상세 사유 없음</span>}
                    </p>
                    <p className="mt-1 text-xs text-gray-400">
                      요청 {new Date(r.createdAt).toLocaleString("ko-KR")}
                      {r.faultParty != null && <> · {FAULT_LABEL[r.faultParty] ?? r.faultParty}</>}
                      {r.refundAmount != null && (
                        <> · 환불 확정 {r.refundAmount.toLocaleString()}원</>
                      )}
                      {r.returnShippingCharged != null && r.returnShippingCharged > 0 && (
                        <> · 회수비 {r.returnShippingCharged.toLocaleString()}원 차감</>
                      )}
                      {r.exchangeShipmentId != null && <> · 교환 재출고 #{r.exchangeShipmentId}</>}
                    </p>
                  </div>

                  <div className="flex shrink-0 flex-wrap items-center gap-2">
                    {/*
                      검수 시 귀책 판정(#8 후속). 비워 두면 구매자 신고 사유에서 파생된다 —
                      "열어보니 하자가 아니더라"를 표현할 자리가 이 셀렉트다. 이 판정이 회수비를
                      누가 무는지를 결정하고, 모든 판정은 이력에 남는다(어드민이 뒤집을 수 있다).
                    */}
                    {actions.includes("INSPECT") && (
                      <select
                        value={faultChoice[r.id] ?? ""}
                        onChange={(e) =>
                          setFaultChoice((prev) => ({ ...prev, [r.id]: e.target.value || undefined }))
                        }
                        disabled={busyId === r.id}
                        className="rounded-lg border border-gray-200 px-2 py-1.5 text-sm disabled:opacity-50"
                        aria-label="귀책 판정"
                      >
                        <option value="">신고대로 ({FAULT_LABEL[r.effectiveFault] ?? r.effectiveFault})</option>
                        <option value="CUSTOMER">고객 귀책</option>
                        <option value="SELLER">셀러 귀책</option>
                        <option value="NONE">귀책 없음</option>
                      </select>
                    )}
                    {actions.length === 0 ? (
                      <span className="text-xs text-gray-400">처리 완료</span>
                    ) : (
                      actions.map((a) => (
                        <button
                          key={a}
                          onClick={() => advance(r, a)}
                          disabled={busyId === r.id}
                          className={`rounded-lg px-3 py-1.5 text-sm font-medium transition disabled:opacity-50 ${
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
                </div>

                {/* 전이 이력 — 누가 언제 무엇을 했는지(엔티티가 전이마다 append). */}
                {r.statusHistory.length > 0 && (
                  <ol className="mt-3 flex flex-wrap gap-x-3 gap-y-1 border-t border-gray-100 pt-3 text-xs text-gray-500">
                    {r.statusHistory.map((h, i) => (
                      <li key={i}>
                        {STATUS_LABEL[h.toStatus]}
                        <span className="ml-1 text-gray-400">
                          {new Date(h.createdAt).toLocaleDateString("ko-KR")}
                        </span>
                        {h.memo && <span className="ml-1 text-gray-400">({h.memo})</span>}
                      </li>
                    ))}
                  </ol>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {(page > 0 || hasNext) && (
        <div className="mt-6 flex justify-center gap-2">
          <button
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0}
            className="rounded-lg border border-gray-200 px-3 py-1.5 text-sm disabled:opacity-40"
          >
            이전
          </button>
          <span className="px-2 py-1.5 text-sm text-gray-500">{page + 1}</span>
          <button
            onClick={() => setPage((p) => p + 1)}
            disabled={!hasNext}
            className="rounded-lg border border-gray-200 px-3 py-1.5 text-sm disabled:opacity-40"
          >
            다음
          </button>
        </div>
      )}
    </div>
  );
}
