package com.commerce.api.settlement.repository;

import com.commerce.api.settlement.entity.SettlementEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<SettlementEntry, Long>, SettlementRepositoryCustom {

    /**
     * 해당 결제에 대한 정산 항목이 이미 있는지.
     * 정산 배치가 같은 결제를 두 번 잡지 않도록(멱등) 사용한다.
     * (DB에도 payment_id UNIQUE 제약을 둬서 동시 실행에도 중복을 막는다.)
     */
    boolean existsByPaymentId(Long paymentId);
}
