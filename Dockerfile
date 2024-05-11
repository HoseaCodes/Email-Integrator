FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY pom.xml .

COPY src ./src

COPY ./target/*.jar app.jar

ENTRYPOINT ["java","-jar","app.jar"]