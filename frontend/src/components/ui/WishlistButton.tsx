"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { useWishlist } from "@/lib/wishlist";
import { useToast } from "@/lib/toast";
import { loginHref } from "@/lib/useRequireAuth";

/**
 * 찜 하트 토글 버튼.
 *
 * - 상태(채움/빈 하트)는 {@link useWishlist} 전역 집합에서 읽는다 → 어느 화면에서 토글해도 일관.
 * - 비로그인 클릭은 로그인으로 보낸다(백엔드도 401이지만 UX상 먼저 라우팅).
 * - 상품 카드는 전체가 <Link>라, 카드 위에 얹는 overlay 변형은 클릭 전파/기본동작을 막는다.
 *
 * variant: "overlay"(카드 코너 — 반투명 원형) / "inline"(상세 페이지 — 테두리 버튼).
 */
export default function WishlistButton({
  productId,
  variant = "overlay",
  className = "",
}: {
  productId: number;
  variant?: "overlay" | "inline";
  className?: string;
}) {
  const { user } = useAuth();
  const { isWishlisted, toggle } = useWishlist();
  const router = useRouter();
  const toast = useToast();
  const [busy, setBusy] = useState(false);

  const active = isWishlisted(productId);

  const onClick = async (e: React.MouseEvent) => {
    e.preventDefault(); // 카드 <Link> 네비게이션 방지
    e.stopPropagation();
    if (!user) {
      // 로그인 후 원래 페이지로 되돌아오도록 returnTo를 실어 보낸다.
      router.push(loginHref(window.location.pathname + window.location.search));
      return;
    }
    setBusy(true);
    try {
      await toggle(productId);
    } catch (err) {
      // 예전엔 조용히 삼켰다 → 이제 실패가 보이도록 토스트로 알린다(상태는 서버 성공 시에만 바뀜).
      toast.error((err as Error).message || "찜 처리에 실패했습니다.");
    } finally {
      setBusy(false);
    }
  };

  const base =
    variant === "overlay"
      ? "absolute right-3 top-3 z-10 grid h-9 w-9 place-items-center rounded-full bg-cream/85 backdrop-blur transition hover:bg-cream disabled:opacity-50"
      : "inline-flex items-center gap-2 rounded-full border border-line bg-paper px-4 py-2.5 text-sm text-ink transition hover:border-clay disabled:opacity-50";

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={busy}
      aria-pressed={active}
      aria-label={active ? "찜 해제" : "찜하기"}
      title={active ? "찜 해제" : "찜하기"}
      className={`${base} ${className}`}
    >
      <Heart filled={active} />
      {variant === "inline" && <span>{active ? "찜함" : "찜하기"}</span>}
    </button>
  );
}

/** 하트 아이콘 — 채움이면 점토색 fill, 아니면 외곽선만. */
function Heart({ filled }: { filled: boolean }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className={`h-5 w-5 transition ${filled ? "text-clay" : "text-muted"}`}
      fill={filled ? "currentColor" : "none"}
      stroke="currentColor"
      strokeWidth="1.8"
      aria-hidden
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M12 20.5l-7.1-7.2a4.5 4.5 0 016.4-6.3l.7.7.7-.7a4.5 4.5 0 016.4 6.3L12 20.5z"
      />
    </svg>
  );
}
