import Skeleton from "@/components/ui/Skeleton";

/**
 * 상품 상세 라우트의 서버 대기 스켈레톤.
 *
 * `/products/[id]`는 서버에서 상품을 받아 메타데이터·JSON-LD·noscript를 만든다(SSR). 그 fetch 구간을
 * 빈 화면 대신 실제 상세 레이아웃(갤러리 + 정보)을 본뜬 골격으로 채운다.
 * (클라이언트 컴포넌트가 자체 데이터를 불러오는 동안의 스켈레톤은 ProductDetailClient가 따로 갖고 있다.)
 */
export default function Loading() {
  return (
    <main className="mx-auto max-w-6xl px-6 py-10">
      <Skeleton className="h-4 w-20" />

      <div className="mt-6 grid gap-10 lg:grid-cols-2">
        {/* 이미지 갤러리 */}
        <div>
          <Skeleton className="aspect-[4/5] w-full rounded-2xl" />
          <div className="mt-3 flex gap-2">
            <Skeleton className="h-16 w-16 rounded-lg" />
            <Skeleton className="h-16 w-16 rounded-lg" />
            <Skeleton className="h-16 w-16 rounded-lg" />
          </div>
        </div>

        {/* 정보 */}
        <div className="lg:py-4">
          <Skeleton className="h-3 w-24" />
          <Skeleton className="mt-3 h-9 w-3/4" />
          <Skeleton className="mt-2 h-4 w-28" />
          <Skeleton className="mt-4 h-5 w-40" />
          <Skeleton className="mt-5 h-8 w-36" />
          <div className="mt-6 space-y-2">
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-5/6" />
            <Skeleton className="h-4 w-2/3" />
          </div>
          <Skeleton className="mt-8 h-12 w-full rounded-full" />
        </div>
      </div>
    </main>
  );
}
