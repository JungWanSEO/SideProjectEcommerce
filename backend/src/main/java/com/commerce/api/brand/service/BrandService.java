package com.commerce.api.brand.service;

import com.commerce.api.brand.dto.BrandCreateRequest;
import com.commerce.api.brand.dto.BrandResponse;
import com.commerce.api.brand.dto.BrandUpdateRequest;
import com.commerce.api.brand.entity.Brand;
import com.commerce.api.brand.repository.BrandRepository;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.seller.repository.SellerRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 브랜드 비즈니스 로직 — 목록 조회 / 등록 / 수정 / 삭제 / 셀러 귀속.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BrandService {

    private final BrandRepository brandRepository;
    // 셀러 존재 검증용(ID 참조 무결성). ProductService가 brandRepository로 참조검증하는 것과 같은 패턴.
    private final SellerRepository sellerRepository;
    // 삭제 시 "이 브랜드를 쓰는 상품이 있는가" 참조 무결성 검증용.
    private final ProductRepository productRepository;

    /** 전체 브랜드 목록. */
    public List<BrandResponse> getBrands() {
        return brandRepository.findAll().stream().map(BrandResponse::from).toList();
    }

    /** 브랜드 등록(ADMIN). 이름이 이미 있으면 409. */
    @Transactional
    public BrandResponse create(BrandCreateRequest request) {
        if (brandRepository.existsByName(request.name())) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 존재하는 브랜드입니다.");
        }
        return BrandResponse.from(brandRepository.save(Brand.create(request.name())));
    }

    /**
     * 브랜드 수정(ADMIN) — 이름만 갱신. 없으면 404,
     * 이름이 다른 브랜드와 겹치면 409(자기 자신은 제외 — 이름 그대로 둬도 통과).
     */
    @Transactional
    public BrandResponse update(Long brandId, BrandUpdateRequest request) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "브랜드를 찾을 수 없습니다."));
        if (brandRepository.existsByNameAndIdNot(request.name(), brandId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 존재하는 브랜드입니다.");
        }
        brand.rename(request.name());   // 영속 엔티티 → dirty checking flush
        return BrandResponse.from(brand);
    }

    /**
     * 브랜드 삭제(ADMIN). 없으면 404. <b>캐스케이드/소프트삭제 없음</b> —
     * 상품이 참조 중이면 409로 막는다(데이터 정합 우선).
     */
    @Transactional
    public void delete(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "브랜드를 찾을 수 없습니다."));
        if (productRepository.existsByBrandId(brandId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이 브랜드를 사용하는 상품이 있어 삭제할 수 없습니다.");
        }
        brandRepository.delete(brand);
    }

    /**
     * 브랜드를 셀러에 귀속(ADMIN). sellerId가 null이면 귀속 해제.
     * 브랜드 없으면 404, null이 아닌데 그 셀러가 없으면 400.
     */
    @Transactional
    public BrandResponse assignSeller(Long brandId, Long sellerId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "브랜드를 찾을 수 없습니다."));
        if (sellerId != null && !sellerRepository.existsById(sellerId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "존재하지 않는 셀러입니다.");
        }
        brand.assignSeller(sellerId);   // 영속 엔티티 → dirty checking flush
        return BrandResponse.from(brand);
    }
}
