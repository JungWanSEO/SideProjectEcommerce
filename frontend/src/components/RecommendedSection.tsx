"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { apiGet } from "@/lib/api";
import { RecommendationResult } from "@/lib/types";
import { useAuth } from "@/lib/auth";
import ProductThumb from "@/components/ui/ProductThumb";
import WishlistButton from "@/components/ui/WishlistButton";
import { productImageSrc } from "@/lib/productImage";

/**
 * 홈 "나를 위한 추천" 섹션 (로그인 사용자만).
 *
 * GET /api/recommendations/me → 행동(구매·찜·조회) 기반 추천. 이력이 없으면 백엔드가 인기순으로 폴백하며
 * personalized=false로 내려주므로, 그때는 제목을 "인기 상품"으로 바꾼다. 추천이 비면 섹션 자체를 숨긴다.
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

  const title = rec.personalized ? "나를 위한 추천" : "인기 상품";
  const caption = rec.personalized ? "최근 보고 찜하고 구매한 취향을 모았어요" : "지금 가장 사랑받는 상품";

  return (
    <section className="mx-auto max-w-6xl px-6 pb-20">
      <header className="mb-6">
        <span className="text-xs uppercase tracking-[0.3em] text-clay">For You</span>
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
