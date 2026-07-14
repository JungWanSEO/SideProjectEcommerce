import ProductGridSkeleton from "@/components/ui/ProductGridSkeleton";
import Skeleton from "@/components/ui/Skeleton";

/**
 * 상품 목록 라우트의 서버 대기 스켈레톤.
 *
 * `/products`는 서버 컴포넌트가 백엔드에서 상품을 받아 렌더한다(크롤 폴백 SSR). 그 fetch가 끝날 때까지
 * Next가 이 파일을 스트리밍해 준다 — 없으면 그 구간이 <b>빈 화면</b>이라 첫 진입이 멈춘 것처럼 보인다.
 * 실제 PLP(툴바 + 3열 그리드) 레이아웃을 본떠 데이터 도착 시 시프트가 적게.
 */
export default function Loading() {
  return (
    <main className="mx-auto max-w-6xl px-6 py-10">
      <Skeleton className="h-8 w-40" />

      {/* 검색·필터 툴바 자리 */}
      <div className="mt-6 flex flex-wrap items-center gap-2">
        <Skeleton className="h-9 w-56 rounded-full" />
        <Skeleton className="h-9 w-32 rounded-full" />
        <Skeleton className="h-9 w-32 rounded-full" />
        <Skeleton className="h-9 w-28 rounded-full" />
      </div>

      <div className="mt-8">
        <ProductGridSkeleton count={9} />
      </div>
    </main>
  );
}
