"use client";

import { useCallback, useEffect, useState } from "react";
import { apiGet, apiPatch } from "@/lib/api";
import { Member, MemberRole, MemberRoleUpdateInput, PageResponse } from "@/lib/types";
import { useAuth } from "@/lib/auth";

/**
 * 회원 관리 화면 (/admin/members, ADMIN).
 * 목록·검색(이메일·닉네임)·권한 필터 + 인라인 권한 변경(USER ↔ ADMIN).
 *
 * 권한 변경은 백엔드에서 @Auditable로 감사 로그에 남는다(누가 누구를 언제 승격/강등했는지).
 * SELLER는 여기서 지정할 수 없다 — 셀러 연결(sellerId)이 필요해 셀러 운영자 지정 API를 거쳐야 한다.
 * 권한 게이팅·셸은 admin/layout.tsx가 담당하므로 여기선 ADMIN을 가정한다.
 */
const ROLE_FILTERS: { value: MemberRole | "ALL"; label: string }[] = [
  { value: "ALL", label: "전체" },
  { value: "USER", label: "일반" },
  { value: "SELLER", label: "셀러" },
  { value: "ADMIN", label: "관리자" },
];

const ROLE_LABEL: Record<MemberRole, string> = {
  USER: "일반",
  SELLER: "셀러",
  ADMIN: "관리자",
};

const ROLE_BADGE: Record<MemberRole, string> = {
  USER: "bg-gray-100 text-gray-600",
  SELLER: "bg-blue-50 text-blue-700",
  ADMIN: "bg-emerald-50 text-emerald-700",
};

export default function AdminMembersPage() {
  const { user } = useAuth();

  const [members, setMembers] = useState<Member[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [role, setRole] = useState<MemberRole | "ALL">("ALL");
  const [keyword, setKeyword] = useState("");
  const [page, setPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [totalElements, setTotalElements] = useState(0);

  const load = useCallback(() => {
    setLoading(true);
    const q = new URLSearchParams({ page: String(page), size: "20" });
    if (role !== "ALL") q.set("role", role);
    if (keyword.trim()) q.set("keyword", keyword.trim());
    apiGet<PageResponse<Member>>(`/api/members/admin?${q.toString()}`)
      .then((p) => {
        setMembers(p.content);
        setHasNext(p.hasNext);
        setTotalElements(p.totalElements);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [page, role, keyword]);

  useEffect(() => {
    load();
  }, [load]);

  const changeRoleFilter = (v: MemberRole | "ALL") => {
    setRole(v);
    setPage(0);
  };

  /** 권한 변경(USER ↔ ADMIN). 되돌리기 쉬운 변경이 아니므로 확인을 받는다. */
  const changeRole = async (member: Member, next: MemberRole) => {
    if (next === member.role) return;
    if (!confirm(`'${member.email}' 회원의 권한을 '${ROLE_LABEL[next]}'(으)로 바꿀까요?`)) return;
    setBusy(true);
    setError(null);
    const body: MemberRoleUpdateInput = { role: next };
    try {
      const updated = await apiPatch<Member>(`/api/members/${member.id}/role`, body);
      setMembers((prev) => prev.map((m) => (m.id === updated.id ? updated : m)));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-xl font-bold">회원</h1>
        <p className="text-sm text-gray-500">
          가입 최신순입니다. 이메일·닉네임으로 검색하고 권한을 일반 ↔ 관리자로 바꿀 수 있습니다(변경은 감사 로그에
          기록). 셀러 지정은 셀러 운영자 지정에서 합니다.
        </p>
      </div>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {/* 필터 */}
      <div className="mb-4 flex flex-wrap items-center gap-2">
        {ROLE_FILTERS.map((f) => (
          <button
            key={f.value}
            onClick={() => changeRoleFilter(f.value)}
            className={`rounded-full border px-3 py-1 text-sm ${
              role === f.value
                ? "border-gray-900 bg-gray-900 text-white"
                : "border-gray-300 bg-white text-gray-600 hover:bg-gray-100"
            }`}
          >
            {f.label}
          </button>
        ))}
        <span className="mx-1 h-4 w-px bg-gray-200" />
        <input
          value={keyword}
          onChange={(e) => {
            setKeyword(e.target.value);
            setPage(0);
          }}
          placeholder="이메일 · 닉네임 검색"
          className="w-64 rounded border border-gray-300 bg-white px-3 py-1 text-sm text-gray-700"
        />
      </div>

      {/* 목록 */}
      <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
            <tr>
              <th className="px-4 py-3">가입일</th>
              <th className="px-4 py-3">이메일</th>
              <th className="px-4 py-3">닉네임</th>
              <th className="px-4 py-3">권한</th>
              <th className="px-4 py-3">셀러</th>
              <th className="px-4 py-3">권한 변경</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading ? (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-gray-400">
                  불러오는 중…
                </td>
              </tr>
            ) : members.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-gray-400">
                  회원이 없습니다.
                </td>
              </tr>
            ) : (
              members.map((m) => {
                const isSelf = m.id === user?.id;
                return (
                  <tr key={m.id} className="hover:bg-gray-50">
                    <td className="whitespace-nowrap px-4 py-3 text-gray-500">
                      {new Date(m.createdAt).toLocaleDateString("ko-KR")}
                    </td>
                    <td className="px-4 py-3 font-medium text-gray-900">
                      {m.email}
                      {isSelf && <span className="ml-2 text-xs text-gray-400">(나)</span>}
                    </td>
                    <td className="px-4 py-3 text-gray-600">{m.nickname}</td>
                    <td className="px-4 py-3">
                      <span
                        className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${ROLE_BADGE[m.role]}`}
                      >
                        {ROLE_LABEL[m.role]}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-gray-500">
                      {m.sellerId != null ? `#${m.sellerId}` : <span className="text-gray-300">—</span>}
                    </td>
                    <td className="px-4 py-3">
                      {/* 자기 자신은 백엔드가 409로 막는다(관리자 잠금 방지) → 화면에서도 비활성 */}
                      <select
                        value={m.role}
                        onChange={(e) => changeRole(m, e.target.value as MemberRole)}
                        disabled={busy || isSelf}
                        className="rounded border border-gray-300 px-2 py-1 text-sm text-gray-800 disabled:bg-gray-50 disabled:text-gray-400"
                      >
                        <option value="USER">일반</option>
                        <option value="ADMIN">관리자</option>
                        {/* SELLER는 셀러 연결이 필요해 여기서 지정 불가 — 현재 셀러인 회원만 표시용으로 둔다 */}
                        {m.role === "SELLER" && <option value="SELLER">셀러</option>}
                      </select>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {/* 페이지네이션 */}
      <div className="mt-4 flex items-center justify-between text-sm text-gray-500">
        <span>총 {totalElements.toLocaleString()}명</span>
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
