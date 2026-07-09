import Skeleton from "@/components/ui/Skeleton";

/**
 * 상품 카드 그리드 로딩 스켈레톤 — 상품 목록/위시리스트의 카드 그리드(ProductThumb 카드)를 본떠
 * shimmer 골격을 깐다. 데이터 도착 시 같은 그리드로 자연스럽게 교체된다.
 *
 * 카드 = 썸네일(aspect-[4/5] rounded-2xl) + 브랜드/상품명/가격 + 사이즈 pill.
 */
export default function ProductGridSkeleton({ count = 8 }: { count?: number }) {
  return (
    <ul className="grid grid-cols-2 gap-x-5 gap-y-10 lg:grid-cols-3">
      {Array.from({ length: count }).map((_, i) => (
        <li key={i}>
          {/* 썸네일 */}
          <Skeleton className="aspect-[4/5] w-full rounded-2xl" />
          {/* 텍스트 블록 */}
          <div className="mt-3 px-0.5">
            <Skeleton className="h-3 w-14" /> {/* 브랜드 */}
            <Skeleton className="mt-2 h-5 w-3/4" /> {/* 상품명 */}
            <Skeleton className="mt-2 h-4 w-20" /> {/* 가격 */}
            <div className="mt-2 flex gap-1">
              {" "}
              {/* 사이즈 pill */}
              <Skeleton className="h-5 w-9 rounded-full" />
              <Skeleton className="h-5 w-9 rounded-full" />
              <Skeleton className="h-5 w-9 rounded-full" />
            </div>
          </div>
        </li>
      ))}
    </ul>
  );
}
