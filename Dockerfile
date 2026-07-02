# Estágio 1: Compilação com Java 21 e Maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copia as configurações e o código fonte
COPY pom.xml .
COPY src ./src

# Compila o projeto criando o JAR com as dependências embutidas
RUN mvn clean package -DskipTests

# Estágio 2: Execução com Java 21 JRE
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copia o JAR correto gerado pelo Maven do primeiro estágio
COPY --from=build /app/target/Jadis-1.0-SNAPSHOT-shaded.jar ./Jadis.jar

# Comando para rodar a sua IA do Discord
CMD ["java", "-jar", "Jadis.jar"]
