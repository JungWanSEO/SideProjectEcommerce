package com.commerce.api.seller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.seller.dto.SellerCreateRequest;
import com.commerce.api.seller.dto.SellerResponse;
import com.commerce.api.seller.dto.SellerUpdateRequest;
import com.commerce.api.seller.entity.Seller;
import com.commerce.api.seller.entity.SellerStatus;
import com.commerce.api.seller.repository.SellerRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SellerService 단위 테스트 (Mockito).
 * 핵심 = 이름 중복(409)·없는 셀러(404)·상태 전이(정지/재개, 잘못된 전이는 409).
 */
@ExtendWith(MockitoExtension.class)
class SellerServiceTest {

    @Mock
    private SellerRepository sellerRepository;
    @InjectMocks
    private SellerService sellerService;

    private SellerCreateRequest sampleCreate() {
        return new SellerCreateRequest("무신사스탠다드", 0.10, "신한 110-123-456789", "123-45-67890");
    }

    /** id를 가진 셀러 생성(JPA 자동생성 id를 리플렉션으로 주입). */
    private Seller sellerWithId(Long id, String name) {
        Seller s = Seller.create(name, 0.10, null, null);
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    @Test
    @DisplayName("등록 성공 - 입력값 반영 + 상태 ACTIVE")
    void create_success() {
        given(sellerRepository.existsByName("무신사스탠다드")).willReturn(false);
        given(sellerRepository.save(any(Seller.class))).willAnswer(inv -> inv.getArgument(0));

        sellerService.create(sampleCreate());

        ArgumentCaptor<Seller> captor = ArgumentCaptor.forClass(Seller.class);
        verify(sellerRepository).save(captor.capture());
        Seller saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("무신사스탠다드");
        assertThat(saved.getCommissionRate()).isEqualTo(0.10);
        assertThat(saved.getStatus()).isEqualTo(SellerStatus.ACTIVE);
        assertThat(saved.getPayoutAccount()).isEqualTo("신한 110-123-456789");
        assertThat(saved.getBusinessNumber()).isEqualTo("123-45-67890");
    }

    @Test
    @DisplayName("등록 실패 - 이름 중복이면 409, 저장하지 않음")
    void create_duplicate() {
        given(sellerRepository.existsByName("무신사스탠다드")).willReturn(true);

        assertThatThrownBy(() -> sellerService.create(sampleCreate()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 존재");
        verify(sellerRepository, never()).save(any());
    }

    @Test
    @DisplayName("단건 조회 - 없으면 404")
    void getSeller_notFound() {
        given(sellerRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> sellerService.getSeller(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("셀러를 찾을 수 없습니다");
    }

    @Test
    @DisplayName("목록 조회 - DTO로 매핑")
    void getSellers_maps() {
        given(sellerRepository.findAll())
                .willReturn(List.of(sellerWithId(1L, "셀러A"), sellerWithId(2L, "셀러B")));

        List<SellerResponse> result = sellerService.getSellers();

        assertThat(result).extracting(SellerResponse::name).containsExactly("셀러A", "셀러B");
    }

    @Test
    @DisplayName("수정 성공 - 내용이 바뀐다")
    void update_success() {
        Seller seller = sellerWithId(1L, "셀러A");
        given(sellerRepository.findById(1L)).willReturn(Optional.of(seller));

        sellerService.update(1L,
                new SellerUpdateRequest("셀러A-수정", 0.15, "국민 222-33-4444", "999-88-77777"));

        assertThat(seller.getName()).isEqualTo("셀러A-수정");
        assertThat(seller.getCommissionRate()).isEqualTo(0.15);
        assertThat(seller.getPayoutAccount()).isEqualTo("국민 222-33-4444");
    }

    @Test
    @DisplayName("수정 실패 - 다른 셀러가 쓰는 이름으로 바꾸면 409")
    void update_duplicateName() {
        Seller seller = sellerWithId(1L, "셀러A");
        given(sellerRepository.findById(1L)).willReturn(Optional.of(seller));
        given(sellerRepository.existsByName("셀러B")).willReturn(true);

        assertThatThrownBy(() -> sellerService.update(1L,
                new SellerUpdateRequest("셀러B", 0.10, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 존재");
    }

    @Test
    @DisplayName("수정 - 이름 그대로면 중복검사 없이 통과(다른 필드만 변경)")
    void update_sameName_ok() {
        Seller seller = sellerWithId(1L, "셀러A");
        given(sellerRepository.findById(1L)).willReturn(Optional.of(seller));

        sellerService.update(1L, new SellerUpdateRequest("셀러A", 0.20, null, null));

        assertThat(seller.getCommissionRate()).isEqualTo(0.20);
        verify(sellerRepository, never()).existsByName(any());
    }

    @Test
    @DisplayName("정지 - ACTIVE → SUSPENDED")
    void suspend_success() {
        Seller seller = sellerWithId(1L, "셀러A");
        given(sellerRepository.findById(1L)).willReturn(Optional.of(seller));

        SellerResponse response = sellerService.suspend(1L);

        assertThat(response.status()).isEqualTo(SellerStatus.SUSPENDED);
    }

    @Test
    @DisplayName("정지 실패 - 이미 정지면 409")
    void suspend_alreadySuspended() {
        Seller seller = sellerWithId(1L, "셀러A");
        seller.suspend();   // 이미 정지
        given(sellerRepository.findById(1L)).willReturn(Optional.of(seller));

        assertThatThrownBy(() -> sellerService.suspend(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 정지");
    }

    @Test
    @DisplayName("재개 - SUSPENDED → ACTIVE")
    void activate_success() {
        Seller seller = sellerWithId(1L, "셀러A");
        seller.suspend();
        given(sellerRepository.findById(1L)).willReturn(Optional.of(seller));

        SellerResponse response = sellerService.activate(1L);

        assertThat(response.status()).isEqualTo(SellerStatus.ACTIVE);
    }

    @Test
    @DisplayName("재개 실패 - 이미 활성이면 409")
    void activate_alreadyActive() {
        Seller seller = sellerWithId(1L, "셀러A");   // 생성 시 ACTIVE
        given(sellerRepository.findById(1L)).willReturn(Optional.of(seller));

        assertThatThrownBy(() -> sellerService.activate(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 활성");
    }
}
