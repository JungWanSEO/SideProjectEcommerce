import Link from "next/link";
import { buttonClass } from "@/components/ui/Button";

/**
 * 404 페이지 — 없는 경로나 `notFound()`를 호출한 페이지(예: 없는 상품)에서 렌더된다.
 *
 * 이게 있어야 없는 상품 URL이 HTTP 200 대신 <b>진짜 404</b>를 반환한다(그 전엔 항상 200이라 크롤러가
 * 존재하지 않는 페이지를 색인할 수 있었다 — SEO 훼손). App Router가 이 파일을 404 응답으로 매핑한다.
 */
export default function NotFound() {
  return (
    <main className="mx-auto flex max-w-lg flex-col items-center justify-center gap-6 px-6 py-32 text-center">
      <span className="text-xs uppercase tracking-[0.35em] text-clay">404</span>
      <h1 className="font-serif text-3xl text-ink">페이지를 찾을 수 없어요</h1>
      <p className="text-muted">
        주소가 바뀌었거나 삭제된 페이지일 수 있습니다. 컬렉션에서 다시 찾아보세요.
      </p>
      <Link href="/products" className={buttonClass("primary", "md", "mt-2")}>
        컬렉션 보기 →
      </Link>
    </main>
  );
}
