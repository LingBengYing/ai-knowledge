FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl ffmpeg tesseract-ocr tesseract-ocr-eng tesseract-ocr-chi-sim && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 10001 knowledge && useradd -u 10001 -g 10001 -M knowledge \
    && install -d -o 10001 -g 10001 -m 0700 /data
WORKDIR /app
COPY application.jar /app/application.jar
USER 10001:10001
ENV RAG_DATA_DIRECTORY=/data
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-XX:-CreateCoredumpOnCrash", "-Duser.home=/tmp", "-jar", "/app/application.jar"]
