"use client";

import { useCallback, useEffect, useState } from "react";
import { apiDelete, apiGet, apiPatch, apiPost, apiPut } from "@/lib/api";
import { Brand, Category, PageResponse, Product, ProductStatus } from "@/lib/types";

/**
 * 상품 관리 화면 (/admin/products, ADMIN).
 * 상품을 골라 사이즈 옵션(재고)·상태(판매중/품절/판매중지)·이미지(갤러리)를 관리한다.
 * 옵션/상태/이미지 API(/api/products/{id}/options·/status·/images)를 쓴다.
 *
 * 목록은 **어드민 전용 API(GET /api/products/admin)** 를 쓴다 — 공개 목록은 판매중·품절만 노출하므로
 * 그걸 재사용하면 판매중지로 바꾼 상품이 어드민에서도 사라져 되돌릴 수 없다(데이터 잠금). 상태 필터로
 * 판매중지만 골라 다시 판매중으로 되돌릴 수 있다. 각 변경 응답이 갱신된 상품이라 그걸로 로컬 상태를 바꾼다.
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

/** 카테고리를 2단계(부모 → 자식 들여쓰기)로 펼친 셀렉트 옵션 목록. 부모·자식 모두 선택 가능. */
function categoryOptions(categories: Category[]): { id: number; label: string }[] {
  const roots = categories.filter((c) => c.parentId == null);
  const out: { id: number; label: string }[] = [];
  for (const r of roots) {
    out.push({ id: r.id, label: r.name });
    for (const ch of categories.filter((c) => c.parentId === r.id)) {
      out.push({ id: ch.id, label: `└ ${ch.name}` });
    }
  }
  // 부모가 목록에 없는 자식(방어)도 누락 없이
  for (const c of categories.filter((c) => c.parentId != null && !roots.some((r) => r.id === c.parentId))) {
    out.push({ id: c.id, label: c.name });
  }
  return out;
}

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

  // 이미지 추가 폼
  const [addImageUrl, setAddImageUrl] = useState("");

  // 카테고리·브랜드 (등록/수정 셀렉트용)
  const [categories, setCategories] = useState<Category[]>([]);
  const [brands, setBrands] = useState<Brand[]>([]);

  // 새 상품 등록 폼 (등록 시 옵션 1개 필수)
  const [nName, setNName] = useState("");
  const [nPrice, setNPrice] = useState("");
  const [nOriginalPrice, setNOriginalPrice] = useState(""); // 정가(선택). 비우면 비할인.
  const [nDesc, setNDesc] = useState("");
  const [nImage, setNImage] = useState("");
  const [nCategory, setNCategory] = useState("");
  const [nBrand, setNBrand] = useState("");
  const [nOptSize, setNOptSize] = useState("FREE");
  const [nOptStock, setNOptStock] = useState("0");

  // 선택 상품 기본정보 수정 폼 (선택 변경 시 채워짐)
  const [bName, setBName] = useState("");
  const [bPrice, setBPrice] = useState("");
  const [bOriginalPrice, setBOriginalPrice] = useState(""); // 정가(선택). 비우면 비할인.
  const [bDesc, setBDesc] = useState("");
  const [bImage, setBImage] = useState("");
  const [bCategory, setBCategory] = useState("");
  const [bBrand, setBBrand] = useState("");

  // 상태 필터(어드민은 판매중지 포함 전 상태를 본다)
  const [statusFilter, setStatusFilter] = useState<ProductStatus | "ALL">("ALL");

  const load = useCallback(() => {
    setLoading(true);
    const q = new URLSearchParams({ size: "100" });
    if (statusFilter !== "ALL") q.set("status", statusFilter);
    // 어드민 전용 목록: 판매중지 포함 전 상태 (공개 목록 API 재사용 금지 — 데이터 잠금 방지)
    apiGet<PageResponse<Product>>(`/api/products/admin?${q.toString()}`)
      .then((page) => setProducts(page.content))
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [statusFilter]);

  useEffect(() => {
    load();
    Promise.all([apiGet<Category[]>("/api/categories"), apiGet<Brand[]>("/api/brands")])
      .then(([cs, bs]) => {
        setCategories(cs);
        setBrands(bs);
      })
      .catch(() => {});
  }, [load]);

  const selected = products.find((p) => p.id === selectedId) ?? null;

  // 상품 선택이 바뀌면 기본정보 수정 폼을 그 상품 값으로 채운다(상품 mutation 시엔 유지).
  useEffect(() => {
    if (!selected) return;
    setBName(selected.name);
    setBPrice(String(selected.price));
    setBOriginalPrice(selected.originalPrice ? String(selected.originalPrice) : "");
    setBDesc(selected.description ?? "");
    setBImage(selected.imageUrl ?? "");
    setBCategory(selected.categoryId ? String(selected.categoryId) : "");
    setBBrand(selected.brandId ? String(selected.brandId) : "");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId]);

  // 갱신된 상품으로 로컬 목록 교체
  const replaceProduct = (updated: Product) =>
    setProducts((prev) => prev.map((p) => (p.id === updated.id ? updated : p)));

  const changeStatus = async (productId: number, status: ProductStatus) => {
    setBusy(true);
    setError(null);
    try {
      replaceProduct(await apiPatch<Product>(`/api/products/${productId}/status`, { status }));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const addImage = async (productId: number) => {
    if (!addImageUrl.trim()) {
      setError("이미지 URL을 입력하세요.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      replaceProduct(await apiPost<Product>(`/api/products/${productId}/images`, { url: addImageUrl.trim() }));
      setAddImageUrl("");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const removeImage = async (productId: number, imageId: number) => {
    if (!confirm("이 이미지를 삭제할까요?")) return;
    setBusy(true);
    setError(null);
    try {
      replaceProduct(await apiDelete<Product>(`/api/products/${productId}/images/${imageId}`));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const createProduct = async () => {
    if (!nName.trim() || !nPrice || !nOptSize.trim()) {
      setError("상품명·가격·옵션(사이즈)은 필수입니다.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await apiPost<Product>("/api/products", {
        name: nName.trim(),
        price: Number(nPrice),
        originalPrice: nOriginalPrice ? Number(nOriginalPrice) : null,
        description: nDesc.trim() || null,
        imageUrl: nImage.trim() || null,
        categoryId: nCategory ? Number(nCategory) : null,
        brandId: nBrand ? Number(nBrand) : null,
        options: [{ size: nOptSize.trim(), stock: Number(nOptStock || 0) }],
      });
      setNName("");
      setNPrice("");
      setNOriginalPrice("");
      setNDesc("");
      setNImage("");
      setNCategory("");
      setNBrand("");
      setNOptSize("FREE");
      setNOptStock("0");
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const saveBasics = async (productId: number) => {
    if (!bName.trim() || !bPrice) {
      setError("상품명·가격은 필수입니다.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      replaceProduct(
        await apiPut<Product>(`/api/products/${productId}`, {
          name: bName.trim(),
          price: Number(bPrice),
          originalPrice: bOriginalPrice ? Number(bOriginalPrice) : null,
          description: bDesc.trim() || null,
          imageUrl: bImage.trim() || null,
          categoryId: bCategory ? Number(bCategory) : null,
          brandId: bBrand ? Number(bBrand) : null,
        }),
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

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
        <h1 className="text-xl font-bold">상품 관리</h1>
        <p className="text-sm text-gray-500">
          상품을 등록하고, 골라서 기본정보·옵션(재고)·상태·이미지를 관리합니다.
        </p>
      </div>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>
      )}

      {/* 새 상품 등록 */}
      <div className="mb-6 rounded-lg border border-gray-200 bg-white p-4">
        <div className="mb-3 text-sm font-semibold text-gray-700">새 상품 등록</div>
        <div className="grid grid-cols-2 gap-3 text-sm md:grid-cols-4">
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">상품명</span>
            <input value={nName} onChange={(e) => setNName(e.target.value)} className="rounded border border-gray-300 px-2 py-1" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">판매가(원)</span>
            <input type="number" value={nPrice} onChange={(e) => setNPrice(e.target.value)} className="rounded border border-gray-300 px-2 py-1" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">정가(원, 선택 · 판매가↑=할인)</span>
            <input type="number" value={nOriginalPrice} onChange={(e) => setNOriginalPrice(e.target.value)} placeholder="비우면 비할인" className="rounded border border-gray-300 px-2 py-1" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">카테고리</span>
            <select value={nCategory} onChange={(e) => setNCategory(e.target.value)} className="rounded border border-gray-300 px-2 py-1">
              <option value="">(없음)</option>
              {categoryOptions(categories).map((o) => (
                <option key={o.id} value={String(o.id)}>{o.label}</option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">브랜드</span>
            <select value={nBrand} onChange={(e) => setNBrand(e.target.value)} className="rounded border border-gray-300 px-2 py-1">
              <option value="">(없음)</option>
              {brands.map((b) => (
                <option key={b.id} value={String(b.id)}>{b.name}</option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 md:col-span-2">
            <span className="text-xs text-gray-500">대표 이미지 URL(선택)</span>
            <input value={nImage} onChange={(e) => setNImage(e.target.value)} placeholder="/products/3.svg" className="rounded border border-gray-300 px-2 py-1" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">옵션 사이즈</span>
            <input value={nOptSize} onChange={(e) => setNOptSize(e.target.value)} className="rounded border border-gray-300 px-2 py-1" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">옵션 재고</span>
            <input type="number" value={nOptStock} onChange={(e) => setNOptStock(e.target.value)} className="rounded border border-gray-300 px-2 py-1" />
          </label>
          <label className="flex flex-col gap-1 md:col-span-4">
            <span className="text-xs text-gray-500">설명(선택)</span>
            <input value={nDesc} onChange={(e) => setNDesc(e.target.value)} className="rounded border border-gray-300 px-2 py-1" />
          </label>
        </div>
        <div className="mt-3">
          <button onClick={createProduct} disabled={busy} className="rounded bg-gray-900 px-4 py-2 text-sm font-medium text-white hover:bg-gray-700 disabled:opacity-50">
            {busy ? "처리 중…" : "상품 등록"}
          </button>
        </div>
      </div>

      <div className="grid gap-6 md:grid-cols-2">
        {/* 상품 목록 */}
        <div>
          {/* 상태 필터 — 어드민은 판매중지 포함 전 상태를 본다(판매중지 골라 다시 판매중으로 되돌리기 가능) */}
          <div className="mb-3 flex flex-wrap gap-2">
            {([
              ["ALL", "전체"],
              ["ON_SALE", STATUS_LABEL.ON_SALE],
              ["SOLD_OUT", STATUS_LABEL.SOLD_OUT],
              ["DISCONTINUED", STATUS_LABEL.DISCONTINUED],
            ] as const).map(([value, label]) => (
              <button
                key={value}
                onClick={() => setStatusFilter(value as ProductStatus | "ALL")}
                className={`rounded-full border px-3 py-1 text-xs ${
                  statusFilter === value
                    ? "border-gray-900 bg-gray-900 text-white"
                    : "border-gray-300 bg-white text-gray-600 hover:bg-gray-100"
                }`}
              >
                {label}
              </button>
            ))}
          </div>

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
        </div>

        {/* 선택 상품의 옵션 편집 */}
        <div className="rounded-lg border border-gray-200 bg-white p-4">
          {!selected ? (
            <p className="py-12 text-center text-sm text-gray-400">왼쪽에서 상품을 선택하세요.</p>
          ) : (
            <div>
              {/* 기본정보 수정 */}
              <div className="mb-4 rounded border border-gray-100 bg-gray-50 p-3">
                <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-400">
                  기본정보 수정
                </div>
                <div className="grid grid-cols-2 gap-2 text-sm">
                  <input value={bName} onChange={(e) => setBName(e.target.value)} placeholder="상품명" className="rounded border border-gray-300 px-2 py-1" />
                  <input type="number" value={bPrice} onChange={(e) => setBPrice(e.target.value)} placeholder="판매가" className="rounded border border-gray-300 px-2 py-1" />
                  <input type="number" value={bOriginalPrice} onChange={(e) => setBOriginalPrice(e.target.value)} placeholder="정가(선택·비우면 비할인)" className="rounded border border-gray-300 px-2 py-1" />
                  <select value={bCategory} onChange={(e) => setBCategory(e.target.value)} className="rounded border border-gray-300 px-2 py-1">
                    <option value="">카테고리(없음)</option>
                    {categoryOptions(categories).map((o) => (
                      <option key={o.id} value={String(o.id)}>{o.label}</option>
                    ))}
                  </select>
                  <select value={bBrand} onChange={(e) => setBBrand(e.target.value)} className="rounded border border-gray-300 px-2 py-1">
                    <option value="">브랜드(없음)</option>
                    {brands.map((b) => (
                      <option key={b.id} value={String(b.id)}>{b.name}</option>
                    ))}
                  </select>
                  <input value={bImage} onChange={(e) => setBImage(e.target.value)} placeholder="대표 이미지 URL" className="col-span-2 rounded border border-gray-300 px-2 py-1" />
                  <input value={bDesc} onChange={(e) => setBDesc(e.target.value)} placeholder="설명" className="col-span-2 rounded border border-gray-300 px-2 py-1" />
                </div>
                <button onClick={() => saveBasics(selected.id)} disabled={busy} className="mt-2 rounded bg-gray-900 px-3 py-1.5 text-xs font-medium text-white hover:bg-gray-700 disabled:opacity-50">
                  기본정보 저장
                </button>
              </div>

              <div className="mb-3 flex items-center justify-between">
                <span className="text-sm font-semibold text-gray-700">{selected.name}</span>
                <label className="flex items-center gap-2 text-xs text-gray-500">
                  상태
                  <select
                    value={selected.status}
                    onChange={(e) => changeStatus(selected.id, e.target.value as ProductStatus)}
                    disabled={busy}
                    className="rounded border border-gray-300 px-2 py-1 text-sm text-gray-800"
                  >
                    <option value="ON_SALE">판매중</option>
                    <option value="SOLD_OUT">품절</option>
                    <option value="DISCONTINUED">판매중지</option>
                  </select>
                </label>
              </div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-400">옵션</div>

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

              {/* 이미지 갤러리 (대표 imageUrl 외 추가분) */}
              <div className="mt-6 border-t border-gray-100 pt-4">
                <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-400">
                  이미지 갤러리
                </div>
                {selected.images.length === 0 ? (
                  <p className="text-xs text-gray-400">추가 이미지가 없습니다(대표 이미지는 imageUrl).</p>
                ) : (
                  <ul className="flex flex-wrap gap-2">
                    {selected.images.map((img) => (
                      <li key={img.id} className="group relative">
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        <img
                          src={img.url}
                          alt="상품 이미지"
                          className="h-16 w-16 rounded border border-gray-200 object-cover"
                        />
                        <button
                          onClick={() => removeImage(selected.id, img.id)}
                          disabled={busy}
                          aria-label="이미지 삭제"
                          className="absolute -right-1 -top-1 rounded-full bg-gray-900 px-1.5 text-xs text-white opacity-0 transition group-hover:opacity-100 disabled:opacity-50"
                        >
                          ✕
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
                <div className="mt-3 flex items-end gap-2">
                  <label className="flex flex-1 flex-col gap-1">
                    <span className="text-xs text-gray-500">이미지 URL</span>
                    <input
                      value={addImageUrl}
                      onChange={(e) => setAddImageUrl(e.target.value)}
                      placeholder="/products/2.svg"
                      className="rounded border border-gray-300 px-2 py-1 text-sm"
                    />
                  </label>
                  <button
                    onClick={() => addImage(selected.id)}
                    disabled={busy}
                    className="rounded bg-gray-900 px-4 py-2 text-sm font-medium text-white hover:bg-gray-700 disabled:opacity-50"
                  >
                    이미지 추가
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
