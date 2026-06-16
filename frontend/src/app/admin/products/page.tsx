"use client";

import { useCallback, useEffect, useState } from "react";
import { apiDelete, apiGet, apiPost, apiPut } from "@/lib/api";
import { PageResponse, Product, ProductStatus } from "@/lib/types";

/**
 * 상품 옵션 관리 화면 (/admin/products, ADMIN).
 * 상품을 골라 사이즈 옵션(재고)을 추가/수정/삭제한다. 옵션 API(POST/PUT/DELETE /api/products/{id}/options)를 쓴다.
 *
 * 참고: 상품 목록은 공개 목록 API(GET /api/products)를 재사용하므로 판매중지(DISCONTINUED)는 보이지 않는다
 * (전용 어드민 상품 목록은 후속). 각 옵션 변경 응답이 갱신된 상품이라 그걸로 로컬 상태를 바꾼다.
 */
const STATUS_BADGE: Record<ProductStatus, string> = {
  ON_SALE: "bg-green-100 text-green-700",
  SOLD_OUT: "bg-gray-200 text-gray-600",
  DISCONTINUED: "bg-red-100 text-red-700",
};
const STATUS_LABEL: Record<ProductStatus, string> = {
  ON_SALE: "판매중",
  SOLD_OUT: "품절",
  DISCONTINUED: "판매중지",
};

