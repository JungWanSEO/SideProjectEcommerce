package com.commerce.api.recommendation.repository;

import com.commerce.api.recommendation.entity.Recommendation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 추천 결과 DB 접근.
 */
public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    /** 회원의 추천 목록 — 점수 내림차순(동점은 productId 오름차순으로 안정 정렬). */
    List<Recommendation> findByMemberIdOrderByScoreDescProductIdAsc(Long memberId);

    /**
     * 배치가 회원별로 다시 계산하기 전에 기존 추천을 지운다(지우고-다시 넣기).
     *
     * <p><b>벌크 DELETE로 명시</b>한 이유: 파생 삭제(derived {@code deleteBy…})는 엔티티를 조회해
     * 영속성 컨텍스트에 remove를 큐잉만 하고, 실제 DELETE는 flush 시점으로 미뤄진다. 그런데 Hibernate
     * 액션 큐는 같은 트랜잭션에서 <b>INSERT를 DELETE보다 먼저</b> 실행하므로, 직후의 {@code saveAll}이
     * 기존 (member,product) 행이 남아 있는 상태로 INSERT돼 유니크 제약(uk_recommendation_member_product)에
     * 걸린다(빈 테이블 첫 실행은 통과하나 재실행 시 중복키). 벌크 쿼리는 호출 즉시 DELETE를 실행해 이 순서 함정을 없앤다.
     * {@code clearAutomatically}로 1차 캐시의 낡은 엔티티도 정리한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Recommendation r where r.memberId = :memberId")
    void deleteByMemberId(@Param("memberId") Long memberId);
}
