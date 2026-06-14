package com.commerce.api.brand.service;

import com.commerce.api.brand.dto.BrandCreateRequest;
import com.commerce.api.brand.dto.BrandResponse;
import com.commerce.api.brand.entity.Brand;
import com.commerce.api.brand.repository.BrandRepository;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.seller.repository.SellerRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 브랜드 비즈니스 로직 — 목록 조회 / 등록 / 셀러 귀속.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BrandService {

    private final BrandRepository brandRepository;
    // 셀러 존재 검증용(ID 참조 무결성). ProductService가 brandRepository로 참조검증하는 것과 같은 패턴.
    private final SellerRepository sellerRepository;

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
