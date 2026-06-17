"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { apiDelete, apiGet, apiPost, apiPut } from "@/lib/api";
import { Category, CategoryCreateInput, CategoryUpdateInput } from "@/lib/types";

/**
 * 카테고리 관리 화면 (/admin/categories, ADMIN).
 * 2단계 계층(최상위 → 자식)을 만들고 부모별로 그룹지어 보여준다.
 * 각 항목은 인라인 수정(이름·부모 재배치)·삭제(confirm) 가능 — 상품 옵션 어드민과 동일 패턴.
 * 삭제는 자식 카테고리가 있거나 상품이 참조 중이면 백엔드가 409로 막고 메시지를 띄운다.
 */
export default function AdminCategoriesPage() {
  const [categories, setCategories] = useState<Category[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 생성 폼 상태
  const [name, setName] = useState("");
  const [parentId, setParentId] = useState(""); // "" = 최상위

  // 인라인 수정 상태 (편집 중인 카테고리 1개)
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editName, setEditName] = useState("");
  const [editParentId, setEditParentId] = useState(""); // "" = 최상위

  const load = useCallback(() => {
    setLoading(true);
    apiGet<Category[]>("/api/categories")
      .then(setCategories)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  // 최상위(부모 후보) — 2단계까지만이므로 부모 select에는 최상위만 노출
  const roots = useMemo(
    () => categories.filter((c) => c.parentId === null),
    [categories],
  );

  // 부모 → 자식 묶음. 각 최상위 밑에 그 자식들을 모은다(들여쓰기 표시용).
  const groups = useMemo(
    () =>
      roots.map((root) => ({
        root,
        children: categories.filter((c) => c.parentId === root.id),
      })),
    [roots, categories],
  );

  const create = async () => {
    if (!name.trim()) {
      setError("카테고리명은 필수입니다.");
      return;
    }
    setBusy(true);
    setError(null);
    const body: CategoryCreateInput = {
      name: name.trim(),
      parentId: parentId ? Number(parentId) : null,
    };
    try {
      await apiPost<Category>("/api/categories", body);
      setName("");
      setParentId("");
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const startEdit = (c: Category) => {
    setEditingId(c.id);
    setEditName(c.name);
    setEditParentId(c.parentId !== null ? String(c.parentId) : "");
    setError(null);
  };

  const cancelEdit = () => setEditingId(null);

  const saveEdit = async (id: number) => {
    if (!editName.trim()) {
      setError("카테고리명은 필수입니다.");
      return;
    }
    setBusy(true);
    setError(null);
    const body: CategoryUpdateInput = {
      name: editName.trim(),
      parentId: editParentId ? Number(editParentId) : null,
    };
    try {
      await apiPut<Category>(`/api/categories/${id}`, body);
      setEditingId(null);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const remove = async (id: number, label: string) => {
    if (
      !confirm(
        `'${label}' 카테고리를 삭제할까요?\n자식 카테고리가 있거나 이 카테고리를 쓰는 상품이 있으면 삭제할 수 없습니다.`,
      )
    )
      return;
    setBusy(true);
    setError(null);
    try {
      await apiDelete<void>(`/api/categories/${id}`);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  // 인라인 수정 폼(한 항목) — 이름 + 부모 재배치 select. 부모 후보는 자기 자신 제외한 최상위.
  const editRow = (c: Category) => (
    <div className="flex flex-wrap items-center gap-2">
      <input
        value={editName}
        onChange={(e) => setEditName(e.target.value)}
        className="w-40 rounded border border-gray-300 px-2 py-1 text-sm"
      />
      <select
        value={editParentId}
        onChange={(e) => setEditParentId(e.target.value)}
        className="rounded border border-gray-300 px-2 py-1 text-sm"
      >
        <option value="">최상위(부모 없음)</option>
        {roots
          .filter((r) => r.id !== c.id)
          .map((r) => (
            <option key={r.id} value={String(r.id)}>
              {r.name}
            </option>
          ))}
      </select>
      <button
        onClick={() => saveEdit(c.id)}
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
    </div>
  );

  // 일반 표시일 때 우측 수정/삭제 액션
  const actions = (c: Category) => (
    <span className="ml-auto flex items-center gap-3">
      <button onClick={() => startEdit(c)} className="text-xs text-gray-500 hover:text-gray-900">
        수정
      </button>
      <button
        onClick={() => remove(c.id, c.name)}
        disabled={busy}
        className="text-xs text-gray-500 hover:text-red-600 disabled:opacity-50"
      >
        삭제
      </button>
    </span>
  );

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-xl font-bold">카테고리 관리</h1>
        <p className="text-sm text-gray-500">
          최상위 카테고리와 그 하위(2단계)를 만듭니다. 부모를 비우면 최상위로 등록됩니다. 각 항목은 수정(이름·부모)·삭제할 수 있습니다.
        </p>
      </div>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>
      )}

      {/* KPI */}
      <div className="mb-6 grid grid-cols-2 gap-4">
        <div className="rounded-lg border border-gray-200 bg-white px-4 py-3">
          <div className="text-xs text-gray-500">최상위 카테고리</div>
          <div className="mt-1 text-lg font-bold">{roots.length}개</div>
        </div>
        <div className="rounded-lg border border-gray-200 bg-white px-4 py-3">
          <div className="text-xs text-gray-500">전체(하위 포함)</div>
          <div className="mt-1 text-lg font-bold">{categories.length}개</div>
        </div>
      </div>

      {/* 등록 폼 */}
      <div className="mb-6 rounded-lg border border-gray-200 bg-white p-4">
        <div className="mb-3 text-sm font-semibold text-gray-700">카테고리 등록</div>
        <div className="grid grid-cols-1 gap-3 text-sm sm:grid-cols-3">
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">이름</span>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="아우터"
              className="rounded border border-gray-300 px-2 py-1"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">부모 카테고리</span>
            <select
              value={parentId}
              onChange={(e) => setParentId(e.target.value)}
              className="rounded border border-gray-300 px-2 py-1"
            >
              <option value="">최상위(부모 없음)</option>
              {roots.map((r) => (
                <option key={r.id} value={String(r.id)}>
                  {r.name}
                </option>
              ))}
            </select>
          </label>
          <div className="flex items-end">
            <button
              onClick={create}
              disabled={busy}
              className="rounded bg-gray-900 px-4 py-2 text-sm font-medium text-white hover:bg-gray-700 disabled:opacity-50"
            >
              {busy ? "등록 중…" : "카테고리 등록"}
            </button>
          </div>
        </div>
      </div>

      {/* 목록 — 부모 → 자식 그룹(들여쓰기) */}
      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
        {loading ? (
          <div className="px-4 py-8 text-center text-gray-400">불러오는 중…</div>
        ) : groups.length === 0 ? (
          <div className="px-4 py-8 text-center text-gray-400">카테고리가 없습니다. 위에서 등록하세요.</div>
        ) : (
          <ul className="divide-y divide-gray-100">
            {groups.map(({ root, children }) => (
              <li key={root.id} className="px-4 py-3">
                {editingId === root.id ? (
                  editRow(root)
                ) : (
                  <div className="flex items-center gap-2 text-sm font-medium text-gray-900">
                    {root.name}
                    <span className="text-xs font-normal text-gray-400">#{root.id}</span>
                    {actions(root)}
                  </div>
                )}
                {children.length > 0 && (
                  <ul className="mt-2 space-y-1 border-l border-gray-200 pl-4">
                    {children.map((child) => (
                      <li key={child.id} className="text-sm text-gray-600">
                        {editingId === child.id ? (
                          editRow(child)
                        ) : (
                          <div className="flex items-center gap-2">
                            <span className="text-gray-300">└</span>
                            {child.name}
                            <span className="text-xs text-gray-400">#{child.id}</span>
                            {actions(child)}
                          </div>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
