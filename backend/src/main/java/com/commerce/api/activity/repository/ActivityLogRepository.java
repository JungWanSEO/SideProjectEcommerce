package com.commerce.api.activity.repository;

import com.commerce.api.activity.entity.ActivityLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 행동 로그 DB 접근. (조회 기록 적재 + 검증/집계용 카운트. 추천 배치의 윈도우 집계 쿼리는 Step 2에서 추가.)
 */
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    /** 이 회원이 남긴 조회 로그 수 — 기록 동작 확인/검증용. */
    long countByMemberId(Long memberId);
}
