"use client";

import { useEffect, useState } from "react";
import { apiGet } from "@/lib/api";
import { CoOccurrenceResult } from "@/lib/types";
import ProductRail from "@/components/ui/ProductRail";

/**
 * 상품 상세 "함께 산 상품" 섹션 (공개 — 로그인 불필요).
 *
 * GET /api/recommendations/products/{id}/together → 이 상품을 산 사람들이 함께 산 상품(주문 통계 기반).
 * 데이터가 없으면 백엔드가 같은 카테고리/브랜드로 폴백하며 cooccurrence=false로 내려주므로,
 * 그때는 문구를 "비슷한 상품"으로 바꾼다. 결과가 비면 섹션 자체를 숨긴다.
 *
 * 회원별 "나를 위한 추천"(홈)과 달리 이건 회원 무관 상품↔상품 통계라 비로그인도 본다.
 * 카드 그리드는 공용 ProductRail(추천·최근 본 상품과 동일 구조).
 */
export default function CoOccurrenceSection({ productId }: { productId: number }) {
  const [rec, setRec] = useState<CoOccurrenceResult | null>(null);

  useEffect(() => {
    apiGet<CoOccurrenceResult>(`/api/recommendations/products/${productId}/together`)
      .then(setRec)
      .catch(() => setRec(null));
  }, [productId]);

  if (!rec || rec.products.length === 0) return null;

  return (
    <ProductRail
      eyebrow="Bought Together"
      title={rec.cooccurrence ? "함께 산 상품" : "비슷한 상품"}
      caption={
        rec.cooccurrence
          ? "이 상품을 산 분들이 함께 구매했어요"
          : "같은 카테고리·브랜드에서 골랐어요"
      }
      products={rec.products}
      className="mt-16 border-t border-line pt-10"
    />
  );
}
