"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { apiGet, apiPost } from "@/lib/api";
import { Order, OrderItem, Product, ProductOption } from "@/lib/types";
import { ORDER_STATUS_LABEL, ORDER_STATUS_BADGE } from "@/lib/orderStatus";
import { useAuth } from "@/lib/auth";
import { loginHref } from "@/lib/useRequireAuth";
import { buttonClass } from "@/components/ui/Button";
import Skeleton from "@/components/ui/Skeleton";

/** 취소 사유 선택지(#8) — 값은 서버 CancelReason enum, 라벨은 구매자용. */
const CANCEL_REASONS = [
  { code: "CHANGE_OF_MIND", label: "단순 변심" },
  { code: "WRONG_ORDER", label: "주문 실수" },
  { code: "DELIVERY_DELAY", label: "배송 지연" },
  { code: "OUT_OF_STOCK", label: "품절" },
  { code: "OTHER", label: "기타" },
];

/** 반품 사유 선택지(#3+#8) — 배송 후 맥락이라 취소 사유와 구성이 다르다(불량·오배송 포함). */
const RETURN_REASONS = [
  { code: "CHANGE_OF_MIND", label: "단순 변심" },
  { code: "DEFECTIVE", label: "상품 불량" },
  { code: "WRONG_DELIVERY", label: "오배송" },
  { code: "OTHER", label: "기타" },
];

