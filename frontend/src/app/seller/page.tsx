"use client";

import { useCallback, useEffect, useState } from "react";
import { apiGet } from "@/lib/api";
import { PageResponse, Seller, SellerSettlementSummary, Settlement, SettlementStatus } from "@/lib/types";
import { SETTLEMENT_STATUS_BADGE, SETTLEMENT_STATUS_LABEL } from "@/lib/settlementStatus";
import { PROVIDER_BADGE, formatRate, providerLabel } from "@/lib/provider";
import StatCard from "@/components/admin/StatCard";

/**
 * 셀러 콘솔 (/seller, SELLER 전용) — 본인 셀러의 정산서만 조회(읽기 전용).
 * 백엔드 /api/seller/me/** 가 로그인 회원의 sellerId로 스코핑하므로 남의 정산은 볼 수 없다.
 * 정산 배치 실행·입금 처리는 ADMIN 권한이라 여기엔 없다.
 */
export default function SellerConsolePage() {
  const [seller, setSeller] = useState<Seller | null>(null);
  const [summary, setSummary] = useState<SellerSettlementSummary | null>(null);
  const [items, setItems] = useState<Settlement[]>([]);
  const [statusFilter, setStatusFilter] = useState<SettlementStatus | "">("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    const qs = new URLSearchParams();
    if (statusFilter) qs.set("status", statusFilter);
    const listQs = new URLSearchParams(qs);
    listQs.set("size", "100");

    Promise.all([
      apiGet<Seller>("/api/seller/me"),
      apiGet<SellerSettlementSummary[]>(`/api/seller/me/summary?${qs.toString()}`),
      apiGet<PageResponse<Settlement>>(`/api/seller/me/settlements?${listQs.toString()}`),
    ])
      .then(([me, sum, page]) => {
        setSeller(me);
        setSummary(sum[0] ?? null); // 셀러 본인 1건(정산 없으면 없음)
        setItems(page.content);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [statusFilter]);

  useEffect(() => {
    load();
  }, [load]);

  // 정산 없을 때 0으로 표시
  const k = summary ?? { count: 0, grossAmount: 0, fee: 0, platformFee: 0, netAmount: 0 };

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-xl font-bold">내 정산{seller ? ` — ${seller.name}` : ""}</h1>
        <p className="text-sm text-gray-500">
          {seller
            ? `플랫폼 판매수수료율 ${formatRate(seller.commissionRate)} · 매출에서 PG수수료·플랫폼수수료를 뗀 실수령을 확인하세요.`
            : "내 셀러 정산을 불러옵니다."}
        </p>
      </div>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>
      )}

      {/* KPI — 매출 ≠ 실수령 */}
      <div className="mb-6 grid grid-cols-5 gap-4">
        <StatCard label="정산 건수" value={`${k.count}건`} />
        <StatCard label="매출 (gross)" value={`${k.grossAmount.toLocaleString()}원`} />
        <StatCard label="PG 수수료" value={`−${k.fee.toLocaleString()}원`} accent="text-amber-600" />
        <StatCard label="플랫폼 수수료" value={`−${k.platformFee.toLocaleString()}원`} accent="text-amber-600" />
        <StatCard label="실수령 (net)" value={`${k.netAmount.toLocaleString()}원`} accent="text-green-700" />
      </div>

      {/* 상태 필터 */}
      <div className="mb-3 flex items-center gap-2 text-sm">
        <select
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value as SettlementStatus | "")}
          className="rounded border border-gray-300 px-2 py-1"
        >
          <option value="">전체 상태</option>
          <option value="SCHEDULED">정산예정</option>
          <option value="PAID_OUT">입금완료</option>
        </select>
      </div>

      {/* 정산 항목 테이블 (읽기 전용) */}
      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
            <tr>
              <th className="px-4 py-3">ID</th>
              <th className="px-4 py-3">PG</th>
              <th className="px-4 py-3 text-right">매출</th>
              <th className="px-4 py-3 text-right">PG수수료</th>
              <th className="px-4 py-3 text-right">플랫폼수수료</th>
              <th className="px-4 py-3 text-right">실수령</th>
              <th className="px-4 py-3">상태</th>
              <th className="px-4 py-3">입금예정일</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading ? (
              <tr>
                <td colSpan={8} className="px-4 py-8 text-center text-gray-400">
                  불러오는 중…
                </td>
              </tr>
            ) : items.length === 0 ? (
              <tr>
                <td colSpan={8} className="px-4 py-8 text-center text-gray-400">
                  정산 항목이 없습니다.
                </td>
              </tr>
            ) : (
              items.map((s) => (
                <tr key={s.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 text-gray-400">#{s.id}</td>
                  <td className="px-4 py-3">
                    <span className={`rounded px-2 py-0.5 text-xs ${PROVIDER_BADGE}`}>{providerLabel(s.provider)}</span>
                  </td>
                  <td className="px-4 py-3 text-right">{s.grossAmount.toLocaleString()}</td>
                  <td className="px-4 py-3 text-right text-amber-600">
                    −{s.fee.toLocaleString()}
                    <span className="ml-1 text-xs text-gray-400">{formatRate(s.feeRate)}</span>
                  </td>
                  <td className="px-4 py-3 text-right text-amber-600">
                    −{s.platformFee.toLocaleString()}
                    <span className="ml-1 text-xs text-gray-400">{formatRate(s.platformFeeRate)}</span>
                  </td>
                  <td className="px-4 py-3 text-right font-medium">{s.netAmount.toLocaleString()}</td>
                  <td className="px-4 py-3">
                    <span className={`rounded px-2 py-0.5 text-xs ${SETTLEMENT_STATUS_BADGE[s.status]}`}>
                      {SETTLEMENT_STATUS_LABEL[s.status]}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-500">{s.settledDate}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
