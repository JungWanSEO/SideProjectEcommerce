package com.commerce.api.member.controller;

import com.commerce.api.audit.aspect.Auditable;
import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.security.SecurityUtil;
import com.commerce.api.member.dto.MemberResponse;
import com.commerce.api.member.dto.MemberRoleUpdateRequest;
import com.commerce.api.member.dto.MemberSearchCondition;
import com.commerce.api.member.dto.MemberSignupRequest;
import com.commerce.api.member.dto.MemberUpdateRequest;
import com.commerce.api.member.dto.MyProfileResponse;
import com.commerce.api.member.dto.PasswordChangeRequest;
import com.commerce.api.member.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 API.
 * <ul>
 *   <li>POST  /api/members            회원가입 (공개)
 *   <li>GET   /api/members/{id}       단건 조회 (로그인)
 *   <li>GET   /api/members/me         내 정보 · PUT /me · PUT /me/password (로그인)
 *   <li>GET   /api/members/admin      회원 목록·검색 (ADMIN)
 *   <li>PATCH /api/members/{id}/role  권한 변경 (ADMIN · 감사 기록)
 * </ul>
 *
 * <p>어드민 목록 경로가 {@code /admin}인 건 {@code GET /api/products/admin}과 같은 규칙(백오피스 뷰는
 * 공개 뷰와 경로를 나눈다). SecurityConfig에서 ADMIN 매처로 명시 보호한다.
 */
@Tag(name = "회원(Member)", description = "회원가입 / 조회 / 어드민 회원 관리 API")
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @Operation(summary = "회원가입", description = "이메일/비밀번호/닉네임으로 회원을 등록한다. 이메일 중복 시 409.")
    @PostMapping
    public ResponseEntity<ApiResponse<MemberResponse>> signup(
            @Valid @RequestBody MemberSignupRequest request) {
        MemberResponse response = memberService.signup(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("회원가입이 완료되었습니다.", response));
    }

    @Operation(summary = "회원 목록·검색 (ADMIN)",
            description = "회원을 가입 최신순으로 조회한다. 선택 필터: keyword(이메일·닉네임 부분일치)·role(USER/SELLER/ADMIN). "
                    + "기본 페이지 크기 20. (정렬은 가입 최신순 고정.)")
    @GetMapping("/admin")
    public ResponseEntity<ApiResponse<PageResponse<MemberResponse>>> searchMembers(
            @ParameterObject MemberSearchCondition condition,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(memberService.search(condition, pageable)));
    }

    @Operation(summary = "회원 권한 변경 (ADMIN)",
            description = "회원 권한을 USER ↔ ADMIN으로 바꾼다. 자기 자신은 변경 불가(409, 관리자 잠금 방지), "
                    + "SELLER 지정은 셀러 운영자 지정 API로(400). 없는 회원 404. 변경 이력은 감사 로그에 남는다.")
    @Auditable(action = "MEMBER_ROLE_UPDATE", targetType = "MEMBER", targetId = "#id")
    @PatchMapping("/{id}/role")
    public ResponseEntity<ApiResponse<MemberResponse>> changeRole(
            @PathVariable Long id,
            @Valid @RequestBody MemberRoleUpdateRequest request) {
        MemberResponse response = memberService.changeRole(
                SecurityUtil.getCurrentMemberId(), id, request.role());
        return ResponseEntity.ok(ApiResponse.success("권한을 변경했습니다.", response));
    }

    @Operation(summary = "회원 단건 조회", description = "회원 ID로 회원 정보를 조회한다. 없으면 404.")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MemberResponse>> getMember(@PathVariable Long id) {
        MemberResponse response = memberService.getMember(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "내 정보 조회", description = "로그인한 본인의 정보(provider·비번 보유 여부 포함)를 조회한다.")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MyProfileResponse>> getMyProfile() {
        MyProfileResponse response = memberService.getMyProfile(SecurityUtil.getCurrentMemberId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "내 정보 수정", description = "로그인한 본인의 닉네임을 수정한다.")
    @PutMapping("/me")
    public ResponseEntity<ApiResponse<MyProfileResponse>> updateMyProfile(
            @Valid @RequestBody MemberUpdateRequest request) {
        MyProfileResponse response = memberService.updateMyProfile(SecurityUtil.getCurrentMemberId(), request);
        return ResponseEntity.ok(ApiResponse.success("회원정보가 수정되었습니다.", response));
    }

    @Operation(summary = "비밀번호 변경/설정",
            description = "본인의 비밀번호를 변경한다. 비번이 있으면 현재 비번 확인 필요, 소셜 전용 계정은 현재 비번 없이 설정. 실패 시 400.")
    @PutMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changeMyPassword(
            @Valid @RequestBody PasswordChangeRequest request) {
        memberService.changeMyPassword(SecurityUtil.getCurrentMemberId(), request);
        return ResponseEntity.ok(ApiResponse.<Void>success("비밀번호가 변경되었습니다.", null));
    }
}