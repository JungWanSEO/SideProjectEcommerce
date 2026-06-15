"use client";

import { useCallback, useEffect, useState } from "react";
import { apiGet, apiPost } from "@/lib/api";
import { PageResponse, SellerSettlementSummary, Settlement, SettlementRunResult } from "@/lib/types";
import { SETTLEMENT_STATUS_BADGE, SETTLEMENT_STATUS_LABEL } from "@/lib/settlementStatus";
import { PROVIDER_BADGE, formatRate, providerLabel } from "@/lib/provider";
import StatCard from "@/components/admin/StatCard";

/**
 * 정산(Settlement) 운영 화면 (/admin/settlements, ADMIN) — 셀러별 정산(Phase 2).
 * - 정산 배치 실행 · 입금 처리(SCHEDULED → PAID_OUT)
 * - 셀러 정산서: 셀러별 매출/PG수수료/플랫폼수수료/실수령 집계 + 셀러·기간 필터
 * - "매출 ≠ 셀러 실수령": gross − PG수수료 − 플랫폼수수료 = net
 */
export default function AdminSettlementsPage() {
  const [items, setItems] = useState<Settlement[]>([]);
  const [summary, setSummary] = useState<SellerSettlementSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [running, setRunning] = useState(false);
  const [runResult, setRunResult] = useState<SettlementRunResult | null>(null);
  const [reverseInfo, setReverseInfo] = useState<string | null>(null);
  const [payoutId, setPayoutId] = useState<number | null>(null);

  // 필터
  const [sellerFilter, setSellerFilter] = useState<number | null>(null);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");

  const load = useCallback(() => {
    setLoading(true);
    const dateQs = new URLSearchParams();
    if (from) dateQs.set("from", from);
    if (to) dateQs.set("to", to);

    const listQs = new URLSearchParams(dateQs);
    if (sellerFilter != null) listQs.set("sellerId", String(sellerFilter));
    listQs.set("size", "100");

    Promise.all([
      apiGet<PageResponse<Settlement>>(`/api/settlements?${listQs.toString()}`),
      apiGet<SellerSettlementSummary[]>(`/api/settlements/summary?${dateQs.toString()}`),
    ])
      .then(([page, sum]) => {
        setItems(page.content);
        setSummary(sum);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [from, to, sellerFilter]);

  useEffect(() => {
    load();
  }, [load]);

  const runBatch = async () => {
    setRunning(true);
    setError(null);
    try {
      const r = await apiPost<SettlementRunResult>("/api/settlements/run");
      setRunResult(r);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setRunning(false);
    }
  };

  // 환불 상계(역분개) — 부분환불로 취소된 항목의 정산을 음수 항목으로 상계
  const reverseRefunds = async () => {
    setRunning(true);
    setError(null);
    try {
      const r = await apiPost<{ reversedCount: number; totalReversedNet: number }>(
        "/api/settlements/reverse-refunds",
      );
      setReverseInfo(
        `환불 상계 완료 — 역분개 ${r.reversedCount}건 · 실수령 조정 ${r.totalReversedNet.toLocaleString()}원`,
      );
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setRunning(false);
    }
  };

  const payout = async (id: number) => {
    setPayoutId(id);
    setError(null);
    try {
      await apiPost<Settlement>(`/api/settlements/${id}/payout`);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPayoutId(null);
    }
  };

  // 셀러 이름 맵 (요약에서) + 표시 헬퍼
  const sellerName = (id: number | null) => {
    if (id == null) return "미귀속(플랫폼)";
    return summary.find((s) => s.sellerId === id)?.sellerName ?? `셀러 #${id}`;
  };

  // 요약 합계 (KPI 카드) — 전체 셀러 기준(필터 무관, 기간만)
  const totals = summary.reduce(
    (acc, s) => ({
      count: acc.count + s.count,
      gross: acc.gross + s.grossAmount,
      fee: acc.fee + s.fee,
      platformFee: acc.platformFee + s.platformFee,
      net: acc.net + s.netAmount,
    }),
    { count: 0, gross: 0, fee: 0, platformFee: 0, net: 0 },
  );

  const sellerOptions = summary.filter((s) => s.sellerId != null);

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold">셀러별 정산</h1>
          <p className="text-sm text-gray-500">
            결제를 셀러별로 분해해 PG수수료·플랫폼수수료를 떼고 셀러 실수령(지급액)까지 집계합니다.
          </p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={reverseRefunds}
            disabled={running}
            className="rounded border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-100 disabled:opacity-50"
          >
            환불 상계
          </button>
          <button
            onClick={runBatch}
            disabled={running}
            className="rounded bg-gray-900 px-4 py-2 text-sm font-medium text-white hover:bg-gray-700 disabled:opacity-50"
          >
            {running ? "실행 중…" : "정산 배치 실행"}
          </button>
        </div>
      </div>
      {reverseInfo && (
        <div className="mb-4 rounded border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-800">{reverseInfo}</div>
      )}

      {runResult && (
        <div className="mb-4 rounded border border-green-200 bg-green-50 px-4 py-3 text-sm text-green-800">
          정산 배치 완료 — 신규 <b>{runResult.createdCount}</b>건 · 매출 {runResult.totalGrossAmount.toLocaleString()}원 ·
          PG수수료 {runResult.totalFee.toLocaleString()}원 · 플랫폼수수료 {runResult.totalPlatformFee.toLocaleString()}원
          · 쿠폰할인 {runResult.totalDiscount.toLocaleString()}원 · 실수령 {runResult.totalNetAmount.toLocaleString()}원
          {runResult.bySeller.length > 0 && (
            <div className="mt-2 flex flex-wrap gap-2">
              {runResult.bySeller.map((b) => (
                <span
                  key={String(b.sellerId)}
                  className="rounded border border-green-200 bg-white px-2 py-1 text-xs text-gray-600"
                >
                  <b>{sellerName(b.sellerId)}</b> · {b.count}건 · 매출 {b.grossAmount.toLocaleString()} · 실수령{" "}
                  {b.netAmount.toLocaleString()}원
                </span>
              ))}
            </div>
          )}
        </div>
      )}
      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>
      )}

      {/* KPI 카드 — 매출 ≠ 셀러 실수령 */}
      <div className="mb-6 grid grid-cols-5 gap-4">
        <StatCard label="정산 건수" value={`${totals.count}건`} />
        <StatCard label="매출 (gross)" value={`${totals.gross.toLocaleString()}원`} />
        <StatCard label="PG 수수료" value={`−${totals.fee.toLocaleString()}원`} accent="text-amber-600" />
        <StatCard label="플랫폼 수수료" value={`−${totals.platformFee.toLocaleString()}원`} accent="text-amber-600" />
        <StatCard label="셀러 실수령 (net)" value={`${totals.net.toLocaleString()}원`} accent="text-green-700" />
      </div>

      {/* 셀러별 정산서 (요약) */}
      <div className="mb-6 overflow-hidden rounded-lg border border-gray-200 bg-white">
        <div className="border-b border-gray-100 px-4 py-3 text-sm font-semibold text-gray-700">셀러별 정산서</div>
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
            <tr>
              <th className="px-4 py-2">셀러</th>
              <th className="px-4 py-2 text-right">건수</th>
              <th className="px-4 py-2 text-right">매출</th>
              <th className="px-4 py-2 text-right">PG수수료</th>
              <th className="px-4 py-2 text-right">플랫폼수수료</th>
              <th className="px-4 py-2 text-right">쿠폰할인</th>
              <th className="px-4 py-2 text-right">실수령</th>
              <th className="px-4 py-2 text-right">액션</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {summary.length === 0 ? (
              <tr>
                <td colSpan={8} className="px-4 py-6 text-center text-gray-400">
                  정산 데이터가 없습니다.
                </td>
              </tr>
            ) : (
              summary.map((s) => (
                <tr key={String(s.sellerId)} className="hover:bg-gray-50">
                  <td className="px-4 py-2 font-medium">{sellerName(s.sellerId)}</td>
                  <td className="px-4 py-2 text-right text-gray-500">{s.count}</td>
                  <td className="px-4 py-2 text-right">{s.grossAmount.toLocaleString()}</td>
                  <td className="px-4 py-2 text-right text-amber-600">−{s.fee.toLocaleString()}</td>
                  <td className="px-4 py-2 text-right text-amber-600">−{s.platformFee.toLocaleString()}</td>
                  <td className="px-4 py-2 text-right text-indigo-600">
                    {s.discountAmount > 0 ? s.discountAmount.toLocaleString() : "—"}
                  </td>
                  <td className="px-4 py-2 text-right font-medium text-green-700">{s.netAmount.toLocaleString()}</td>
                  <td className="px-4 py-2 text-right">
                    {s.sellerId != null && (
                      <button
                        onClick={() => setSellerFilter(s.sellerId)}
                        className="rounded border border-gray-300 px-2 py-1 text-xs hover:bg-gray-100"
                      >
                        항목 보기
                      </button>
                    )}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* 필터 툴바 */}
      <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
        <select
          value={sellerFilter ?? ""}
          onChange={(e) => setSellerFilter(e.target.value ? Number(e.target.value) : null)}
          className="rounded border border-gray-300 px-2 py-1"
        >
          <option value="">전체 셀러</option>
          {sellerOptions.map((s) => (
            <option key={String(s.sellerId)} value={String(s.sellerId)}>
              {s.sellerName ?? `셀러 #${s.sellerId}`}
            </option>
          ))}
        </select>
        <label className="text-gray-500">정산일</label>
        <input
          type="date"
          value={from}
          onChange={(e) => setFrom(e.target.value)}
          className="rounded border border-gray-300 px-2 py-1"
        />
        <span className="text-gray-400">~</span>
        <input
          type="date"
          value={to}
          onChange={(e) => setTo(e.target.value)}
          className="rounded border border-gray-300 px-2 py-1"
        />
        {(sellerFilter != null || from || to) && (
          <button
            onClick={() => {
              setSellerFilter(null);
              setFrom("");
              setTo("");
            }}
            className="rounded px-2 py-1 text-xs text-gray-500 underline hover:text-gray-700"
          >
            필터 초기화
          </button>
        )}
      </div>

      {/* 정산 항목 테이블 */}
      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
            <tr>
              <th className="px-4 py-3">ID</th>
              <th className="px-4 py-3">셀러</th>
              <th className="px-4 py-3">PG</th>
              <th className="px-4 py-3 text-right">매출</th>
              <th className="px-4 py-3 text-right">PG수수료</th>
              <th className="px-4 py-3 text-right">플랫폼수수료</th>
              <th className="px-4 py-3 text-right">실수령</th>
              <th className="px-4 py-3">상태</th>
              <th className="px-4 py-3">입금예정일</th>
              <th className="px-4 py-3 text-right">액션</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading ? (
              <tr>
                <td colSpan={10} className="px-4 py-8 text-center text-gray-400">
                  불러오는 중…
                </td>
              </tr>
            ) : items.length === 0 ? (
              <tr>
                <td colSpan={10} className="px-4 py-8 text-center text-gray-400">
                  정산 항목이 없습니다. “정산 배치 실행”을 눌러보세요.
                </td>
              </tr>
            ) : (
              items.map((s) => (
                <tr key={s.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 text-gray-400">#{s.id}</td>
                  <td className="px-4 py-3 font-medium">{sellerName(s.sellerId)}</td>
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
                  <td className="px-4 py-3 text-right">
                    {s.status === "SCHEDULED" ? (
                      <button
                        onClick={() => payout(s.id)}
                        disabled={payoutId === s.id}
                        className="rounded border border-gray-300 px-2 py-1 text-xs hover:bg-gray-100 disabled:opacity-50"
                      >
                        {payoutId === s.id ? "처리 중…" : "입금 처리"}
                      </button>
                    ) : (
                      <span className="text-xs text-gray-300">—</span>
                    )}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
