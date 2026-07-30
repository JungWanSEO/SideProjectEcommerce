"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { apiGet, apiPatch } from "@/lib/api";
import { Notification, PageResponse } from "@/lib/types";

/** 안읽음 카운트 폴링 주기(ms) — 인앱 벨은 실시간까지 필요 없어 가볍게 30초. */
const POLL_MS = 30_000;

/** 생성 시각을 "방금 전 / N분 전 / N시간 전 / N일 전"으로. */
function relativeTime(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const diffSec = Math.floor((Date.now() - then) / 1000);
  if (diffSec < 60) return "방금 전";
  const min = Math.floor(diffSec / 60);
  if (min < 60) return `${min}분 전`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}시간 전`;
  return `${Math.floor(hr / 24)}일 전`;
}

/** 톤 — 스토어(크림/clay)와 셀러 콘솔(화이트/그레이)의 색만 다르고 동작은 같다. */
const TONES = {
  store: {
    button: "text-ink/70 hover:text-clay",
    badge: "bg-clay",
    panel: "border-line bg-cream",
    divide: "divide-line",
    header: "border-line text-ink",
    action: "text-muted hover:text-clay",
    unreadRow: "bg-clay/5",
    dot: "bg-clay",
    rowHover: "hover:bg-line/30",
    title: "text-ink",
    muted: "text-muted",
  },
  console: {
    button: "text-gray-500 hover:text-gray-900",
    badge: "bg-gray-900",
    panel: "border-gray-200 bg-white",
    divide: "divide-gray-100",
    header: "border-gray-200 text-gray-900",
    action: "text-gray-500 hover:text-gray-900",
    unreadRow: "bg-gray-50",
    dot: "bg-gray-900",
    rowHover: "hover:bg-gray-50",
    title: "text-gray-900",
    muted: "text-gray-500",
  },
} as const;

/**
 * 알림 벨(#6) — 안읽음 뱃지 + 드롭다운 인박스. 본인 스코프 알림만(백엔드가 강제).
 *
 * <p>안읽음 수는 `{basePath}/unread-count`를 주기적으로 폴링하고, 열 때 목록을 불러온다.
 * 항목 클릭 시 읽음 처리 후 딥링크로 이동한다.
 *
 * <p><b>구매자·셀러 공용</b>: 두 인박스는 엔드포인트와 색만 다르고 동작이 같아 컴포넌트를 복제하지 않고
 * `basePath`(구매자 `/api/notifications` · 셀러 `/api/seller/me/notifications`)와 `tone`으로 주입한다.
 * 수신자 스코핑은 서버가 하므로(셀러는 자기 sellerId 것만) FE는 경로만 바꿔 끼우면 된다.
 */
export default function NotificationBell({
  basePath = "/api/notifications",
  tone = "store",
}: {
  basePath?: string;
  tone?: keyof typeof TONES;
} = {}) {
  const t = TONES[tone];
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [unread, setUnread] = useState(0);
  const [items, setItems] = useState<Notification[]>([]);
  const [loading, setLoading] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);

  const loadUnread = useCallback(async () => {
    try {
      setUnread(await apiGet<number>(`${basePath}/unread-count`));
    } catch {
      /* 벨은 비필수 — 실패는 조용히 무시(다음 폴링에 회복) */
    }
  }, [basePath]);

  // 마운트 시 + 주기 폴링으로 안읽음 수 갱신
  useEffect(() => {
    loadUnread();
    const timer = setInterval(loadUnread, POLL_MS);
    return () => clearInterval(timer);
  }, [loadUnread]);

  // 바깥 클릭 시 닫기
  useEffect(() => {
    if (!open) return;
    const onClick = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onClick);
    return () => document.removeEventListener("mousedown", onClick);
  }, [open]);

  const loadList = useCallback(async () => {
    setLoading(true);
    try {
      const page = await apiGet<PageResponse<Notification>>(basePath);
      setItems(page.content);
    } catch {
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, [basePath]);

  const toggle = () => {
    const next = !open;
    setOpen(next);
    if (next) loadList(); // 열 때 최신 목록
  };

  const openItem = async (n: Notification) => {
    if (!n.read) {
      setItems((prev) => prev.map((it) => (it.id === n.id ? { ...it, read: true } : it)));
      setUnread((c) => Math.max(0, c - 1));
      try {
        await apiPatch(`${basePath}/${n.id}/read`);
      } catch {
        /* 낙관적 업데이트 — 실패해도 다음 폴링/조회에 정정 */
      }
    }
    if (n.link) {
      setOpen(false);
      router.push(n.link);
    }
  };

  const markAll = async () => {
    setItems((prev) => prev.map((it) => ({ ...it, read: true })));
    setUnread(0);
    try {
      await apiPatch(`${basePath}/read-all`);
    } catch {
      /* 무시 */
    }
  };

  return (
    <div className="relative" ref={boxRef}>
      <button
        onClick={toggle}
        aria-label={`알림${unread > 0 ? ` (안읽음 ${unread})` : ""}`}
        className={`relative flex items-center transition ${t.button}`}
      >
        {/* 벨 아이콘(인라인 SVG — 외부 의존 없음) */}
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
          strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
          <path d="M13.73 21a2 2 0 0 1-3.46 0" />
        </svg>
        {unread > 0 && (
          <span className={`absolute -right-2 -top-2 flex h-4 min-w-4 items-center justify-center rounded-full px-1 text-[10px] font-semibold leading-none text-white ${t.badge}`}>
            {unread > 9 ? "9+" : unread}
          </span>
        )}
      </button>

      {open && (
        <div className={`absolute right-0 top-9 z-40 w-80 overflow-hidden rounded-xl border shadow-lg ${t.panel}`}>
          <div className={`flex items-center justify-between border-b px-4 py-2.5 ${t.header}`}>
            <span className={`text-sm font-semibold ${t.title}`}>알림</span>
            {items.some((i) => !i.read) && (
              <button onClick={markAll} className={`text-xs transition ${t.action}`}>
                모두 읽음
              </button>
            )}
          </div>
          <ul className={`max-h-96 divide-y overflow-y-auto ${t.divide}`}>
            {loading ? (
              <li className={`px-4 py-6 text-center text-sm ${t.muted}`}>불러오는 중…</li>
            ) : items.length === 0 ? (
              <li className={`px-4 py-8 text-center text-sm ${t.muted}`}>알림이 없습니다.</li>
            ) : (
              items.map((n) => (
                <li key={n.id}>
                  <button
                    onClick={() => openItem(n)}
                    className={`flex w-full items-start gap-2 px-4 py-3 text-left transition ${t.rowHover} ${
                      n.read ? "" : t.unreadRow
                    }`}
                  >
                    <span
                      className={`mt-1.5 h-1.5 w-1.5 flex-shrink-0 rounded-full ${
                        n.read ? "bg-transparent" : t.dot
                      }`}
                      aria-hidden="true"
                    />
                    <span className="min-w-0 flex-1">
                      <span className={`block text-sm ${n.read ? t.muted : `font-medium ${t.title}`}`}>
                        {n.message}
                      </span>
                      <span className={`mt-0.5 block text-xs ${t.muted}`}>{relativeTime(n.createdAt)}</span>
                    </span>
                  </button>
                </li>
              ))
            )}
          </ul>
        </div>
      )}
    </div>
  );
}
