-- V25: 쿠폰/프로모션 도메인 (Step 1 — 코어 + 체크아웃 적용). Phase 2 후속.
--  · coupon: 발급된 한 행을 여러 주문이 코드로 사용(코드 입력형). 코드는 대문자 정규화해 저장(UNIQUE).
--  · discount_type/funded_by/status enum 값 순서 = Java enum 선언 순서(= Hibernate ENUM DDL) → ddl-auto: validate 통과.
--    DiscountType(FIXED_AMOUNT, PERCENTAGE) · CouponFundedBy(PLATFORM, SELLER) · CouponStatus(ACTIVE, DISABLED). 모두 알파벳순.
--  · 금액은 bigint(원). discount_value=정액(원)/정률(퍼센트 1~100). max_discount_amount=정률 상한(원, nullable).
--  · seller_id: null=플랫폼 와이드(주문 전체), 값=해당 셀러 상품 소계에만(셀러 한정). 다른 애그리거트 → ID 참조(FK 안 둠).
--  · funded_by(플랫폼/셀러 부담)는 셀러별 정산 분담의 분류축 — 실제 정산 분해 반영은 Step 2(SettlementEntry).
--  · orders: discount_amount(쿠폰 할인액)·coupon_code(적용 코드 스냅샷) 추가. 결제액 payable = total_price - discount_amount.
--    항목 소계(gross)는 보존 → 정산 Step 2가 원가 기준으로 셀러별 안분. funded_by/seller_id 주문 스냅샷은 Step 2에서 추가.

CREATE TABLE `coupon` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `code` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `discount_type` enum('FIXED_AMOUNT','PERCENTAGE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `discount_value` bigint NOT NULL,
  `max_discount_amount` bigint DEFAULT NULL,
  `min_order_amount` bigint NOT NULL DEFAULT 0,
  `funded_by` enum('PLATFORM','SELLER') COLLATE utf8mb4_unicode_ci NOT NULL,
  `seller_id` bigint DEFAULT NULL,
  `valid_from` datetime(6) NOT NULL,
  `valid_until` datetime(6) NOT NULL,
  `status` enum('ACTIVE','DISABLED') COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_coupon_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `orders`
  ADD COLUMN `discount_amount` bigint NOT NULL DEFAULT 0,
  ADD COLUMN `coupon_code` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL;
