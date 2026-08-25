FROM eclipse-temurin:21-jre

WORKDIR /app
COPY target/lookahead-content-api.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
