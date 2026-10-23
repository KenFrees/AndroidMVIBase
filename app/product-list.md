# DummyJSON 商品列表示例

从首页进入“商品列表示例”，展示商品缩略图、名称、价格和 ID。默认每批 10 条，下拉从第一页刷新，接近底部自动请求下一批；“慢速演示”开关使用服务端 delay=1500，便于观察加载效果。返回 [app README](README.md)。

## 真实调用链

| 位置 | 职责与源码 |
| --- | --- |
| API 与 DTO | [ProductApi.kt](src/main/java/com/ttxz/base/data/remote/ProductApi.kt)、[ProductDto.kt](src/main/java/com/ttxz/base/data/remote/ProductDto.kt)：声明 GET products 和所需字段，不进入公共 network 模块 |
| Hilt 绑定 | [ProductApiModule.kt](src/main/java/com/ttxz/base/data/remote/ProductApiModule.kt)：基于已有 Retrofit.newBuilder 替换服务地址，复用 OkHttp、Moshi 与超时配置 |
| 分页源 | [ProductPagingSource.kt](src/main/java/com/ttxz/base/demo/product/ProductPagingSource.kt)：偏移量、末页、错误和取消转换 |
| 数据入口 | [ProductRepository.kt](src/main/java/com/ttxz/base/demo/product/ProductRepository.kt)：Pager、每批数量、预取距离和每代新分页源 |
| 页面状态 | [ProductListViewModel.kt](src/main/java/com/ttxz/base/demo/product/ProductListViewModel.kt)：沿用 BaseViewModel，UiState 仅保存慢速设置；分页流 cachedIn(viewModelScope) |
| 页面与条目 | [ProductListScreen.kt](src/main/java/com/ttxz/base/demo/product/ProductListScreen.kt)：消费分页流，复用 ui 的 PagingList；图片直接使用 common 提供的 Coil 共享加载器 |

页面不持有 API、Retrofit 或 OkHttp。分页数据与加载状态由 Paging 提供，不复制到 AppResult 或 UiState 中。此页面没有临时 Effect；刷新/重试由 PagingList 调用 Paging 的 refresh/retry。

## 接口与分页规则

服务地址为 `https://dummyjson.com/`，请求形式：

```text
GET products?limit=10&skip=0&delay=0&select=id,title,price,thumbnail
GET products?limit=10&skip=10&delay=0&select=id,title,price,thumbnail
```

skip 是偏移量，初始为 0；limit 使用 Paging 本次请求的 loadSize。下一偏移量按 `skip + products.size` 计算，达到响应 total 或返回空列表时结束。没有按固定商品总数或“少于 10 条”判断结束。响应偏移量不匹配或分页元数据无效时，作为加载错误交给 Paging 重试。

请求中的 limit 是期望条数，DummyJSON 响应中的 limit 是实际返回条数。例如请求 limit=10、skip=194，在本次总数 194 的情况下会返回 products=[]、limit=0，这是合法结束。分页源允许这种空批；非空数据配 limit=0、负 limit、负 total 或不匹配的 skip 仍作为异常处理。不能在判断空批前一律要求响应 limit>0。

刷新固定从第一页开始，因此分页源的 getRefreshKey 返回 null，prevKey 也为 null。该示例只做向后追加，不支持从旧滚动锚点向前补页。

ProductPageDto 和 ProductDto 沿用现有 KotlinJsonAdapterFactory 反射解析，使用 AndroidX Keep 保留 Release 中的 DTO 名称、字段和构造器。价格按接口原值显示两位小数，没有预设币种。缩略图缺失、加载中或失败时使用此页面自己的占位资源。

