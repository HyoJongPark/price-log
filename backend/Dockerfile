FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY *.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
