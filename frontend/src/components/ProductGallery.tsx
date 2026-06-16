"use client";

import { useState } from "react";
import ProductThumb from "@/components/ui/ProductThumb";
import Badge from "@/components/ui/Badge";
import { Product } from "@/lib/types";
import { productImageSrc } from "@/lib/productImage";

/**
 * 상품 상세 이미지 갤러리.
 * 대표 이미지(imageUrl/placeholder) + 갤러리(추가 이미지)를 메인 + 썸네일 스트립으로 보여준다.
 * 갤러리가 없으면(이미지 1장) 기존처럼 단일 이미지만 — 추가 이미지가 생기면 자연히 썸네일이 붙는다.
 */
export default function ProductGallery({ product }: { product: Product }) {
  const urls = [productImageSrc(product), ...product.images.map((i) => i.url)];
  const [idx, setIdx] = useState(0);
  const main = urls[idx] ?? urls[0];

  return (
    <div className="relative">
      <ProductThumb name={product.name} src={main} className="aspect-[4/5] w-full rounded-2xl shadow-soft" />
      {product.status === "SOLD_OUT" && (
        <span className="absolute left-4 top-4">
          <Badge tone="dark">품절</Badge>
        </span>
      )}

      {urls.length > 1 && (
        <div className="mt-3 flex flex-wrap gap-2">
          {urls.map((u, i) => (
            <button
              key={i}
              type="button"
              onClick={() => setIdx(i)}
              aria-label={`이미지 ${i + 1}`}
              className={`overflow-hidden rounded-lg border transition ${
                i === idx ? "border-clay" : "border-line hover:border-clay/50"
              }`}
            >
              <ProductThumb name={product.name} src={u} className="h-16 w-16" />
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
