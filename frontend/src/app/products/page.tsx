"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { apiGet } from "@/lib/api";
import { Brand, Category, PageResponse, Product } from "@/lib/types";
import ProductThumb from "@/components/ui/ProductThumb";
import Badge from "@/components/ui/Badge";
import Stars from "@/components/ui/Stars";
import Select from "@/components/ui/Select";
import WishlistButton from "@/components/ui/WishlistButton";
import { productImageSrc } from "@/lib/productImage";

/**
 * 상품 목록 + 검색/필터/정렬 (/products).
 *
 * UX 설계 근거(deep-research, Baymard·NN/g): 패션처럼 필터가 적고(≈5) 이미지 비교가 중요한 카탈로그는
 * 상단 가로 툴바가 사이드바를 이길 수 있다(가로공간→큰 썸네일). 드롭다운은 Shell Approach(닫힘 커스텀/열림 네이티브,
 * 접근성). 데스크톱은 즉시 적용. 적용된 필터는 제거 가능한 칩으로 상시 노출(전체 해제 + 항목별). 검색은 시각 카탈로그라
 * 과하지 않게 컴팩트 인라인. (모바일 바텀시트·배치 적용은 반응형 단계에서.)
 */

const SORTS = [
  { value: "createdAt,desc", label: "최신순" },
  { value: "price,asc", label: "낮은 가격순" },
  { value: "price,desc", label: "높은 가격순" },
  { value: "ratingCount,desc", label: "리뷰 많은순" },
  { value: "ratingAverage,desc", label: "평점 높은순" },
  { value: "wishlistCount,desc", label: "인기순(찜)" },
];

interface Query {
  keyword: string;
  categoryId: string; // "" = 전체
  brandId: string;
  minPrice: string;
  maxPrice: string;
  optionSize: string;
  sort: string;
}
const EMPTY: Query = {
  keyword: "",
  categoryId: "",
  brandId: "",
  minPrice: "",
  maxPrice: "",
  optionSize: "",
  sort: "createdAt,desc",
};

const pillField =
  "rounded-full border border-line bg-paper px-4 py-2.5 text-sm text-ink placeholder:text-muted transition focus:border-clay focus:outline-none focus:ring-2 focus:ring-clay/30";

function distinctSizes(products: Product[]): string[] {
  const set = new Set<string>();
  products.forEach((p) => p.options.forEach((o) => set.add(o.size)));
  return [...set];
}

/**
 * 카테고리 필터 드롭다운 옵션을 2단계(부모 → 자식)로 펼친다.
 * 최상위는 그대로, 자식은 부모 바로 아래에 `└ ` 들여쓰기로 — 어드민 상품 폼과 같은 표시 컨벤션.
 * 부모·자식 모두 선택 가능(선택 시 그 카테고리 id로 필터). 맨 앞에 "전체".
 */
function categoryFilterOptions(categories: Category[]): { value: string; label: string }[] {
  const roots = categories.filter((c) => c.parentId == null);
  const out = [{ value: "", label: "카테고리 전체" }];
  for (const r of roots) {
    out.push({ value: String(r.id), label: r.name });
    for (const ch of categories.filter((c) => c.parentId === r.id)) {
      out.push({ value: String(ch.id), label: `└ ${ch.name}` });
    }
  }
  // 부모가 목록에 없는 고아 자식(방어) — 평면으로 뒤에 붙인다.
  for (const c of categories.filter((c) => c.parentId != null && !roots.some((r) => r.id === c.parentId))) {
    out.push({ value: String(c.id), label: c.name });
  }
  return out;
}

function priceLabel(min: string, max: string): string {
  const fmt = (v: string) => Number(v).toLocaleString();
  if (min && max) return `${fmt(min)}~${fmt(max)}원`;
  if (min) return `${fmt(min)}원 이상`;
  return `${fmt(max)}원 이하`;
}

