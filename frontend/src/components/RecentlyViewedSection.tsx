"use client";

import { useEffect, useState } from "react";
import { apiGet } from "@/lib/api";
import { Product } from "@/lib/types";
import { useAuth } from "@/lib/auth";
import ProductRail from "@/components/ui/ProductRail";

/**
 * "최근 본 상품" 레일 (로그인 사용자만 — 조회 로그가 회원 단위라 익명은 기록 자체가 없다).
 *
 * GET /api/activity/recently-viewed → 내 조회 로그를 상품별 최신 1건으로 접어 최신순.
 * 추천과 달리 폴백이 없다(안 본 사람에겐 "최근 본"이 없는 게 맞다) → 비면 섹션을 숨긴다.
 *
 * excludeProductId: 상품 상세에서 쓰면 "지금 보고 있는 상품"이 자기 자신을 추천하는 걸 막는다
 * (상세 진입 시 조회를 기록하므로 그냥 두면 이 상품이 항상 1번으로 뜬다).
 */
export default function RecentlyViewedSection({
  excludeProductId,
  className = "mx-auto max-w-6xl px-6 pb-20",
}: {
  excludeProductId?: number;
  className?: string;
}) {
  const { user, loading } = useAuth();
  const [products, setProducts] = useState<Product[]>([]);

  useEffect(() => {
    if (!user) {
      setProducts([]);
      return;
    }
    const exclude = excludeProductId ? `&exclude=${excludeProductId}` : "";
    apiGet<Product[]>(`/api/activity/recently-viewed?limit=8${exclude}`)
      .then(setProducts)
      .catch(() => setProducts([]));
  }, [user, excludeProductId]);

  if (loading || !user || products.length === 0) return null;

  return (
    <ProductRail
      eyebrow="Recently Viewed"
      title="최근 본 상품"
      caption="다시 보고 싶었던 그 옷"
      products={products}
      className={className}
    />
  );
}
