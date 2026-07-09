"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { apiGet, apiPost } from "@/lib/api";
import { ClaimableCoupon, MemberCoupon } from "@/lib/types";
import { useAuth } from "@/lib/auth";
import Badge from "@/components/ui/Badge";
import Skeleton from "@/components/ui/Skeleton";
import { formatDiscountOf } from "@/lib/coupon";

/**
 * 내 쿠폰함 (/account/coupons). 두 섹션으로 구성:
 *  1) 받을 수 있는 쿠폰 — 선착순 발급형 쿠폰을 직접 "받기"(POST claim, 동시성 제어 V35).
 *  2) 보유 쿠폰 — 받은 쿠폰을 보고 체크아웃에서 골라 쓴다.
 * 적용·단일 사용은 체크아웃이, 전체 일괄 발급은 운영(ADMIN)이 처리한다.
 */
export default function CouponWalletPage() {
  const { user, loading: authLoading } = useAuth();
  const router = useRouter();

  const [coupons, setCoupons] = useState<MemberCoupon[]>([]);
  const [claimable, setClaimable] = useState<ClaimableCoupon[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [claimingId, setClaimingId] = useState<number | null>(null); // 받기 중인 쿠폰(중복 클릭 방지)

  useEffect(() => {
    if (!authLoading && !user) router.replace("/login");
  }, [authLoading, user, router]);

  // 보유 + 받을 수 있는 쿠폰을 함께 로드. (.NET이면 Task.WhenAll 두 API 동시 await.)
  const load = () =>
    Promise.all([
      apiGet<MemberCoupon[]>("/api/member-coupons/me"),
      apiGet<ClaimableCoupon[]>("/api/member-coupons/claimable"),
    ]).then(([mine, avail]) => {
      setCoupons(mine);
      setClaimable(avail);
    });

  useEffect(() => {
    if (!user) return;
    load()
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [user]);

  // 쿠폰 받기 — 성공하면 두 목록 새로고침(잔여수량·받음 상태가 즉시 반영). 마감/중복은 서버가 409.
  const claim = async (couponId: number) => {
    setClaimingId(couponId);
    setError(null);
    setNotice(null);
    try {
      await apiPost(`/api/member-coupons/claim/${couponId}`);
      setNotice("쿠폰을 받았습니다.");
      await load();
    } catch (e) {
      setError((e as Error).message);
      await load(); // 마감 등으로 실패해도 최신 잔여수량 반영
    } finally {
      setClaimingId(null);
    }
  };

  if (authLoading || (user && loading))
    return (
      <main className="mx-auto max-w-3xl px-6 py-12">
        <Skeleton className="mb-8 h-9 w-32" />
        {Array.from({ length: 2 }).map((_, s) => (
          <section key={s} className="mb-10">
            <Skeleton className="mb-3 h-6 w-40" />
            <ul className="flex flex-col gap-3">
              {Array.from({ length: 2 }).map((_, i) => (
                <li key={i} className="rounded-2xl border border-line bg-paper p-5">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0 space-y-2">
                      <Skeleton className="h-6 w-40" />
                      <Skeleton className="h-4 w-52" />
                      <Skeleton className="h-3 w-64" />
                    </div>
                    <Skeleton className="h-7 w-16 rounded-full" />
                  </div>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </main>
    );
  if (!user) return null;

  return (
    <main className="mx-auto max-w-3xl px-6 py-12">
      <h1 className="mb-8 text-3xl font-bold text-ink">쿠폰함</h1>

      {error && <p className="mb-4 text-sm text-danger">{error}</p>}
      {notice && <p className="mb-4 text-sm text-sage-600">{notice}</p>}

      {/* 1) 받을 수 있는 쿠폰 (선착순) */}
      <section className="mb-10">
        <h2 className="mb-3 font-serif text-xl text-ink">받을 수 있는 쿠폰</h2>
        {claimable.length === 0 ? (
          <div className="rounded-2xl border border-line bg-paper p-8 text-center text-sm text-muted">
            지금 받을 수 있는 쿠폰이 없습니다.
          </div>
        ) : (
          <ul className="flex flex-col gap-3">
            {claimable.map((c) => {
              const disabled = c.alreadyClaimed || c.soldOut;
              return (
                <li
                  key={c.id}
                  className={`rounded-2xl border p-5 transition ${
                    disabled ? "border-line bg-paper opacity-70" : "border-clay/40 bg-clay-50/30"
                  }`}
                >
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <p className="font-serif text-xl text-ink">
                        {formatDiscountOf(c.discountType, c.discountValue, c.maxDiscountAmount)} 할인
                      </p>
                      <p className="mt-1 text-sm text-muted">{c.name}</p>
                      <p className="mt-0.5 text-xs text-muted">
                        {c.validFrom.slice(0, 10)} ~ {c.validUntil.slice(0, 10)}
                        {c.minOrderAmount > 0
                          ? ` · ${c.minOrderAmount.toLocaleString()}원 이상`
                          : ""}
                        {c.totalQuantity != null
                          ? ` · 남은 수량 ${c.remainingQuantity?.toLocaleString()}/${c.totalQuantity.toLocaleString()}장`
                          : ""}
                      </p>
                    </div>
                    {c.alreadyClaimed ? (
                      <Badge tone="sage">받음</Badge>
                    ) : c.soldOut ? (
                      <Badge tone="danger">마감</Badge>
                    ) : (
                      <button
                        onClick={() => claim(c.id)}
                        disabled={claimingId === c.id}
                        className="shrink-0 rounded-full bg-clay px-4 py-2 text-sm font-medium text-cream transition hover:bg-clay-700 disabled:opacity-50"
                      >
                        {claimingId === c.id ? "받는 중…" : "받기"}
                      </button>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      {/* 2) 보유 쿠폰 */}
      <section>
        <h2 className="mb-3 font-serif text-xl text-ink">보유 쿠폰</h2>
        {coupons.length === 0 ? (
          <div className="rounded-2xl border border-line bg-paper p-8 text-center text-sm text-muted">
            발급받은 쿠폰이 없습니다.
          </div>
        ) : (
          <ul className="flex flex-col gap-3">
            {coupons.map((c) => (
              <li
                key={c.id}
                className={`rounded-2xl border p-5 transition ${
                  c.usable ? "border-clay/40 bg-clay-50/30" : "border-line bg-paper opacity-70"
                }`}
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="font-serif text-xl text-ink">
                      {formatDiscountOf(c.discountType, c.discountValue, c.maxDiscountAmount)} 할인
                    </p>
                    <p className="mt-1 text-sm text-muted">
                      {c.name} · 코드 {c.code}
                    </p>
                    <p className="mt-0.5 text-xs text-muted">
                      {c.validFrom.slice(0, 10)} ~ {c.validUntil.slice(0, 10)}
                      {c.minOrderAmount > 0 ? ` · ${c.minOrderAmount.toLocaleString()}원 이상` : ""}
                    </p>
                  </div>
                  {c.status === "USED" ? (
                    <Badge tone="neutral">사용 완료</Badge>
                  ) : c.usable ? (
                    <Badge tone="sage">사용 가능</Badge>
                  ) : (
                    <Badge tone="neutral">기간 외</Badge>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}
