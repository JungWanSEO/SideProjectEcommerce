"use client";

import { useEffect } from "react";
import { buttonClass } from "@/components/ui/Button";

/**
 * 라우트 에러 바운더리 — 렌더/데이터 로딩 중 던져진 예외를 잡아 흰 화면(크래시) 대신 복구 UI를 보여준다.
 *
 * App Router는 각 세그먼트의 error.tsx를 클라이언트 컴포넌트 에러 바운더리로 감싼다. reset()은 해당
 * 세그먼트를 다시 렌더 시도한다(일시적 오류면 복구). 그 전엔 바운더리가 없어 예외 시 화면이 통째로 깨졌다.
 */
export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // 운영에선 여기서 관측 도구로 리포트. 지금은 콘솔로 흔적만 남긴다.
    console.error(error);
  }, [error]);

  return (
    <main className="mx-auto flex max-w-lg flex-col items-center justify-center gap-6 px-6 py-32 text-center">
      <span className="text-xs uppercase tracking-[0.35em] text-clay">Error</span>
      <h1 className="font-serif text-3xl text-ink">잠시 문제가 생겼어요</h1>
      <p className="text-muted">일시적인 오류일 수 있습니다. 다시 시도해 주세요.</p>
      <button onClick={reset} className={buttonClass("primary", "md", "mt-2")}>
        다시 시도
      </button>
    </main>
  );
}
