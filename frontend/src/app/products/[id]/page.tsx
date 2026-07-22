import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import ProductDetailClient from "./ProductDetailClient";

/**
 * 상품 상세 라우트 — 서버 컴포넌트 셸.
 *
 * <p>사용자(JS)는 {@link ProductDetailClient}의 인터랙티브 상세(갤러리·옵션·담기·찜·리뷰)를 쓴다.
 * 검색/AI 크롤러를 위해 서버에서 상품을 받아 (1) generateMetadata로 상품별 title·description·canonical·
 * OpenGraph, (2) JSON-LD 구조화 데이터(schema.org Product — 가격·재고·평점 리치 스니펫), (3) &lt;noscript&gt;
 * 핵심 콘텐츠를 <b>초기 HTML</b>에 넣는다. 전량 CSR이던 상세가 무JS 크롤러(Bing/AI)와 Googlebot 1차 크롤에
 * 실제 콘텐츠로 노출된다(progressive enhancement).
 */
const API = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const SITE = process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000";

type Product = {
  id: number;
  name: string;
  price: number;
  description: string | null;
  imageUrl: string | null;
  status: string;
  brandName: string | null;
  categoryName: string | null;
  ratingCount: number;
  ratingAverage: number;
};

/**
 * 상품 조회 결과 — 3가지를 <b>구분</b>한다:
 *  · Product      정상
 *  · "NOT_FOUND"  백엔드가 404(정말 없는 상품) → 페이지가 notFound()로 진짜 404 응답
 *  · "UNREACHABLE" 백엔드 미가동·네트워크 오류 → 404로 오판하면 안 됨(클라 상세로 폴백)
 */
type ProductResult = Product | "NOT_FOUND" | "UNREACHABLE";

async function fetchProduct(id: string): Promise<ProductResult> {
  try {
    const res = await fetch(`${API}/api/products/${id}`, { next: { revalidate: 300 } });
    if (res.status === 404) return "NOT_FOUND"; // 정말 없는 상품
    if (!res.ok) return "UNREACHABLE"; // 5xx 등 — 없는 게 아니라 못 읽은 것
    const body = await res.json();
    return body?.data ?? "NOT_FOUND";
  } catch {
    return "UNREACHABLE"; // 백엔드 미가동 등 — 클라이언트 상세만 동작(404로 단정하지 않음)
  }
}

/** 결과가 실제 Product인지(문자열 마커가 아닌지). */
function isProduct(r: ProductResult): r is Product {
  return typeof r !== "string";
}

/** OG/JSON-LD 이미지엔 절대 URL이 필요. 상대경로는 SITE로 보정, 없으면 undefined. */
function absImage(imageUrl: string | null): string | undefined {
  if (!imageUrl) return undefined;
  if (imageUrl.startsWith("http")) return imageUrl;
  if (imageUrl.startsWith("/")) return `${SITE}${imageUrl}`;
  return undefined;
}

export async function generateMetadata({ params }: { params: Promise<{ id: string }> }): Promise<Metadata> {
  const { id } = await params;
  const result = await fetchProduct(id);
  if (!isProduct(result)) return { title: "상품", alternates: { canonical: `${SITE}/products/${id}` } };
  const p = result;

  const description = (
    p.description ?? `${p.brandName ?? ""} ${p.name} · ${p.price.toLocaleString()}원`
  ).slice(0, 160);
  const image = absImage(p.imageUrl);

  return {
    title: `${p.name}${p.brandName ? ` · ${p.brandName}` : ""}`,
    description,
    alternates: { canonical: `${SITE}/products/${p.id}` },
    openGraph: {
      title: p.name,
      description,
      type: "website",
      url: `${SITE}/products/${p.id}`,
      ...(image ? { images: [{ url: image }] } : {}),
    },
  };
}

export default async function ProductDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const result = await fetchProduct(id);
  // 정말 없는 상품이면 진짜 404(soft-404 방지). 백엔드 미가동(UNREACHABLE)이면 404로 단정하지 않고
  // 클라이언트 상세(ProductDetailClient)가 자체 조회·처리하도록 그대로 렌더한다.
  if (result === "NOT_FOUND") notFound();
  const p = isProduct(result) ? result : null;
  const image = absImage(p?.imageUrl ?? null);

  const jsonLd = p
    ? {
        "@context": "https://schema.org",
        "@type": "Product",
        name: p.name,
        ...(image ? { image: [image] } : {}),
        ...(p.description ? { description: p.description } : {}),
        ...(p.brandName ? { brand: { "@type": "Brand", name: p.brandName } } : {}),
        offers: {
          "@type": "Offer",
          price: p.price,
          priceCurrency: "KRW",
          availability:
            p.status === "ON_SALE"
              ? "https://schema.org/InStock"
              : p.status === "SOLD_OUT"
                ? "https://schema.org/OutOfStock"
                : "https://schema.org/Discontinued",
          url: `${SITE}/products/${p.id}`,
        },
        ...(p.ratingCount > 0
          ? {
              aggregateRating: {
                "@type": "AggregateRating",
                ratingValue: p.ratingAverage,
                reviewCount: p.ratingCount,
              },
            }
          : {}),
      }
    : null;

  return (
    <>
      {/* 사용자(JS): 인터랙티브 상세 */}
      <ProductDetailClient />

      {p && jsonLd && (
        <>
          {/* 구조화 데이터(schema.org Product) — 모든 크롤러가 raw HTML에서 읽는 가격·재고·평점 리치 스니펫 */}
          <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }} />

          {/* 크롤/무JS 폴백: 서버 HTML의 핵심 상품 콘텐츠. JS 사용자엔 미노출. */}
          <noscript>
            <div className="mx-auto max-w-3xl px-6 py-8">
              <h1 className="text-2xl font-bold">{p.name}</h1>
              {p.brandName && <p>{p.brandName}</p>}
              <p>{p.price.toLocaleString()}원</p>
              {p.description && <p>{p.description}</p>}
              {image && (
                // eslint-disable-next-line @next/next/no-img-element
                <img src={image} alt={p.name} width={400} />
              )}
              {p.ratingCount > 0 && (
                <p>
                  평점 {p.ratingAverage} ({p.ratingCount}개 리뷰)
                </p>
              )}
              <p>
                <Link href="/products">← 전체 상품</Link>
              </p>
            </div>
          </noscript>
        </>
      )}
    </>
  );
}
