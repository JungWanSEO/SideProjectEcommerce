import type { Metadata } from "next";
import ProductsClient from "./ProductsClient";

/**
 * 상품 목록 라우트 — 서버 컴포넌트 셸.
 *
 * <p>사용자(JS)는 {@link ProductsClient}의 인터랙티브 PLP(검색·필터·정렬·무한스크롤)를 쓴다.
 * 크롤러/무JS는 Googlebot이 스크롤·'더 보기' 클릭을 하지 않으므로, 서버 렌더 HTML에 <b>실제 &lt;a href&gt;
 * 상품 링크 + ?page=n 페이지네이션</b>을 &lt;noscript&gt;로 두어 봇이 상품·다음 페이지에 도달하게 한다
 * (progressive enhancement). canonical/title은 generateMetadata가 부여한다.
 */
const API = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const SITE = process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000";
const PAGE_SIZE = 20;

type Sp = { [k: string]: string | string[] | undefined };

const FILTER_KEYS = ["keyword", "categoryId", "brandId", "minPrice", "maxPrice", "optionSize"] as const;

/** 백엔드 offset 검색 쿼리(필터+정렬+page). 크롤 폴백은 안정적 순회면 되므로 offset 사용. */
function backendQuery(sp: Sp, page: number): string {
  const q = new URLSearchParams();
  for (const k of FILTER_KEYS) {
    const v = sp[k];
    if (typeof v === "string" && v) q.set(k, v);
  }
  q.set("sort", typeof sp.sort === "string" && sp.sort ? sp.sort : "createdAt,desc");
  q.set("page", String(page));
  q.set("size", String(PAGE_SIZE));
  return q.toString();
}

/** 페이지네이션 링크 URL(page만 다름·나머지 필터 유지). page=1은 파라미터 생략(정규 URL). */
function pageHref(sp: Sp, page: number): string {
  const q = new URLSearchParams();
  for (const k of [...FILTER_KEYS, "sort"]) {
    const v = sp[k];
    if (typeof v === "string" && v) q.set(k, v);
  }
  if (page > 1) q.set("page", String(page));
  const s = q.toString();
  return s ? `/products?${s}` : "/products";
}

type ProductLite = { id: number; name: string; price: number };

async function fetchPage(sp: Sp, page: number): Promise<{ items: ProductLite[]; totalPages: number }> {
  try {
    const res = await fetch(`${API}/api/products?${backendQuery(sp, page)}`, { next: { revalidate: 300 } });
    if (!res.ok) return { items: [], totalPages: 0 };
    const body = await res.json();
    const data = body?.data;
    return { items: data?.content ?? [], totalPages: data?.totalPages ?? 0 };
  } catch {
    return { items: [], totalPages: 0 }; // 백엔드 미가동 등 — 폴백은 비고 클라이언트 PLP만 동작
  }
}

export async function generateMetadata({ searchParams }: { searchParams: Promise<Sp> }): Promise<Metadata> {
  const sp = await searchParams;
  const page = Math.max(1, Number(sp.page) || 1);
  return {
    title: page > 1 ? `전체 상품 · ${page}페이지` : "전체 상품",
    description: "패션 셀렉트샵 ATELIER의 전체 상품.",
    alternates: { canonical: `${SITE}${pageHref(sp, page)}` }, // 각 페이지 자기참조 canonical
  };
}

export default async function ProductsPage({ searchParams }: { searchParams: Promise<Sp> }) {
  const sp = await searchParams;
  const page = Math.max(1, Number(sp.page) || 1);
  const { items, totalPages } = await fetchPage(sp, page);

  // 페이지 번호 링크는 다음(next) 체인이 크롤을 보장하므로, 목록은 현재 주변 창으로 제한(대형 카탈로그 대비).
  const windowStart = Math.max(1, page - 3);
  const windowEnd = Math.min(totalPages, page + 3);
  const pageNums: number[] = [];
  for (let n = windowStart; n <= windowEnd; n++) pageNums.push(n);

  return (
    <>
      {/* 사용자(JS): 인터랙티브 PLP. 하이드레이션 후 이 화면을 쓴다. */}
      <ProductsClient />

      {/* 크롤/무JS 폴백: 서버 HTML의 실제 <a href> 상품 링크 + ?page=n 페이지네이션(봇 발견/색인용). JS 사용자엔 미노출. */}
      <noscript>
        <div className="mx-auto max-w-6xl px-6 py-8">
          <h2 className="mb-4 text-lg font-bold">전체 상품{page > 1 ? ` · ${page}페이지` : ""}</h2>
          <ul className="grid grid-cols-2 gap-4 lg:grid-cols-3">
            {items.map((p) => (
              <li key={p.id}>
                <a href={`/products/${p.id}`}>
                  {p.name} — {p.price.toLocaleString()}원
                </a>
              </li>
            ))}
          </ul>
          {totalPages > 1 && (
            <nav aria-label="상품 페이지" className="mt-8 flex flex-wrap gap-3">
              {page > 1 && <a href={pageHref(sp, page - 1)}>← 이전</a>}
              {pageNums.map((n) => (
                <a key={n} href={pageHref(sp, n)} aria-current={n === page ? "page" : undefined}>
                  {n}
                </a>
              ))}
              {page < totalPages && <a href={pageHref(sp, page + 1)}>다음 →</a>}
            </nav>
          )}
        </div>
      </noscript>
    </>
  );
}
