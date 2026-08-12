"use client";

import { useCallback, useEffect, useState } from "react";
import { apiGet, apiPost } from "@/lib/api";
import { PageResponse, Payout, Seller } from "@/lib/types";

/**
 * 지급 묶음(Payout) 운영 화면 (/admin/payouts, ADMIN).
 * 셀러+기간으로 SCHEDULED 정산 항목을 묶어 한 번에 지급(PENDING → PAID).
 */
export default function AdminPayoutsPage() {
  const [payouts, setPayouts] = useState<Payout[]>([]);
  const [sellers, setSellers] = useState<Seller[]>([]);
  const [sellerId, setSellerId] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [payingId, setPayingId] = useState<number | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    Promise.all([
      apiGet<PageResponse<Payout>>("/api/payouts?size=100"),
      apiGet<Seller[]>("/api/sellers"),
    ])
      .then(([page, sl]) => {
        setPayouts(page.content);
        setSellers(sl);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const create = async () => {
    if (!sellerId || !from || !to) {
      setError("셀러와 기간(시작·끝)을 모두 선택하세요.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await apiPost<Payout>("/api/payouts", { sellerId: Number(sellerId), from, to });
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const pay = async (id: number) => {
    setPayingId(id);
    setError(null);
    try {
      await apiPost<Payout>(`/api/payouts/${id}/pay`);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPayingId(null);
    }
  };

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-xl font-bold">지급 묶음</h1>
        <p className="text-sm text-gray-500">셀러의 정산 항목을 기간으로 묶어 한 번에 지급합니다(PENDING → PAID).</p>
      </div>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>
      )}

      {/* 생성 폼 */}
      <div className="mb-6 flex flex-wrap items-end gap-2 rounded-lg border border-gray-200 bg-white p-4 text-sm">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-gray-500">셀러</span>
          <select
            value={sellerId}
            onChange={(e) => setSellerId(e.target.value)}
            className="rounded border border-gray-300 px-2 py-1"
          >
            <option value="">선택</option>
            {sellers.map((s) => (
              <option key={s.id} value={String(s.id)}>
                {s.name}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-gray-500">정산일 시작</span>
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="rounded border border-gray-300 px-2 py-1" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-gray-500">정산일 끝</span>
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="rounded border border-gray-300 px-2 py-1" />
        </label>
        <button
          onClick={create}
          disabled={busy}
          className="rounded bg-gray-900 px-4 py-2 font-medium text-white hover:bg-gray-700 disabled:opacity-50"
        >
          {busy ? "생성 중…" : "지급 묶음 생성"}
        </button>
      </div>

      {/* 목록 */}
      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
            <tr>
              <th className="px-4 py-3">ID</th>
              <th className="px-4 py-3">셀러</th>
              <th className="px-4 py-3">기간</th>
              <th className="px-4 py-3 text-right">건수</th>
              <th className="px-4 py-3 text-right">매출</th>
              <th className="px-4 py-3 text-right">수수료합</th>
              <th className="px-4 py-3 text-right">이월</th>
              <th className="px-4 py-3 text-right">실지급액</th>
              <th className="px-4 py-3">상태</th>
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
            ) : payouts.length === 0 ? (
              <tr>
                <td colSpan={10} className="px-4 py-8 text-center text-gray-400">
                  지급 묶음이 없습니다. 위에서 셀러+기간으로 생성하세요.
                </td>
              </tr>
            ) : (
              payouts.map((p) => (
                <tr key={p.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 text-gray-400">#{p.id}</td>
                  <td className="px-4 py-3 font-medium">{p.sellerName ?? `셀러 #${p.sellerId}`}</td>
                  <td className="px-4 py-3 text-gray-500">
                    {p.periodFrom} ~ {p.periodTo}
                  </td>
                  <td className="px-4 py-3 text-right text-gray-500">{p.entryCount}</td>
                  <td className="px-4 py-3 text-right">{p.totalGross.toLocaleString()}</td>
                  <td className="px-4 py-3 text-right text-amber-600">
                    −{(p.totalFee + p.totalPlatformFee).toLocaleString()}
                  </td>
                  {/*
                    이월(#8 후속) — 반품 역분개·셀러 귀책 과금이 그 기간 매출을 넘으면 지급은 0원이 되고
                    부족분이 다음 기간으로 넘어간다. 예전엔 지급 묶음 자체를 안 만들어(400) 정상 매출까지
                    통째로 막혔고, 셀러는 "왜 안 나왔는지"를 알 방법이 없었다.
                  */}
                  <td className="px-4 py-3 text-right text-xs">
                    {p.carriedIn !== 0 && (
                      <div className="text-amber-700">전월 {p.carriedIn.toLocaleString()}</div>
                    )}
                    {p.carriedOver !== 0 && (
                      <div className="text-red-600">차월 {p.carriedOver.toLocaleString()}</div>
                    )}
                    {p.carriedIn === 0 && p.carriedOver === 0 && <span className="text-gray-300">—</span>}
                  </td>
                  <td className="px-4 py-3 text-right font-medium text-green-700">{p.totalNet.toLocaleString()}</td>
                  <td className="px-4 py-3">
                    <span
                      className={`rounded px-2 py-0.5 text-xs ${
                        p.status === "PAID" ? "bg-green-100 text-green-700" : "bg-amber-100 text-amber-700"
                      }`}
                    >
                      {p.status === "PAID" ? "지급완료" : "지급대기"}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right">
                    {p.status === "PENDING" ? (
                      <button
                        onClick={() => pay(p.id)}
                        disabled={payingId === p.id}
                        className="rounded border border-gray-300 px-2 py-1 text-xs hover:bg-gray-100 disabled:opacity-50"
                      >
                        {payingId === p.id ? "처리 중…" : "지급 처리"}
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