官方资料：[商品与分页](https://dummyjson.com/docs/products)、[延迟响应](https://dummyjson.com/docs)。

## 页面行为

- 首次进入自动加载商品；首屏加载、空数据和失败重试由公共组件处理。
- 每批 10 条，预取距离为 2；快网可能直接看到后续数据，慢响应滑到底部会显示加载尾部。
- 追加失败保留已有商品，点击底部重试；刷新失败保留已有商品，并在列表上方显示重试提示。
- 打开慢速演示后，后续请求附加服务端 1.5 秒延迟；关闭则恢复 delay=0。切换模式会取消旧数据流的收集并从第一页加载，页面回到顶部。回顶只由开关操作触发；配置变更重建页面时，沿用 rememberLazyListState 的位置恢复，不在首次 Composition 强制回顶。
- 同一目的地重组时复用 ViewModel 的分页流；页面退出后作用域随目的地移除而结束。未提供进程恢复或强制后台暂停请求。
- 条目没有详情跳转或购买操作；文字与图片仅用于观察列表与图片加载。

列表交互与样式参数见 [库侧列表说明](../ui/components/list.md)。

## 网络与调试

宿主已有 INTERNET 权限。若设备正在使用已安装用户 CA 的 HTTPS 抓包代理，Debug 的 [网络安全配置](src/debug/res/xml/debug_network_security_config.xml) 对 dummyjson.com、cdn.dummyjson.com 增加用户 CA 信任，限这两个准确域名；不关闭 TLS 校验。Release 不包含该调试配置。

该服务是外部演示服务；接口返回文本主要为英文。数据及网络可用性以实际请求为准，不把调试环境结果当作所有设备已验证。

## 验证与学习

[ProductPagingSourceTest](src/test/java/com/ttxz/base/demo/product/ProductPagingSourceTest.kt) 使用内存 API 替身，覆盖初始 loadSize、后续偏移量、短页继续、total 末页、空页、网络失败、取消传播、异常元数据和刷新从第一页开始，不访问真实服务。

[ProductApiIntegrationTest](src/test/java/com/ttxz/base/demo/product/ProductApiIntegrationTest.kt) 使用真实 ProductApiModule、network 的 Retrofit/OkHttp/Moshi 配置与本机 MockWebServer，验证目标域名及分页查询参数、商品 JSON 转换、缺失缩略图与 HTTP 503 错误映射。MockWebServer 和 Moshi 的直接测试依赖仅进入 testImplementation。

[ProductListScreenTest](src/androidTest/java/com/ttxz/base/demo/product/ProductListScreenTest.kt) 使用内存 API、真实页面和 ViewModel，在设备上验证保存状态后的组合销毁/恢复，以及用户切换模式回顶。它不会访问 DummyJSON，不代表 Activity 旋转回调或进程重建的完整验收。

构建及检查：`:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug`。设备检查：进入商品列表查看图片，滚动确认商品 ID 与已加载数增长，打开慢速演示观察底部加载，回到顶部下拉刷新，最终检查没有更多与返回首页。

2026-10-22 本轮验证：Debug 和 Release（Hilt/KSP、R8）构建通过；app 共 12 项 JVM 测试通过，其中 9 项商品分页源测试、2 项商品 HTTP 集成测试、1 项原有模板测试。lint 为 0 错误、8 个原有警告和 1 个原有提示。Release seeds 确认两个反射 DTO 及所需成员保留，合并 Manifest 确认网络安全调试配置只在 Debug 生效。

2026-10-23 补充真机验收：在 DAYU_A6（Android 15 / API 35）安装当前 Debug APK，通过真实 DummyJSON 接口验证：

- 首屏 10 条商品的名称、价格、ID 和缩略图正常显示；滚动后自动增加到 20 条。
- 开启慢速演示后回到首屏；在追加请求期间读到尾部“正在加载”，完成后数量增加到 20 条。关闭慢速模式后可继续分页。
- 首屏下拉刷新出现顶部进度圈，等待期间保留已有商品；完成后已加载数从 20 条回到 10 条，仍从商品 #1 开始。
- 连续滚动到商品 #194，显示“没有更多了”；继续上滑保持 194 条。进程网络日志确认后续请求按 skip 递增。194 是本次服务返回的总数，不是客户端常量。
- 页面返回按钮及系统返回均回到首页；重新进入从商品 #1 加载 10 条。

以上仅覆盖该设备当前网络环境的 Debug 成功路径。未人为断网验证本商品页面的失败重试，未安装运行 Release，也未覆盖旋转、后台切换或其他 Android 版本。前期本机 HTTP 测试和库内内存源设备测试仍分别保留各自验证范围。

2026-10-23 Review 修正验证：

- 新增真实空批格式的回归用例后，旧代码在 3 个合法空批场景失败；修正响应 limit 校验后通过。覆盖首次为空、追加时总数减少、非空但 limit=0、负 limit，以及经过真实 HTTP/JSON 转换的空批。
- Debug/Release 构建通过；app 共 16 项 JVM 测试通过（12 项分页源、3 项 HTTP 集成、1 项模板测试），lint 为 0 错误、8 个原有警告和 1 个原有提示。
- DAYU_A6（Android 15 / API 35）通过 1 项新增页面仪器测试：保存中间位置，销毁并恢复 Composition 后位置不变；点击慢速开关后重新从第一页加载并回顶。测试使用内存接口，验证本次修复涉及的组合状态恢复，不扩大为真实旋转、进程恢复或 Release 运行通过。

阅读顺序：ProductApi → ProductPagingSource → ProductRepository → ProductListViewModel → ProductListScreen → 库侧 PagingList。

练习：把 Paging 的 initialLoadSize 与 pageSize 设为不同值，解释下一次 skip 为什么仍然正确；再观察切换慢速模式后为什么不会把旧流的结果追加到新列表。
