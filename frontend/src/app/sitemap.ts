import type { MetadataRoute } from "next";

/**
 * sitemap.xml — Next.js App Router가 /sitemap.xml 로 생성한다.
 *
 * <p><b>왜 필요한가:</b> 우리 상품목록은 CSR 무한스크롤이라 검색봇이 스크롤/'더 보기'로 개별 상품에
 * 도달하지 못한다(Googlebot은 스크롤·클릭 안 함). Google은 이런 경우 <b>sitemap(또는 상품 피드)으로
 * 모든 상품 URL을 직접 제공</b>하라고 권장한다 — 봇이 목록을 훑지 않아도 각 상품 상세를 발견/색인할 수 있다.
 *
 * <p>상품 URL은 빌드가 아니라 요청 시 백엔드에서 동적으로 받는다(1시간 재검증).
 */
const API = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const SITE = process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000";

export const revalidate = 3600; // 1시간마다 재생성

/** 공개 상품(ON_SALE·SOLD_OUT)을 페이지 순회로 모두 수집. 백엔드가 없으면 빈 배열(정적 경로만 노출). */
async function fetchAllProductIds(): Promise<number[]> {
  const ids: number[] = [];
  for (let page = 0; page < 50; page++) {
    // 최신순 offset 페이지네이션 — sitemap은 안정적 순회면 충분(커서 아님).
    const res = await fetch(
      `${API}/api/products?page=${page}&size=100&sort=createdAt,desc`,
      { next: { revalidate } },
    );
    if (!res.ok) break;
    const body = await res.json();
    const data = body?.data;
    if (!data?.content?.length) break;
    for (const p of data.content) ids.push(p.id);
    if (!data.hasNext) break;
  }
  return ids;
}

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  let productIds: number[] = [];
  try {
    productIds = await fetchAllProductIds();
  } catch {
    // 백엔드 미가동 등 — 정적 경로만이라도 노출한다(sitemap 자체가 500 나지 않게).
  }

  const staticRoutes: MetadataRoute.Sitemap = [
    { url: `${SITE}/`, changeFrequency: "daily", priority: 1 },
    { url: `${SITE}/products`, changeFrequency: "daily", priority: 0.9 },
    { url: `${SITE}/signup`, changeFrequency: "monthly", priority: 0.3 },
    { url: `${SITE}/login`, changeFrequency: "monthly", priority: 0.3 },
  ];

  const productRoutes: MetadataRoute.Sitemap = productIds.map((id) => ({
    url: `${SITE}/products/${id}`,
    changeFrequency: "weekly",
    priority: 0.7,
  }));

  return [...staticRoutes, ...productRoutes];
}
