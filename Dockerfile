FROM eclipse-temurin:17-jre

WORKDIR /app

RUN mkdir -p /data/h2

COPY build/libs/*.jar app.jar

# 기본 타임존 설정 (docker-compose에서 오버라이드 가능)
ENV TZ=Asia/Seoul
ENV JAVA_OPTS="-Duser.timezone=Asia/Seoul"

EXPOSE 8080

#ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=docker"]
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

