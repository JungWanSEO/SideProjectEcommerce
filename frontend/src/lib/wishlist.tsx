"use client";

import { createContext, useCallback, useContext, useEffect, useState, ReactNode } from "react";
import { apiDelete, apiGet, apiPost } from "./api";
import { useAuth } from "./auth";

/**
 * 찜(위시리스트) 상태 전역 관리.
 *
 * 하트 버튼이 상품 목록·상세·찜 페이지 여러 곳에 흩어져 있어, "내가 찜한 상품 ID 집합"을 한 번 받아
 * 전역으로 공유한다(매 상품마다 찜 여부를 따로 묻지 않음 — GET /api/wishlist/me/product-ids 한 방).
 * AuthProvider와 같은 컨텍스트 패턴. 로그인하면 채우고, 로그아웃하면 비운다.
 */
interface WishlistContextType {
  isWishlisted: (productId: number) => boolean;
  toggle: (productId: number) => Promise<void>; // 찜/해제 토글 (낙관적 갱신)
  count: number; // 내 찜 개수
  loading: boolean; // 최초 ID 목록 로딩 중
}

const WishlistContext = createContext<WishlistContextType | null>(null);

export function WishlistProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const [ids, setIds] = useState<Set<number>>(new Set());
  const [loading, setLoading] = useState(false);

  // 로그인 사용자가 바뀌면 내 찜 상품 ID를 다시 받는다. 비로그인이면 비운다.
  useEffect(() => {
    if (!user) {
      setIds(new Set());
      return;
    }
    setLoading(true);
    apiGet<number[]>("/api/wishlist/me/product-ids")
      .then((list) => setIds(new Set(list)))
      .catch(() => setIds(new Set())) // 401 등 → 비움
      .finally(() => setLoading(false));
  }, [user]);

  const isWishlisted = useCallback((productId: number) => ids.has(productId), [ids]);

  // 토글: 현재 찜이면 해제(DELETE), 아니면 추가(POST). 서버 성공 후 로컬 집합 갱신.
  const toggle = useCallback(
    async (productId: number) => {
      if (ids.has(productId)) {
        await apiDelete<void>(`/api/wishlist/${productId}`);
        setIds((prev) => {
          const next = new Set(prev);
          next.delete(productId);
          return next;
        });
      } else {
        await apiPost<unknown>("/api/wishlist", { productId });
        setIds((prev) => new Set(prev).add(productId));
      }
    },
    [ids],
  );

  return (
    <WishlistContext.Provider value={{ isWishlisted, toggle, count: ids.size, loading }}>
      {children}
    </WishlistContext.Provider>
  );
}

export function useWishlist() {
  const ctx = useContext(WishlistContext);
  if (!ctx) throw new Error("useWishlist는 WishlistProvider 안에서만 사용할 수 있습니다.");
  return ctx;
}
