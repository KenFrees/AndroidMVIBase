# AndroidMVIBase

一个边搭建、边学习的 Android 基础框架。目标是逐步形成可供新项目复用的公共能力，并通过项目规则和 Skills 约束 AI 的实现与 Review。

## 当前进度

当前是可编译的基础骨架，不是已完成的生产框架。

| 模块 | 当前能力 | 阅读入口 |
| --- | --- | --- |
| `app` | Application、Hilt 入口、Compose 欢迎页与主题 | [app/README.md](app/README.md) |
| `common` | `AppResult` 以及成功、失败回调扩展 | [common/README.md](common/README.md) |
| `network` | Hilt 提供网络对象、Moshi 转换、`safeApiCall` | [network/README.md](network/README.md) |

当前 Gradle 依赖：`app → network → common`，同时 `app → common`。这些是模块依赖，不代表 UI 应直接请求网络。

MVI 基类、Repository 示例、列表页、统一业务错误码、登录态、离线存储均未完成。依赖已引入不等于能力已封装。

## 技术与工程约定

- Kotlin、Compose + Material3、Hilt + KSP、Retrofit + OkHttp + Moshi。
- 异步采用 Coroutines，后续以 Flow / StateFlow 组织数据与 UI 状态。
- 表现层计划采用轻量 MVI，具体 State / Event / Effect API 在对应学习步骤中确定。
- 包名前缀 `com.ttxz.base`，最低 Android API 26。
- 模块与 `app` 同级；本仓库本身就是基础框架，不再套 `core/`。
- 依赖版本集中在 [libs.versions.toml](gradle/libs.versions.toml)，构建工具版本以文件实际配置为准。

## 怎样一步步学习

每一步都按以下方式推进：

1. **开始前明确提醒“这一步需要你学习”**，说明要解决的问题、涉及模块，以及应该先读哪几个文件。
2. 解释本步必要概念，用真实代码说明输入、处理、输出。先理解当前实现，再讨论封装。
3. 完成一个小范围改动，说明为什么这样设计、有什么取舍；涉及新接口或模块拆分时先与用户对齐。
4. 同步模块 README，执行与改动匹配的验证，区分编译、单测、设备运行结果。
5. 给出一个阅读或操作练习，以及本步验收标准。到学习节点停下，让用户提问或反馈后再进入下一阶段。

用户明确要求连续执行某个范围时，按该范围推进，同时保留学习说明。纯文档整理可以完成本次约定的文档范围，不因此提前实现下一阶段能力。

### 建议顺序

| 步骤 | 学什么 | 产出与通过标准 | 当前状态 |
| --- | --- | --- | --- |
| 1 | 模块依赖、Hilt/KSP 与构建关系 | 空工程可构建，能解释三个模块职责 | 骨架已建立 |
| 2 | `AppResult`、泛型、sealed interface、结果与 UI 状态的区别 | 能解释 Success / Error / Loading 的语义 | **建议从这里开始学习** |
| 3 | Retrofit → Moshi → `safeApiCall`、协程取消与错误映射 | 修正取消异常处理，验证 HTTP/IO/取消分支 | 基础实现有待完善 |
| 4 | 网络环境配置、日志范围、服务注入 | 明确开发与正式环境的网络行为并验证 | 待开展 |
| 5 | 轻量 MVI 的状态、用户操作与一次性效果 | 小型可测试基类，讲清生命周期和事件语义 | 待设计 |
| 6 | API → Repository → ViewModel → Compose | 完整示例请求，覆盖加载、成功、失败与重试 | 待开展 |
| 7 | 复用与 AI Review | 用第二个功能检查规范是否够用，按实际代码完善 Skill | 待开展 |

这是学习路线，不是对所有后续实现的一次性授权。Room、分页、复杂导航等按真实需求再加入。

## 如何构建和使用

1. 使用 Android Studio 打开仓库，配置本机 Android SDK（`local.properties` 不提交），然后 Sync。
2. 选择 `app` 在 API 26 或更高版本设备上运行；当前预期显示欢迎文字。
3. 命令行验证使用仓库 Wrapper：

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```

命令行需要可用的 Java 启动环境；Gradle daemon JDK 由 [gradle-daemon-jvm.properties](gradle/gradle-daemon-jvm.properties) 指定，不要把 Java/Kotlin 的字节码目标版本与运行 Gradle 的 JDK 版本混为一谈。

2026-09-14 已完成一次 Debug 构建验证：KSP 从旧版本调整到 `2.3.12` 后，`:app:assembleDebug` 成功。该记录不是以后修改后的验证证明，也不代表设备、接口与发布场景已验证。

作为新项目底座使用前，先完成网络已知问题和完整示例验证。当前可在本仓库学习、逐步扩展；尚未发布 Maven 组件，也没有“一键创建业务工程”的工具。

## 人与 AI 如何参与

- [AGENTS.md](AGENTS.md)：精简项目边界和 Skill 路由。
- **编写和维护框架**：阅读 [框架开发规范](docs/framework-development.md) 与 [维护者 Skill](skills/android-mvi-base-maintainer/SKILL.md)。约束模块设计、封装、包名/类名、公共 API、注释、学习与框架 Review。
- **在业务项目使用框架**：阅读 [使用者 Skill](skills/android-mvi-base/SKILL.md) 与模块 README。指导依赖接入、调用和使用方式 Review，不要求接入方采用本仓库的内部包名与教学节奏。
- 当前将 Skill 放在项目 `skills/` 下，由 `AGENTS.md` 明确要求读取。即使工具没有自动发现该目录，也可让 AI 直接读取上述文件；不能把“文件存在”当成“已自动加载”。
- 维护指令：“使用 android-mvi-base-maintainer，先带我学习 common 的 AppResult，只做这一步。”
- 框架 Review：“使用 android-mvi-base-maintainer 审查 network 的封装，先列问题，不改代码。”
- 接入 Review：“使用 android-mvi-base 检查业务 Repository 对 safeApiCall 的调用是否符合契约。”
- 使用者 Skill 目前与源码一起提供，未打包发布；复制到其他位置时需保留或重新指定对应框架版本的模块文档路径。

## 文档维护与当前限制

每个正式模块都维护 README，包含职责、现有能力、关键文件、使用示例、依赖边界、学习顺序、验证方式和未完成项。新增模块时一起创建，公共 API 或行为改变时一起更新；Skills 引用 README，不复制整套 API 文档。

当前优先关注：

- `safeApiCall` 的通用 Exception 捕获会吞掉协程取消，见 [network 说明](network/README.md)。
- 网络日志始终为 BODY，尚未区分 Debug/Release；Base URL 固定为演示服务。
- 尚无业务响应码、会话过期、重试和离线策略。
- 规则文档和 Skill 用于引导与检查，不等于编译器层面的架构约束，也不能替代测试与人工 Review。
