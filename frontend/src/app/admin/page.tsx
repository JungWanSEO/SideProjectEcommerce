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
import { apiGet } from "@/lib/api";
import { Dashboard } from "@/lib/types";
import { ORDER_STATUS_BADGE, ORDER_STATUS_LABEL } from "@/lib/orderStatus";

/**
 * 어드민 대시보드 (/admin 랜딩) — 운영 요약 한 화면.
 * KPI 카드 + 주문 상태별 분포 + 최근 매출 추이(recharts). 데이터는 GET /api/dashboard 한 번으로 받는다.
 * 권한 게이팅·셸은 admin/layout.tsx가 담당하므로 여기선 ADMIN을 가정한다.
 */
export default function AdminDashboardPage() {
  const [days, setDays] = useState(30); // 매출 추이 기간(7/30일)
  const [data, setData] = useState<Dashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    apiGet<Dashboard>(`/api/dashboard?days=${days}`)
      .then(setData)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [days]);

  return (
    <div>
      <h1 className="mb-6 text-2xl font-bold text-ink">대시보드</h1>

      {error && <p className="mb-4 text-sm text-danger">{error}</p>}
      {loading && !data ? (
        <p className="text-gray-500">불러오는 중…</p>
      ) : data ? (
        <div className="flex flex-col gap-8">
          {/* KPI 카드 */}
          <section className="grid grid-cols-2 gap-4 lg:grid-cols-5">
            <KpiCard label="전체 주문" value={`${data.kpi.totalOrders.toLocaleString()}건`} />
            <KpiCard label="결제완료 매출" value={formatWon(data.kpi.paidRevenue)} accent />
            <KpiCard label="정산 대기" value={formatWon(data.kpi.pendingSettlement)} />
            <KpiCard label="회원 수" value={`${data.kpi.memberCount.toLocaleString()}명`} />
            <KpiCard label="판매 중 상품" value={`${data.kpi.activeProductCount.toLocaleString()}개`} />
          </section>

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
              <h2 className="text-sm font-semibold text-gray-500">매출 추이 (결제완료 기준)</h2>
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
                    formatter={(v) => [formatWon(Number(v)), "매출"]}
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
