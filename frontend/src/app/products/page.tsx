"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { apiGet } from "@/lib/api";
import { Brand, Category, PageResponse, Product, ProductCursorResponse } from "@/lib/types";
import ProductThumb from "@/components/ui/ProductThumb";
import Badge from "@/components/ui/Badge";
import Stars from "@/components/ui/Stars";
import Select from "@/components/ui/Select";
import WishlistButton from "@/components/ui/WishlistButton";
import ProductGridSkeleton from "@/components/ui/ProductGridSkeleton";
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

// Baymard 하이브리드: 누적 이 개수까지만 자동 append(무한스크롤), 넘으면 '더 보기' 버튼으로 전환한다.
//   목표지향 이커머스 그리드엔 순수 무한스크롤보다 Load-more가 낫다(Baymard 대규모 사용성테스트·NN/g). 권장 범위 ~50~100.
const AUTO_APPEND_LIMIT = 60;

export default function ProductsPage() {
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true); // 첫 로드(쿼리 변경)
  const [loadingMore, setLoadingMore] = useState(false); // 다음 청크
  // 자동 append 허용 상한 — 누적이 넘으면 '더 보기' 버튼. 버튼 클릭 시 상한을 늘려 다음 구간을 다시 자동 append.
  const [autoLimit, setAutoLimit] = useState(AUTO_APPEND_LIMIT);
  const [hasMore, setHasMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [categories, setCategories] = useState<Category[]>([]);
  const [brands, setBrands] = useState<Brand[]>([]);
  const [sizes, setSizes] = useState<string[]>([]); // 사이즈 칩 — 최초 결과에서 한 번 채운다

  const [query, setQuery] = useState<Query>(EMPTY);
  // 입력 중 값(키워드·가격은 검색/Enter로 commit)
  const [keywordInput, setKeywordInput] = useState("");
  const [minInput, setMinInput] = useState("");
  const [maxInput, setMaxInput] = useState("");

  // 페이지네이션 전략: 기본 뷰(필터 없음 + 최신순)는 커서 피드(/feed, no-offset),
  //   필터/정렬이 걸리면 offset 검색(/api/products?page=N). 둘 다 무한 스크롤로 append.
  const isFeed =
    !query.keyword &&
    !query.categoryId &&
    !query.brandId &&
    !query.minPrice &&
    !query.maxPrice &&
    !query.optionSize &&
    query.sort === "createdAt,desc";
  const cursorRef = useRef<number | null>(null); // 피드 모드: 마지막으로 본 상품 id
  const pageRef = useRef(0); // 검색 모드: 다음에 요청할 페이지 번호
  const genRef = useRef(0); // 쿼리가 바뀌면 증가 → 진행 중이던 이전 응답을 폐기

  useEffect(() => {
    apiGet<Category[]>("/api/categories").then(setCategories).catch(() => {});
    apiGet<Brand[]>("/api/brands").then(setBrands).catch(() => {});
  }, []);

  // 다음 청크를 가져온다. reset=true면 첫 페이지. 모드(isFeed)에 따라 커서/offset 분기.
  const fetchChunk = useCallback(
    async (reset: boolean): Promise<Product[]> => {
      if (isFeed) {
        const c = reset ? null : cursorRef.current;
        const qs = new URLSearchParams({ size: "20" });
        if (c != null) qs.set("cursor", String(c));
        const res = await apiGet<ProductCursorResponse>(`/api/products/feed?${qs.toString()}`);
        cursorRef.current = res.nextCursor;
        setHasMore(res.hasNext);
        return res.items;
      }
      const p = reset ? 0 : pageRef.current;
      const qs = new URLSearchParams();
      if (query.keyword) qs.set("keyword", query.keyword);
      if (query.categoryId) qs.set("categoryId", query.categoryId);
      if (query.brandId) qs.set("brandId", query.brandId);
      if (query.minPrice) qs.set("minPrice", query.minPrice);
      if (query.maxPrice) qs.set("maxPrice", query.maxPrice);
      if (query.optionSize) qs.set("optionSize", query.optionSize);
      qs.set("sort", query.sort);
      qs.set("page", String(p));
      const page = await apiGet<PageResponse<Product>>(`/api/products?${qs.toString()}`);
      pageRef.current = p + 1;
      setHasMore(page.hasNext);
      return page.content;
    },
    [isFeed, query],
  );

  // 쿼리 변경 → 커서/페이지 리셋하고 처음부터 다시 로드
  useEffect(() => {
    const gen = ++genRef.current;
    cursorRef.current = null;
    pageRef.current = 0;
    setAutoLimit(AUTO_APPEND_LIMIT); // 새 쿼리 → 자동 append 상한 초기화
    setLoading(true);
    setError(null);
    fetchChunk(true)
      .then((items) => {
        if (gen !== genRef.current) return; // 그새 쿼리가 또 바뀌었으면 폐기
        setProducts(items);
        setSizes((prev) => (prev.length ? prev : distinctSizes(items)));
      })
      .catch((e: Error) => gen === genRef.current && setError(e.message))
      .finally(() => gen === genRef.current && setLoading(false));
  }, [fetchChunk]);

  const loadMore = useCallback(() => {
    if (loadingMore) return;
    const gen = genRef.current;
    setLoadingMore(true);
    fetchChunk(false)
      .then((items) => {
        if (gen === genRef.current) setProducts((prev) => [...prev, ...items]);
      })
      .catch(() => {})
      .finally(() => gen === genRef.current && setLoadingMore(false));
  }, [fetchChunk, loadingMore]);

  // 무한 스크롤: 하단 sentinel이 뷰포트 근처(400px)에 들어오면 다음 청크
  const sentinelRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = sentinelRef.current;
    // 자동 append는 누적이 상한 미만일 때만 관찰 — 상한 넘으면 중단하고 '더 보기' 버튼으로 넘긴다.
    if (!el || !hasMore || loading || products.length >= autoLimit) return;
    const io = new IntersectionObserver(
      (entries) => entries[0]?.isIntersecting && loadMore(),
      { rootMargin: "400px" },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [hasMore, loading, loadMore, products.length, autoLimit]);

  // '더 보기' 클릭: 자동 append 상한을 한 구간 늘리고 즉시 다음 청크.
  //   클릭은 CLS의 500ms 예외를 받아(스크롤은 못 받음) 레이아웃 확장이 점수에 안 잡힌다.
  const handleLoadMoreClick = () => {
    setAutoLimit((n) => n + AUTO_APPEND_LIMIT);
    loadMore();
  };

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
        <ProductGridSkeleton count={8} />
      ) : error ? (
        <p className="py-12 text-center text-danger">에러: {error}</p>
      ) : products.length === 0 ? (
        <p className="py-12 text-center text-muted">조건에 맞는 상품이 없습니다.</p>
      ) : (
        <>
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

        {/* 무한 스크롤 sentinel — 자동 append는 임계치 전까지만(그 뒤엔 아래 '더 보기' 버튼) */}
        <div ref={sentinelRef} aria-hidden className="h-px" />

        {/* append 로딩: 전체 그리드 재로딩이 아니라 콘텐츠 크기 스켈레톤 카드 한 줄(NN/g append 원칙·CLS 안전·그리드 리듬 유지) */}
        {loadingMore && (
          <div className="mt-10" role="status" aria-label="상품 더 불러오는 중">
            <ProductGridSkeleton count={3} />
          </div>
        )}

        {/* Baymard 하이브리드: 누적 임계치 후엔 자동 무한스크롤 대신 '더 보기'로 사용자 통제·푸터 접근을 되돌린다 */}
        {hasMore && !loadingMore && products.length >= autoLimit && (
          <div className="mt-10 flex justify-center">
            <button
              onClick={handleLoadMoreClick}
              className="rounded-full border border-line px-8 py-3 text-sm font-medium text-ink transition hover:border-clay hover:text-clay"
            >
              더 보기
            </button>
          </div>
        )}

        {!hasMore && (
          <p className="py-10 text-center text-xs text-muted">모든 상품을 둘러봤어요</p>
        )}
        </>
      )}
    </main>
  );
}
