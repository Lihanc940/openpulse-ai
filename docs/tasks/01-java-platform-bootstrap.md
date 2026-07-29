# Java 第一步：搭建 Platform 最小骨架

## 任务定位

- 负责人：Java 负责人
- 所在分支：`feat/platform-bootstrap`
- 所属阶段：P1 本地最小闭环
- 本任务只搭建 Java 后端骨架，不接数据库、GitHub、C++、AI 或前端

## 为什么先做这一步

我们先证明三个最基础的事实：

1. 项目可以在不依赖 IDE 的情况下构建。
2. Spring Boot 应用可以启动。
3. 一个 HTTP 接口有自动化测试，也可以被真实访问。

如果这三个基础能力不稳定，继续加入数据库和 C++ 调用只会让问题混在一起。

## 本步骤需要理解的名词

新对话在使用这些概念前，应先用简单语言解释：

- **JDK**：编译和运行 Java 的工具包。
- **Maven**：管理依赖、编译、测试和打包的构建工具。
- **`pom.xml`**：Maven 项目的配置清单。
- **依赖（Dependency）**：项目复用的外部代码库。
- **Spring Boot**：帮助快速建立可运行 Java 应用的框架。
- **Controller**：接收 HTTP 请求并返回响应的入口。
- **DTO**：专门用于传输数据的对象，不承载复杂业务逻辑。
- **单元/接口测试**：用程序自动验证行为是否符合预期。
- **Maven Wrapper**：仓库自带的 Maven 启动脚本，让不同电脑使用一致的构建方式。
- **API 版本**：路径中的 `/v1`，用于未来升级接口时保留兼容空间。

## 固定的项目参数

```text
Group:       io.github.lihanc940
Artifact:    openpulse-platform
Package:     io.github.lihanc940.openpulse
Java:        21
Build tool:  Maven
Packaging:   jar
```

Spring Boot 使用支持 Java 21 的稳定版本。开始生成项目前，应查阅官方版本信息并在 `pom.xml` 中固定具体版本，不使用 `LATEST` 或动态版本。

## 最小依赖

只加入：

- `spring-boot-starter-web`
- `spring-boot-starter-test`，仅用于测试

本步骤不加入 Lombok。先学习普通 Java 代码，避免把生成行为藏在注解后面。

## 目标目录

完成后至少包含：

```text
openpulse-platform/
  .mvn/
    wrapper/
  mvnw
  mvnw.cmd
  pom.xml
  src/
    main/
      java/
        io/github/lihanc940/openpulse/
          OpenPulseApplication.java
          system/
            api/
              HealthController.java
              HealthResponse.java
      resources/
        application.yml
    test/
      java/
        io/github/lihanc940/openpulse/
          system/
            api/
              HealthControllerTest.java
```

放入真实文件后，删除 `openpulse-platform/.gitkeep`。

## 接口要求

实现：

```text
GET /api/v1/health
```

返回 HTTP `200`，JSON 格式固定为：

```json
{
  "status": "UP",
  "service": "openpulse-platform",
  "version": "0.1.0-SNAPSHOT"
}
```

`HealthResponse` 推荐使用 Java `record`。新对话应先解释 `record` 与普通 class 的区别。

## 配置要求

`application.yml` 至少包含：

```yaml
spring:
  application:
    name: openpulse-platform

server:
  port: ${SERVER_PORT:8080}
```

`${SERVER_PORT:8080}` 表示：如果环境变量提供 `SERVER_PORT` 就使用它，否则使用 `8080`。这是最早的一项可部署性准备。

## 自动化测试

`HealthControllerTest` 至少验证：

- HTTP 状态是 `200`
- `Content-Type` 是 JSON
- `status` 等于 `UP`
- `service` 等于 `openpulse-platform`
- `version` 等于 `0.1.0-SNAPSHOT`

测试不能依赖数据库、网络或当前时间。

## 推荐实施顺序

### 1. 确认分支和环境

```powershell
git status
git branch --show-current
java -version
mvn -version
```

当前分支必须是：

```text
feat/platform-bootstrap
```

### 2. 建立 Maven/Spring Boot 项目

先解释 `pom.xml` 的 parent、groupId、artifactId、version 和 dependencies，再创建文件。

### 3. 建立应用入口

创建 `OpenPulseApplication.java`，解释：

- `main` 方法
- `@SpringBootApplication`
- Spring Boot 如何启动内置 Web 服务器

### 4. 建立健康检查接口

创建 `HealthResponse` 和 `HealthController`，解释：

- HTTP GET
- 路径映射
- JSON 序列化
- 为什么接口对象和内部业务对象要分开

### 5. 编写测试

先写清预期，再运行测试：

```powershell
.\mvnw.cmd test
```

### 6. 打包

```powershell
.\mvnw.cmd -DskipTests package
```

应在 `openpulse-platform/target/` 生成可运行 JAR。`target/` 已被 `.gitignore` 排除。

### 7. 实际启动和访问

启动：

```powershell
.\mvnw.cmd spring-boot:run
```

另开 PowerShell 验证：

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
```

验证完成后正常停止应用，不留下后台 Java 进程。

### 8. 检查变化并创建本地提交

```powershell
git status
git diff
```

确认没有构建产物和个人配置后：

```powershell
git add openpulse-platform
git diff --cached
git commit -m "feat(platform): bootstrap Spring Boot service"
```

本步骤先不 push，不创建 Pull Request。回到项目主线对话完成复核后再发布。

## 明确不做

本步骤禁止提前加入：

- MySQL、Redis、数据库表
- GitHub API 和仓库下载
- C++ 进程调用
- AI API
- JWT、登录和权限
- Dockerfile、Docker Compose
- Vue 前端
- 全局异常体系
- 复杂的通用工具类
- 微服务组件

这些不是永远不做，而是没有进入当前验收范围。

## 完成标准

以下条件必须全部满足：

- `.\mvnw.cmd test` 通过
- `.\mvnw.cmd -DskipTests package` 通过
- 应用可以启动
- `/api/v1/health` 返回约定 JSON
- `target/` 没有进入 Git
- 没有密码、Token 或本地绝对路径
- 已创建一个范围单一的本地 commit
- 尚未 push，等待主线对话复核

## 回主线对话时提交的信息

```text
分支：
commit：
测试命令与结果：
打包命令与结果：
健康检查返回：
新增文件：
我理解的新名词：
遇到的问题：
```

## 新对话启动提示词

在项目工作区创建新对话后，发送：

```text
请先阅读 README.md、docs/04-architecture.md、
docs/07-development-environment.md 和
docs/tasks/01-java-platform-bootstrap.md。

我是 Java 初学者，请严格完成“Java 第一步：搭建 Platform 最小骨架”。
使用当前 feat/platform-bootstrap 分支。
每使用一个新名词前先用简单语言解释，再执行对应操作并验证。
一次只推进一个可验证的小步骤，等我理解后再继续。
不要加入任务单明确禁止的功能。
完成实现、测试、打包、接口验证和本地 commit，但不要 push 或创建 PR。
最后按任务单中的格式生成回主线对话的验收报告。
```
