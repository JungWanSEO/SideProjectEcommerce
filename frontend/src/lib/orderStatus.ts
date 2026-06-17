// 주문 상태의 표시 라벨/뱃지 스타일을 한 곳에서 관리 (목록·상세·결제 화면 공통).
// 상태가 늘면 여기만 고치면 된다.
import { OrderStatus } from "./types";

export const ORDER_STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING: "결제대기",
  PAID: "결제완료",
  SHIPPING: "배송중",
  DELIVERED: "배송완료",
  CANCELLED: "취소됨",
};

// Tailwind 뱃지 클래스(웜 부티크 토큰): 결제대기=연점토(주목), 완료=연세이지(positive),
// 배송중=진점토(진행), 배송완료=채운 세이지(완료), 취소=흐림
export const ORDER_STATUS_BADGE: Record<OrderStatus, string> = {
  PENDING: "bg-clay-50 text-clay-700",
  PAID: "bg-sage-50 text-sage-600",
  SHIPPING: "bg-clay-100 text-clay-700",
  DELIVERED: "bg-sage text-paper",
  CANCELLED: "bg-line/60 text-muted",
};
