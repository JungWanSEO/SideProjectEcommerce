# commerce-api 백엔드 컨테이너 이미지 (멀티스테이지).
# 빌드(JDK로 jar 생성) ↔ 실행(작은 JRE에 jar만) 단계를 분리해 최종 이미지를 가볍게 한다.
# 빌드 컨텍스트 = backend/ (이 파일이 있는 곳). 배포 플랫폼(Render/Koyeb/Fly 등)이 이 Dockerfile로 빌드한다.

# ---- 빌드 단계: Gradle로 실행 가능한 bootJar 생성 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# 1) 래퍼·빌드스크립트 먼저 복사 — 소스만 바뀔 땐 이 레이어(의존성)가 캐시돼 재빌드가 빠르다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew
# 2) 소스 복사 후 jar 빌드. 테스트는 CI에서 돌리므로 이미지 빌드에선 제외(-x test).
COPY src ./src
RUN ./gradlew clean bootJar -x test --no-daemon

# ---- 실행 단계: 작은 JRE 이미지에 jar만 ----
FROM eclipse-temurin:21-jre AS run
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# 플랫폼이 PORT 환경변수를 주입하면 server.port=${PORT:8080}가 그 포트로 바인딩한다(EXPOSE는 문서용).
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
