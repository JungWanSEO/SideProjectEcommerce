"use client";

import Link from "next/link";
import ProductThumb from "@/components/ui/ProductThumb";
import WishlistButton from "@/components/ui/WishlistButton";
import { productImageSrc } from "@/lib/productImage";
import { Product } from "@/lib/types";

/**
 * 상품 레일 — 제목 + 상품 카드 그리드(최대 8개).
 *
 * 홈 "나를 위한 추천", 상세 "함께 산 상품"·"최근 본 상품"이 모두 같은 카드 구조라 여기로 모았다.
 * 데이터를 가져오는 건 각 섹션 컴포넌트의 몫이고, 이건 표시만 한다(프레젠테이션).
 * 섹션마다 여백/구분선이 달라(홈은 아래 여백, 상세는 위 구분선) className으로 감싼 <section>을 조절한다.
 */
export default function ProductRail({
  eyebrow,
  title,
  caption,
  products,
  className = "",
}: {
  eyebrow: string; // 카테고리 라벨(영문 소제목)
  title: string;
  caption: string;
  products: Product[];
  className?: string;
}) {
  if (products.length === 0) return null;

  return (
    <section className={className}>
      <header className="mb-6">
        <span className="text-xs uppercase tracking-[0.3em] text-clay">{eyebrow}</span>
        <h2 className="mt-1 font-serif text-2xl text-ink">{title}</h2>
        <p className="mt-1 text-sm text-muted">{caption}</p>
      </header>

      <ul className="grid grid-cols-2 gap-x-5 gap-y-10 lg:grid-cols-4">
        {products.slice(0, 8).map((p) => (
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
