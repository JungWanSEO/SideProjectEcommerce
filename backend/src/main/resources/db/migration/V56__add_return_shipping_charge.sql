-- #8 후속 P2: 반품 회수비(왕복배송비) — 고객 귀책 반품에서 고객이 부담하는 회수 편도 비용.
--
--  · return_request.return_shipping_fee     : 반품 신청 시점의 정책 요율 스냅샷(귀책과 무관하게 항상 기록).
--  · return_request.return_shipping_charged : 검수확정 시 실제로 차감된 금액(귀책·클램프 적용 후). 그 전 NULL.
--  · orders.return_shipping_charge          : 그 주문에서 누적 차감된 회수비 합(payable 가산용).
--
-- 왜 요율과 실차감액을 나눠 두는가 — refund_amount의 의미가 '실효가'에서 '실지급액'으로 바뀌기 때문이다.
--   총액(effectivePrice) = 실지급액(refund_amount) + 차감액(return_shipping_charged)을 원장에 남겨야
--   "왜 덜 받았는지"가 사라지지 않는다. 요율은 신청 시점 스냅샷이라 정책이 바뀌어도 과거 반품은 불변이다.
--   (원배송비는 '주문 시점' 스냅샷, 회수비는 '반품 신청 시점' 스냅샷 — 기준 시점이 다른 건 의도된 비대칭이다.
--    각각 "고객이 그 거래를 시작한 시점의 약속"이기 때문.)
--
-- 왜 orders에 누계 컬럼이 필요한가 — 이게 이 작업의 급소다.
--   취소 환불 공식은 refundNow = (payment.amount − refundedAmount) − order.getPayableAmount() 한 줄이고,
--   payment.amount − refundedAmount == getPayableAmount() 라는 항등식을 전제로 한다.
--   회수비를 '그냥 덜 환불'하면 잔여가 payable보다 회수비만큼 크게 남고, 같은 주문의 다른 항목을 취소하는
--   순간 이 공식이 그 차액을 자동으로 환불해 버린다 — 고객이 회수비를 되돌려받는 무음 누수.
--   그래서 차감한 만큼을 payable에 되더해 항등식을 유지한다(#4가 배송비에 쓴 'payable 접기'와 같은 트릭).
ALTER TABLE return_request ADD COLUMN return_shipping_fee bigint NULL;
ALTER TABLE return_request ADD COLUMN return_shipping_charged bigint NULL;
ALTER TABLE orders ADD COLUMN return_shipping_charge bigint NOT NULL DEFAULT 0;
