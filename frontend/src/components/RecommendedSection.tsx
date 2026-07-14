"use client";

import { useEffect, useState } from "react";
import { apiGet } from "@/lib/api";
import { RecommendationResult } from "@/lib/types";
import { useAuth } from "@/lib/auth";
import ProductRail from "@/components/ui/ProductRail";

/**
 * 홈 "나를 위한 추천" 섹션 (로그인 사용자만).
 *
 * GET /api/recommendations/me → 행동(구매·찜·조회) 기반 추천. 이력이 없으면 백엔드가 인기순으로 폴백하며
 * personalized=false로 내려주므로, 그때는 제목을 "인기 상품"으로 바꾼다. 추천이 비면 섹션 자체를 숨긴다.
 * 카드 그리드는 공용 ProductRail(함께 산 상품·최근 본 상품과 동일 구조).
 */
export default function RecommendedSection() {
  const { user, loading } = useAuth();
  const [rec, setRec] = useState<RecommendationResult | null>(null);

  useEffect(() => {
    if (!user) {
      setRec(null);
      return;
    }
    apiGet<RecommendationResult>("/api/recommendations/me")
      .then(setRec)
      .catch(() => setRec(null));
  }, [user]);

  if (loading || !user || !rec || rec.products.length === 0) return null;

  return (
    <ProductRail
      eyebrow="For You"
      title={rec.personalized ? "나를 위한 추천" : "인기 상품"}
      caption={
        rec.personalized ? "최근 보고 찜하고 구매한 취향을 모았어요" : "지금 가장 사랑받는 상품"
      }
      products={rec.products}
      className="mx-auto max-w-6xl px-6 pb-20"
    />
  );
}
