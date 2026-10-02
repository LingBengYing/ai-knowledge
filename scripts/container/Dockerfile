# Isolated runtime acceptance image, not a production release.
# Build context must contain only this file and the verified application.jar.
FROM eclipse-temurin@sha256:96975602e131485862eb8cd32927face8a06d7591a5e865944b634a701d9df72
WORKDIR /app
RUN install -d -o 10001 -g 10001 -m 0700 /data
COPY --chmod=0444 application.jar /app/application.jar
ENV RAG_DATA_DIRECTORY=/data
USER 10001:10001
ENTRYPOINT ["java", "-Xmx384m", "-XX:MaxMetaspaceSize=160m", "-XX:MaxDirectMemorySize=32m", "-XX:+ExitOnOutOfMemoryError", "-XX:-CreateCoredumpOnCrash", "-Duser.home=/tmp", "-jar", "/app/application.jar"]
