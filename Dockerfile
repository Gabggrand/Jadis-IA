# Etapa 1: build do projeto com Maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Etapa 2: imagem final, só com o Java Runtime e o jar gerado
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/Jadis.jar app.jar

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]