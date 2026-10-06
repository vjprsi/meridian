# One image per Meridian service (domain services and platform apps), built from the Spring Boot jar:
#   ./gradlew :services:ledger-service:bootJar
#   docker build -f infra/docker/Dockerfile --build-arg JAR=services/ledger-service/build/libs/ledger-service.jar \
#                -t meridian/ledger-service:<version> .
# Layered extraction keeps dependencies in their own cached layer; runs as a non-root user on a read-only root fs.
FROM eclipse-temurin:25-jre AS extract
WORKDIR /build
ARG JAR
COPY ${JAR} app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 meridian && useradd --system --uid 10001 --gid meridian --no-create-home meridian
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
