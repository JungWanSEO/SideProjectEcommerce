"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { apiGet } from "@/lib/api";
import { PageResponse, ReturnRequest, ReturnStatus } from "@/lib/types";
import { useAuth } from "@/lib/auth";
import { loginHref } from "@/lib/useRequireAuth";
import Skeleton from "@/components/ui/Skeleton";

/**
 * 내 반품/교환 (/account/returns) — 신청한 반품의 진행 상황을 본다.
 *
 * 신청은 주문 상세(`/orders/[id]`)에서 하고, 처리(승인·수거·검수·환불)는 셀러가 한다. 구매자에게 필요한 건
 * "지금 어디까지 왔는가"라서 상태 단계와 전이 이력을 보여준다(알림 벨로도 같은 전이가 통지된다).
 */

const STATUS_LABEL: Record<ReturnStatus, string> = {
  REQUESTED: "요청 접수",
  APPROVED: "승인됨 · 수거 대기",
  PICKED_UP: "수거 완료 · 검수 대기",
  INSPECTED: "검수 완료",
  REFUNDED: "환불 완료",
  COMPLETED: "교환 완료",
  REJECTED: "거부됨",
};

const STATUS_BADGE: Record<ReturnStatus, string> = {
  REQUESTED: "bg-clay/10 text-clay",
  APPROVED: "bg-clay/10 text-clay",
  PICKED_UP: "bg-clay/10 text-clay",
  INSPECTED: "bg-clay/10 text-clay",
  REFUNDED: "bg-sage-100 text-sage-600",
  COMPLETED: "bg-sage-100 text-sage-600",
  REJECTED: "bg-line text-muted",
};

/** 진행 단계 — 구매자 눈높이의 4칸(요청→수거→검수→완료). 거부는 별도 상태로 표시한다. */
const STEPS: { key: string; label: string; reached: (s: ReturnStatus) => boolean }[] = [
  { key: "requested", label: "요청", reached: () => true },
  {
    key: "picked",
    label: "수거",
    reached: (s) => ["PICKED_UP", "INSPECTED", "REFUNDED", "COMPLETED"].includes(s),
  },
  { key: "inspected", label: "검수", reached: (s) => ["INSPECTED", "REFUNDED", "COMPLETED"].includes(s) },
  { key: "done", label: "완료", reached: (s) => ["REFUNDED", "COMPLETED"].includes(s) },
];

const REASON_LABEL: Record<string, string> = {
  CHANGE_OF_MIND: "단순 변심",
  WRONG_ORDER: "주문 실수",
  DELIVERY_DELAY: "배송 지연",
  OUT_OF_STOCK: "품절",
  DEFECTIVE: "상품 불량",
  WRONG_DELIVERY: "오배송",
  OTHER: "기타",
};

export default function MyReturnsPage() {
  const { user, loading: authLoading } = useAuth();
  const router = useRouter();

  const [items, setItems] = useState<ReturnRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!authLoading && !user) router.replace(loginHref("/account/returns"));
  }, [authLoading, user, router]);

  useEffect(() => {
    if (!user) return;
    apiGet<PageResponse<ReturnRequest>>("/api/returns/me?page=0&size=20")
      .then((p) => setItems(p.content))
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [user]);

  if (authLoading || !user) {
    return (
      <main className="mx-auto max-w-3xl px-6 py-12">
        <Skeleton className="h-8 w-40" />
        <Skeleton className="mt-6 h-24 w-full rounded-2xl" />
      </main>
    );
  }

  return (
    <main className="mx-auto max-w-3xl px-6 py-12">
      <h1 className="font-serif text-3xl text-ink">내 반품 · 교환</h1>
      <p className="mt-1 text-sm text-muted">
        신청은 주문 상세에서, 처리는 판매자가 합니다. 진행 상황이 바뀌면 알림으로도 알려드립니다.
      </p>

      {error && <p className="mt-6 text-sm text-danger">{error}</p>}

      {loading ? (
        <Skeleton className="mt-6 h-24 w-full rounded-2xl" />
      ) : items.length === 0 ? (
        <p className="mt-10 text-center text-sm text-muted">
          신청한 반품/교환이 없습니다.{" "}
          <Link href="/orders" className="underline hover:text-clay">
            주문 내역
          </Link>
          에서 배송완료 상품을 반품할 수 있습니다.
        </p>
      ) : (
        <ul className="mt-6 flex flex-col gap-4">
          {items.map((r) => (
            <li key={r.id} className="rounded-2xl border border-line bg-paper p-5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex flex-wrap items-center gap-2">
                  <span className={`rounded-full px-3 py-1 text-xs font-medium ${STATUS_BADGE[r.status]}`}>
                    {STATUS_LABEL[r.status]}
                  </span>
                  <span className="rounded-full bg-line px-2.5 py-0.5 text-xs text-muted">
                    {r.type === "RETURN" ? "반품" : "교환"}
                  </span>
                  <Link href={`/orders/${r.orderId}`} className="text-sm text-muted underline hover:text-clay">
                    주문 #{r.orderId}
                  </Link>
                </div>
                {r.refundAmount != null && (
                  <span className="text-right text-sm font-medium text-ink">
                    환불 {r.refundAmount.toLocaleString()}원
                    {/*
                      회수비가 차감됐다면 "왜 덜 받았는지"를 반드시 보여준다(#8 후속).
                      금액만 줄어 있고 이유가 없으면 그대로 CS 문의가 된다.
                    */}
                    {r.returnShippingCharged != null && r.returnShippingCharged > 0 && (
                      <span className="mt-0.5 block text-xs font-normal text-muted">
                        회수비 {r.returnShippingCharged.toLocaleString()}원 차감 후 금액입니다
                      </span>
                    )}
                  </span>
                )}
              </div>

              {/* 진행 단계 — 거부되면 단계 대신 안내만 보여준다(더 갈 곳이 없다). */}
              {r.status === "REJECTED" ? (
                <p className="mt-4 text-sm text-muted">
                  요청이 거부되었습니다. 자세한 사유는 고객센터로 문의해 주세요.
                </p>
              ) : (
                <ol className="mt-4 flex items-center gap-2">
                  {STEPS.map((step, i) => {
                    const done = step.reached(r.status);
                    return (
                      <li key={step.key} className="flex flex-1 items-center gap-2">
                        <span
                          className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs ${
                            done ? "bg-clay text-white" : "bg-line text-muted"
                          }`}
                        >
                          {i + 1}
                        </span>
                        <span className={`text-xs ${done ? "text-ink" : "text-muted"}`}>{step.label}</span>
                        {i < STEPS.length - 1 && (
                          <span className={`h-px flex-1 ${done ? "bg-clay/40" : "bg-line"}`} />
                        )}
                      </li>
                    );
                  })}
                </ol>
              )}

              <p className="mt-4 text-sm text-ink">
                {r.reasonCode && (
                  <span className="mr-2 font-medium">{REASON_LABEL[r.reasonCode] ?? r.reasonCode}</span>
                )}
                {r.reason || <span className="text-muted">상세 사유 없음</span>}
              </p>
              <p className="mt-1 text-xs text-muted">
                신청 {new Date(r.createdAt).toLocaleString("ko-KR")} · {r.quantity}개
              </p>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
