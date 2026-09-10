# ==============================================================================
# Estágio 1: Build da aplicação e extração das camadas (Layered Jar)
# ==============================================================================
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# 1. Copia o pom.xml e baixa as dependências (cache)
COPY pom.xml .
RUN mvn dependency:go-offline

# 2. Copia o código fonte e compila a aplicação
COPY src ./src
RUN mvn clean package -DskipTests

# 3. Extrai o JAR em camadas (Acelera builds futuros)
# O Spring Boot layertools divide o app em: dependências, dependências snapshot e código.
WORKDIR /app/target/extracted
RUN java -Djarmode=layertools -jar /app/target/*.jar extract


# ==============================================================================
# Estágio 2: Imagem final (Runner) otimizada e segura
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
VOLUME /tmp

# Criação de um grupo e usuário sem privilégios (non-root) para segurança
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copia as camadas individualmente do estágio de build
# A ordem é importante para maximizar o cache do Docker!
COPY --from=build /app/target/extracted/dependencies/ ./
COPY --from=build /app/target/extracted/spring-boot-loader/ ./
COPY --from=build /app/target/extracted/snapshot-dependencies/ ./
COPY --from=build /app/target/extracted/application/ ./

# Expõe a porta
EXPOSE 8085

# Comando para iniciar a aplicação usando o Launcher em camadas do Spring Boot
# Note que usamos org.springframework.boot.loader.launch.JarLauncher ao invés de '-jar'
ENTRYPOINT ["java", \
            "-XX:InitialRAMPercentage=50.0", \
            "-XX:MaxRAMPercentage=80.0", \
            "-XX:+UseZGC", \
            "org.springframework.boot.loader.launch.JarLauncher"]