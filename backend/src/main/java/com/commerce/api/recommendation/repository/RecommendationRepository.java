package com.commerce.api.recommendation.repository;

import com.commerce.api.recommendation.entity.Recommendation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 추천 결과 DB 접근.
 */
public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    /** 회원의 추천 목록 — 점수 내림차순(동점은 productId 오름차순으로 안정 정렬). */
    List<Recommendation> findByMemberIdOrderByScoreDescProductIdAsc(Long memberId);

    /** 배치가 회원별로 다시 계산하기 전에 기존 추천을 지운다(지우고-다시 넣기). */
    void deleteByMemberId(Long memberId);
}
