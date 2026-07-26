-- V48: 반품/교환 요청 애그리거트(#3) — return_request + return_status_history.
--
-- 배송완료 후 역방향 다단계 워크플로(요청→승인→수거→검수→환불/교환완료)를 담는 독립 애그리거트.
-- 다른 애그리거트(주문/항목/배송/셀러/회원)는 ID 참조만 — FK 금지·KEY 인덱스만(V45 컨벤션). enum 알파벳순(validate).
-- version bigint NOT NULL DEFAULT 0(낙관락, V44 패턴).

CREATE TABLE `return_request` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `created_at` datetime(6) DEFAULT NULL,
    `updated_at` datetime(6) DEFAULT NULL,
    `version` bigint NOT NULL DEFAULT 0,
    `order_id` bigint NOT NULL,
    `order_item_id` bigint NOT NULL,
    `shipment_id` bigint NOT NULL,
    `seller_id` bigint DEFAULT NULL COMMENT '셀러 스냅샷(인가·정산 귀속). NULL=플랫폼 버킷',
    `member_id` bigint NOT NULL COMMENT '요청한 구매자',
    `type` enum('EXCHANGE','RETURN') COLLATE utf8mb4_unicode_ci NOT NULL,
    `status` enum('APPROVED','COMPLETED','INSPECTED','PICKED_UP','REFUNDED','REJECTED','REQUESTED')
        COLLATE utf8mb4_unicode_ci NOT NULL,
    `reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `quantity` int NOT NULL,
    `refund_amount` bigint DEFAULT NULL COMMENT '검수확정 시 확정(RETURN)',
    `restock` bit(1) NOT NULL DEFAULT b'1',
    `exchange_option_id` bigint DEFAULT NULL COMMENT '교환 대상 옵션(EXCHANGE)',
    `exchange_shipment_id` bigint DEFAULT NULL COMMENT '교환 재출고 shipment(P6)',
    PRIMARY KEY (`id`),
    -- 진행 중 중복 반품 가드.
    KEY `idx_return_order_item` (`order_item_id`, `status`),
    -- 셀러 콘솔의 셀러별 반품 목록.
    KEY `idx_return_seller` (`seller_id`, `status`),
    -- 구매자 내 반품 목록.
    KEY `idx_return_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `return_status_history` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `created_at` datetime(6) DEFAULT NULL,
    `updated_at` datetime(6) DEFAULT NULL,
    `return_request_id` bigint NOT NULL,
    `from_status` enum('APPROVED','COMPLETED','INSPECTED','PICKED_UP','REFUNDED','REJECTED','REQUESTED')
        COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `to_status` enum('APPROVED','COMPLETED','INSPECTED','PICKED_UP','REFUNDED','REJECTED','REQUESTED')
        COLLATE utf8mb4_unicode_ci NOT NULL,
    `changed_by` bigint DEFAULT NULL,
    `memo` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_rsh_return` (`return_request_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
