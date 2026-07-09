import Skeleton from "@/components/ui/Skeleton";

/**
 * 대시보드 로딩 스켈레톤 — 실제 /admin 대시보드 레이아웃(KPI 5카드·주문 상태 분포·매출 추이 차트)을
 * 그대로 본떠 shimmer 골격을 깐다. 데이터 도착 시 같은 프레임으로 자연스럽게 교체된다(레이아웃 시프트 최소화).
 */
export default function DashboardSkeleton() {
  return (
    <div className="flex flex-col gap-8">
      {/* KPI 카드 5개 */}
      <section className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="rounded-2xl border border-gray-200 bg-white p-5">
            <Skeleton className="h-3 w-16" />
            <Skeleton className="mt-3 h-7 w-24" />
          </div>
        ))}
      </section>

      {/* 주문 상태별 분포 */}
      <section className="rounded-2xl border border-gray-200 bg-white p-5">
        <Skeleton className="mb-4 h-4 w-28" />
        <div className="flex flex-wrap gap-3">
          {Array.from({ length: 5 }).map((_, i) => (
            <div
              key={i}
              className="flex min-w-[120px] flex-1 flex-col gap-2 rounded-xl border border-gray-100 bg-gray-50 p-4"
            >
              <Skeleton className="h-5 w-16 rounded-full" />
              <Skeleton className="h-7 w-12" />
            </div>
          ))}
        </div>
      </section>

      {/* 매출 추이 차트 */}
      <section className="rounded-2xl border border-gray-200 bg-white p-5">
        <div className="mb-4 flex items-center justify-between">
          <Skeleton className="h-4 w-40" />
          <Skeleton className="h-6 w-24 rounded-full" />
        </div>
        <Skeleton className="h-72 w-full rounded-xl" />
      </section>
    </div>
  );
}
