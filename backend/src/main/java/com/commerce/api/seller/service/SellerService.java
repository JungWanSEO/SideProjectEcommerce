package com.commerce.api.seller.service;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.seller.dto.SellerCreateRequest;
import com.commerce.api.seller.dto.SellerResponse;
import com.commerce.api.seller.dto.SellerUpdateRequest;
import com.commerce.api.seller.entity.Seller;
import com.commerce.api.seller.repository.SellerRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 비즈니스 로직 — 조회 / 등록 / 수정 / 상태 전이(정지·재개).
 *
 * <p>전부 ADMIN 운영 업무다(접근 제어는 SecurityConfig). 변경 메서드는 영속 엔티티를
 * dirty-checking으로 flush하므로 별도 save 호출이 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerService {

    private final SellerRepository sellerRepository;

    /** 전체 셀러 목록. */
    public List<SellerResponse> getSellers() {
        return sellerRepository.findAll().stream().map(SellerResponse::from).toList();
    }

    /** 단건 조회(없으면 404). */
    public SellerResponse getSeller(Long id) {
        return SellerResponse.from(findById(id));
    }

    /** 셀러 등록. 이름 중복이면 409. */
    @Transactional
    public SellerResponse create(SellerCreateRequest request) {
        if (sellerRepository.existsByName(request.name())) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 존재하는 셀러입니다.");
        }
        Seller seller = Seller.create(
                request.name(), request.commissionRate(), request.payoutAccount(), request.businessNumber());
        return SellerResponse.from(sellerRepository.save(seller));
    }

    /** 기본 정보 수정. 다른 셀러가 이미 쓰는 이름으로는 못 바꾼다(409). */
    @Transactional
    public SellerResponse update(Long id, SellerUpdateRequest request) {
        Seller seller = findById(id);
        if (!seller.getName().equals(request.name()) && sellerRepository.existsByName(request.name())) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 존재하는 셀러입니다.");
        }
        seller.update(
                request.name(), request.commissionRate(), request.payoutAccount(), request.businessNumber());
        return SellerResponse.from(seller);
    }

    /** 입점 정지. */
    @Transactional
    public SellerResponse suspend(Long id) {
        Seller seller = findById(id);
        seller.suspend();
        return SellerResponse.from(seller);
    }

    /** 입점 재개. */
    @Transactional
    public SellerResponse activate(Long id) {
        Seller seller = findById(id);
        seller.activate();
        return SellerResponse.from(seller);
    }

    private Seller findById(Long id) {
        return sellerRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "셀러를 찾을 수 없습니다."));
    }
}
