FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /source
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -DskipTests package
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN mkdir -p /app/.runtime && chown -R 10001:10001 /app
COPY --from=build /source/target/enterprise-agent-runtime-0.1.0-SNAPSHOT.jar /app/runtime.jar
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/runtime.jar"]
