"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { apiGet } from "@/lib/api";
import { MemberCoupon } from "@/lib/types";
import { useAuth } from "@/lib/auth";
import Badge from "@/components/ui/Badge";
import { formatDiscountOf } from "@/lib/coupon";

/**
 * 내 쿠폰함 (/account/coupons). 발급받은 쿠폰을 보고, 체크아웃에서 골라 쓴다.
 * 발급은 운영(ADMIN)이, 적용·단일 사용은 체크아웃이 처리한다.
 */
export default function CouponWalletPage() {
  const { user, loading: authLoading } = useAuth();
  const router = useRouter();

  const [coupons, setCoupons] = useState<MemberCoupon[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!authLoading && !user) router.replace("/login");
  }, [authLoading, user, router]);

  useEffect(() => {
    if (!user) return;
    apiGet<MemberCoupon[]>("/api/member-coupons/me")
      .then(setCoupons)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [user]);

  if (authLoading || (user && loading))
    return <p className="p-12 text-center text-muted">불러오는 중…</p>;
  if (!user) return null;

  return (
    <main className="mx-auto max-w-3xl px-6 py-12">
      <h1 className="mb-8 text-3xl font-bold text-ink">쿠폰함</h1>

      {error && <p className="mb-4 text-sm text-danger">{error}</p>}

      {coupons.length === 0 ? (
        <div className="rounded-2xl border border-line bg-paper p-12 text-center text-muted">
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
    </main>
  );
}
