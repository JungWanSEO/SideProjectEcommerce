package com.commerce.api.global.security.oauth2;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.util.StringUtils;

/**
 * 소셜 로그인 {@link ClientRegistrationRepository} — <b>provider별 opt-in</b>.
 *
 * <p><b>왜 Boot 기본 대신 직접 만드나:</b> {@code spring.security.oauth2.client.*} 자동설정은 yml에 존재하는
 * 모든 registration을 검증해, 비어있는 registration이 하나라도 있으면 <b>"client id must not be empty"로 부팅을
 * 실패</b>시킨다(구글만 채우고 카카오는 비우기가 불가). 그래서 자격증명을 {@code app.oauth2.*}로 받아
 * <b>client-id가 채워진 provider만</b> 골라 Repository를 구성한다.
 *
 * <p>둘 다 비면 이 빈 자체가 생성되지 않아({@code @ConditionalOnExpression}) 소셜 로그인이 비활성 —
 * {@code SecurityConfig}가 이 빈 유무로 {@code oauth2Login} 활성 여부를 판단한다(테스트·기존 로컬 로그인 무영향).
 */
@Configuration
@ConditionalOnExpression("'${app.oauth2.google.client-id:}${app.oauth2.kakao.client-id:}'.length() > 0")
public class OAuth2ClientConfig {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${app.oauth2.google.client-id:}") String googleId,
            @Value("${app.oauth2.google.client-secret:}") String googleSecret,
            @Value("${app.oauth2.kakao.client-id:}") String kakaoId,
            @Value("${app.oauth2.kakao.client-secret:}") String kakaoSecret) {
        List<ClientRegistration> registrations = new ArrayList<>();
        if (StringUtils.hasText(googleId)) {
            registrations.add(googleRegistration(googleId, googleSecret));
        }
        if (StringUtils.hasText(kakaoId)) {
            registrations.add(kakaoRegistration(kakaoId, kakaoSecret));
        }
        // @ConditionalOnExpression이 최소 1개 보장 → 리스트는 비어있지 않다(InMemory 생성자 제약 충족).
        return new InMemoryClientRegistrationRepository(registrations);
    }

    /** 구글: Spring 내장 프리셋(CommonOAuth2Provider) — 엔드포인트·scope(openid/profile/email)·sub 자동 세팅. */
    private ClientRegistration googleRegistration(String clientId, String clientSecret) {
        return CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build();
    }

    /** 카카오: 내장 프리셋이 없어 엔드포인트를 직접 지정. secret 있으면 client_secret_post, 없으면 none. */
    private ClientRegistration kakaoRegistration(String clientId, String clientSecret) {
        ClientRegistration.Builder builder = ClientRegistration.withRegistrationId("kakao")
                .clientId(clientId)
                .clientAuthenticationMethod(StringUtils.hasText(clientSecret)
                        ? ClientAuthenticationMethod.CLIENT_SECRET_POST
                        : ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("profile_nickname", "profile_image")   // email은 비즈앱+검수 필요 → 제외(email-free)
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .tokenUri("https://kauth.kakao.com/oauth/token")
                .userInfoUri("https://kapi.kakao.com/v2/user/me")
                .userNameAttributeName("id")   // 카카오 사용자 식별자는 최상위 "id"
                .clientName("Kakao");
        if (StringUtils.hasText(clientSecret)) {
            builder.clientSecret(clientSecret);
        }
        return builder.build();
    }
}
