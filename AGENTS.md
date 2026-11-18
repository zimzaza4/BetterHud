# AGENTS.md

## 分支约定

- 本 fork 的主分支是 **`special`**（`origin/special`），所有开发、比对与合入基线均以 `special` 为准。
- `master` 不作为本 fork 的主分支。

## 构建（本地）

构建 bukkit 插件 jar 必须设置环境变量 `CI=true`：

```bash
CI=true ./gradlew :bootstrap:bukkit:shadowJar
```

原因：paperweight 的 `remapMinecraft`（codebook）在非 CI 模式下会用自动下载的 JDK 25 运行，其内置 ASM 无法解析 Java 25 class 文件（`Unsupported class file major version 69`），导致 `:nms:v1_21_R3:paperweightUserdevSetup` 失败。设置 `CI=true` 后 paperweight 走 CI 路径可正常完成。

产物输出到 `build/libs/BetterHud-bukkit-<version>.jar`。

## 用户级 gitignore

本机 IDE/系统产生的个人文件（如 `.idea/AndroidProjectSystem.xml`）不要提交进仓库，通过**用户级 gitignore** 排除：

```bash
git config --global core.excludesFile ~/.gitignore_global
```

`~/.gitignore_global` 内容示例：

```
# IDE - IntelliJ IDEA (本机个人文件，勿提交)
.idea/AndroidProjectSystem.xml

# OS
.DS_Store
Thumbs.db
```

## 备注

- 本机网络对部分 Maven 仓库（repo.maven.apache.org 等）的 Java TLS 连接不稳定时，可加 `GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"` 并优先使用代理。
- 首次构建需联网下载依赖（paperweight dev-bundle、geyser/floodgate 等）。
