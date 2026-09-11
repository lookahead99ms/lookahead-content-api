FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/main/ src/main/
COPY src/test/ src/test/
COPY tools/container/Healthcheck.java tools/container/Healthcheck.java
RUN ./mvnw --batch-mode --no-transfer-progress verify \
    && javac --release 21 -d /workspace/healthcheck tools/container/Healthcheck.java

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN groupadd --gid 10001 lookahead \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin lookahead

WORKDIR /app
COPY --from=build /workspace/target/lookahead-content-api.jar app.jar
COPY --from=build /workspace/healthcheck/ healthcheck/

USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=3 \
    CMD ["java", "-cp", "/app/healthcheck", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
