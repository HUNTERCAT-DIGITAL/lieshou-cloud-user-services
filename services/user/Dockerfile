# syntax=docker/dockerfile:1.7
# LieShouCloud · User Service (port 8081) multi-stage
# docker build context = 仓库根（services/ 一层即可，上游 framework 走 Nexus，本仓无 submodule）
# 依赖：maven-public（内网 Nexus · Caddy 内部 CA → builder 里 keytool 导入 services/.ci/caddy-root.crt）

FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /workspace
COPY . /workspace/

# 内部 CA 进 JVM 信任库（否则 https://nexus.ts.huntercat.cn 报 PKIX）
RUN keytool -importcert -noprompt -trustcacerts -alias caddy-root \
      -file /workspace/services/.ci/caddy-root.crt \
      -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit

# ⚠️ 不能 COPY settings 到 /root/.m2/settings.xml：下面 RUN 把 cache 挂到 /root/.m2 会把该文件**遮住**
#   （BuildKit cache mount 覆盖镜像层）→ 实测 Maven 回落到公网 central 并写负缓存：
#   "framework-parent:pom:0.3.3 was not found in repo.maven.apache.org during a previous attempt"
#   因此用 -s 显式指向仓内 settings（mirrorOf=* → 内网 Nexus maven-public）。
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -s /workspace/services/.m2-settings.xml -Dmaven.test.skip=true -f pom.xml -pl services/user clean package

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

COPY --from=builder /workspace/services/user/target/lieshoucloud-user.jar /app/app.jar

# 用户头像目录（2026-09 · compose 挂 dwjk-user-avatars volume，首次挂载继承此所有权）
RUN mkdir -p /data/avatars && chown -R app:app /data/avatars

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Dfile.encoding=UTF-8"
USER app

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -Y off -qO- http://127.0.0.1:8081/actuator/health >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
