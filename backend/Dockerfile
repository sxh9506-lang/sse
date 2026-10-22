# Build the executable Spring Boot jar.
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# Run as a non-root user in a small Java 17 runtime image.
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN useradd --system --uid 10001 --home-dir /app appuser
COPY --from=build /workspace/target/sse-backend-1.0.0.jar /app/app.jar
USER 10001
EXPOSE 8080
ENV SERVER_PORT=8080 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]
