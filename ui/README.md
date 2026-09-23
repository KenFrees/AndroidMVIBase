# ui：公共 Compose 组件

`ui` 是与 `app`、`common`、`network` 同级的 Android Library，命名空间为 `com.ttxz.base.ui`，minSdk 26。它提供可复用的 Compose 界面组件，不持有业务或网络状态，也不依赖 `app`。`common` 保留不依赖 Compose 的公共类型。

## 查找组件

当前已实现 `LoadingDialog` 和 `ToastHost`。从 [组件目录](components/README.md) 按名称或用途查找。目录只列已实现组件，并指向各自的用法说明和源码；新增公共组件时同步登记，不把所有组件说明堆在本页。

## 接入与依赖

同一 Gradle 工程中的调用方加入 `implementation(project(":ui"))`，并按目标组件的说明调用。例如 `LoadingDialog(visible = isLoading, message = "正在加载")`，或在 `Scaffold` 中放置 `ToastHost` 并调用 `ToastHostState.showSuccess`；实际文案应使用资源，状态约束见各自的 [组件说明](components/README.md)。当前模块通过 [build.gradle.kts](build.gradle.kts) 使用项目已有的 Compose BOM、UI、Animation、Material 3 和 Coroutines 依赖，没有额外的组件库。

## 验证与限制

学习时先按用途在目录中定位组件，再看用法说明、源码和 app 演示；练习见各组件说明。组件及宿主编译可执行 `:ui:assembleDebug :app:assembleDebug`；外观与交互仍需设备验证。组件目录中的示例会标明真实演示与拟议用法，不能把依赖已加入等同于能力已实现。
