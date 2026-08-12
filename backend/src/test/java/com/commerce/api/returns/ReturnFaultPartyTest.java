package com.commerce.api.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerce.api.global.common.CancelReason;
import com.commerce.api.global.common.CancelReason.Fault;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.returns.dto.ReturnAction;
import com.commerce.api.returns.dto.ReturnCreateRequest;
import com.commerce.api.returns.dto.ReturnResponse;
import com.commerce.api.returns.dto.ReturnStatusUpdateRequest;
import com.commerce.api.returns.entity.ReturnStatus;
import com.commerce.api.returns.entity.ReturnType;
import com.commerce.api.returns.service.ReturnService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 귀책 확정(#8 후속 P1) — 검수(INSPECT)에서 부담 주체를 확정하고 스냅샷한다.
 *
 * <p>이 phase는 <b>돈을 건드리지 않는다</b>. 여기서 확정된 {@code faultParty}를 P2(고객 부담 회수비)·
 * P4(셀러 귀책 과금)가 읽어 쓴다. 핵심은 "구매자 자기신고를 그대로 돈에 연결하지 않는다"는 것:
 * 신고는 참고값이고, 수거·검수 후 셀러가 확정하며, 어드민이 종료 전까지 뒤집을 수 있다.
 */
@SpringBootTest
@Transactional
class ReturnFaultPartyTest {

    @Autowired private ReturnService returnService;
    @Autowired private OrderRepository orderRepository;

    private OrderItem item(Long sellerId, long price) {
        return OrderItem.builder().productId(sellerId == null ? 99L : sellerId).optionId(11L).sellerId(sellerId)
                .productName("P").size("M").orderPrice(price).quantity(1).build();
    }

    private Order deliveredOrder() {
        Order order = Order.create(100L);
        order.addItem(item(1L, 5000L));
        order.markPaid();
        order.advanceShipping(OrderStatus.SHIPPING, null, "CJ", "1");
        order.advanceShipping(OrderStatus.DELIVERED, null, null, null);
        return orderRepository.saveAndFlush(order);
    }

    /** 주어진 사유로 반품을 요청하고 수거(PICKED_UP)까지 전진시킨다 — 검수 직전 상태. */
    private ReturnResponse pickedUp(Order order, CancelReason reason) {
        long itemId = order.getOrderItems().get(0).getId();
        ReturnResponse req = returnService.create(100L, false, order.getId(),
                new ReturnCreateRequest(itemId, ReturnType.RETURN, "상세", reason, null));
        returnService.advanceForSeller(req.id(), 1L, new ReturnStatusUpdateRequest(ReturnAction.APPROVE, null), 7L);
        returnService.advanceForSeller(req.id(), 1L, new ReturnStatusUpdateRequest(ReturnAction.PICK_UP, null), 7L);
        return req;
    }

    @Test
    @DisplayName("검수 시 귀책 미지정 - 구매자 신고 사유에서 파생 (DEFECTIVE → SELLER)")
    void inspectWithoutFault_derivesFromReason() {
        Order order = deliveredOrder();
        ReturnResponse req = pickedUp(order, CancelReason.DEFECTIVE);

        ReturnResponse inspected = returnService.advanceForSeller(req.id(), 1L,
                new ReturnStatusUpdateRequest(ReturnAction.INSPECT, null), 7L);

        assertThat(inspected.status()).isEqualTo(ReturnStatus.INSPECTED);
        assertThat(inspected.faultParty()).isEqualTo(Fault.SELLER);   // 신고대로 확정
        assertThat(inspected.effectiveFault()).isEqualTo(Fault.SELLER);
    }

    @Test
    @DisplayName("검수 시 셀러가 판정 - 구매자가 '불량'이라 신고해도 검수 결과가 이긴다 (SELLER → CUSTOMER)")
    void inspectWithFault_sellerJudgementWins() {
        Order order = deliveredOrder();
        ReturnResponse req = pickedUp(order, CancelReason.DEFECTIVE);   // 구매자 신고 = 셀러 귀책

        ReturnResponse inspected = returnService.advanceForSeller(req.id(), 1L,
                new ReturnStatusUpdateRequest(ReturnAction.INSPECT, "열어보니 하자 없음", Fault.CUSTOMER), 7L);

        assertThat(inspected.faultParty()).isEqualTo(Fault.CUSTOMER);
        assertThat(inspected.effectiveFault()).isEqualTo(Fault.CUSTOMER);
        // 신고값(reasonCode)은 그대로 보존 — 무엇을 신고했고 무엇으로 판정됐는지 둘 다 남아야 분쟁 추적이 된다
        assertThat(inspected.reasonCode()).isEqualTo(CancelReason.DEFECTIVE);
    }

    @Test
    @DisplayName("사유 없는 반품 - 확정 귀책도 NONE (플랫폼 흡수 = 모르면 우리가 문다)")
    void noReason_fallsBackToNone() {
        Order order = deliveredOrder();
        ReturnResponse req = pickedUp(order, null);

        ReturnResponse inspected = returnService.advanceForSeller(req.id(), 1L,
                new ReturnStatusUpdateRequest(ReturnAction.INSPECT, null), 7L);

        assertThat(inspected.faultParty()).isEqualTo(Fault.NONE);
        assertThat(inspected.effectiveFault()).isEqualTo(Fault.NONE);
    }

    @Test
    @DisplayName("검수 전에는 확정 귀책이 없다 - 다만 실효 귀책은 신고 사유에서 파생돼 조회 가능")
    void beforeInspect_snapshotNullButEffectiveDerived() {
        Order order = deliveredOrder();
        ReturnResponse req = pickedUp(order, CancelReason.CHANGE_OF_MIND);

        assertThat(req.faultParty()).isNull();                       // 아직 확정 안 됨
        assertThat(req.effectiveFault()).isEqualTo(Fault.CUSTOMER);  // 신고 기준 예상값
    }

    @Test
    @DisplayName("ADMIN 재정 - SET_FAULT는 상태를 전이시키지 않고 귀책만 뒤집는다")
    void adminOverride_doesNotTransition() {
        Order order = deliveredOrder();
        ReturnResponse req = pickedUp(order, CancelReason.CHANGE_OF_MIND);
        returnService.advanceForSeller(req.id(), 1L,
                new ReturnStatusUpdateRequest(ReturnAction.INSPECT, null), 7L);

        ReturnResponse overridden = returnService.advanceForAdmin(order.getId(), req.id(),
                new ReturnStatusUpdateRequest(ReturnAction.SET_FAULT, "고객 이의 인정", Fault.SELLER), 9L);

        assertThat(overridden.status()).isEqualTo(ReturnStatus.INSPECTED);   // 상태는 그대로
        assertThat(overridden.faultParty()).isEqualTo(Fault.SELLER);
        // 재정 이력이 남는다 — 셀러가 자기 이익 방향으로 판정할 수 있으므로 누가 뒤집었는지 추적 가능해야 한다
        assertThat(overridden.statusHistory())
                .anyMatch(h -> h.changedBy() != null && h.changedBy().equals(9L)
                        && h.memo() != null && h.memo().contains("귀책 재정"));
    }

    @Test
    @DisplayName("셀러는 귀책을 재정할 수 없다 - SET_FAULT 403")
    void sellerCannotOverride() {
        Order order = deliveredOrder();
        ReturnResponse req = pickedUp(order, CancelReason.CHANGE_OF_MIND);
        returnService.advanceForSeller(req.id(), 1L,
                new ReturnStatusUpdateRequest(ReturnAction.INSPECT, null), 7L);

        assertThatThrownBy(() -> returnService.advanceForSeller(req.id(), 1L,
                new ReturnStatusUpdateRequest(ReturnAction.SET_FAULT, null, Fault.SELLER), 7L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("셀러가 INSPECT 외 액션에 귀책을 실어도 무시된다 - 판정 시점은 검수 하나뿐")
    void sellerFaultIgnoredOutsideInspect() {
        Order order = deliveredOrder();
        long itemId = order.getOrderItems().get(0).getId();
        ReturnResponse req = returnService.create(100L, false, order.getId(),
                new ReturnCreateRequest(itemId, ReturnType.RETURN, "상세", CancelReason.DEFECTIVE, null));

        // 승인에 CUSTOMER를 실어 보내도 확정되지 않는다
        ReturnResponse approved = returnService.advanceForSeller(req.id(), 1L,
                new ReturnStatusUpdateRequest(ReturnAction.APPROVE, null, Fault.CUSTOMER), 7L);

        assertThat(approved.faultParty()).isNull();
        assertThat(approved.effectiveFault()).isEqualTo(Fault.SELLER);   // 여전히 신고 기준
    }

    @Test
    @DisplayName("종료된 반품의 귀책은 재정 불가 - REJECTED 이후 409")
    void terminalReturnCannotBeOverridden() {
        Order order = deliveredOrder();
        long itemId = order.getOrderItems().get(0).getId();
        ReturnResponse req = returnService.create(100L, false, order.getId(),
                new ReturnCreateRequest(itemId, ReturnType.RETURN, "상세", CancelReason.CHANGE_OF_MIND, null));
        returnService.advanceForSeller(req.id(), 1L,
                new ReturnStatusUpdateRequest(ReturnAction.REJECT, "요청 거부"), 7L);

        assertThatThrownBy(() -> returnService.advanceForAdmin(order.getId(), req.id(),
                new ReturnStatusUpdateRequest(ReturnAction.SET_FAULT, null, Fault.SELLER), 9L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("SET_FAULT에 귀책이 빠지면 400")
    void setFaultRequiresValue() {
        Order order = deliveredOrder();
        ReturnResponse req = pickedUp(order, CancelReason.CHANGE_OF_MIND);

        assertThatThrownBy(() -> returnService.advanceForAdmin(order.getId(), req.id(),
                new ReturnStatusUpdateRequest(ReturnAction.SET_FAULT, null, null), 9L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }
}
