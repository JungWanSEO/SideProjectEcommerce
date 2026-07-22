"use client";

import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from "react";

/**
 * 인하우스 토스트 — 외부 의존성 0. 앱 전체에 "담기 완료 / 저장됨 / 실패" 같은 결과를 일관되게 알린다.
 *
 * 왜 만들었나: 지금까지 모든 mutation 결과는 버튼 옆 inline <span>이거나(눈에 잘 안 띔), WishlistButton처럼
 * 아예 조용히 삼켜져(catch {}) 실패가 안 보였다. 화면 우하단에 잠깐 떴다 사라지는 토스트로 통일한다.
 *
 * 접근성: 컨테이너는 role="status" + aria-live="polite"라 스크린리더가 새 메시지를 읽는다.
 */
type ToastType = "success" | "error";

interface Toast {
  id: number;
  message: string;
  type: ToastType;
}

interface ToastContextType {
  /** 성공 토스트 */
  success: (message: string) => void;
  /** 실패 토스트 (조금 더 오래 유지) */
  error: (message: string) => void;
}

const ToastContext = createContext<ToastContextType | null>(null);

const AUTO_DISMISS_MS = { success: 2500, error: 4000 };

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const seq = useRef(0);

  const remove = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const push = useCallback(
    (message: string, type: ToastType) => {
      const id = ++seq.current;
      setToasts((prev) => [...prev, { id, message, type }]);
      // setTimeout은 렌더와 무관한 정리 작업이라 effect 없이 바로 예약해도 안전(언마운트돼도 remove는 no-op).
      setTimeout(() => remove(id), AUTO_DISMISS_MS[type]);
    },
    [remove],
  );

  const success = useCallback((message: string) => push(message, "success"), [push]);
  const error = useCallback((message: string) => push(message, "error"), [push]);

  return (
    <ToastContext.Provider value={{ success, error }}>
      {children}
      {/* 화면 우하단 스택. pointer-events-none으로 아래 UI 클릭을 막지 않되, 각 토스트만 클릭 가능. */}
      <div
        role="status"
        aria-live="polite"
        className="pointer-events-none fixed bottom-4 right-4 z-[60] flex flex-col gap-2"
      >
        {toasts.map((t) => (
          <button
            key={t.id}
            onClick={() => remove(t.id)}
            className={`pointer-events-auto max-w-xs rounded-xl px-4 py-3 text-left text-sm shadow-soft transition ${
              t.type === "success"
                ? "bg-ink text-paper"
                : "border border-red-200 bg-red-50 text-red-700"
            }`}
          >
            {t.message}
          </button>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

/** 토스트 트리거 훅. ToastProvider 밖에서 쓰면 에러. */
export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast는 ToastProvider 안에서만 사용할 수 있습니다.");
  return ctx;
}
