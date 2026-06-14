package com.commerce.api.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.dto.OrderResponse;
import com.commerce.api.order.dto.OrderResponse.OrderItemResponse;
import com.commerce.api.order.dto.OrderSummaryResponse;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.service.OrderService;
import com.commerce.api.payment.service.PaymentService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OrderController 통합 테스트 (@WebMvcTest + MockMvc).
 * 보안 필터는 비활성하고, 현재 로그인 회원(principal=memberId)을 SecurityContext에 직접 주입한다.
 */
@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private PaymentService paymentService;   // 컨트롤러가 취소를 위임하는 대상

    @BeforeEach
    void setAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private OrderResponse sampleOrder(OrderStatus status) {
        return new OrderResponse(1L, 1L, status, 30000L,
                List.of(new OrderItemResponse(1L, 10L, 7L, 3L, "반팔티셔츠", "M", 10000L, 3, 30000L)),
                null,   // shipping (배송지 없음 — 컨트롤러 슬라이스 테스트엔 불필요)
                LocalDateTime.now());
    }

    @Test
    @DisplayName("POST /api/orders - 주문 생성 성공 시 201")
    void create_success() throws Exception {
        given(orderService.create(eq(1L), any())).willReturn(sampleOrder(OrderStatus.PENDING));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"optionId":1,"quantity":3}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalPrice").value(30000))
                .andExpect(jsonPath("$.data.items[0].productName").value("반팔티셔츠"))
                .andExpect(jsonPath("$.data.items[0].size").value("M"));
    }

    @Test
    @DisplayName("POST /api/orders/checkout - 체크아웃 성공 시 201")
    void checkout_success() throws Exception {
        given(orderService.checkout(eq(1L), any())).willReturn(sampleOrder(OrderStatus.PENDING));

        mockMvc.perform(post("/api/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"addressId":5,"deliveryMemo":"문 앞에"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.totalPrice").value(30000));
    }

    @Test
    @DisplayName("POST /api/orders/checkout - 배송지(addressId) 누락 시 400")
    void checkout_validationFail() throws Exception {
        mockMvc.perform(post("/api/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deliveryMemo":"주소 없음"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/orders - 항목이 비어있으면 400")
    void create_validationFail() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/orders/{id} - 조회 성공 시 200")
    void getOrder_success() throws Exception {
        given(orderService.getOrder(eq(1L), eq(1L), eq(false)))   // principal=1L, ROLE_USER → isAdmin=false
                .willReturn(sampleOrder(OrderStatus.PENDING));

        mockMvc.perform(get("/api/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("POST /api/orders/{id}/cancel - 취소 성공 시 200, 상태 CANCELLED")
    void cancel_success() throws Exception {
        // 컨트롤러는 취소+환불을 PaymentService.cancelOrder(memberId, orderId, admin)에 위임한다.
        given(paymentService.cancelOrder(eq(1L), eq(1L), eq(false))).willReturn(sampleOrder(OrderStatus.CANCELLED));

        mockMvc.perform(post("/api/orders/1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("GET /api/orders/{id} - 없는 주문이면 404")
    void getOrder_notFound() throws Exception {
        given(orderService.getOrder(eq(999L), eq(1L), eq(false)))
                .willThrow(new BusinessException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."));

        mockMvc.perform(get("/api/orders/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/orders/{id} - 남의 주문이면 403")
    void getOrder_forbidden() throws Exception {
        given(orderService.getOrder(eq(1L), eq(1L), eq(false)))
                .willThrow(new BusinessException(HttpStatus.FORBIDDEN, "본인의 주문만 접근할 수 있습니다."));

        mockMvc.perform(get("/api/orders/1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/orders - 내 주문 목록 200, 페이지 메타 + 기본 정렬(createdAt desc, size 20) + 현재 회원(principal=1)으로 조회")
    void getMyOrders_success() throws Exception {
        PageResponse<OrderSummaryResponse> page = new PageResponse<>(
                List.of(new OrderSummaryResponse(
                        1L, OrderStatus.PENDING, 30000L, LocalDateTime.now(), "반팔티셔츠", 2)),
                0, 20, 1L, 1, false);
        given(orderService.getMyOrders(eq(1L), any(Pageable.class))).willReturn(page);

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.content[0].representativeProductName").value("반팔티셔츠"))
                .andExpect(jsonPath("$.data.content[0].itemCount").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        // 파라미터 없으면 @PageableDefault 적용 + 현재 로그인 회원(principal=1L)으로 호출됐는지 캡처 검증
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderService).getMyOrders(eq(1L), captor.capture());
        Pageable used = captor.getValue();
        assertThat(used.getPageSize()).isEqualTo(20);
        Sort.Order order = used.getSort().getOrderFor("createdAt");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }
}
