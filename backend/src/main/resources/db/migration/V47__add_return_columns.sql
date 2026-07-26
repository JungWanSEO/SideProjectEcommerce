-- V47: 반품/교환(#3) 토대 컬럼 — add-only/expand(비파괴).
--
--  · order_item.status에 RETURNED 추가(배송완료 후 반품 확정 항목 — 취소 CANCELLED와 원장 구분).
--    isActive()==ACTIVE만 참이라 RETURNED는 자동 비활성 → 정산 reverseRefunds(status!=ACTIVE)가 코드 변경 없이 상계.
--  · shipment.delivered_at: 배송완료 시각(반품 기한 DELIVERED+N일 O(1) 판정). DELIVERED 전이 시 앱이 세팅.
--  · shipment.kind: 원배송(ORIGINAL) vs 교환 재출고(EXCHANGE). EXCHANGE는 주문 rollup/일괄전진에서 제외돼
--    DELIVERED 주문이 재출고로 SHIPPING 후퇴하는 것을 막는다.
--
-- enum 값은 알파벳순(hbm2ddl validate 정합, V4·V24·V45 컨벤션). 기존 행은 DEFAULT로 안전 백필.

ALTER TABLE `order_item`
    MODIFY COLUMN `status` enum('ACTIVE','CANCELLED','RETURNED')
        COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE `shipment`
    ADD COLUMN `delivered_at` datetime(6) DEFAULT NULL COMMENT '배송완료 시각(반품 기한 기산점)',
    ADD COLUMN `kind` enum('EXCHANGE','ORIGINAL')
        COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ORIGINAL';

-- 레거시(V47 이전) 배송완료 shipment의 delivered_at 백필 — 이력의 DELIVERED 전이 시각으로.
--   안 하면 기존 배송완료 주문 전량이 delivered_at=NULL이 되어 반품 기한 판정이 불가(반품기한 게이트가 NULL을 별도 처리하지만
--   정확한 기산을 위해 소급). 이력이 없으면 NULL 유지(게이트가 유예/조회 폴백).
UPDATE `shipment` s
SET s.`delivered_at` = (
    SELECT MIN(h.`created_at`) FROM `shipment_status_history` h
    WHERE h.`shipment_id` = s.`id` AND h.`to_status` = 'DELIVERED'
)
WHERE s.`status` = 'DELIVERED' AND s.`delivered_at` IS NULL;
