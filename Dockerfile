FROM ubuntu:16.04
RUN apt-get update
RUN apt-get install -y default-jdk maven
COPY . .
RUN mvn clean install -DskipTests

