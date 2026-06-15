package com.commerce.api.activity.repository;

import com.commerce.api.activity.entity.ActivityLog;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 행동 로그 DB 접근. (조회 기록 적재 + 검증용 카운트 + 추천 배치의 최근 윈도우 조회.)
 */
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    /** 이 회원이 남긴 조회 로그 수 — 기록 동작 확인/검증용. */
    long countByMemberId(Long memberId);

    /** 최근(since 이후) 조회 로그 — 추천 배치가 "최근 관심"만 신호로 쓰도록 기간 윈도우. */
    List<ActivityLog> findByCreatedAtAfter(LocalDateTime since);
}
