package com.commerce.api.notification.controller;

import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.security.SecurityUtil;
import com.commerce.api.notification.dto.NotificationResponse;
import com.commerce.api.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 API(#6 P1) — 인앱 인박스. 모두 로그인 사용자(구매자) 본인 것으로 스코핑된다
 * (SecurityConfig의 anyRequest().authenticated()가 커버, 서비스가 recipient=본인 강제).
 */
@Tag(name = "알림(Notification)", description = "인앱 알림 인박스 — 목록·안읽음 카운트·읽음 처리 (#6)")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "내 알림 목록",
            description = "로그인 사용자의 알림을 최신순으로 조회한다. unreadOnly=true면 안읽음만.")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<NotificationResponse>>> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        PageResponse<NotificationResponse> response = notificationService.getMyNotifications(
                SecurityUtil.getCurrentMemberId(), unreadOnly, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "안읽음 개수", description = "헤더 벨 뱃지용 — 안읽음 알림 수.")
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Long>> unreadCount() {
        long count = notificationService.unreadCount(SecurityUtil.getCurrentMemberId());
        return ResponseEntity.ok(ApiResponse.success(count));
    }

    @Operation(summary = "알림 읽음 처리", description = "본인 알림만 가능. 없거나 남의 것이면 404.")
    @PatchMapping("/{id}/read")
    public ResponseEntity<ApiResponse<Void>> read(@PathVariable Long id) {
        notificationService.markRead(SecurityUtil.getCurrentMemberId(), id);
        return ResponseEntity.ok(ApiResponse.success("읽음 처리되었습니다.", null));
    }

    @Operation(summary = "전체 읽음 처리", description = "안읽음 알림을 모두 읽음으로. 처리 건수 반환.")
    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<Integer>> readAll() {
        int marked = notificationService.markAllRead(SecurityUtil.getCurrentMemberId());
        return ResponseEntity.ok(ApiResponse.success("모두 읽음 처리되었습니다.", marked));
    }
}
