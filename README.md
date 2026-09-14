# lieshou-cloud-user-services · 开源后端服务:user

> 自 lieshou-cloud-core/services/user 拆分(2026-08 细粒度)。
> **上游全部走内网 Nexus**：本仓无 submodule；`framework-parent` / `framework-*` 由
> `https://nexus.ts.huntercat.cn/repository/maven-public/` 解析（framework 版本线 0.3.1）。
> 本仓只留 Controller + 装配 + 启动，业务在 `lieshou-framework`（上游同源唯一）。

## 构建

```bash
# 前提：~/.m2/settings.xml 已 mirror 到内网 Nexus（新机器：ssh mesh-dell 'cat /srv/nexus/client-setup.sh' | bash）
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -B -ntp -DskipTests -f pom.xml package
# 只构建 user 服务（本仓只有这一个模块，-pl 只是显式化）：
mvn -B -ntp -DskipTests -f pom.xml -pl services/user package
# 全量门（spotless + 单测 + jacoco；PG 集成测试需 docker 或外部 SPRING_DATASOURCE_URL）
mvn -B -ntp verify
```

镜像：`docker build -f services/user/Dockerfile .`（context = 仓根；builder 阶段导入 `services/.ci/caddy-root.crt`）。

## 制品

tag `vX.Y.Z` → GitLab CI `deploy` stage 发 `cn.huntercat:lieshoucloud-user:<version>` 到 Nexus `maven-releases`
（tag 必须与 pom 版本一致；凭证在项目 CI 变量 `NEXUS_DEPLOY_USER/PASS`）。
