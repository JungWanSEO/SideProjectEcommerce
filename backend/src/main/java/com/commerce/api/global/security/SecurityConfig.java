package com.commerce.api.global.security;

import java.util.List;
import lombok.RequiredArgsConstructor;
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
     * CORS 설정: 프론트엔드 dev 서버에서의 브라우저 호출을 허용한다.
     * (CORS는 브라우저 보호 정책 — 다른 origin의 JS가 우리 API를 부를 때 서버가 명시 허용해야 함)
     * TODO(운영): 허용 origin을 환경변수/프로파일로 분리(배포 도메인).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3000"));   // Next.js dev 서버
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));                       // Authorization·Content-Type 등
        config.setAllowCredentials(true);                             // 추후 쿠키 기반 인증 대비
        config.setMaxAge(3600L);                                      // preflight 결과 캐시(초)

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 프론트엔드(다른 origin)에서의 브라우저 호출 허용. preflight(OPTIONS)는 Spring이 자동 처리.
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
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
                        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/categories/**", "/api/brands/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs/**", "/actuator/health").permitAll()
                        // 관리자
                        .requestMatchers(HttpMethod.POST, "/api/products").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/categories", "/api/brands").hasRole("ADMIN")
                        // 브랜드 변경(셀러 귀속 등)은 ADMIN (GET /api/brands/** 는 위에서 공개)
                        .requestMatchers(HttpMethod.PUT, "/api/brands/**").hasRole("ADMIN")
                        // 셀러 관리·정산·대사는 운영 업무 → 전 경로 ADMIN 전용
                        .requestMatchers("/api/sellers/**").hasRole("ADMIN")
                        .requestMatchers("/api/settlements/**").hasRole("ADMIN")
                        .requestMatchers("/api/reconciliations/**").hasRole("ADMIN")
                        // 그 외는 인증 필요
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
