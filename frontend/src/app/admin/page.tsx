"use client";

import { useEffect, useState } from "react";
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import Link from "next/link";
import { apiGet } from "@/lib/api";
import { CacheStats, CancelReasonStats, Dashboard, LowStockReport } from "@/lib/types";
import { ORDER_STATUS_BADGE, ORDER_STATUS_LABEL } from "@/lib/orderStatus";
import DashboardSkeleton from "@/components/admin/DashboardSkeleton";

/**
 * 어드민 대시보드 (/admin 랜딩) — 운영 요약 한 화면.
 * KPI 카드 + 주문 상태별 분포 + 최근 매출 추이(recharts) + 재고 임박·품절.
 * 매출은 GET /api/dashboard, 재고는 GET /api/dashboard/low-stock(캐시 안 함 — 재고는 실시간이어야 한다).
 * 권한 게이팅·셸은 admin/layout.tsx가 담당하므로 여기선 ADMIN을 가정한다.
 */
export default function AdminDashboardPage() {
  const [days, setDays] = useState(30); // 매출 추이 기간(7/30일)
  const [data, setData] = useState<Dashboard | null>(null);
  const [cacheStats, setCacheStats] = useState<CacheStats[]>([]); // 캐시 적중률(부가 정보)
  const [lowStock, setLowStock] = useState<LowStockReport | null>(null);
  const [threshold, setThreshold] = useState(5); // 임박 기준 재고(이하)
  const [reasons, setReasons] = useState<CancelReasonStats | null>(null); // 취소·반품 사유 집계(#8)
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    apiGet<Dashboard>(`/api/dashboard?days=${days}`)
      .then(setData)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [days]);

  // 캐시 적중률은 부가 정보 — 실패해도 대시보드는 정상(에러 무시). Redis 모드면 빈 목록.
  useEffect(() => {
    apiGet<CacheStats[]>("/api/monitoring/caches")
      .then(setCacheStats)
      .catch(() => setCacheStats([]));
  }, []);

  // 취소·반품 사유 집계 — 부가 정보라 실패는 무시(위젯만 숨김). 전체 기간 기준이라 파라미터 없음.
  useEffect(() => {
    apiGet<CancelReasonStats>("/api/dashboard/cancel-reasons")
      .then(setReasons)
      .catch(() => setReasons(null));
  }, []);

  // 재고 임박·품절 — 기준 재고를 바꾸면 다시 조회.
  useEffect(() => {
    apiGet<LowStockReport>(`/api/dashboard/low-stock?threshold=${threshold}&limit=10`)
      .then(setLowStock)
      .catch(() => setLowStock(null));
  }, [threshold]);

  return (
    <div>
      <h1 className="mb-6 text-2xl font-bold text-ink">대시보드</h1>

      {error && <p className="mb-4 text-sm text-danger">{error}</p>}
      {loading && !data ? (
        <DashboardSkeleton />
      ) : data ? (
        <div className="flex flex-col gap-8">
          {/* KPI 카드 */}
          <section className="grid grid-cols-2 gap-4 lg:grid-cols-5">
            <KpiCard label="전체 주문" value={`${data.kpi.totalOrders.toLocaleString()}건`} />
            <KpiCard label="순매출 (환불 차감)" value={formatWon(data.kpi.netRevenue)} accent />
            <KpiCard label="정산 대기" value={formatWon(data.kpi.pendingSettlement)} />
            <KpiCard label="회원 수" value={`${data.kpi.memberCount.toLocaleString()}명`} />
            <KpiCard label="판매 중 상품" value={`${data.kpi.activeProductCount.toLocaleString()}개`} />
          </section>

          {/* 재고 임박·품절 — 매출만 보면 놓치는 손실(품절 방치). 재고는 옵션(사이즈) 단위. */}
          {lowStock && (
            <section className="rounded-2xl border border-gray-200 bg-white p-5">
              <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                <div className="flex items-center gap-3">
                  <h2 className="text-sm font-semibold text-gray-500">재고 임박·품절</h2>
                  <span className="rounded-full bg-red-50 px-2.5 py-0.5 text-xs font-medium text-red-700">
                    품절 {lowStock.soldOutCount.toLocaleString()}
                  </span>
                  <span className="rounded-full bg-amber-50 px-2.5 py-0.5 text-xs font-medium text-amber-700">
                    임박 {lowStock.lowStockCount.toLocaleString()}
                  </span>
                </div>
                <div className="flex items-center gap-1 text-xs text-gray-500">
                  <span>기준 재고</span>
                  {[3, 5, 10].map((t) => (
                    <button
                      key={t}
                      onClick={() => setThreshold(t)}
                      className={`rounded-full px-3 py-1 ${
                        threshold === t ? "bg-gray-900 text-white" : "text-gray-600 hover:bg-gray-100"
                      }`}
                    >
                      ≤{t}
                    </button>
                  ))}
                </div>
              </div>

              {lowStock.items.length === 0 ? (
                <p className="py-6 text-center text-sm text-gray-400">
                  재고 {threshold}개 이하인 옵션이 없습니다.
                </p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-gray-100 text-left text-xs text-gray-500">
                        <th className="py-2 pr-4">상품</th>
                        <th className="py-2 pr-4">사이즈</th>
                        <th className="py-2 pr-4 text-right">재고</th>
                        <th className="py-2 text-right">상태</th>
                      </tr>
                    </thead>
                    <tbody>
                      {lowStock.items.map((i) => (
                        <tr key={i.optionId} className="border-b border-gray-50">
                          <td className="py-2 pr-4 font-medium text-ink">{i.productName}</td>
                          <td className="py-2 pr-4 text-gray-600">{i.size}</td>
                          <td
                            className={`py-2 pr-4 text-right font-medium ${
                              i.stock === 0 ? "text-red-600" : "text-amber-600"
                            }`}
                          >
                            {i.stock}
                          </td>
                          <td className="py-2 text-right">
                            <span
                              className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                                i.stock === 0
                                  ? "bg-red-50 text-red-700"
                                  : "bg-amber-50 text-amber-700"
                              }`}
                            >
                              {i.stock === 0 ? "품절" : "임박"}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              <p className="mt-2 text-xs text-gray-400">
                재고 적은 순 상위 {lowStock.items.length}건(판매중지 상품 제외). 재고 보충은{" "}
                <Link href="/admin/products" className="underline hover:text-gray-600">
                  상품 관리
                </Link>
                의 옵션 수정에서 합니다.
              </p>
            </section>
          )}

          {/* 취소·반품 사유(#8) — "왜 이탈했는가". 사유가 enum으로 구조화돼 있어 집계가 가능하다.
              귀책(fault)은 사유가 들고 있는 메타 — 셀러 귀책 비중이 높아지면 정책(정산 귀책·왕복 배송비)으로 이어질 지점. */}
          {reasons && reasons.byReason.length > 0 && (
            <section className="rounded-2xl border border-gray-200 bg-white p-5">
              <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                <div className="flex flex-wrap items-center gap-3">
                  <h2 className="text-sm font-semibold text-gray-500">취소·반품 사유</h2>
                  <span className="rounded-full bg-gray-100 px-2.5 py-0.5 text-xs font-medium text-gray-700">
                    취소 {reasons.totalCancelledItems.toLocaleString()}건
                  </span>
                  <span className="rounded-full bg-gray-100 px-2.5 py-0.5 text-xs font-medium text-gray-700">
                    반품 {reasons.totalReturns.toLocaleString()}건
                  </span>
                </div>
                <div className="flex flex-wrap items-center gap-2 text-xs">
                  {reasons.byFault.map((f) => (
                    <span key={f.fault} className={`rounded-full px-2.5 py-0.5 font-medium ${FAULT_BADGE[f.fault] ?? "bg-gray-100 text-gray-700"}`}>
                      {FAULT_LABEL[f.fault] ?? f.fault} {f.total.toLocaleString()}
                    </span>
                  ))}
                </div>
              </div>

              <ul className="flex flex-col gap-2">
                {reasons.byReason.map((r) => {
                  const max = reasons.byReason[0].total || 1; // 최다 사유 기준 상대 막대
                  return (
                    <li key={r.reason} className="flex items-center gap-3">
                      <span className="w-28 shrink-0 text-sm text-gray-700">{REASON_LABEL[r.reason] ?? r.reason}</span>
                      <span className={`w-14 shrink-0 rounded-full px-2 py-0.5 text-center text-[11px] font-medium ${FAULT_BADGE[r.fault] ?? "bg-gray-100 text-gray-700"}`}>
                        {FAULT_LABEL[r.fault] ?? r.fault}
                      </span>
                      <span className="h-2 flex-1 overflow-hidden rounded-full bg-gray-100">
                        <span
                          className="block h-full rounded-full bg-gray-800"
                          style={{ width: `${Math.round((r.total / max) * 100)}%` }}
                        />
                      </span>
                      <span className="w-24 shrink-0 text-right text-xs text-gray-500">
                        취소 {r.cancelCount} · 반품 {r.returnCount}
                      </span>
                      <span className="w-10 shrink-0 text-right text-sm font-medium text-ink">{r.total}</span>
                    </li>
                  );
                })}
              </ul>

              {(reasons.unrecordedCancels > 0 || reasons.unrecordedReturns > 0) && (
                <p className="mt-3 text-xs text-gray-400">
                  사유 미기록 — 취소 {reasons.unrecordedCancels.toLocaleString()}건 · 반품{" "}
                  {reasons.unrecordedReturns.toLocaleString()}건 (사유 도입 이전 데이터·시스템 취소). 사유별 합계와
                  더해야 전체 건수가 됩니다.
                </p>
              )}
            </section>
          )}

          {/* 주문 상태별 분포 */}
          <section className="rounded-2xl border border-gray-200 bg-white p-5">
            <h2 className="mb-4 text-sm font-semibold text-gray-500">주문 상태별 분포</h2>
            <div className="flex flex-wrap gap-3">
              {data.orderStatusDistribution.map((s) => (
                <div
                  key={s.status}
                  className="flex min-w-[120px] flex-1 flex-col gap-1 rounded-xl border border-gray-100 bg-gray-50 p-4"
                >
                  <span
                    className={`inline-flex w-fit items-center rounded-full px-2 py-0.5 text-xs font-medium ${ORDER_STATUS_BADGE[s.status]}`}
                  >
                    {ORDER_STATUS_LABEL[s.status]}
                  </span>
                  <span className="text-2xl font-bold text-ink">{s.count.toLocaleString()}</span>
                </div>
              ))}
            </div>
          </section>

          {/* 매출 추이 */}
          <section className="rounded-2xl border border-gray-200 bg-white p-5">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="text-sm font-semibold text-gray-500">순매출 추이 (환불 차감)</h2>
              <div className="flex gap-1">
                {[7, 30].map((d) => (
                  <button
                    key={d}
                    onClick={() => setDays(d)}
                    className={`rounded-full px-3 py-1 text-xs ${
                      days === d ? "bg-gray-900 text-white" : "text-gray-600 hover:bg-gray-100"
                    }`}
                  >
                    {d}일
                  </button>
                ))}
              </div>
            </div>
            <div className="h-72 w-full">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={data.revenueTrend} margin={{ top: 8, right: 12, bottom: 0, left: 4 }}>
                  <defs>
                    <linearGradient id="revFill" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="#c06b4c" stopOpacity={0.35} />
                      <stop offset="100%" stopColor="#c06b4c" stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="#e8e0d4" vertical={false} />
                  <XAxis
                    dataKey="date"
                    tickFormatter={(d: string) => d.slice(5)} // MM-DD
                    tick={{ fontSize: 11, fill: "#908779" }}
                    minTickGap={20}
                  />
                  <YAxis
                    tickFormatter={formatWonCompact}
                    tick={{ fontSize: 11, fill: "#908779" }}
                    width={48}
                  />
                  <Tooltip
                    formatter={(v) => [formatWon(Number(v)), "순매출"]}
                    labelFormatter={(label) => String(label)}
                    contentStyle={{ borderRadius: 12, border: "1px solid #e8e0d4", fontSize: 12 }}
                  />
                  <Area
                    type="monotone"
                    dataKey="revenue"
                    stroke="#c06b4c"
                    strokeWidth={2}
                    fill="url(#revFill)"
                  />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          </section>

          {/* 시스템 — 캐시 적중률 (부가 정보, Caffeine 모드) */}
          {cacheStats.length > 0 && (
            <section className="rounded-2xl border border-gray-200 bg-white p-5">
              <h2 className="mb-4 text-sm font-semibold text-gray-500">시스템 — 캐시 적중률</h2>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-gray-100 text-left text-xs text-gray-500">
                      <th className="py-2 pr-4">캐시</th>
                      <th className="py-2 pr-4 text-right">적중률</th>
                      <th className="py-2 pr-4 text-right">요청</th>
                      <th className="py-2 pr-4 text-right">hit</th>
                      <th className="py-2 pr-4 text-right">miss</th>
                      <th className="py-2 text-right">크기</th>
                    </tr>
                  </thead>
                  <tbody>
                    {cacheStats.map((c) => (
                      <tr key={c.cacheName} className="border-b border-gray-50">
                        <td className="py-2 pr-4 font-medium text-ink">{c.cacheName}</td>
                        <td className="py-2 pr-4 text-right">{(c.hitRate * 100).toFixed(1)}%</td>
                        <td className="py-2 pr-4 text-right text-gray-500">
                          {c.requestCount.toLocaleString()}
                        </td>
                        <td className="py-2 pr-4 text-right text-sage-600">{c.hitCount.toLocaleString()}</td>
                        <td className="py-2 pr-4 text-right text-gray-400">{c.missCount.toLocaleString()}</td>
                        <td className="py-2 text-right text-gray-500">{c.estimatedSize.toLocaleString()}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="mt-2 text-xs text-gray-400">
                Caffeine 누적 통계(프로세스 시작 이후). Redis 모드에선 비어 있음 — Grafana에서 확인.
              </p>
            </section>
          )}
        </div>
      ) : null}
    </div>
  );
}

/** KPI 한 칸. accent면 포인트색 강조(매출). */
function KpiCard({ label, value, accent }: { label: string; value: string; accent?: boolean }) {
  return (
    <div className="rounded-2xl border border-gray-200 bg-white p-5">
      <p className="text-xs text-gray-500">{label}</p>
      <p className={`mt-2 text-2xl font-bold ${accent ? "text-clay" : "text-ink"}`}>{value}</p>
    </div>
  );
}

/** 금액 표시 — "1,234,000원" */
function formatWon(n: number): string {
  return `${n.toLocaleString()}원`;
}

/** 축 눈금용 간략 표기 — 1만 이상은 "만" 단위, 그 미만은 그대로. */
function formatWonCompact(n: number): string {
  if (n >= 10000) return `${Math.round(n / 10000).toLocaleString()}만`;
  return n.toLocaleString();
}

/** 사유 코드 → 한글 라벨(백엔드 CancelReason enum과 1:1). 없는 코드는 코드 그대로 표시된다. */
const REASON_LABEL: Record<string, string> = {
  CHANGE_OF_MIND: "단순 변심",
  WRONG_ORDER: "주문 실수",
  DELIVERY_DELAY: "배송 지연",
  OUT_OF_STOCK: "품절",
  DEFECTIVE: "상품 불량",
  WRONG_DELIVERY: "오배송",
  OTHER: "기타",
};

/** 귀책 라벨·색 — 셀러 귀책은 눈에 띄어야 한다(정책·비용 부담으로 이어지는 신호). */
const FAULT_LABEL: Record<string, string> = {
  CUSTOMER: "고객",
  SELLER: "셀러",
  PLATFORM: "플랫폼",
  NONE: "기타",
};

const FAULT_BADGE: Record<string, string> = {
  CUSTOMER: "bg-blue-50 text-blue-700",
  SELLER: "bg-amber-50 text-amber-700",
  PLATFORM: "bg-purple-50 text-purple-700",
  NONE: "bg-gray-100 text-gray-700",
};
