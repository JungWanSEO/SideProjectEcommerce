package com.commerce.api.global.security;

import com.commerce.api.global.security.oauth2.HttpCookieOAuth2AuthorizationRequestRepository;
import com.commerce.api.global.security.oauth2.OAuth2LoginSuccessHandler;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security 설정.
 * - 무상태(STATELESS) + JWT 필터.
 * - 경로별 인가 정책(공개 / 인증 / ADMIN).
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;   // 미인증 → 401
    private final JwtAccessDeniedHandler accessDeniedHandler;             // 권한부족 → 403

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS 설정: 프론트엔드(다른 origin)에서의 브라우저 호출을 허용한다.
     * (CORS는 브라우저 보호 정책 — 다른 origin의 JS가 우리 API를 부를 때 서버가 명시 허용해야 함)
     *
     * <p>허용 origin은 {@code app.cors.allowed-origins}(콤마 구분)로 외부화한다 — 로컬 기본은
     * Next.js dev 서버, 운영은 env(예: {@code APP_CORS_ALLOWED_ORIGINS=https://xxx.vercel.app})로 덮어쓴다.
     * 쿠키 인증({@code allowCredentials=true})이라 {@code *} 와일드카드는 못 쓰고 origin을 명시해야 한다.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:3000}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));                       // Authorization·Content-Type 등
        config.setAllowCredentials(true);                             // httpOnly 쿠키 기반 인증
        config.setMaxAge(3600L);                                      // preflight 결과 캐시(초)

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http, CorsConfigurationSource corsConfigurationSource,
            ObjectProvider<ClientRegistrationRepository> clientRegistrationRepositoryProvider,
            ObjectProvider<OAuth2LoginSuccessHandler> oAuth2SuccessHandlerProvider,
            ObjectProvider<HttpCookieOAuth2AuthorizationRequestRepository> authRequestRepositoryProvider)
            throws Exception {
        http
                // 프론트엔드(다른 origin)에서의 브라우저 호출 허용. preflight(OPTIONS)는 Spring이 자동 처리.
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 미인증 → 401(EntryPoint), 권한부족 → 403(AccessDeniedHandler). 둘 다 JSON(ApiResponse).
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // 공개 (로그인/재발급/로그아웃은 인증 불필요 — 토큰이 없거나 만료된 상태에서 호출되므로)
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        // /api/auth/me 는 인증 필요 → 아래 anyRequest().authenticated()로 처리
                        .requestMatchers(HttpMethod.POST, "/api/members").permitAll()
                        // ⚠️ 순서 주의: 아래 공개 GET /api/products/** 보다 반드시 먼저 와야 한다.
                        //   매처는 위에서부터 첫 매치가 이기므로, 뒤에 두면 어드민 목록이 공개로 뚫린다.
                        .requestMatchers(HttpMethod.GET, "/api/products/admin").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/categories/**", "/api/brands/**").permitAll()
                        // 함께 산 상품(상품 통계, 개인정보 아님)은 상품 상세처럼 공개
                        .requestMatchers(HttpMethod.GET, "/api/recommendations/products/*/together").permitAll()
                        // actuator: health·prometheus만 공개(로컬 Prometheus 스크레이프용). 나머지 actuator는 인증 필요.
                        // 🔒 공개 배포에선 이 permitAll에 기대지 않는다 — 운영은 MANAGEMENT_ENDPOINTS=health 로
                        //   노출 목록 자체를 줄여 prometheus/metrics/caches를 404로 만들고(application.yml),
                        //   Caddy에서 /actuator/* 중 health 외 전부 차단한다(deploy/Caddyfile, 심층방어).
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs/**", "/actuator/health", "/actuator/prometheus").permitAll()
                        // 소셜 로그인 시작(/oauth2/authorization/**)·콜백(/login/oauth2/code/**)은 인증 전 접근 필요
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        // 장바구니는 비로그인 게스트도 사용(#7) — 소유는 서비스가 판별(로그인=memberId / 게스트=cart_token 쿠키).
                        //   JWT 필터는 permitAll이어도 토큰이 있으면 SecurityContext를 채우므로 회원 카트는 정상 스코핑된다.
                        .requestMatchers("/api/carts/**").permitAll()
                        // 관리자
                        .requestMatchers(HttpMethod.POST, "/api/products").hasRole("ADMIN")
                        // 상품 기본정보 수정(단건 PUT /api/products/{id}) — ADMIN (옵션 PUT은 아래 별도 매처)
                        .requestMatchers(HttpMethod.PUT, "/api/products/*").hasRole("ADMIN")
                        // 상품 옵션(사이즈/재고) 추가·수정·삭제는 운영 업무 → ADMIN (GET /api/products/** 는 위에서 공개)
                        .requestMatchers(HttpMethod.POST, "/api/products/*/options").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/products/*/options/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/products/*/options/**").hasRole("ADMIN")
                        // 상품 상태 변경(판매중/품절/판매중지)도 운영 업무 → ADMIN
                        .requestMatchers(HttpMethod.PATCH, "/api/products/*/status").hasRole("ADMIN")
                        // 상품 이미지(갤러리) 추가·삭제도 운영 업무 → ADMIN
                        .requestMatchers(HttpMethod.POST, "/api/products/*/images").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/products/*/images/**").hasRole("ADMIN")
                        // 어드민 주문 관리(전체 목록·배송 상태 전진)는 운영 업무 → ADMIN
                        //  (내 주문 GET /api/orders·POST 등은 아래 anyRequest().authenticated()로 본인 스코핑)
                        .requestMatchers(HttpMethod.GET, "/api/orders/admin").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/orders/*/status").hasRole("ADMIN")
                        // 배송 건(shipment) 단위 전진(#1 c안) — 셀러별 개별/플랫폼 직매입 출고는 ADMIN.
                        //   셀러 자기 출고는 별경로 PATCH /api/seller/me/shipments/*/status(SELLER). 이 ADMIN 매처를 완화 금지(IDOR).
                        .requestMatchers(HttpMethod.PATCH, "/api/orders/*/shipments/*/status").hasRole("ADMIN")
                        // 반품/교환 ADMIN 대행(#3) — 셀러 자기 처리는 /api/seller/me/returns/*/status(SELLER).
                        //   구매자 요청 POST /api/orders/*/returns·GET /api/returns/me는 아래 authenticated()가 커버(서비스가 소유권 강제).
                        .requestMatchers(HttpMethod.PATCH, "/api/orders/*/returns/*/status").hasRole("ADMIN")
                        // 어드민 회원 관리(목록·검색·권한 변경) → ADMIN
                        //  (회원가입 POST /api/members는 위에서 공개, 본인 조회·수정 /me는 아래 authenticated)
                        .requestMatchers(HttpMethod.GET, "/api/members/admin").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/members/*/role").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/categories", "/api/brands").hasRole("ADMIN")
                        // 카테고리 수정·삭제도 운영 업무 → ADMIN (GET /api/categories/** 는 위에서 공개)
                        .requestMatchers(HttpMethod.PUT, "/api/categories/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/categories/*").hasRole("ADMIN")
                        // 브랜드 변경(이름 수정·셀러 귀속)·삭제는 ADMIN (GET /api/brands/** 는 위에서 공개)
                        .requestMatchers(HttpMethod.PUT, "/api/brands/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/brands/*").hasRole("ADMIN")
                        // 셀러 관리·정산·대사는 운영 업무 → 전 경로 ADMIN 전용
                        .requestMatchers("/api/sellers/**").hasRole("ADMIN")
                        .requestMatchers("/api/settlements/**").hasRole("ADMIN")
                        .requestMatchers("/api/payouts/**").hasRole("ADMIN")
                        .requestMatchers("/api/reconciliations/**").hasRole("ADMIN")
                        // 쿠폰 발급·조회는 운영 업무 → ADMIN 전용(고객은 체크아웃에서 코드만 입력)
                        .requestMatchers("/api/coupons/**").hasRole("ADMIN")
                        // 어드민 대시보드(집계 KPI·매출 추이)는 운영 전용
                        .requestMatchers("/api/dashboard/**").hasRole("ADMIN")
                        // 운영 모니터링(캐시 적중률 등)은 운영 전용
                        .requestMatchers("/api/monitoring/**").hasRole("ADMIN")
                        // 감사 로그(어드민 변경 이력) 조회는 운영 전용
                        .requestMatchers("/api/audit-logs/**").hasRole("ADMIN")
                        // 셀러 콘솔(본인 정산 조회)은 SELLER 전용 — 자기 sellerId로만 스코핑
                        .requestMatchers("/api/seller/**").hasRole("SELLER")
                        // 추천 배치 수동 재계산은 운영 업무 → ADMIN (조회 /api/recommendations/me 는 아래 authenticated)
                        .requestMatchers(HttpMethod.POST,
                                "/api/recommendations/run", "/api/recommendations/cooccurrence/run").hasRole("ADMIN")
                        // 그 외는 인증 필요
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class);

        // 소셜 로그인: GOOGLE_CLIENT_ID/SECRET가 설정돼 ClientRegistrationRepository 빈이 있을 때만 활성.
        //   (자격증명 없으면 자동설정 backoff → 이 블록 skip → 기존 로컬 로그인만. 테스트/기존 흐름 무영향.)
        //   인가 요청은 세션 대신 쿠키에 저장(STATELESS 유지), 성공 시 우리 JWT 쿠키를 굽는 핸들러로 위임.
        if (clientRegistrationRepositoryProvider.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .authorizationEndpoint(endpoint -> endpoint
                            .authorizationRequestRepository(authRequestRepositoryProvider.getObject()))
                    .successHandler(oAuth2SuccessHandlerProvider.getObject()));
        }
        return http.build();
    }
}
