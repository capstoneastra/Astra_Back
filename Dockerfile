# ASTRA 백엔드 이미지 (시연 서버용). 실행은 compose.prod.yaml 로 합니다.
#   docker compose -f compose.prod.yaml up -d --build
#
# 1단계(build): JDK 21 로 실행 jar 만들기. 테스트는 CI(GitHub Actions)가 하므로 여기서는 건너뜀
#   (테스트는 Testcontainers 라 이미지 빌드 안에서는 Docker 를 쓸 수 없음)
# 2단계(run):   JRE 21 만 있는 작은 이미지에서 jar 실행, root 가 아닌 사용자로

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 의존성 먼저 받아 두기 (소스만 바뀌면 이 층은 캐시 재사용)
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q > /dev/null

COPY src src
RUN ./gradlew bootJar --no-daemon -x test \
    && find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /workspace/app.jar \;

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system astra && useradd --system --gid astra --home /app astra
COPY --from=build --chown=astra:astra /workspace/app.jar app.jar
USER astra

EXPOSE 8080
# 컨테이너 메모리의 75% 까지 힙 사용. 시간대는 한국 (로그 시각)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Seoul"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
