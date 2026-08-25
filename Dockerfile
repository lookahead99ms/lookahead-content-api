FROM eclipse-temurin:21-jre

WORKDIR /app
COPY target/java-interview-guide-api.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