export default function ProductsPage() {
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [categories, setCategories] = useState<Category[]>([]);
  const [brands, setBrands] = useState<Brand[]>([]);
  const [sizes, setSizes] = useState<string[]>([]); // 사이즈 칩 — 최초 결과에서 한 번 채운다

  const [query, setQuery] = useState<Query>(EMPTY);
  // 입력 중 값(키워드·가격은 검색/Enter로 commit)
  const [keywordInput, setKeywordInput] = useState("");
  const [minInput, setMinInput] = useState("");
  const [maxInput, setMaxInput] = useState("");

  useEffect(() => {
    apiGet<Category[]>("/api/categories").then(setCategories).catch(() => {});
    apiGet<Brand[]>("/api/brands").then(setBrands).catch(() => {});
  }, []);

  const load = useCallback(() => {
    const p = new URLSearchParams();
    if (query.keyword) p.set("keyword", query.keyword);
    if (query.categoryId) p.set("categoryId", query.categoryId);
    if (query.brandId) p.set("brandId", query.brandId);
    if (query.minPrice) p.set("minPrice", query.minPrice);
    if (query.maxPrice) p.set("maxPrice", query.maxPrice);
    if (query.optionSize) p.set("optionSize", query.optionSize);
    p.set("sort", query.sort);

    setLoading(true);
    setError(null);
    return apiGet<PageResponse<Product>>(`/api/products?${p.toString()}`)
      .then((page) => {
        setProducts(page.content);
        setSizes((prev) => (prev.length ? prev : distinctSizes(page.content)));
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [query]);

  useEffect(() => {
    load();
  }, [load]);

  // 드롭다운/칩/정렬은 즉시 적용(데스크톱 표준). 키워드·가격만 검색/Enter로 commit.
  const set = (patch: Partial<Query>) => setQuery((q) => ({ ...q, ...patch }));
  const applySearch = () =>
    set({ keyword: keywordInput.trim(), minPrice: minInput.trim(), maxPrice: maxInput.trim() });
  const reset = () => {
    setQuery(EMPTY);
    setKeywordInput("");
    setMinInput("");
    setMaxInput("");
  };

  // 적용된 필터 overview(제거 가능 칩). 정렬은 항상 있으니 칩에서 제외.
  const chips: { key: string; label: string; clear: () => void }[] = [];
  if (query.keyword)
    chips.push({ key: "kw", label: `“${query.keyword}”`, clear: () => { setKeywordInput(""); set({ keyword: "" }); } });
  if (query.categoryId)
    chips.push({
      key: "cat",
      label: categories.find((c) => String(c.id) === query.categoryId)?.name ?? "카테고리",
      clear: () => set({ categoryId: "" }),
    });
  if (query.brandId)
    chips.push({
      key: "brand",
      label: brands.find((b) => String(b.id) === query.brandId)?.name ?? "브랜드",
      clear: () => set({ brandId: "" }),
    });
  if (query.optionSize)
    chips.push({ key: "size", label: `사이즈 ${query.optionSize}`, clear: () => set({ optionSize: "" }) });
  if (query.minPrice || query.maxPrice)
    chips.push({
      key: "price",
      label: priceLabel(query.minPrice, query.maxPrice),
      clear: () => { setMinInput(""); setMaxInput(""); set({ minPrice: "", maxPrice: "" }); },
    });

  return (
    <main className="mx-auto max-w-6xl px-6 py-12">
      <header className="mb-8">
        <span className="text-xs uppercase tracking-[0.3em] text-clay">Collection</span>
        <h1 className="mt-2 text-3xl font-bold text-ink">전체 상품</h1>
      </header>

      {/* 검색 / 필터 / 정렬 툴바 */}
      <div className="mb-6 space-y-3">
        {/* 컴팩트 검색 + 정렬 */}
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="relative w-full sm:w-72">
            <button
              type="button"
              onClick={applySearch}
              aria-label="검색"
              className="absolute left-3.5 top-1/2 -translate-y-1/2 text-muted transition hover:text-clay"
            >
              <svg className="h-4 w-4" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden>
                <circle cx="9" cy="9" r="6" />
                <path d="M14 14l4 4" strokeLinecap="round" />
              </svg>
            </button>
            <input
              value={keywordInput}
              onChange={(e) => setKeywordInput(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && applySearch()}
              placeholder="상품 검색"
              className={`w-full pl-10 ${pillField}`}
            />
          </div>

          <div className="flex items-center gap-2">
            <span className="text-xs text-muted">정렬</span>
            <Select value={query.sort} onChange={(v) => set({ sort: v })} options={SORTS} />
          </div>
        </div>

        {/* 필터: 카테고리 · 브랜드 · 가격 · 사이즈 */}
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
          <Select
            value={query.categoryId}
            onChange={(v) => set({ categoryId: v })}
            options={categoryFilterOptions(categories)}
          />
          <Select
            value={query.brandId}
            onChange={(v) => set({ brandId: v })}
            options={[
              { value: "", label: "브랜드 전체" },
              ...brands.map((b) => ({ value: String(b.id), label: b.name })),
            ]}
          />

          <div className="flex items-center gap-1.5 text-sm text-muted">
            <input
              value={minInput}
              onChange={(e) => setMinInput(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && applySearch()}
              inputMode="numeric"
              placeholder="최소"
              className={`w-24 ${pillField}`}
            />
            <span>~</span>
            <input
              value={maxInput}
              onChange={(e) => setMaxInput(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && applySearch()}
              inputMode="numeric"
              placeholder="최대"
              className={`w-24 ${pillField}`}
            />
            <span>원</span>
          </div>

          {sizes.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {sizes.map((sz) => {
                const on = query.optionSize === sz;
                return (
                  <button
                    key={sz}
                    type="button"
                    onClick={() => set({ optionSize: on ? "" : sz })}
                    className={`rounded-full border px-3 py-1 text-xs transition ${
                      on ? "border-clay bg-clay text-cream" : "border-line text-muted hover:border-clay"
                    }`}
                  >
                    {sz}
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* 적용된 필터 overview (제거 가능 칩 + 전체 해제) */}
        {chips.length > 0 && (
          <div className="flex flex-wrap items-center gap-2 border-t border-line pt-3">
            <span className="text-xs text-muted">적용된 필터</span>
            {chips.map((c) => (
              <button
                key={c.key}
                type="button"
                onClick={c.clear}
                className="group inline-flex items-center gap-1.5 rounded-full border border-clay/40 bg-clay-50 px-3 py-1 text-xs text-clay-700"
              >
                {c.label}
                <span className="text-clay/50 transition group-hover:text-clay" aria-hidden>
                  ✕
                </span>
              </button>
            ))}
            <button
              type="button"
              onClick={reset}
              className="text-xs text-muted underline-offset-2 transition hover:text-clay hover:underline"
            >
              전체 해제
            </button>
          </div>
        )}
      </div>

      {/* 결과 */}
      {loading ? (
        <p className="py-12 text-center text-muted">불러오는 중…</p>
      ) : error ? (
        <p className="py-12 text-center text-danger">에러: {error}</p>
      ) : products.length === 0 ? (
        <p className="py-12 text-center text-muted">조건에 맞는 상품이 없습니다.</p>
      ) : (
        <ul className="grid grid-cols-2 gap-x-5 gap-y-10 lg:grid-cols-3">
          {products.map((p) => (
            <li key={p.id} className="relative">
              {/* 하트는 카드 <Link> 바깥(형제)에 둔다 — 앵커 안에 버튼을 넣지 않기 위해(유효 HTML) */}
              <WishlistButton productId={p.id} variant="overlay" />
              <Link href={`/products/${p.id}`} className="group block">
                <div className="relative overflow-hidden rounded-2xl">
                  <ProductThumb
                    name={p.name}
                    src={productImageSrc(p)}
                    className="aspect-[4/5] w-full transition duration-500 group-hover:scale-[1.03]"
                  />
                  {p.status === "SOLD_OUT" && (
                    <span className="absolute left-3 top-3">
                      <Badge tone="dark">품절</Badge>
                    </span>
                  )}
                </div>

                <div className="mt-3 px-0.5">
                  {p.brandName && (
                    <p className="text-xs uppercase tracking-wider text-muted">{p.brandName}</p>
                  )}
                  <h2 className="mt-1 font-serif text-lg text-ink">{p.name}</h2>
                  <p className="mt-1 font-medium text-ink">{p.price.toLocaleString()}원</p>

                  {(p.ratingCount > 0 || p.wishlistCount > 0) && (
                    <div className="mt-1 flex items-center gap-2.5">
                      {p.ratingCount > 0 && (
                        <span className="flex items-center gap-1.5">
                          <Stars value={p.ratingAverage} className="text-xs" />
                          <span className="text-xs text-muted">
                            {p.ratingAverage.toFixed(1)} ({p.ratingCount})
                          </span>
                        </span>
                      )}
                      {p.wishlistCount > 0 && (
                        <span className="text-xs text-muted">♥ {p.wishlistCount}</span>
                      )}
                    </div>
                  )}

                  <div className="mt-2 flex flex-wrap gap-1">
                    {p.options.map((o) => (
                      <span
                        key={o.id}
                        className={`rounded-full border px-2 py-0.5 text-[11px] ${
                          o.soldOut ? "border-line text-line line-through" : "border-line text-muted"
                        }`}
                      >
                        {o.size}
                      </span>
                    ))}
                  </div>
                </div>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
