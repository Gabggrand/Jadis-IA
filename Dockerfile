# Estágio 1: Compilação (Build)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copia os arquivos de configuração e código fonte
COPY pom.xml .
COPY src ./src

# Compila o projeto e gera o arquivo .jar (ignora os testes para acelerar)
RUN mvn clean package -DskipTests

# Estágio 2: Execução (Runtime)
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copia o .jar gerado no primeiro estágio para o container final
# NOTA: Verifique se o nome do jar no seu pom.xml é exatamente "Jadis.jar"
COPY --from=build /app/target/Jadis.jar ./Jadis.jar

# Comando para rodar a sua IA do Discord
CMD ["java", "-jar", "Jadis.jar"]