# lieshou-cloud-user-services · 开源后端服务:user

> 自 lieshou-cloud-core/services/user 拆分(2026-08 细粒度)。
> 聚合构建：`framework` submodule + `services/user` 同处一个 reactor 一次成包。
> 统一 parent/BOM 来自 lieshou-framework 的 `framework-parent`（0.3.1 起）。

```bash
# 在仓根执行（不要 cd services —— 旧聚合父 pom 已删除）
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -B -ntp -DskipTests -f pom.xml package
# 只构建 user 服务：
mvn -B -ntp -DskipTests -f pom.xml -pl services/user -am package
```