export default function AdminProductsPage() {
  const [products, setProducts] = useState<Product[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 옵션 추가 폼
  const [addSize, setAddSize] = useState("");
  const [addStock, setAddStock] = useState("0");

  // 인라인 수정
  const [editingOptionId, setEditingOptionId] = useState<number | null>(null);
  const [editSize, setEditSize] = useState("");
  const [editStock, setEditStock] = useState("0");

  const load = useCallback(() => {
    setLoading(true);
    apiGet<PageResponse<Product>>("/api/products?size=100")
      .then((page) => setProducts(page.content))
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const selected = products.find((p) => p.id === selectedId) ?? null;

  // 갱신된 상품으로 로컬 목록 교체
  const replaceProduct = (updated: Product) =>
    setProducts((prev) => prev.map((p) => (p.id === updated.id ? updated : p)));

  const addOption = async (productId: number) => {
    if (!addSize.trim()) {
      setError("사이즈를 입력하세요.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const updated = await apiPost<Product>(`/api/products/${productId}/options`, {
        size: addSize.trim(),
        stock: Number(addStock || 0),
      });
      replaceProduct(updated);
      setAddSize("");
      setAddStock("0");
    } catch (e) {
      setError((e as Error).message); // 중복 사이즈 409 등
    } finally {
      setBusy(false);
    }
  };

  const startEdit = (optionId: number, size: string, stock: number) => {
    setEditingOptionId(optionId);
    setEditSize(size);
    setEditStock(String(stock));
    setError(null);
  };

  const saveEdit = async (productId: number, optionId: number) => {
    if (!editSize.trim()) {
      setError("사이즈를 입력하세요.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const updated = await apiPut<Product>(`/api/products/${productId}/options/${optionId}`, {
        size: editSize.trim(),
        stock: Number(editStock || 0),
      });
      replaceProduct(updated);
      setEditingOptionId(null);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const removeOption = async (productId: number, optionId: number) => {
    if (!confirm("이 옵션을 삭제할까요?")) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await apiDelete<Product>(`/api/products/${productId}/options/${optionId}`);
      replaceProduct(updated);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-xl font-bold">상품 옵션 관리</h1>
        <p className="text-sm text-gray-500">
          상품을 골라 사이즈 옵션(재고)을 추가·수정·삭제합니다. 재고는 옵션(사이즈) 단위입니다.
        </p>
      </div>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>
      )}

      <div className="grid gap-6 md:grid-cols-2">
        {/* 상품 목록 */}
        <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
              <tr>
                <th className="px-4 py-3">상품</th>
                <th className="px-4 py-3">상태</th>
                <th className="px-4 py-3 text-right">옵션 수</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {loading ? (
                <tr>
                  <td colSpan={3} className="px-4 py-8 text-center text-gray-400">
                    불러오는 중…
                  </td>
                </tr>
              ) : products.length === 0 ? (
                <tr>
                  <td colSpan={3} className="px-4 py-8 text-center text-gray-400">
                    상품이 없습니다.
                  </td>
                </tr>
              ) : (
                products.map((p) => (
                  <tr
                    key={p.id}
                    onClick={() => setSelectedId(p.id)}
                    className={`cursor-pointer hover:bg-gray-50 ${
                      p.id === selectedId ? "bg-gray-100" : ""
                    }`}
                  >
                    <td className="px-4 py-3">
                      <div className="font-medium">{p.name}</div>
                      <div className="text-xs text-gray-400">{p.price.toLocaleString()}원</div>
                    </td>
                    <td className="px-4 py-3">
                      <span className={`rounded px-2 py-0.5 text-xs ${STATUS_BADGE[p.status]}`}>
                        {STATUS_LABEL[p.status]}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right text-gray-500">{p.options.length}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {/* 선택 상품의 옵션 편집 */}
        <div className="rounded-lg border border-gray-200 bg-white p-4">
          {!selected ? (
            <p className="py-12 text-center text-sm text-gray-400">왼쪽에서 상품을 선택하세요.</p>
          ) : (
            <div>
              <div className="mb-3 text-sm font-semibold text-gray-700">{selected.name} · 옵션</div>

              <table className="w-full text-sm">
                <thead className="text-left text-xs uppercase tracking-wide text-gray-400">
                  <tr>
                    <th className="py-2">사이즈</th>
                    <th className="py-2 text-right">재고</th>
                    <th className="py-2 text-right">관리</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {selected.options.length === 0 ? (
                    <tr>
                      <td colSpan={3} className="py-6 text-center text-gray-400">
                        옵션이 없습니다. 아래에서 추가하세요.
                      </td>
                    </tr>
                  ) : (
                    selected.options.map((o) =>
                      editingOptionId === o.id ? (
                        <tr key={o.id}>
                          <td className="py-2">
                            <input
                              value={editSize}
                              onChange={(e) => setEditSize(e.target.value)}
                              className="w-20 rounded border border-gray-300 px-2 py-1"
                            />
                          </td>
                          <td className="py-2 text-right">
                            <input
                              type="number"
                              value={editStock}
                              onChange={(e) => setEditStock(e.target.value)}
                              className="w-20 rounded border border-gray-300 px-2 py-1 text-right"
                            />
                          </td>
                          <td className="py-2 text-right">
                            <button
                              onClick={() => saveEdit(selected.id, o.id)}
                              disabled={busy}
                              className="rounded bg-gray-900 px-2 py-1 text-xs text-white hover:bg-gray-700 disabled:opacity-50"
                            >
                              저장
                            </button>
                            <button
                              onClick={() => setEditingOptionId(null)}
                              className="ml-1 rounded border border-gray-300 px-2 py-1 text-xs hover:bg-gray-100"
                            >
                              취소
                            </button>
                          </td>
                        </tr>
                      ) : (
                        <tr key={o.id}>
                          <td className="py-2 font-medium">{o.size}</td>
                          <td className="py-2 text-right">
                            {o.stock}
                            {o.soldOut && <span className="ml-1 text-xs text-red-500">품절</span>}
                          </td>
                          <td className="py-2 text-right">
                            <button
                              onClick={() => startEdit(o.id, o.size, o.stock)}
                              className="text-xs text-gray-500 hover:text-gray-900"
                            >
                              수정
                            </button>
                            <button
                              onClick={() => removeOption(selected.id, o.id)}
                              disabled={busy}
                              className="ml-3 text-xs text-gray-500 hover:text-red-600 disabled:opacity-50"
                            >
                              삭제
                            </button>
                          </td>
                        </tr>
                      ),
                    )
                  )}
                </tbody>
              </table>

              {/* 옵션 추가 */}
              <div className="mt-4 flex items-end gap-2 border-t border-gray-100 pt-4">
                <label className="flex flex-col gap-1">
                  <span className="text-xs text-gray-500">사이즈</span>
                  <input
                    value={addSize}
                    onChange={(e) => setAddSize(e.target.value)}
                    placeholder="M"
                    className="w-24 rounded border border-gray-300 px-2 py-1 text-sm"
                  />
                </label>
                <label className="flex flex-col gap-1">
                  <span className="text-xs text-gray-500">재고</span>
                  <input
                    type="number"
                    value={addStock}
                    onChange={(e) => setAddStock(e.target.value)}
                    className="w-24 rounded border border-gray-300 px-2 py-1 text-right text-sm"
                  />
                </label>
                <button
                  onClick={() => addOption(selected.id)}
                  disabled={busy}
                  className="rounded bg-gray-900 px-4 py-2 text-sm font-medium text-white hover:bg-gray-700 disabled:opacity-50"
                >
                  {busy ? "처리 중…" : "옵션 추가"}
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
