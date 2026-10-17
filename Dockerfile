FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw -q -DskipTests package || mvn -q -DskipTests package
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/target/distributed-job-scheduler-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
