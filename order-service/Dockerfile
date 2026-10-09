FROM eclipse-temurin:17-jre-alpine
COPY build/libs/*.jar app.jar
EXPOSE 3000
ENTRYPOINT ["java", "-jar", "app.jar"]