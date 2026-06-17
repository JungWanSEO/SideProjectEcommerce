"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { apiDelete, apiGet, apiPost, apiPut } from "@/lib/api";
import { Brand, BrandCreateInput, BrandUpdateInput, Seller } from "@/lib/types";

/**
 * 브랜드 관리 화면 (/admin/brands, ADMIN).
 * 브랜드를 등록하고 목록을 본다. 각 행은 인라인 이름 수정·삭제(confirm) 가능 — 카테고리 화면과 동일 패턴.
 * 삭제는 상품이 참조 중이면 백엔드가 409로 막고 메시지를 띄운다. 셀러 귀속(표시만)은 별도 API.
 */
export default function AdminBrandsPage() {
  const [brands, setBrands] = useState<Brand[]>([]);
  const [sellers, setSellers] = useState<Seller[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 생성 폼 상태
  const [name, setName] = useState("");

  // 인라인 수정 상태 (편집 중인 브랜드 1개)
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editName, setEditName] = useState("");

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

  const startEdit = (b: Brand) => {
    setEditingId(b.id);
    setEditName(b.name);
    setError(null);
  };

  const cancelEdit = () => setEditingId(null);

  const saveEdit = async (id: number) => {
    if (!editName.trim()) {
      setError("브랜드명은 필수입니다.");
      return;
    }
    setBusy(true);
    setError(null);
    const body: BrandUpdateInput = { name: editName.trim() };
    try {
      await apiPut<Brand>(`/api/brands/${id}`, body);
      setEditingId(null);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const remove = async (id: number, label: string) => {
    if (!confirm(`'${label}' 브랜드를 삭제할까요?\n이 브랜드를 쓰는 상품이 있으면 삭제할 수 없습니다.`)) return;
    setBusy(true);
    setError(null);
    try {
      await apiDelete<void>(`/api/brands/${id}`);
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
          브랜드를 등록·수정·삭제합니다. 셀러 귀속은 현황만 표시하며, 변경은 별도(PUT /api/brands/{`{id}`}/seller) 예정.
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
              <th className="px-4 py-3 text-right">관리</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading ? (
              <tr>
                <td colSpan={4} className="px-4 py-8 text-center text-gray-400">
                  불러오는 중…
                </td>
              </tr>
            ) : brands.length === 0 ? (
              <tr>
                <td colSpan={4} className="px-4 py-8 text-center text-gray-400">
                  브랜드가 없습니다. 위에서 등록하세요.
                </td>
              </tr>
            ) : (
              brands.map((b) => (
                <tr key={b.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 text-gray-400">#{b.id}</td>
                  <td className="px-4 py-3 font-medium text-gray-900">
                    {editingId === b.id ? (
                      <input
                        value={editName}
                        onChange={(e) => setEditName(e.target.value)}
                        className="w-40 rounded border border-gray-300 px-2 py-1 text-sm"
                      />
                    ) : (
                      b.name
                    )}
                  </td>
                  <td className="px-4 py-3 text-gray-600">
                    {b.sellerId !== null ? (
                      sellerName.get(b.sellerId) ?? `셀러 #${b.sellerId}`
                    ) : (
                      <span className="text-gray-300">미귀속</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-right">
                    {editingId === b.id ? (
                      <span className="flex items-center justify-end gap-2">
                        <button
                          onClick={() => saveEdit(b.id)}
                          disabled={busy}
                          className="rounded bg-gray-900 px-3 py-1 text-xs text-white hover:bg-gray-700 disabled:opacity-50"
                        >
                          저장
                        </button>
                        <button
                          onClick={cancelEdit}
                          className="rounded border border-gray-300 px-3 py-1 text-xs hover:bg-gray-100"
                        >
                          취소
                        </button>
                      </span>
                    ) : (
                      <span className="flex items-center justify-end gap-3">
                        <button
                          onClick={() => startEdit(b)}
                          className="text-xs text-gray-500 hover:text-gray-900"
                        >
                          수정
                        </button>
                        <button
                          onClick={() => remove(b.id, b.name)}
                          disabled={busy}
                          className="text-xs text-gray-500 hover:text-red-600 disabled:opacity-50"
                        >
                          삭제
                        </button>
                      </span>
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
