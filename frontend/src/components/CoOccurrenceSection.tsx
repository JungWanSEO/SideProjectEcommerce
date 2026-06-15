"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { apiGet } from "@/lib/api";
import { CoOccurrenceResult } from "@/lib/types";
import ProductThumb from "@/components/ui/ProductThumb";
import WishlistButton from "@/components/ui/WishlistButton";
import { productImageSrc } from "@/lib/productImage";

/**
 * 상품 상세 "함께 산 상품" 섹션 (공개 — 로그인 불필요).
 *
 * GET /api/recommendations/products/{id}/together → 이 상품을 산 사람들이 함께 산 상품(주문 통계 기반).
 * 데이터가 없으면 백엔드가 같은 카테고리/브랜드로 폴백하며 cooccurrence=false로 내려주므로,
 * 그때는 문구를 "비슷한 상품"으로 바꾼다. 결과가 비면 섹션 자체를 숨긴다.
 *
 * 회원별 "나를 위한 추천"(홈)과 달리 이건 회원 무관 상품↔상품 통계라 비로그인도 본다.
 * 카드 구조는 RecommendedSection과 동일(ProductThumb·WishlistButton·Link)하게 맞춰 시각적 일관성을 유지.
 */
export default function CoOccurrenceSection({ productId }: { productId: number }) {
  const [rec, setRec] = useState<CoOccurrenceResult | null>(null);

  useEffect(() => {
    apiGet<CoOccurrenceResult>(`/api/recommendations/products/${productId}/together`)
      .then(setRec)
      .catch(() => setRec(null));
  }, [productId]);

  if (!rec || rec.products.length === 0) return null;

  const title = rec.cooccurrence ? "함께 산 상품" : "비슷한 상품";
  const caption = rec.cooccurrence
    ? "이 상품을 산 분들이 함께 구매했어요"
    : "같은 카테고리·브랜드에서 골랐어요";

  return (
    <section className="mt-16 border-t border-line pt-10">
      <header className="mb-6">
        <span className="text-xs uppercase tracking-[0.3em] text-clay">Bought Together</span>
        <h2 className="mt-1 font-serif text-2xl text-ink">{title}</h2>
        <p className="mt-1 text-sm text-muted">{caption}</p>
      </header>

      <ul className="grid grid-cols-2 gap-x-5 gap-y-10 lg:grid-cols-4">
        {rec.products.slice(0, 8).map((p) => (
          <li key={p.id} className="relative">
            <WishlistButton productId={p.id} variant="overlay" />
            <Link href={`/products/${p.id}`} className="group block">
              <div className="overflow-hidden rounded-2xl">
                <ProductThumb
                  name={p.name}
                  src={productImageSrc(p)}
                  className="aspect-[4/5] w-full transition duration-500 group-hover:scale-[1.03]"
                />
              </div>
              <div className="mt-3 px-0.5">
                {p.brandName && (
                  <p className="text-xs uppercase tracking-wider text-muted">{p.brandName}</p>
                )}
                <h3 className="mt-1 font-serif text-base text-ink">{p.name}</h3>
                <p className="mt-1 font-medium text-ink">{p.price.toLocaleString()}원</p>
              </div>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
