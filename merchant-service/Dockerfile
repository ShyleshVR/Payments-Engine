# Image for merchant-service. Build the jar first (mvn package), then from the repo root:
#   docker build -t payflow/merchant-service:local merchant-service
# scripts/build-images.sh does both for every service.

# 1. Split the Spring Boot jar into layers (dependencies change rarely, application code often),
#    so a rebuild after a code change only replaces the small top layer.
FROM eclipse-temurin:21-jre-alpine AS extract
WORKDIR /build
COPY target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# 2. Runtime image: JRE only, non-root user, layers in least-to-most frequently changed order.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 1001 payflow && adduser -S -u 1001 -G payflow payflow
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
# Numeric, so Kubernetes can verify runAsNonRoot.
USER 1001
# Size the heap from the container's memory limit; restart (via Kubernetes) rather than limp on after OOM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8084
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
