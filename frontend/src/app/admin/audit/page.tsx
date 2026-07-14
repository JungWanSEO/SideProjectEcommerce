"use client";

import { useCallback, useEffect, useState } from "react";
import { apiGet } from "@/lib/api";
import { AuditLog, AuditResult, PageResponse } from "@/lib/types";

/**
 * 감사 로그 화면 (/admin/audit, ADMIN) — 읽기전용.
 * 어드민 변경(@Auditable)을 AuditAspect가 자동 기록한 이력을 최신순으로 보여준다.
 * 필터: 결과(성공/실패)·대상 종류·액션 코드. (적재는 AOP가 하므로 이 화면은 조회만.)
 */
const RESULT_FILTERS: { value: AuditResult | "ALL"; label: string }[] = [
  { value: "ALL", label: "전체" },
  { value: "SUCCESS", label: "성공" },
  { value: "FAILURE", label: "실패" },
];

// 백엔드 @Auditable(targetType=...)에서 쓰는 대상 종류. 필터 드롭다운 옵션.
const TARGET_TYPES = [
  "PRODUCT",
  "CATEGORY",
  "BRAND",
  "COUPON",
  "ORDER",
  "SELLER",
  "MEMBER",
  "SETTLEMENT",
  "PAYOUT",
  "RECONCILIATION",
  "MISMATCH",
];

const RESULT_BADGE: Record<AuditResult, string> = {
  SUCCESS: "bg-emerald-50 text-emerald-700",
  FAILURE: "bg-red-50 text-red-700",
};

export default function AdminAuditPage() {
  const [logs, setLogs] = useState<AuditLog[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [result, setResult] = useState<AuditResult | "ALL">("ALL");
  const [targetType, setTargetType] = useState<string>("");
  const [action, setAction] = useState<string>("");
  const [page, setPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [totalElements, setTotalElements] = useState(0);

  const load = useCallback(() => {
    setLoading(true);
    const q = new URLSearchParams({ page: String(page), size: "20" });
    if (result !== "ALL") q.set("result", result);
    if (targetType) q.set("targetType", targetType);
    if (action.trim()) q.set("action", action.trim());
    apiGet<PageResponse<AuditLog>>(`/api/audit-logs?${q.toString()}`)
      .then((p) => {
        setLogs(p.content);
        setHasNext(p.hasNext);
        setTotalElements(p.totalElements);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [page, result, targetType, action]);

  useEffect(() => {
    load();
  }, [load]);

  // 필터 변경 시 첫 페이지로
  const changeResult = (v: AuditResult | "ALL") => {
    setResult(v);
    setPage(0);
  };
  const changeTargetType = (v: string) => {
    setTargetType(v);
    setPage(0);
  };

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-xl font-bold">감사 로그</h1>
        <p className="text-sm text-gray-500">
          운영자의 변경 이력입니다. 상품·카테고리·브랜드·쿠폰·주문·셀러·회원 권한과 돈 흐름(정산·지급·대사) 변경이
          자동 기록되며(AOP), 실패한 시도도 함께 남습니다.
        </p>
      </div>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>
      )}

      {/* 필터 */}
      <div className="mb-4 flex flex-wrap items-center gap-2">
        {RESULT_FILTERS.map((f) => (
          <button
            key={f.value}
            onClick={() => changeResult(f.value)}
            className={`rounded-full border px-3 py-1 text-sm ${
              result === f.value
                ? "border-gray-900 bg-gray-900 text-white"
                : "border-gray-300 bg-white text-gray-600 hover:bg-gray-100"
            }`}
          >
            {f.label}
          </button>
        ))}
        <span className="mx-1 h-4 w-px bg-gray-200" />
        <select
          value={targetType}
          onChange={(e) => changeTargetType(e.target.value)}
          className="rounded border border-gray-300 bg-white px-3 py-1 text-sm text-gray-700"
        >
          <option value="">전체 대상</option>
          {TARGET_TYPES.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
        <input
          value={action}
          onChange={(e) => {
            setAction(e.target.value);
            setPage(0);
          }}
          placeholder="액션 코드 (예: PRODUCT_UPDATE)"
          className="w-64 rounded border border-gray-300 bg-white px-3 py-1 text-sm text-gray-700"
        />
      </div>

      {/* 목록 */}
      <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
            <tr>
              <th className="px-4 py-3">시각</th>
              <th className="px-4 py-3">행위자</th>
              <th className="px-4 py-3">액션</th>
              <th className="px-4 py-3">대상</th>
              <th className="px-4 py-3">결과</th>
              <th className="px-4 py-3">상세</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading ? (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-gray-400">
                  불러오는 중…
                </td>
              </tr>
            ) : logs.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-gray-400">
                  감사 로그가 없습니다.
                </td>
              </tr>
            ) : (
              logs.map((log) => (
                <tr key={log.id} className="hover:bg-gray-50">
                  <td className="whitespace-nowrap px-4 py-3 text-gray-500">
                    {new Date(log.createdAt).toLocaleString("ko-KR")}
                  </td>
                  <td className="px-4 py-3 text-gray-600">
                    {log.actorEmail ?? (log.actorMemberId != null ? `회원 #${log.actorMemberId}` : <span className="text-gray-300">시스템</span>)}
                  </td>
                  <td className="whitespace-nowrap px-4 py-3 font-medium text-gray-900">{log.action}</td>
                  <td className="whitespace-nowrap px-4 py-3 text-gray-600">
                    {log.targetType ?? <span className="text-gray-300">—</span>}
                    {log.targetId && <span className="text-gray-400"> #{log.targetId}</span>}
                  </td>
                  <td className="px-4 py-3">
                    <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${RESULT_BADGE[log.result]}`}>
                      {log.result === "SUCCESS" ? "성공" : "실패"}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-400">{log.detail ?? "—"}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* 페이지네이션 */}
      <div className="mt-4 flex items-center justify-between text-sm text-gray-500">
        <span>총 {totalElements.toLocaleString()}건</span>
        <div className="flex items-center gap-2">
          <button
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0 || loading}
            className="rounded border border-gray-300 px-3 py-1 hover:bg-gray-100 disabled:opacity-40"
          >
            이전
          </button>
          <span>{page + 1} 페이지</span>
          <button
            onClick={() => setPage((p) => p + 1)}
            disabled={!hasNext || loading}
            className="rounded border border-gray-300 px-3 py-1 hover:bg-gray-100 disabled:opacity-40"
          >
            다음
          </button>
        </div>
      </div>
    </div>
  );
}
