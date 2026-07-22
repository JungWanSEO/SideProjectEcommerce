"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { apiGet, apiPost } from "@/lib/api";
import { Cart, Address, Order, CouponPreview, MemberCoupon } from "@/lib/types";
import { useAuth } from "@/lib/auth";
import { loginHref } from "@/lib/useRequireAuth";
import Badge from "@/components/ui/Badge";
import Skeleton from "@/components/ui/Skeleton";
import { formatDiscountOf } from "@/lib/coupon";

/**
 * 주문서 / 체크아웃 확인 페이지 (/checkout). 장바구니 → 결제 사이 단계.
 * 주문 요약 + 배송지 선택(주소록) + 배송 메모 → POST /api/orders/checkout(addressId) → 결제 화면.
 * 항목은 서버 장바구니가 진실의 원천이라 보내지 않는다(여기선 표시만).
 */
export default function CheckoutPage() {
  const { user, loading: authLoading } = useAuth();
  const router = useRouter();

  const [cart, setCart] = useState<Cart | null>(null);
  const [addresses, setAddresses] = useState<Address[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [memo, setMemo] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [ordering, setOrdering] = useState(false);

  // 쿠폰: 쿠폰함에서 선택하거나 코드 입력 → "적용"(미리보기) → 할인·결제액 인라인 표시. 적용된 코드만 주문에 보낸다.
  const [couponCode, setCouponCode] = useState("");
  const [coupon, setCoupon] = useState<CouponPreview | null>(null);
  const [couponError, setCouponError] = useState<string | null>(null);
  const [applyingCoupon, setApplyingCoupon] = useState(false);
  const [wallet, setWallet] = useState<MemberCoupon[]>([]); // 내 쿠폰함(사용 가능한 발급 쿠폰)

  /**
   * 멱등키 — 이 체크아웃 화면에서 딱 한 번 발급하고, 재시도(더블클릭·네트워크 타임아웃 후 재요청)에도 같은 값을 보낸다.
   * 서버는 같은 키를 다시 받으면 새 주문을 만들지 않고 처음 만든 주문을 그대로 돌려준다(중복 주문 방지).
   * useRef라 리렌더에도 값이 유지되고, 화면을 새로 들어오면 새 키가 발급된다(= 새 주문 의도).
   */
  const idempotencyKey = useRef<string>(crypto.randomUUID());

  useEffect(() => {
    if (!authLoading && !user) router.replace(loginHref(window.location.pathname + window.location.search));
  }, [authLoading, user, router]);

  useEffect(() => {
    if (!user) return;
    // 장바구니 + 주소록 + 쿠폰함을 함께 로드. 기본배송지(없으면 첫 주소)를 미리 선택.
    Promise.all([
      apiGet<Cart>("/api/carts"),
      apiGet<Address[]>("/api/addresses"),
      apiGet<MemberCoupon[]>("/api/member-coupons/me"),
    ])
      .then(([c, a, w]) => {
        setCart(c);
        setAddresses(a);
        const def = a.find((x) => x.isDefault) ?? a[0];
        setSelectedId(def ? def.id : null);
        setWallet(w.filter((mc) => mc.usable)); // 사용 가능한 것만 선택지로
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [user]);

  // 쿠폰 적용(미리보기): 주문을 만들지 않고 현재 장바구니 기준 할인을 받아 표시한다.
  const previewCode = async (rawCode: string) => {
    const code = rawCode.trim();
    if (!code) return;
    setApplyingCoupon(true);
    setCouponError(null);
    try {
      const preview = await apiPost<CouponPreview>("/api/orders/coupon-preview", { couponCode: code });
      setCoupon(preview);
    } catch (e) {
      setCoupon(null);
      setCouponError((e as Error).message); // 미보유·이미 사용·기간 외·최소금액 미달 등 400
    } finally {
      setApplyingCoupon(false);
    }
  };

  const applyCoupon = () => previewCode(couponCode);

  // 쿠폰함에서 선택 → 코드 채우고 즉시 미리보기
  const selectWalletCoupon = (code: string) => {
    setCouponCode(code);
    if (code) previewCode(code);
  };

  const removeCoupon = () => {
    setCoupon(null);
    setCouponCode("");
    setCouponError(null);
  };

  const placeOrder = async () => {
    if (selectedId == null) {
      setError("배송지를 선택해 주세요.");
      return;
    }
    setOrdering(true);
    setError(null);
    try {
      const order = await apiPost<Order>("/api/orders/checkout", {
        addressId: selectedId,
        deliveryMemo: memo.trim() || null,
        couponCode: coupon ? coupon.couponCode : null, // "적용"한 쿠폰만 전송
        idempotencyKey: idempotencyKey.current, // 재시도해도 주문은 하나
      });
      router.push(`/orders/${order.id}/pay`); // 주문(PENDING) 생성됨 → 결제 화면으로
    } catch (e) {
      setError((e as Error).message); // 빈 장바구니(400)·재고·쿠폰 등
      setOrdering(false);
    }
  };

  if (authLoading || (user && loading))
    return (
      <main className="mx-auto max-w-3xl px-6 py-12">
        {/* 제목 */}
        <Skeleton className="mb-8 h-9 w-32" />

        {/* 배송지 */}
        <section className="mb-8">
          <div className="mb-3 flex items-center justify-between">
            <Skeleton className="h-6 w-20" />
            <Skeleton className="h-4 w-20" />
          </div>
          <ul className="flex flex-col gap-2">
            {Array.from({ length: 2 }).map((_, i) => (
              <li key={i}>
                <div className="flex items-start gap-3 rounded-2xl border border-line bg-paper p-4">
                  <Skeleton className="mt-1 h-4 w-4 rounded-full" />
                  <div className="min-w-0 flex-1">
                    <Skeleton className="h-5 w-28" />
                    <Skeleton className="mt-2 h-4 w-24" />
                    <Skeleton className="mt-2 h-4 w-64 max-w-full" />
                  </div>
                </div>
              </li>
            ))}
          </ul>
          <Skeleton className="mt-3 h-11 w-full rounded-xl" />
        </section>

        {/* 주문 상품 */}
        <section className="mb-8">
          <Skeleton className="mb-3 h-6 w-24" />
          <ul className="overflow-hidden rounded-2xl border border-line bg-paper">
            {Array.from({ length: 2 }).map((_, i) => (
              <li
                key={i}
                className={`flex items-center justify-between px-5 py-4 ${i > 0 ? "border-t border-line" : ""}`}
              >
                <div>
                  <Skeleton className="h-5 w-40" />
                  <Skeleton className="mt-2 h-4 w-28" />
                </div>
                <Skeleton className="h-5 w-20" />
              </li>
            ))}
          </ul>
        </section>

        {/* 쿠폰 */}
        <section className="mb-6">
          <Skeleton className="mb-3 h-6 w-16" />
          <div className="rounded-2xl border border-line bg-paper p-4">
            <Skeleton className="mb-2 h-11 w-full rounded-xl" />
            <div className="flex gap-2">
              <Skeleton className="h-11 flex-1 rounded-xl" />
              <Skeleton className="h-11 w-20 rounded-full" />
            </div>
          </div>
        </section>

        {/* 결제 금액 */}
        <div className="border-t border-line pt-5">
          <div className="flex justify-between">
            <Skeleton className="h-4 w-40" />
            <Skeleton className="h-4 w-20" />
          </div>
          <div className="mt-3 flex items-center justify-between border-t border-line pt-3">
            <Skeleton className="h-5 w-28" />
            <Skeleton className="h-8 w-32" />
          </div>
        </div>

        {/* 주문하고 결제하기 버튼 */}
        <Skeleton className="mt-6 h-[3.25rem] w-full rounded-full" />
      </main>
    );
  if (!user) return null;

  const items = cart?.items ?? [];
  const total = items.reduce((s, it) => s + it.subtotal, 0);

  if (items.length === 0) {
    return (
      <main className="mx-auto max-w-3xl px-6 py-12">
        <h1 className="mb-6 text-3xl font-bold text-ink">주문서</h1>
        <div className="rounded-2xl border border-line bg-paper p-12 text-center">
          <p className="text-muted">장바구니가 비어 있습니다.</p>
          <Link href="/products" className="mt-3 inline-block text-clay hover:underline">
            상품 보러가기 →
          </Link>
        </div>
      </main>
    );
  }

  return (
    <main className="mx-auto max-w-3xl px-6 py-12">
      <h1 className="mb-8 text-3xl font-bold text-ink">주문서</h1>

      {/* 배송지 선택 */}
      <section className="mb-8">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="font-serif text-xl text-ink">배송지</h2>
          <Link href="/account/addresses" className="text-sm text-muted transition hover:text-clay">
            주소 관리 →
          </Link>
        </div>

        {addresses.length === 0 ? (
          <div className="rounded-2xl border border-line bg-paper p-6 text-center text-sm text-muted">
            저장된 배송지가 없습니다.{" "}
            <Link href="/account/addresses" className="text-clay hover:underline">
              배송지를 먼저 등록
            </Link>
            해 주세요.
          </div>
        ) : (
          <ul className="flex flex-col gap-2">
            {addresses.map((a) => (
              <li key={a.id}>
                <label
                  className={`flex cursor-pointer items-start gap-3 rounded-2xl border p-4 transition ${
                    selectedId === a.id
                      ? "border-clay bg-clay-50/40"
                      : "border-line bg-paper hover:border-clay/50"
                  }`}
                >
                  <input
                    type="radio"
                    name="address"
                    className="mt-1 accent-clay"
                    checked={selectedId === a.id}
                    onChange={() => setSelectedId(a.id)}
                  />
                  <div className="min-w-0">
                    <p className="flex items-center gap-2 font-medium text-ink">
                      {a.recipient}
                      {a.isDefault && <Badge tone="sage">기본배송지</Badge>}
                    </p>
                    <p className="mt-0.5 text-sm text-muted">{a.phone}</p>
                    <p className="mt-0.5 text-sm text-ink/80">
                      [{a.zipcode}] {a.address1}
                      {a.address2 ? ` ${a.address2}` : ""}
                    </p>
                  </div>
                </label>
              </li>
            ))}
          </ul>
        )}

        <input
          className="mt-3 w-full rounded-xl border border-line bg-paper px-4 py-2.5 text-ink outline-none transition placeholder:text-muted focus:border-clay"
          placeholder="배송 요청사항 (선택)"
          maxLength={200}
          value={memo}
          onChange={(e) => setMemo(e.target.value)}
        />
      </section>

      {/* 주문 상품 요약 */}
      <section className="mb-8">
        <h2 className="mb-3 font-serif text-xl text-ink">주문 상품</h2>
        <ul className="overflow-hidden rounded-2xl border border-line bg-paper">
          {items.map((it, i) => (
            <li
              key={it.optionId}
              className={`flex items-center justify-between px-5 py-4 ${i > 0 ? "border-t border-line" : ""}`}
            >
              <div>
                <p className="text-ink">{it.productName}</p>
                <p className="mt-0.5 text-sm text-muted">
                  사이즈 {it.size} · {it.quantity}개
                </p>
              </div>
              <span className="font-medium text-ink">{it.subtotal.toLocaleString()}원</span>
            </li>
          ))}
        </ul>
      </section>

      {/* 쿠폰 */}
      <section className="mb-6">
        <h2 className="mb-3 font-serif text-xl text-ink">쿠폰</h2>
        <div className="rounded-2xl border border-line bg-paper p-4">
          {/* 쿠폰함에서 선택(발급 쿠폰) */}
          {wallet.length > 0 && (
            <select
              className="mb-2 w-full rounded-xl border border-line bg-paper px-4 py-2.5 text-ink outline-none transition focus:border-clay disabled:opacity-50"
              value=""
              disabled={!!coupon}
              onChange={(e) => selectWalletCoupon(e.target.value)}
            >
              <option value="">내 쿠폰함에서 선택 ({wallet.length}장)</option>
              {wallet.map((mc) => (
                <option key={mc.id} value={mc.code}>
                  {formatDiscountOf(mc.discountType, mc.discountValue, mc.maxDiscountAmount)} · {mc.name} ({mc.code})
                </option>
              ))}
            </select>
          )}
          <div className="flex gap-2">
            <input
              className="flex-1 rounded-xl border border-line bg-paper px-4 py-2.5 text-ink outline-none transition placeholder:text-muted focus:border-clay disabled:opacity-50"
              placeholder="쿠폰 코드 직접 입력"
              maxLength={40}
              value={couponCode}
              disabled={!!coupon}
              onChange={(e) => setCouponCode(e.target.value.toUpperCase())}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !coupon) applyCoupon();
              }}
            />
            {coupon ? (
              <button
                onClick={removeCoupon}
                className="rounded-full border border-line bg-paper px-5 py-2.5 text-sm font-medium text-muted transition hover:border-danger hover:text-danger"
              >
                제거
              </button>
            ) : (
              <button
                onClick={applyCoupon}
                disabled={!couponCode.trim() || applyingCoupon}
                className="rounded-full bg-ink px-6 py-2.5 text-sm font-medium text-cream transition hover:bg-ink/90 disabled:opacity-50"
              >
                {applyingCoupon ? "확인 중…" : "적용"}
              </button>
            )}
          </div>
          {coupon && (
            <p className="mt-3 rounded-xl bg-sage-50 px-4 py-2.5 text-sm text-sage-600">
              <b>{coupon.couponCode}</b> 적용 — {coupon.discountAmount.toLocaleString()}원 할인
            </p>
          )}
          {couponError && <p className="mt-2 text-sm text-danger">{couponError}</p>}
        </div>
      </section>

      {/* 결제 금액 */}
      <div className="border-t border-line pt-5">
        <div className="flex justify-between text-sm">
          <span className="text-muted">상품 합계 · 총 {cart?.totalQuantity ?? 0}개</span>
          <span className="text-ink">{total.toLocaleString()}원</span>
        </div>
        {coupon && (
          <div className="mt-1.5 flex justify-between text-sm">
            <span className="text-sage-600">쿠폰 할인</span>
            <span className="font-medium text-sage-600">−{coupon.discountAmount.toLocaleString()}원</span>
          </div>
        )}
        <div className="mt-3 flex items-center justify-between border-t border-line pt-3">
          <span className="font-medium text-ink">최종 결제 예정</span>
          <span className="text-2xl font-bold text-ink">
            {(coupon ? coupon.payableAmount : total).toLocaleString()}원
          </span>
        </div>
      </div>

      {error && <p className="mt-4 text-sm text-danger">{error}</p>}

      <button
        onClick={placeOrder}
        disabled={ordering || addresses.length === 0}
        className="mt-6 w-full rounded-full bg-clay px-4 py-3.5 font-medium text-cream transition hover:bg-clay-600 disabled:opacity-50"
      >
        {ordering ? "주문 처리 중…" : "주문하고 결제하기"}
      </button>
    </main>
  );
}
