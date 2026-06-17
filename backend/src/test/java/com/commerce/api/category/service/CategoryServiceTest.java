package com.commerce.api.category.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.category.dto.CategoryCreateRequest;
import com.commerce.api.category.dto.CategoryResponse;
import com.commerce.api.category.dto.CategoryUpdateRequest;
import com.commerce.api.category.entity.Category;
import com.commerce.api.category.repository.CategoryRepository;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.product.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * CategoryService 단위 테스트 (Mockito).
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ProductRepository productRepository;
    @InjectMocks
    private CategoryService categoryService;

    private Category categoryWithId(Long id, String name) {
        Category c = Category.create(name);
        ReflectionTestUtils.setField(c, "id", id);   // DB가 채울 id를 테스트에서 강제 주입
        return c;
    }

    private Category childCategory(Long id, String name, Long parentId) {
        Category c = Category.create(name, parentId);
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    @Test
    @DisplayName("카테고리 등록 성공")
    void create_success() {
        given(categoryRepository.existsByName("상의")).willReturn(false);
        given(categoryRepository.save(any(Category.class))).willReturn(categoryWithId(1L, "상의"));

        CategoryResponse response = categoryService.create(new CategoryCreateRequest("상의"));

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("상의");
    }

    @Test
    @DisplayName("카테고리 등록 실패 - 이름 중복이면 409, 저장하지 않음")
    void create_duplicate() {
        given(categoryRepository.existsByName("상의")).willReturn(true);

        assertThatThrownBy(() -> categoryService.create(new CategoryCreateRequest("상의")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 존재");
        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("카테고리 목록 조회 - DTO로 매핑(parentId 포함)")
    void getCategories_maps() {
        given(categoryRepository.findAll())
                .willReturn(List.of(categoryWithId(1L, "상의"), childCategory(2L, "티셔츠", 1L)));

        List<CategoryResponse> result = categoryService.getCategories();

        assertThat(result).extracting(CategoryResponse::name).containsExactly("상의", "티셔츠");
        assertThat(result).extracting(CategoryResponse::parentId).containsExactly(null, 1L);
    }

    @Test
    @DisplayName("자식 카테고리 등록 성공 - 부모가 최상위면 저장")
    void create_child_success() {
        given(categoryRepository.existsByName("티셔츠")).willReturn(false);
        given(categoryRepository.findById(1L)).willReturn(Optional.of(categoryWithId(1L, "상의")));
        given(categoryRepository.save(any(Category.class))).willReturn(childCategory(2L, "티셔츠", 1L));

        CategoryResponse response = categoryService.create(new CategoryCreateRequest("티셔츠", 1L));

        assertThat(response.name()).isEqualTo("티셔츠");
        assertThat(response.parentId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("자식 카테고리 등록 실패 - 부모가 없으면 400")
    void create_child_invalidParent() {
        given(categoryRepository.existsByName("티셔츠")).willReturn(false);
        given(categoryRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.create(new CategoryCreateRequest("티셔츠", 99L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("존재하지 않는 상위 카테고리");
        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("자식 카테고리 등록 실패 - 부모가 이미 자식이면(3단계) 400")
    void create_child_threeLevel() {
        given(categoryRepository.existsByName("반팔")).willReturn(false);
        given(categoryRepository.findById(2L)).willReturn(Optional.of(childCategory(2L, "티셔츠", 1L)));

        assertThatThrownBy(() -> categoryService.create(new CategoryCreateRequest("반팔", 2L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2단계");
        verify(categoryRepository, never()).save(any());
    }

    // ----- 수정(update) -----

    @Test
    @DisplayName("카테고리 수정 성공 - 이름 변경(부모 없음)")
    void update_success() {
        Category category = categoryWithId(1L, "상의");
        given(categoryRepository.findById(1L)).willReturn(Optional.of(category));
        given(categoryRepository.existsByNameAndIdNot("아우터", 1L)).willReturn(false);

        CategoryResponse response = categoryService.update(1L, new CategoryUpdateRequest("아우터"));

        assertThat(response.name()).isEqualTo("아우터");
        assertThat(response.parentId()).isNull();
        assertThat(category.getName()).isEqualTo("아우터");   // 영속 엔티티가 갱신됨
    }

    @Test
    @DisplayName("카테고리 수정 실패 - 없으면 404")
    void update_notFound() {
        given(categoryRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.update(99L, new CategoryUpdateRequest("아우터")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("찾을 수 없습니다");
    }

    @Test
    @DisplayName("카테고리 수정 실패 - 다른 카테고리와 이름 중복이면 409")
    void update_duplicateName() {
        given(categoryRepository.findById(1L)).willReturn(Optional.of(categoryWithId(1L, "상의")));
        given(categoryRepository.existsByNameAndIdNot("하의", 1L)).willReturn(true);

        assertThatThrownBy(() -> categoryService.update(1L, new CategoryUpdateRequest("하의")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 존재");
    }

    @Test
    @DisplayName("카테고리 재배치 성공 - 최상위 부모 밑으로 이동")
    void update_reParent_success() {
        given(categoryRepository.findById(2L)).willReturn(Optional.of(categoryWithId(2L, "후드")));
        given(categoryRepository.existsByNameAndIdNot("후드", 2L)).willReturn(false);
        given(categoryRepository.findById(1L)).willReturn(Optional.of(categoryWithId(1L, "상의")));
        given(categoryRepository.existsByParentId(2L)).willReturn(false);

        CategoryResponse response = categoryService.update(2L, new CategoryUpdateRequest("후드", 1L));

        assertThat(response.parentId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("카테고리 재배치 실패 - 자기 자신을 부모로 지정하면 400")
    void update_reParent_self() {
        given(categoryRepository.findById(1L)).willReturn(Optional.of(categoryWithId(1L, "상의")));
        given(categoryRepository.existsByNameAndIdNot("상의", 1L)).willReturn(false);

        assertThatThrownBy(() -> categoryService.update(1L, new CategoryUpdateRequest("상의", 1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("자기 자신");
    }

    @Test
    @DisplayName("카테고리 재배치 실패 - 없는 부모면 400")
    void update_reParent_invalidParent() {
        given(categoryRepository.findById(2L)).willReturn(Optional.of(categoryWithId(2L, "후드")));
        given(categoryRepository.existsByNameAndIdNot("후드", 2L)).willReturn(false);
        given(categoryRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.update(2L, new CategoryUpdateRequest("후드", 99L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("존재하지 않는 상위 카테고리");
    }

    @Test
    @DisplayName("카테고리 재배치 실패 - 부모가 이미 자식이면(3단계) 400")
    void update_reParent_threeLevel() {
        given(categoryRepository.findById(3L)).willReturn(Optional.of(categoryWithId(3L, "반팔")));
        given(categoryRepository.existsByNameAndIdNot("반팔", 3L)).willReturn(false);
        given(categoryRepository.findById(2L)).willReturn(Optional.of(childCategory(2L, "티셔츠", 1L)));

        assertThatThrownBy(() -> categoryService.update(3L, new CategoryUpdateRequest("반팔", 2L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2단계");
    }

    @Test
    @DisplayName("카테고리 재배치 실패 - 자식을 가진 카테고리는 하위로 못 옮김 400")
    void update_reParent_hasChildren() {
        given(categoryRepository.findById(1L)).willReturn(Optional.of(categoryWithId(1L, "상의")));
        given(categoryRepository.existsByNameAndIdNot("상의", 1L)).willReturn(false);
        given(categoryRepository.findById(5L)).willReturn(Optional.of(categoryWithId(5L, "하의")));
        given(categoryRepository.existsByParentId(1L)).willReturn(true);

        assertThatThrownBy(() -> categoryService.update(1L, new CategoryUpdateRequest("상의", 5L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("자식 카테고리가 있어");
    }

    // ----- 삭제(delete) -----

    @Test
    @DisplayName("카테고리 삭제 성공 - 자식·참조 상품 없으면 삭제")
    void delete_success() {
        Category category = categoryWithId(1L, "상의");
        given(categoryRepository.findById(1L)).willReturn(Optional.of(category));
        given(categoryRepository.existsByParentId(1L)).willReturn(false);
        given(productRepository.existsByCategoryId(1L)).willReturn(false);

        categoryService.delete(1L);

        verify(categoryRepository).delete(category);
    }

    @Test
    @DisplayName("카테고리 삭제 실패 - 없으면 404")
    void delete_notFound() {
        given(categoryRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.delete(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("찾을 수 없습니다");
        verify(categoryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("카테고리 삭제 실패 - 자식 카테고리가 있으면 409")
    void delete_hasChildren() {
        given(categoryRepository.findById(1L)).willReturn(Optional.of(categoryWithId(1L, "상의")));
        given(categoryRepository.existsByParentId(1L)).willReturn(true);

        assertThatThrownBy(() -> categoryService.delete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("자식 카테고리가 있어");
        verify(categoryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("카테고리 삭제 실패 - 참조하는 상품이 있으면 409")
    void delete_referencedByProduct() {
        given(categoryRepository.findById(1L)).willReturn(Optional.of(categoryWithId(1L, "상의")));
        given(categoryRepository.existsByParentId(1L)).willReturn(false);
        given(productRepository.existsByCategoryId(1L)).willReturn(true);

        assertThatThrownBy(() -> categoryService.delete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("사용하는 상품이 있어");
        verify(categoryRepository, never()).delete(any());
    }
}
