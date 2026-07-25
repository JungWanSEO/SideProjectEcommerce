-- V45: 셀러별 배송 단위(shipment) + 배송 상태 이력 — 멀티셀러 주문 상태 단위(#1 c안).
--
-- 배경: orders.status는 주문 전체 배송축이라, 한 주문에 셀러가 여럿 섞이면 셀러별 개별 출고를 표현 못 한다
--   (advanceShipping이 주문 전체를 SHIPPING으로 올려, 아직 출고 안 한 셀러 항목까지 취소를 막았다).
--   → 배송축을 셀러별 shipment로 내리고, orders.status는 shipment들의 rollup 파생값으로 재계산한다(P3).
--
-- 모델:
--   · shipment = (주문, 셀러)별 출고 묶음 한 행. seller_id=NULL은 플랫폼 직매입 버킷(ADMIN 출고).
--     항목과의 연결은 FK가 아니라 (order_id, seller_id) 매칭으로 암묵적 — order_item 스키마 불변.
--   · 결제 시점(P2)에 활성 항목을 seller_id로 팬아웃해 각 shipment status=PAID로 생성.
--   · 전이는 forward-only PAID→SHIPPING→DELIVERED(Shipment.advanceShipping), 셀러별 version으로 독립 전진.
--   · courier/tracking_number를 shipment로 내린다(셀러별 개별 송장). orders.courier/tracking_number는
--     P6(코드 컷오버 후)에서 DROP — 지금은 expand 단계라 남겨둔다.
--
-- 컨벤션: 애그리거트 내부 테이블은 FK 금지·KEY 인덱스만(V39·V43 관례). enum 값은 알파벳순(hbm2ddl validate 정합).
--   version bigint NOT NULL DEFAULT 0(백필 INSERT 안전, V44 패턴). add-only 비파괴.

CREATE TABLE `shipment` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `created_at` datetime(6) DEFAULT NULL,
    `updated_at` datetime(6) DEFAULT NULL,
    `version` bigint NOT NULL DEFAULT 0,
    `order_id` bigint NOT NULL,
    `seller_id` bigint DEFAULT NULL COMMENT '셀러 ID 스냅샷 (NULL=플랫폼 직매입 버킷, ADMIN 출고)',
    `status` enum('CANCELLED','DELIVERED','PAID','SHIPPING') COLLATE utf8mb4_unicode_ci NOT NULL,
    `courier` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '택배사 (SHIPPING 전이 시 입력)',
    `tracking_number` varchar(60) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '운송장 번호',
    PRIMARY KEY (`id`),
    -- 주문별 shipment 조회(상세·rollup·백필).
    KEY `idx_shipment_order` (`order_id`),
    -- 셀러 콘솔의 셀러별·상태별 출고 목록.
    KEY `idx_shipment_seller_status` (`seller_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `shipment_status_history` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `created_at` datetime(6) DEFAULT NULL,
    `updated_at` datetime(6) DEFAULT NULL,
    `shipment_id` bigint NOT NULL,
    `from_status` enum('CANCELLED','DELIVERED','PAID','SHIPPING') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `to_status`   enum('CANCELLED','DELIVERED','PAID','SHIPPING') COLLATE utf8mb4_unicode_ci NOT NULL,
    `changed_by` bigint DEFAULT NULL COMMENT '변경 주체 회원 ID (셀러/ADMIN, 시스템이면 NULL)',
    `memo` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    PRIMARY KEY (`id`),
    -- shipment별 이력 시간순 조회 — shipment_id 선두, id로 안정 정렬.
    KEY `idx_ssh_shipment` (`shipment_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