/** 교환 사유 선택지 — 같은 CancelReason enum이지만 교환 맥락의 라벨을 쓴다(사이즈 교환이 대부분). */
const EXCHANGE_REASONS = [
  { code: "CHANGE_OF_MIND", label: "사이즈·옵션이 안 맞아요" },
  { code: "DEFECTIVE", label: "상품 불량" },
  { code: "WRONG_DELIVERY", label: "오배송" },
  { code: "OTHER", label: "기타" },
];

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
  const [cancelReason, setCancelReason] = useState("CHANGE_OF_MIND"); // 취소 사유(#8, 기록·집계)
  // 반품·교환 신청(#3) — 대상 항목이 있으면 모달을 띄운다. 항목 객체를 그대로 들고 있는 이유는
  // 교환 탭이 productId(대체 옵션 조회)·optionId(현재 사이즈 제외)·quantity(재고 비교)를 모두 쓰기 때문.
  const [returnTarget, setReturnTarget] = useState<OrderItem | null>(null);
  const [returnType, setReturnType] = useState<"RETURN" | "EXCHANGE">("RETURN");
  const [returnReason, setReturnReason] = useState("CHANGE_OF_MIND");
  const [returnDetail, setReturnDetail] = useState("");
  const [returning, setReturning] = useState(false);
  // 교환 대체 옵션 — 상품 상세 API를 재사용해 가져온다(전용 엔드포인트 없이. options에 available·soldOut 포함).
  const [exchangeOptions, setExchangeOptions] = useState<ProductOption[] | null>(null);
  const [optionsError, setOptionsError] = useState<string | null>(null);
  const [exchangeOptionId, setExchangeOptionId] = useState<number | null>(null);

  useEffect(() => {
    if (!authLoading && !user) router.replace(loginHref(window.location.pathname + window.location.search));
  }, [authLoading, user, router]);

  useEffect(() => {
    if (!user) return;
    apiGet<Order>(`/api/orders/${id}`)
      .then(setOrder)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [user, id]);

  /**
   * 교환 탭을 처음 열 때만 대체 옵션을 조회한다(반품만 할 사람에게 불필요한 요청을 만들지 않도록 지연 로딩).
   * 판매중지·삭제된 상품이면 조회가 실패하는데, 그건 "교환 불가"로 안내하고 반품 탭은 그대로 쓸 수 있게 둔다.
   */
  useEffect(() => {
    if (!returnTarget || returnType !== "EXCHANGE" || exchangeOptions || optionsError) return;
    apiGet<Product>(`/api/products/${returnTarget.productId}`)
      .then((p) => setExchangeOptions(p.options))
      .catch(() => setOptionsError("이 상품은 현재 교환할 수 있는 옵션을 불러올 수 없습니다."));
  }, [returnTarget, returnType, exchangeOptions, optionsError]);

  /** 모달을 열 때마다 상태를 초기화 — 이전 항목의 사유·선택 옵션이 남아 다른 항목에 적용되는 것을 막는다. */
  const openReturnModal = (item: OrderItem) => {
    setReturnTarget(item);
    setReturnType("RETURN");
    setReturnReason("CHANGE_OF_MIND");
    setReturnDetail("");
    setExchangeOptions(null);
    setOptionsError(null);
    setExchangeOptionId(null);
  };

  /**
   * 반품·교환 신청(#3) — 자격(활성 항목·배송완료·기한 7일)의 최종 판정은 서버가 한다.
   * 신청이 접수되면 셀러가 승인→수거→검수→환불(반품)/재출고(교환)로 진행하므로, 여기선 접수 결과만 알려준다.
   */
  const requestReturn = async () => {
    if (!returnTarget) return;
    if (returnType === "EXCHANGE" && exchangeOptionId == null) {
      setError("교환할 사이즈를 선택해 주세요.");
      return;
    }
    setReturning(true);
    setError(null);
    try {
      await apiPost(`/api/orders/${id}/returns`, {
        orderItemId: returnTarget.id,
        type: returnType,
        reason: returnDetail.trim() || null,
        reasonCode: returnReason,
        exchangeOptionId: returnType === "EXCHANGE" ? exchangeOptionId : null,
      });
      setReturnTarget(null);
      setReturnDetail("");
      alert(
        returnType === "EXCHANGE"
          ? "교환 신청이 접수되었습니다. 셀러 확인 후 수거·검수를 거쳐 새 상품이 발송됩니다."
          : "반품 신청이 접수되었습니다. 셀러 확인 후 수거가 진행됩니다.",
      );
      const refreshed = await apiGet<Order>(`/api/orders/${id}`);
      setOrder(refreshed);
    } catch (e) {
      setError((e as Error).message); // 기한 초과·중복 요청·품절(409) 등 서버 문구 그대로
    } finally {
      setReturning(false);
    }
  };

  const cancel = async () => {
    // PAID 주문 취소는 환불까지 동반 → 문구로 구분
    const msg = order?.status === "PAID" ? "결제를 취소하고 환불하시겠어요?" : "주문을 취소하시겠어요?";
    if (!confirm(msg)) return;
    setCancelling(true);
    setError(null);
    try {
      const updated = await apiPost<Order>(`/api/orders/${id}/cancel`, { reason: cancelReason });
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
      const updated = await apiPost<Order>(`/api/orders/${id}/items/${itemId}/cancel`, { reason: cancelReason });
      setOrder(updated);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setCancelling(false);
    }
  };

  if (authLoading || (user && loading))
    return (
      <main className="mx-auto max-w-3xl px-6 py-10">
        <Skeleton className="h-4 w-20" />

        <div className="mt-5 flex items-center justify-between">
          <Skeleton className="h-9 w-40" />
          <Skeleton className="h-6 w-16 rounded-full" />
        </div>
        <Skeleton className="mt-1 h-4 w-36" />

        <ul className="mt-6 overflow-hidden rounded-2xl border border-line bg-paper">
          {Array.from({ length: 2 }).map((_, i) => (
            <li
              key={i}
              className={`flex items-center justify-between px-5 py-4 ${i > 0 ? "border-t border-line" : ""}`}
            >
              <div>
                <Skeleton className="h-6 w-40" />
                <Skeleton className="mt-1.5 h-4 w-48" />
              </div>
              <Skeleton className="h-5 w-20" />
            </li>
          ))}
        </ul>

        <div className="mt-6 border-t border-line pt-5">
          <div className="flex items-center justify-between">
            <Skeleton className="h-4 w-16" />
            <Skeleton className="h-8 w-32" />
          </div>
        </div>

        <section className="mt-6 rounded-2xl border border-line bg-paper p-5">
          <Skeleton className="mb-3 h-6 w-20" />
          <Skeleton className="h-5 w-44" />
          <Skeleton className="mt-2 h-4 w-56" />
        </section>

        <div className="mt-7 flex gap-3">
          <Skeleton className="h-11 w-28 rounded-full" />
          <Skeleton className="h-11 w-28 rounded-full" />
        </div>
      </main>
    );
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
              it.status !== "ACTIVE" ? "opacity-50" : ""
            }`}
          >
            <div>
              <p className="font-serif text-lg text-ink">
                {it.productName}
                {it.status === "CANCELLED" && (
                  <span className="ml-2 rounded bg-line px-1.5 py-0.5 text-xs text-muted">취소됨</span>
                )}
                {it.status === "RETURNED" && (
                  <span className="ml-2 rounded bg-line px-1.5 py-0.5 text-xs text-muted">반품됨</span>
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
              {/* 배송완료(DELIVERED) 항목만 반품·교환 신청 — 자격(활성·배송완료·7일)의 최종 판정은 서버가 한다(#3). */}
              {order.status === "DELIVERED" && it.status === "ACTIVE" && (
                <button
                  onClick={() => openReturnModal(it)}
                  className="rounded-full border border-line px-3 py-1 text-xs text-muted transition hover:border-clay hover:text-clay"
                >
                  반품·교환
                </button>
              )}
            </div>
          </li>
        ))}
      </ul>

      <div className="mt-6 border-t border-line pt-5">
        {(order.discountAmount > 0 || order.shippingFee > 0) && (
          <div className="flex justify-between text-sm">
            <span className="text-muted">상품 합계</span>
            <span className="text-ink">{order.totalPrice.toLocaleString()}원</span>
          </div>
        )}
        {order.discountAmount > 0 && (
          <div className="mt-1 flex justify-between text-sm">
            <span className="text-sage-600">
              쿠폰 할인{order.couponCode ? ` (${order.couponCode})` : ""}
            </span>
            <span className="font-medium text-sage-600">−{order.discountAmount.toLocaleString()}원</span>
          </div>
        )}
        {order.shippingFee > 0 && (
          <div className="mt-1 flex justify-between text-sm">
            <span className="text-muted">배송비</span>
            <span className="text-ink">{order.shippingFee.toLocaleString()}원</span>
          </div>
        )}
        <div className="mt-2 flex items-center justify-between">
          <span className="text-muted">
            {order.discountAmount > 0 || order.shippingFee > 0 ? "결제 금액" : "합계"}
          </span>
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

      {/* 송장 (배송 시작 후 택배사·운송장이 입력됐을 때만) */}
      {(order.courier || order.trackingNumber) && (
        <section className="mt-6 rounded-2xl border border-line bg-paper p-5">
          <h2 className="mb-2 font-serif text-lg text-ink">배송 정보</h2>
          {order.courier && (
            <p className="text-sm text-ink/80">
              택배사 <span className="ml-2 font-medium text-ink">{order.courier}</span>
            </p>
          )}
          {order.trackingNumber && (
            <p className="mt-1 text-sm text-ink/80">
              운송장 <span className="ml-2 font-medium text-ink">{order.trackingNumber}</span>
            </p>
          )}
        </section>
      )}

      {/* 주문 진행 타임라인 (상태 이력) */}
      {order.statusHistory.length > 0 && (
        <section className="mt-6 rounded-2xl border border-line bg-paper p-5">
          <h2 className="mb-3 font-serif text-lg text-ink">주문 진행 상황</h2>
          <ol className="flex flex-col gap-3">
            {order.statusHistory.map((h, i) => (
              <li key={i} className="flex items-start gap-3">
                <span
                  className={`mt-1 h-2 w-2 shrink-0 rounded-full ${
                    i === order.statusHistory.length - 1 ? "bg-clay" : "bg-line"
                  }`}
                />
                <div>
                  <p className="text-sm font-medium text-ink">{ORDER_STATUS_LABEL[h.toStatus]}</p>
                  {h.memo && <p className="text-xs text-muted">{h.memo}</p>}
                  <p className="text-xs text-muted">{new Date(h.createdAt).toLocaleString("ko-KR")}</p>
                </div>
              </li>
            ))}
          </ol>
        </section>
      )}

      {error && <p className="mt-4 text-sm text-danger">{error}</p>}

      <div className="mt-7 flex flex-wrap items-center gap-3">
        {/* PENDING(결제대기): 결제하기 + 취소 / PAID(결제완료): 취소=환불 / CANCELLED: 버튼 없음 */}
        {order.status === "PENDING" && (
          <Link href={`/orders/${order.id}/pay`} className={buttonClass("primary", "md")}>
            결제하기
          </Link>
        )}
        {(order.status === "PENDING" || order.status === "PAID") && (
          <>
            {/* 취소 사유(#8) — 항목 부분취소에도 같은 사유가 적용된다 */}
            <select
              value={cancelReason}
              onChange={(e) => setCancelReason(e.target.value)}
              disabled={cancelling}
              aria-label="취소 사유"
              className="rounded-full border border-line px-3 py-2.5 text-sm text-ink"
            >
              {CANCEL_REASONS.map((r) => (
                <option key={r.code} value={r.code}>{r.label}</option>
              ))}
            </select>
            <button
              onClick={cancel}
              disabled={cancelling}
              className="rounded-full border border-line px-5 py-2.5 text-sm text-muted transition hover:border-danger hover:text-danger disabled:opacity-50"
            >
              {cancelling ? "취소 처리 중…" : "주문 취소"}
            </button>
          </>
        )}
      </div>

      {/* 반품·교환 신청 모달(#3) — 사유는 구조화 코드(집계용) + 상세 텍스트. 교환은 같은 상품의 다른 사이즈를 고른다. */}
      {returnTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 p-4">
          <div className="max-h-[90vh] w-full max-w-md overflow-y-auto rounded-2xl bg-paper p-6">
            <h2 className="font-serif text-xl text-ink">반품·교환 신청</h2>
            <p className="mt-1 text-sm text-muted">
              {returnTarget.productName} · 사이즈 {returnTarget.size} · {returnTarget.quantity}개
            </p>

            {/* 반품/교환 선택 — 접수 후 흐름이 갈린다(반품=환불, 교환=대체품 재출고). */}
            <div className="mt-4 flex gap-2">
              {(
                [
                  { value: "RETURN", label: "반품 (환불)" },
                  { value: "EXCHANGE", label: "교환 (사이즈 변경)" },
                ] as const
              ).map((t) => (
                <button
                  key={t.value}
                  onClick={() => {
                    setReturnType(t.value);
                    setReturnReason("CHANGE_OF_MIND");
                    setError(null);
                  }}
                  disabled={returning}
                  className={`flex-1 rounded-full border px-3 py-2 text-sm transition disabled:opacity-50 ${
                    returnType === t.value
                      ? "border-clay bg-clay/10 font-medium text-clay"
                      : "border-line text-muted hover:text-ink"
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>

            <p className="mt-3 text-sm text-muted">
              {returnType === "EXCHANGE"
                ? "접수 후 셀러가 확인하면 수거가 진행됩니다. 검수가 끝나면 선택한 사이즈로 재발송됩니다(추가 결제 없음)."
                : "접수 후 셀러가 확인하면 수거가 진행됩니다. 검수가 끝나면 환불됩니다."}
            </p>

            {/* 교환 대체 옵션 — 현재 사이즈는 제외, 재고 부족은 비활성(서버도 신청 시점에 다시 검증한다). */}
            {returnType === "EXCHANGE" && (
              <div className="mt-5">
                <p className="text-sm text-ink">변경할 사이즈</p>
                {optionsError ? (
                  <p className="mt-2 text-sm text-danger">{optionsError}</p>
                ) : !exchangeOptions ? (
                  <Skeleton className="mt-2 h-10 w-full" />
                ) : (
                  (() => {
                    const candidates = exchangeOptions.filter((o) => o.id !== returnTarget.optionId);
                    if (candidates.length === 0) {
                      return <p className="mt-2 text-sm text-muted">교환 가능한 다른 사이즈가 없습니다.</p>;
                    }
                    return (
                      <div className="mt-2 flex flex-wrap gap-2">
                        {candidates.map((o) => {
                          const short = o.available < returnTarget.quantity;
                          return (
                            <button
                              key={o.id}
                              onClick={() => setExchangeOptionId(o.id)}
                              disabled={short || returning}
                              className={`rounded-lg border px-3 py-2 text-sm transition ${
                                exchangeOptionId === o.id
                                  ? "border-clay bg-clay/10 font-medium text-clay"
                                  : "border-line text-ink hover:border-clay"
                              } ${short ? "cursor-not-allowed opacity-40" : ""}`}
                            >
                              {o.size}
                              <span className="ml-1.5 text-xs text-muted">
                                {short ? "품절" : `재고 ${o.available}`}
                              </span>
                            </button>
                          );
                        })}
                      </div>
                    );
                  })()
                )}
              </div>
            )}

            <label className="mt-5 block text-sm text-ink">
              {returnType === "EXCHANGE" ? "교환" : "반품"} 사유
              <select
                value={returnReason}
                onChange={(e) => setReturnReason(e.target.value)}
                disabled={returning}
                className="mt-1 w-full rounded-lg border border-line bg-cream px-3 py-2 text-sm text-ink"
              >
                {(returnType === "EXCHANGE" ? EXCHANGE_REASONS : RETURN_REASONS).map((r) => (
                  <option key={r.code} value={r.code}>{r.label}</option>
                ))}
              </select>
            </label>

            <label className="mt-4 block text-sm text-ink">
              상세 사유 <span className="text-muted">(선택)</span>
              <textarea
                value={returnDetail}
                onChange={(e) => setReturnDetail(e.target.value)}
                disabled={returning}
                rows={3}
                maxLength={500}
                placeholder="예) 사이즈가 생각보다 작아요"
                className="mt-1 w-full resize-none rounded-lg border border-line bg-cream px-3 py-2 text-sm text-ink"
              />
            </label>

            {/* 모달이 화면을 덮으므로 서버 문구(기한 초과·중복 요청·품절 409)를 모달 안에서도 보여준다. */}
            {error && (
              <p className="mt-4 rounded-lg bg-danger/10 px-3 py-2 text-sm text-danger">{error}</p>
            )}

            <div className="mt-6 flex justify-end gap-2">
              <button
                onClick={() => setReturnTarget(null)}
                disabled={returning}
                className="rounded-full border border-line px-4 py-2 text-sm text-muted transition hover:text-ink disabled:opacity-50"
              >
                닫기
              </button>
              <button
                onClick={requestReturn}
                disabled={returning || (returnType === "EXCHANGE" && exchangeOptionId == null)}
                className={buttonClass()}
              >
                {returning ? "접수 중…" : returnType === "EXCHANGE" ? "교환 신청" : "반품 신청"}
              </button>
            </div>
          </div>
        </div>
      )}
    </main>
  );
}
