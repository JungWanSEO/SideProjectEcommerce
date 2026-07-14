"use client";

import { useCallback, useEffect, useState } from "react";
import { apiDownload, apiGet } from "@/lib/api";
import { AuditLog, AuditResult, PageResponse } from "@/lib/types";

/**
 * 감사 로그 화면 (/admin/audit, ADMIN) — 읽기전용.
 * 어드민 변경(@Auditable)을 AuditAspect가 자동 기록한 이력을 최신순으로 보여준다.
 * 필터: 결과(성공/실패)·대상 종류·액션 코드. (적재는 AOP가 하므로 이 화면은 조회만.)
 *
 * 감사는 "뽑아서 보관"이 용도라 **같은 필터 그대로 CSV 내보내기**를 제공하고,
 * 표에서 잘리는 detail은 **행 클릭 → 상세**로 전문을 본다.
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
  const [selected, setSelected] = useState<AuditLog | null>(null); // 행 클릭 → 상세
  const [exporting, setExporting] = useState(false);

  /** 현재 필터를 쿼리스트링으로 (목록·CSV가 같은 필터를 쓰도록 한 곳에서 만든다). */
  const filterQuery = useCallback(() => {
    const q = new URLSearchParams();
    if (result !== "ALL") q.set("result", result);
    if (targetType) q.set("targetType", targetType);
    if (action.trim()) q.set("action", action.trim());
    return q;
  }, [result, targetType, action]);

  const load = useCallback(() => {
    setLoading(true);
    const q = filterQuery();
    q.set("page", String(page));
    q.set("size", "20");
    apiGet<PageResponse<AuditLog>>(`/api/audit-logs?${q.toString()}`)
      .then((p) => {
        setLogs(p.content);
        setHasNext(p.hasNext);
        setTotalElements(p.totalElements);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [page, filterQuery]);

  useEffect(() => {
    load();
  }, [load]);

  /** 지금 보고 있는 필터 그대로 CSV로 (페이지는 빼고 — 파일은 전체 기간을 담는다). */
  const exportCsv = async () => {
    setExporting(true);
    setError(null);
    try {
      const q = filterQuery().toString();
      await apiDownload(
        `/api/audit-logs/export${q ? `?${q}` : ""}`,
        `audit-logs-${new Date().toISOString().slice(0, 10)}.csv`,
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setExporting(false);
    }
  };

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
      <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold">감사 로그</h1>
          <p className="text-sm text-gray-500">
            운영자의 변경 이력입니다. 상품·카테고리·브랜드·쿠폰·주문·셀러·회원 권한과 돈 흐름(정산·지급·대사) 변경이
            자동 기록되며(AOP), 실패한 시도도 함께 남습니다. 행을 클릭하면 상세를 봅니다.
          </p>
        </div>
        <button
          onClick={exportCsv}
          disabled={exporting}
          className="rounded border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-100 disabled:opacity-40"
        >
          {exporting ? "내보내는 중…" : "CSV 내보내기"}
        </button>
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
                <tr
                  key={log.id}
                  onClick={() => setSelected(log)}
                  className="cursor-pointer hover:bg-gray-50"
                >
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
                  <td className="max-w-xs truncate px-4 py-3 text-gray-400">{log.detail ?? "—"}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {selected && <AuditDetail log={selected} onClose={() => setSelected(null)} />}

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

/**
 * 감사 로그 상세 — 표에서 잘리는 detail(요청 URL·실패 메시지) 전문을 본다.
 * 배경 클릭·닫기 버튼으로 닫는다(간단한 모달 — 라이브러리 없이).
 */
function AuditDetail({ log, onClose }: { log: AuditLog; onClose: () => void }) {
  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4"
    >
      <div
        onClick={(e) => e.stopPropagation()} // 카드 안쪽 클릭은 닫지 않는다
        className="max-h-[80vh] w-full max-w-lg overflow-y-auto rounded-2xl border border-gray-200 bg-white p-6 shadow-xl"
      >
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-bold text-gray-900">감사 로그 #{log.id}</h2>
          <button onClick={onClose} className="text-sm text-gray-400 hover:text-gray-600">
            닫기
          </button>
        </div>

        <dl className="flex flex-col gap-3 text-sm">
          <Field label="시각" value={new Date(log.createdAt).toLocaleString("ko-KR")} />
          <Field
            label="행위자"
            value={log.actorEmail ?? (log.actorMemberId != null ? `회원 #${log.actorMemberId}` : "시스템")}
          />
          <Field label="액션" value={log.action} />
          <Field
            label="대상"
            value={log.targetType ? `${log.targetType}${log.targetId ? ` #${log.targetId}` : ""}` : "—"}
          />
          <Field label="결과" value={log.result === "SUCCESS" ? "성공" : "실패"} />
          <div>
            <dt className="text-xs text-gray-500">상세</dt>
            <dd className="mt-1 whitespace-pre-wrap break-all rounded bg-gray-50 p-3 font-mono text-xs text-gray-700">
              {log.detail ?? "—"}
            </dd>
          </div>
        </dl>
      </div>
    </div>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-4">
      <dt className="text-xs text-gray-500">{label}</dt>
      <dd className="text-right text-gray-800">{value}</dd>
    </div>
  );
}
