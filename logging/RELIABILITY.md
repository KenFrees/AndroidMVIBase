> 当前 API 已移除 ZIP 导出与文件快照，改为日志文件查询；下文导出相关内容属于历史记录。当前用法与边界以 [README](README.md#存储与文件查询) 为准。

# logging 第二阶段可靠性验收（2026-10-10）

## 范围与结论

本轮以第一阶段提交 4eaddaa 的生产实现为基线，增加确定性故障测试，更新外部目录压力与强杀测试，实际验收示例页面。没有修改生产日志 API、增加历史自动压缩、分享或上传功能。当前 API 35 范围通过；用户明确选择先完成现有设备验证，其他 Android 版本列为待验收，因此第二阶段跨版本部分尚未完成。

当前入口：最终方案与最新测试统计见文末“当前方案：移除等待与分档吞吐验证”；吞吐范围见 [README](README.md#实测吞吐范围2026-10-10)。限时等待章节仅保存历史实验，相关参数与专属用例已删除。

设备：DAYU_A6，Android 15 / API 35，串号 3127RH1010015919。示例 app targetSdk 37，库 minSdk 26 / compileSdk 37。本机没有可用 AVD 或系统镜像，本轮不下载模拟器。

## 文件与故障覆盖

| 场景 | 证据 | 结果 |
| --- | --- | --- |
| 配置外部目录与 null 外部目录 | LoggerDeviceTest | 配置路径一致；目录不可用时初始化停用、中文原因可查询，不回退内部目录、不抛给上层 |
| 重开前目录被占用，退避与恢复 | StorageReliabilityTest | 既有日志保留；退避期间记录跳过并计失败，恢复后新记录可写 |
| 首次分片重命名失败 | StorageReliabilityTest | 原文件及冲突目标不覆盖，故障解除后正常滚动 |
| 过期文件删除失败 | StorageReliabilityTest | 失败显式报告，文件保留，后续重试成功；不清理配置目录外文件 |
| 多行异常残尾与不完整 UTF-8 | StorageReliabilityTest | 导出修复副本不改源，重开去除未结束尾行，保留此前完整行 |
| 队列满、并发、脱敏失败、刷新/ZIP/发布/清理故障、取消及关闭 | 既有 JVM 回归 | 继续通过，操作上下文与关闭失败反馈正确 |
| 未分片名称、首次滚动、重启追加、多行异常与 ZIP | LoggerDeviceTest | 实际外部目录中的内容与命名通过 |

当前最终非阻塞方案 JVM 测试共 54 个，0 失败/错误/跳过；常规 API 35 LoggerDeviceTest 共 5 个，0 失败/错误/跳过。logging Lint 无问题；app Lint 0 错误、26 个既有警告；Debug app 与 instrumentation APK 编译通过。故障注入是确定性模拟，不等同于真实磁盘耗尽、物理存储拔出或设备掉电。

## 性能与内存

测试使用 getExternalFilesDir(null) 下独立配置目录；每次主线程批量提交 500 条，每批后显式刷新，每轮 10000 条后请求 GC 并观测进程堆。每条普通正文约 100 个汉字；异常混合负载每 20 次提交附带一个固定异常（5%）。异常被丢弃/接受的实际比例可能受等级和容量影响。

| 观测 | 普通正文 | 5% 异常混合 |
| --- | ---: | ---: |
| 提交次数 | 300000 | 300000 |
| 接受 | 144022 | 146458 |
| 丢弃 | 155978 | 153542 |
| p95 提交耗时 | 31 微秒 | 44 微秒 |
| 最大提交耗时 | 6434 微秒 | 5277 微秒 |
| 测量段耗时 | 37654 ms | 42969 ms |
| GC 后基线堆 | 4819920 字节 | 4819920 字节 |
| GC 后最大堆 | 4828128 字节 | 4860896 字节 |
| 相对基线最大增长 | 8208 字节 | 40976 字节 |
| 测量段进程累计分配 | 352089168 字节 | 398533760 字节 |

两次测试均 OK (1 test)；接受的记录全部成功追加到文件缓冲，failed=0，最终日志文件总量不超过 20 MiB。累计分配包含测试及运行时分配，GC 后堆也属于整个进程，不能等同于库独占内存。批量提交刻意制造容量压力，丢弃属于预期策略；接受/写入缓冲/刷新不是 fsync 持久性承诺。上述约 38–43 秒样本不代表长期负载，也未覆盖巨型正文、慢速脱敏或所有设备的帧耗时。

## 实际强杀

独立测试包在外部目录 crash-recovery-stage2 写入并刷新一条多行异常，然后阻塞消费者并接受一条 pending。宿主收到 LOGGER_CRASH_READY 后执行 am force-stop，再用新进程执行恢复验证：OK (1 test)。已刷新的正文、异常标题与 at 帧保留；pending 丢失且不会重放；重启追加成功。

可复现脚本 [verify-force-stop.ps1](scripts/verify-force-stop.ps1) 只停止 com.ttxz.base.logging.test，不停止示例 app；未进入 READY 不视为通过。此实验验证进程停止，不验证物理掉电或 fsync。

## 页面验收

使用 phone-harness 在示例 app 实际操作：首页 → 日志测试 → 输入 stage2-ui-1010 标识 → DEBUG 过滤 → INFO/WARN/ERROR 接受 → 刷新 → 导出 → 返回首页 → 再进入。

- 显示 /storage/emulated/0/Android/data/com.ttxz.base/files/logs。
- DEBUG 提示未启用；INFO/WARN/ERROR 后显示接受 3、写入缓冲 3、拒绝 0、失败 0。
- 下载本次缓存 ZIP 并读取：标识恰好出现 3 次，异常标题后实际换行，at 帧带实际制表缩进。
- 返回首页再进入后，目录和累计计数保留，应用级日志系统没有随页面关闭。
- 自动化工具的部分文本匹配曾点到说明文案；改为树中完全匹配按钮位置后通过，未据此修改产品代码。未自动分享或上传归档。

## 复现命令与产物

```powershell
.\gradlew.bat :logging:testDebugUnitTest :logging:lintDebug :app:assembleDebug :app:lintDebug :logging:assembleDebugAndroidTest --console=plain
.\gradlew.bat :logging:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.ttxz.base.logging.LoggerDeviceTest' --console=plain
# 确保当前 instrumentation APK 已安装；重复安装前按自己的设备序号替换。
adb -s 3127RH1010015919 install -r logging/build/outputs/apk/androidTest/debug/logging-debug-androidTest.apk
adb -s 3127RH1010015919 shell am instrument -w -e class com.ttxz.base.logging.LoggerStressDeviceTest -e stressRounds 30 com.ttxz.base.logging.test/androidx.test.runner.AndroidJUnitRunner
adb -s 3127RH1010015919 shell am instrument -w -e class com.ttxz.base.logging.LoggerStressDeviceTest -e stressRounds 30 -e stressWithThrowable true com.ttxz.base.logging.test/androidx.test.runner.AndroidJUnitRunner
.\logging\scripts\verify-force-stop.ps1 -Serial 3127RH1010015919
```

原始本地输出位于 build/stage2/：stress-plain.txt、stress-metrics.txt、stress-throwable.txt、stress-throwable-metrics.txt、force-stop-prepare.txt、force-stop-verify.txt、force-stop-metrics.txt、page-qa.jsonl、page-export-result.json 和 page-export.zip。build 不入库，清理后需重跑生成。JVM/设备 XML 和 Lint 报告位于对应 build 标准目录；本文保存关键观测值。

## 待验收与边界

- API 26、29/30、37：待提供设备或模拟器后验收，不能凭编译通过宣称兼容性完成。
- 长期生产负载、真实磁盘满/外部存储拔出、掉电持久性：未实测；队列仍按条数限制，巨大正文积压仍可能增加内存。
- 本版 R8 未复测，第一阶段历史 R8 证据不作为本版验收；未新增专用构建变体。
- 多行异常骤停后可能只剩部分完整行；修复只去掉未结束尾行，不保证异常记录原子性。
- 自动压缩历史日志及业务项目接入属于后续阶段，本轮不实施。未提交或推送。


## 后台限时等待实验记录（历史，已移除）

此处为已移除方案的实验数据，不是当前 API。曾新增 `enqueueTimeoutMillis`，默认 0；正值只允许后台线程限时等待空间并跳过提前丢弃，主线程仍非阻塞。未采用无限等待，也未扩大队列容量或释放正在处理记录的容量。每最多 10ms 检查关闭；中断拒绝且保留中断标记。默认行为不变。

Android 15 同一设备、同一顺序执行一次对比；每条约 100 个汉字，90% INFO、5% WARN、5% ERROR，无 Throwable、Logcat 关闭、队列 256。固定速率为每 100ms 一批、持续计划 3 秒；突发为连续提交 10000 条。结果不代表稳态极限、跨设备性能或统计显著性；首组包含冷启动/JIT 影响。等待模式可能不能跟上计划速率，所以同时列实际提交时间。

| 负载 | 默认接受/丢弃 | 等待 20ms 接受/丢弃 | 默认/等待提交耗时 | 默认/等待 p95 |
| --- | --- | --- | --- | --- |
| 100 条/秒，300 条 | 300 / 0 | 300 / 0 | 2905 / 2903ms | 1743 / 1449μs |
| 1000 条/秒，3000 条 | 3000 / 0 | 3000 / 0 | 2906 / 2911ms | 132 / 217μs |
| 5000 条/秒，15000 条 | 7996 / 7004 | 15000 / 0 | 2911 / 3362ms | 44 / 412μs |
| 连续突发 10000 条 | 698 / 9302 | 10000 / 0 | 97 / 2134ms | 30 / 398μs |

5000 条/秒默认拒绝全部是 INFO；突发默认 INFO/WARN/ERROR 分别拒绝 8783/264/255 条。所有已接受记录均成功写入缓冲并刷新，本轮失败操作为 0。等待模式突发最长单次调用 5421μs，5000 条/秒最长 5570μs。等待明显减少本轮丢失，但减慢生产线程；20ms 只作测试参数，不建议全局无条件开启，默认仍为 0。尚未覆盖长期持续过载、多生产线程竞争、慢磁盘及更大正文的吞吐保证。

历史实验入口（当前已删除，不能直接复现）：`compareRateControlledAndBurstBackpressure`、`configuredWaitStillRejectsImmediatelyOnRealMainThread`、`QueueWaitTest`。原控制台标识为 LOGGER_RATE，数组顺序 DEBUG、INFO、WARN、ERROR。当前非阻塞验证使用 `LoggerStressDeviceTest#measureNonBlockingRateRangeAndHeap`、`fullQueueRejectsImmediatelyOnRealMainThread` 和 JVM `QueueConcurrencyTest`。

补充验收：60 条 JVM 测试全部通过；Android 15 固定速率/突发对比通过，真实主线程保护单独执行 1 条通过（0.134s）；原 LoggerDeviceTest 5 条回归通过。logging/app lint、app assembleDebug 通过，git diff --check 通过。其他 Android 版本仍待验收。


## 多生产线程、大正文和慢消费者实验记录（历史）

此处等待模式和专属测试已移除；非阻塞并发/正文完整性测试保留。Android 15 单次实测 `concurrentLargeMessagesWithSlowConsumerRecoverWithoutRetention`：8 个固定生产线程，每轮各 50 条，共 3 轮 1200 条；每条独立创建 16384 个 ASCII 字符正文及唯一编号，队列 16，关闭 Logcat；脱敏测试回调人为 sleep 2ms/条以模拟慢消费者（正式回调不得如此阻塞）。两模式顺序运行，非性能基准或跨设备保证。

| 模式 | 接受 | 拒绝 | 调用 p95 | 调用最大值 | 排空后 GC 保留堆最大增量 |
| --- | --- | --- | --- | --- | --- |
| 默认 0ms | 42 | 1158 | 157μs | 16803μs | 4096 字节 |
| 后台等待 20ms | 230 | 970 | 20928μs | 25316μs | 20496 字节 |

已接受日志全部逐条验证编号唯一、正文长度和每个字符，写入数等于接受数、failed 为 0。等待降低本轮拒绝数但无法解决持续过载。20ms 仅限制入队空间等待预算，调度、正文处理等可使总调用耗时超过 20ms；默认非等待也不代表调用耗时恒为零。

堆样本：默认 [2362320, 2366416, 2366416, 2366416]；等待 [2386896, 2395088, 2403296, 2407392]。这只是排空后显式 GC 的进程保留堆，包括测试代码，不是积压峰值或日志库独占内存。测试没有证明任意生产线程数量或任意大正文都安全；等待者仍可能持有调用参数，不在记录槽数限制中。不增加内存自适应或新的公开参数。

JVM 新增并发超时后槽位可复用、延迟正文未执行，以及关闭时所有并发等待调用返回的测试。该历史实验当时的 62 条 JVM 全部通过；本次 Android 15 新增测试 1 条通过；logging lint 和 git diff --check 通过。本轮仅测试与文档变化，不重复宿主构建。


## 当前方案：移除等待与分档吞吐验证（2026-10-10）

用户确认后移除 enqueueTimeoutMillis、主线程判断和所有限时等待逻辑；所有线程均在队列无空间时立即拒绝。移除等待专属用例，保留非阻塞并发容量复用、延迟正文不构造、关闭后拒绝、真实主线程和大正文完整性测试。上面等待相关数据和测试数量仅为历史实验，不是当前功能。

新增 `measureNonBlockingRateRangeAndHeap`，条件和汇总见 README“实测吞吐范围”。每档预热 100 条后取基线，预分配计时与接受/读取标记数组，每 10ms 用独立采样线程读取整个进程 Java 堆；正文包含唯一编号与约 100 汉字，每 100ms 一批。每档独立目录，默认队列、文件预算和刷新策略，后台单线程全部 INFO。持续计划 10 秒结束后 flush，再逐条流式检查文件内编号、正文和唯一性。堆回落在正文读取校验前采样，避免读文件测试本身污染回落值。

| 条/秒 | 接受/尝试 | 拒绝 | 提交窗口ms | p95μs | 最大μs | 基线Java堆B | 采样峰值B | 排空GC后B |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1000 | 10000/10000 | 0 | 10000 | 216 | 1643 | 2452432 | 8666080 | 2456544 |
| 1500 | 15000/15000 | 0 | 10000 | 91 | 3866 | 2550752 | 8825824 | 2558944 |
| 2000 | 20000/20000 | 0 | 10000 | 81 | 5447 | 2608096 | 8874976 | 2608096 |
| 2500 | 23089/25000 | 1911 | 10000 | 67 | 4965 | 2661344 | 9087968 | 2661344 |
| 3000 | 22970/30000 | 7030 | 10000 | 29 | 776 | 2710496 | 8948704 | 2710496 |

单设备单轮、分档顺序执行；JIT/GC、采样和调度会影响结果，拒绝调用也计入延迟分位数，不能将高负载低 p95 理解为写入更快。不做精确极限或全设备承诺。最高测试零丢弃档为 2000 条/秒；2000–2500 未进一步细分，瞬间突发不适用这个结论。

当前 54 条 JVM 测试全部通过（移除等待专属用例导致数量下降）；Android 15 速率/内存用例 1 条、LoggerDeviceTest 5 条、真实主线程 1 条、非阻塞并发大正文 1 条通过。logging/app lint、app assembleDebug、git diff --check 通过。跨 Android 版本仍待验收，未提交。
