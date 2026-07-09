/**
 * 스켈레톤 골격 블록 — 로딩 중 실제 콘텐츠 자리에 shimmer 플레이스홀더를 깐다.
 * 크기·모서리는 className(Tailwind h-, w-, rounded- 등)으로 지정. shimmer 애니메이션은 globals.css의 .skeleton.
 *
 * 예: <Skeleton className="h-4 w-32" />  ·  <Skeleton className="h-24 w-full rounded-xl" />
 */
export default function Skeleton({ className = "" }: { className?: string }) {
  return <div aria-hidden className={`skeleton rounded-md ${className}`} />;
}
