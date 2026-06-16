"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { apiGet, apiPost } from "@/lib/api";
import { Brand, BrandCreateInput, Seller } from "@/lib/types";

/**
 * 브랜드 관리 화면 (/admin/brands, ADMIN).
 * 브랜드를 등록하고 목록(셀러 귀속은 표시만)을 본다. 카테고리 화면과 대칭·동일 톤.
 * 셀러 귀속 변경은 기존 PUT /api/brands/{id}/seller 로 후속 — 여기선 현황만 노출.
 */
export default function AdminBrandsPage() {
  const [brands, setBrands] = useState<Brand[]>([]);
  const [sellers, setSellers] = useState<Seller[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 생성 폼 상태
  const [name, setName] = useState("");

  const load = useCallback(() => {
    setLoading(true);
    Promise.all([apiGet<Brand[]>("/api/brands"), apiGet<Seller[]>("/api/sellers")])
      .then(([bs, sl]) => {
        setBrands(bs);
        setSellers(sl);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  // sellerId → 셀러명 매핑(목록 표시용)
  const sellerName = useMemo(() => {
    const m = new Map<number, string>();
    sellers.forEach((s) => m.set(s.id, s.name));
    return m;
  }, [sellers]);

  const assignedCount = useMemo(() => brands.filter((b) => b.sellerId !== null).length, [brands]);

  const create = async () => {
    if (!name.trim()) {
      setError("브랜드명은 필수입니다.");
      return;
    }
    setBusy(true);
    setError(null);
    const body: BrandCreateInput = { name: name.trim() };
    try {
      await apiPost<Brand>("/api/brands", body);
      setName("");
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-xl font-bold">브랜드 관리</h1>
        <p className="text-sm text-gray-500">
          브랜드를 등록합니다. 셀러 귀속은 현황만 표시하며, 변경은 후속(PUT /api/brands/{`{id}`}/seller) 예정.
        </p>
      </div>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>
      )}

      {/* KPI */}
      <div className="mb-6 grid grid-cols-2 gap-4">
        <div className="rounded-lg border border-gray-200 bg-white px-4 py-3">
          <div className="text-xs text-gray-500">전체 브랜드</div>
          <div className="mt-1 text-lg font-bold">{brands.length}개</div>
        </div>
        <div className="rounded-lg border border-gray-200 bg-white px-4 py-3">
          <div className="text-xs text-gray-500">셀러 귀속</div>
          <div className="mt-1 text-lg font-bold">{assignedCount}개</div>
        </div>
      </div>

      {/* 등록 폼 */}
      <div className="mb-6 rounded-lg border border-gray-200 bg-white p-4">
        <div className="mb-3 text-sm font-semibold text-gray-700">브랜드 등록</div>
        <div className="grid grid-cols-1 gap-3 text-sm sm:grid-cols-3">
          <label className="flex flex-col gap-1 sm:col-span-2">
            <span className="text-xs text-gray-500">이름</span>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="나이키"
              className="rounded border border-gray-300 px-2 py-1"
            />
          </label>
          <div className="flex items-end">
            <button
              onClick={create}
              disabled={busy}
              className="rounded bg-gray-900 px-4 py-2 text-sm font-medium text-white hover:bg-gray-700 disabled:opacity-50"
            >
              {busy ? "등록 중…" : "브랜드 등록"}
            </button>
          </div>
        </div>
      </div>

      {/* 목록 */}
      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
            <tr>
              <th className="px-4 py-3">ID</th>
              <th className="px-4 py-3">브랜드명</th>
              <th className="px-4 py-3">셀러 귀속</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading ? (
              <tr>
                <td colSpan={3} className="px-4 py-8 text-center text-gray-400">
                  불러오는 중…
                </td>
              </tr>
            ) : brands.length === 0 ? (
              <tr>
                <td colSpan={3} className="px-4 py-8 text-center text-gray-400">
                  브랜드가 없습니다. 위에서 등록하세요.
                </td>
              </tr>
            ) : (
              brands.map((b) => (
                <tr key={b.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 text-gray-400">#{b.id}</td>
                  <td className="px-4 py-3 font-medium text-gray-900">{b.name}</td>
                  <td className="px-4 py-3 text-gray-600">
                    {b.sellerId !== null ? (
                      sellerName.get(b.sellerId) ?? `셀러 #${b.sellerId}`
                    ) : (
                      <span className="text-gray-300">미귀속</span>
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
