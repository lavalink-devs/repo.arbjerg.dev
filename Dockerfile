FROM eclipse-temurin:18-jre-focal

WORKDIR /opt/repo
COPY build/libs/dev.arbjerg.repo-0.0.1-all.jar repo.jar

ENTRYPOINT ["java", "-Xmx256m", "-jar", "repo.jar"]
