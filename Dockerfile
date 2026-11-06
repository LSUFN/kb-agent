# syntax=docker/dockerfile:1
# 后端镜像（M6）：多阶段构建。第一阶段用 Maven 打包并按 Spring Boot 分层解压，第二阶段只带 JRE 运行。
# 构建：docker compose build backend（或 docker build -t kb-agent-backend .）

# ---------- 构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# 可选的 Maven 仓库镜像，例如 https://maven.aliyun.com/repository/public（国内网络下载依赖更快）
ARG MAVEN_MIRROR=
COPY pom.xml ./
COPY src/ src/
# 依赖缓存在 BuildKit 的缓存目录里，改代码后重新构建不必重新下载
RUN --mount=type=cache,target=/root/.m2 \
    set -eu; \
    settings=""; \
    if [ -n "$MAVEN_MIRROR" ]; then \
      printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>*</mirrorOf><url>%s</url></mirror></mirrors></settings>' \
        "$MAVEN_MIRROR" > /tmp/settings.xml; \
      settings="-s /tmp/settings.xml"; \
    fi; \
    mvn -B -q $settings -DskipTests package; \
    cp target/kb-agent-*.jar /tmp/app.jar; \
    java -Djarmode=tools -jar /tmp/app.jar extract --layers --launcher --destination /tmp/extracted

# ---------- 运行 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
# 非 root 用户运行；上传的文件写在 /app/data（由 Compose 挂载卷）
RUN groupadd --system app && useradd --system --gid app --no-create-home app \
    && mkdir -p /app/data/storage && chown -R app:app /app/data
# 按变化频率从低到高复制，改代码只会让最后一层失效
COPY --from=build /tmp/extracted/dependencies/ ./
COPY --from=build /tmp/extracted/spring-boot-loader/ ./
COPY --from=build /tmp/extracted/snapshot-dependencies/ ./
COPY --from=build /tmp/extracted/application/ ./
USER app
ENV TZ=Asia/Shanghai \
    APP_STORAGE_ROOT=/app/data/storage \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fs http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
