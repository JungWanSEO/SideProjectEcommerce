"use client";

import { useCallback, useEffect, useState } from "react";
import { apiGet, apiPost } from "@/lib/api";
import { Coupon, CouponCreateInput, DiscountType, CouponFundedBy, Seller } from "@/lib/types";
import {
  DISCOUNT_TYPE_LABEL,
  FUNDED_BY_LABEL,
  FUNDED_BY_BADGE,
  COUPON_STATUS_LABEL,
  COUPON_STATUS_BADGE,
  formatDiscount,
  scopeLabel,
  effectiveStatus,
} from "@/lib/coupon";

/**
 * 쿠폰 관리 화면 (/admin/coupons, ADMIN).
 * 쿠폰 발급(정액/정률·플랫폼/셀러 부담·전체/셀러한정) + 현황 목록. 고객은 체크아웃에서 코드를 입력해 적용한다.
 */
export default function AdminCouponsPage() {
  const [coupons, setCoupons] = useState<Coupon[]>([]);
  const [sellers, setSellers] = useState<Seller[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 발급 폼 상태
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [discountType, setDiscountType] = useState<DiscountType>("FIXED_AMOUNT");
  const [discountValue, setDiscountValue] = useState("");
  const [maxDiscountAmount, setMaxDiscountAmount] = useState("");
  const [minOrderAmount, setMinOrderAmount] = useState("0");
  const [fundedBy, setFundedBy] = useState<CouponFundedBy>("PLATFORM");
  const [sellerId, setSellerId] = useState(""); // "" = 플랫폼 와이드
  const [validFrom, setValidFrom] = useState("");
  const [validUntil, setValidUntil] = useState("");

  const load = useCallback(() => {
    setLoading(true);
    Promise.all([apiGet<Coupon[]>("/api/coupons"), apiGet<Seller[]>("/api/sellers")])
      .then(([cs, sl]) => {
        setCoupons(cs);
        setSellers(sl);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const resetForm = () => {
    setCode("");
    setName("");
    setDiscountType("FIXED_AMOUNT");
    setDiscountValue("");
    setMaxDiscountAmount("");
    setMinOrderAmount("0");
    setFundedBy("PLATFORM");
    setSellerId("");
    setValidFrom("");
    setValidUntil("");
  };

  const create = async () => {
    if (!code.trim() || !name.trim() || !discountValue || !validFrom || !validUntil) {
      setError("코드·이름·할인 값·유효기간(시작·끝)은 필수입니다.");
      return;
    }
    setBusy(true);
    setError(null);
    const body: CouponCreateInput = {
      code: code.trim(),
      name: name.trim(),
      discountType,
      discountValue: Number(discountValue),
      maxDiscountAmount: discountType === "PERCENTAGE" && maxDiscountAmount ? Number(maxDiscountAmount) : null,
      minOrderAmount: Number(minOrderAmount || 0),
      fundedBy,
      sellerId: sellerId ? Number(sellerId) : null,
      validFrom,
      validUntil,
    };
    try {
      await apiPost<Coupon>("/api/coupons", body);
      resetForm();
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const now = new Date();
  const activeCount = coupons.filter((c) => effectiveStatus(c, now) === "ACTIVE").length;

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-xl font-bold">쿠폰 관리</h1>
        <p className="text-sm text-gray-500">
          정액/정률 · 플랫폼/셀러 부담 · 전체/셀러한정 쿠폰을 발급합니다. 부담 주체는 셀러별 정산에 반영됩니다.
        </p>
      </div>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>
      )}

      {/* KPI */}
      <div className="mb-6 grid grid-cols-3 gap-4">
        <div className="rounded-lg border border-gray-200 bg-white px-4 py-3">
          <div className="text-xs text-gray-500">총 쿠폰</div>
          <div className="mt-1 text-lg font-bold">{coupons.length}개</div>
        </div>
        <div className="rounded-lg border border-gray-200 bg-white px-4 py-3">
          <div className="text-xs text-gray-500">활성(현재 사용 가능)</div>
          <div className="mt-1 text-lg font-bold text-green-700">{activeCount}개</div>
        </div>
        <div className="rounded-lg border border-gray-200 bg-white px-4 py-3">
          <div className="text-xs text-gray-500">비활성/기간 외</div>
          <div className="mt-1 text-lg font-bold text-gray-500">{coupons.length - activeCount}개</div>
        </div>
      </div>

      {/* 발급 폼 */}
      <div className="mb-6 rounded-lg border border-gray-200 bg-white p-4">
        <div className="mb-3 text-sm font-semibold text-gray-700">쿠폰 발급</div>
        <div className="grid grid-cols-2 gap-3 text-sm md:grid-cols-4">
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">코드</span>
            <input
              value={code}
              onChange={(e) => setCode(e.target.value.toUpperCase())}
              placeholder="WELCOME5000"
              className="rounded border border-gray-300 px-2 py-1"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">이름</span>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="신규가입 5천원"
              className="rounded border border-gray-300 px-2 py-1"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">할인 종류</span>
            <select
              value={discountType}
              onChange={(e) => setDiscountType(e.target.value as DiscountType)}
              className="rounded border border-gray-300 px-2 py-1"
            >
              <option value="FIXED_AMOUNT">정액(원)</option>
              <option value="PERCENTAGE">정률(%)</option>
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">
              {discountType === "FIXED_AMOUNT" ? "할인액(원)" : "할인율(%)"}
            </span>
            <input
              type="number"
              value={discountValue}
              onChange={(e) => setDiscountValue(e.target.value)}
              placeholder={discountType === "FIXED_AMOUNT" ? "5000" : "10"}
              className="rounded border border-gray-300 px-2 py-1"
            />
          </label>

          {discountType === "PERCENTAGE" && (
            <label className="flex flex-col gap-1">
              <span className="text-xs text-gray-500">할인 상한(원, 선택)</span>
              <input
                type="number"
                value={maxDiscountAmount}
                onChange={(e) => setMaxDiscountAmount(e.target.value)}
                placeholder="10000"
                className="rounded border border-gray-300 px-2 py-1"
              />
            </label>
          )}
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">최소 주문금액(원)</span>
            <input
              type="number"
              value={minOrderAmount}
              onChange={(e) => setMinOrderAmount(e.target.value)}
              className="rounded border border-gray-300 px-2 py-1"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">부담 주체</span>
            <select
              value={fundedBy}
              onChange={(e) => setFundedBy(e.target.value as CouponFundedBy)}
              className="rounded border border-gray-300 px-2 py-1"
            >
              <option value="PLATFORM">플랫폼 부담</option>
              <option value="SELLER">셀러 부담</option>
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">적용 범위</span>
            <select
              value={sellerId}
              onChange={(e) => setSellerId(e.target.value)}
              className="rounded border border-gray-300 px-2 py-1"
            >
              <option value="">전체(플랫폼 와이드)</option>
              {sellers.map((s) => (
                <option key={s.id} value={String(s.id)}>
                  셀러 한정 · {s.name}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">유효 시작</span>
            <input
              type="datetime-local"
              value={validFrom}
              onChange={(e) => setValidFrom(e.target.value)}
              className="rounded border border-gray-300 px-2 py-1"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">유효 종료</span>
            <input
              type="datetime-local"
              value={validUntil}
              onChange={(e) => setValidUntil(e.target.value)}
              className="rounded border border-gray-300 px-2 py-1"
            />
          </label>
        </div>
        <div className="mt-3">
          <button
            onClick={create}
            disabled={busy}
            className="rounded bg-gray-900 px-4 py-2 text-sm font-medium text-white hover:bg-gray-700 disabled:opacity-50"
          >
            {busy ? "발급 중…" : "쿠폰 발급"}
          </button>
        </div>
      </div>

      {/* 목록 */}
      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
            <tr>
              <th className="px-4 py-3">코드</th>
              <th className="px-4 py-3">이름</th>
              <th className="px-4 py-3">할인</th>
              <th className="px-4 py-3">적용 범위</th>
              <th className="px-4 py-3">부담</th>
              <th className="px-4 py-3 text-right">최소주문</th>
              <th className="px-4 py-3">유효기간</th>
              <th className="px-4 py-3">상태</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading ? (
              <tr>
                <td colSpan={8} className="px-4 py-8 text-center text-gray-400">
                  불러오는 중…
                </td>
              </tr>
            ) : coupons.length === 0 ? (
              <tr>
                <td colSpan={8} className="px-4 py-8 text-center text-gray-400">
                  발급된 쿠폰이 없습니다. 위에서 발급하세요.
                </td>
              </tr>
            ) : (
              coupons.map((c) => {
                const st = effectiveStatus(c, now);
                return (
                  <tr key={c.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-mono font-medium">{c.code}</td>
                    <td className="px-4 py-3 text-gray-600">{c.name}</td>
                    <td className="px-4 py-3">
                      <span className="text-gray-400">{DISCOUNT_TYPE_LABEL[c.discountType]} · </span>
                      {formatDiscount(c)}
                    </td>
                    <td className="px-4 py-3 text-gray-600">{scopeLabel(c)}</td>
                    <td className="px-4 py-3">
                      <span className={`rounded px-2 py-0.5 text-xs ${FUNDED_BY_BADGE[c.fundedBy]}`}>
                        {FUNDED_BY_LABEL[c.fundedBy]}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right text-gray-500">
                      {c.minOrderAmount > 0 ? `${c.minOrderAmount.toLocaleString()}원` : "—"}
                    </td>
                    <td className="px-4 py-3 text-xs text-gray-500">
                      {c.validFrom.slice(0, 10)} ~ {c.validUntil.slice(0, 10)}
                    </td>
                    <td className="px-4 py-3">
                      <span className={`rounded px-2 py-0.5 text-xs ${COUPON_STATUS_BADGE[st]}`}>
                        {COUPON_STATUS_LABEL[st]}
                      </span>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
