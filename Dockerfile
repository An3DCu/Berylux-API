# Build the jar with the project's own Gradle, then run it on a JRE as a user without root.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --quiet help
COPY config config
COPY src src
RUN ./gradlew --no-daemon --quiet bootJar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S berylux && adduser -S berylux -G berylux
USER berylux
WORKDIR /app
COPY --from=build /src/build/libs/*.jar api.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/api.jar"]
