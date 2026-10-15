FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /app

# Resolve dependencies in their own layer so code changes don't re-download them.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q package -DskipTests && cp target/docbound-*.jar app.jar

FROM eclipse-temurin:21-jre-noble
RUN useradd --system --create-home app
WORKDIR /app
COPY --from=build /app/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
