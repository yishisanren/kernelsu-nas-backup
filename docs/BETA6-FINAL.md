# beta.6 模拟器最终验收

2026-10-24，版本 `0.1.0-beta.6`。一次性 Android 15 / API 35 arm64 模拟器完成完整复验；所有备份、恢复和故障测试均使用合成数据与本机回环 WebDAV。本轮按用户要求停止继续真机验收，未执行真机恢复。

正式模块源码未改动，安装包仍为已发布的 beta.6。补充内容为原生 Android WebView 验收工具、打包隔离检查与验证记录，不重写已有版本标签。Release 中新增的复验源码包对应更新后的源码提交，原安装包和原始标签源码包保留。

## 验收结果

| 范围 | 结果 |
| --- | --- |
| 内外网双入口 | 51 项通过；多 SSID、网络不可达回退、共用路径/备份库、跨入口零内容上传、错误库拒绝、身份变更与初始化竞争 |
| Android 回归 | 73 项通过；格式 1/2、SQLite/WAL、现代六表/旧版三表 LSPosed、内容和权限、回退与缓存 inode |
| 压缩分包 | 35 项通过；格式 3、增量、分块大文件、损坏/半写包、恢复/导出与路径边界 |
| APK 与数据 | PackageManager 真实 base/split 安装、错包名拒绝、UID 映射、CE/DE/外部数据恢复及回退通过 |
| WebUI 逻辑 | 24 项通过；串行轮询、草稿保留、密码不回填、SSID 特殊字符、互斥、预检及确认门禁 |
| 浏览器与 CLI | 93 项通过；响应式布局、亮暗主题、字号/对比度、键盘操作、真实 CLI 备份与增量 |
| 原生 Android WebView | 28 项通过；ADB 触摸双向滚动、软键盘收起、设置保存、密码保留、SSID 去重、连接、备份、历史及预检；无 JS 错误 |
| 自动调度 | 同一生产 daemon 连续通过首次观察、模块版本更新、每日到期补查，成功后无需等待额外失败退避 |
| 独立比对 | WebDAV 提交标记、分包/成员 SHA-256、源文件内容和权限/UID/GID 一致 |
| 正式恢复 | 空目标重新下载与安装 APK；两次实际模拟器重启后完成，126 个文件及最终阶段 670 项断言通过 |
| 安装包 | 可复现重建、CRC、脚本语法、源码对应与测试类隔离通过；测试 APK 不进入安装包 |

正式恢复准备 **1,495ms**，未预取；准备后移除 NAS 的 ADB reverse，两次启动阶段离线执行。首次启动恢复配置并暂停 LSPosed 模块，解锁恢复应用数据，第二次启动恢复启用状态、作用域及 BLOB。文件、属主、权限、链接/FIFO、缓存 inode 与恢复后模块脚本均核验。

## 响应速度和增量

同一模拟器、同一配置，beta.5 / beta.6 交替各执行 15 次已预热状态读取，中位数 **914.84ms → 47.94ms**，减少 **94.76%**，包含相同 ADB 往返。

原生 WebView 通过真实触摸启动备份：未变化快照 **4.367 秒 / 0B**；修改一份配置后 **4.199 秒 / 290B / 1 包**。耗时包含触摸坐标稳定、命令启动和完成观察，不等于大文件传输吞吐。双入口专项还确认外网新客户端复用内网基线时上传内容为 **0B**。

自动调度从观察开始到提交分别为：首次观察 **64.44 秒 / 0B**，模块更新 **122.06 秒 / 302B**，每日到期且签名不变 **61.94 秒 / 0B**。每日测试仅把“上次成功时间”设为一天前，未修改系统时间，未声称连续观察 24 小时。

原始结果与画面均位于 [evidence/beta6-final](evidence/beta6-final/)：包括双入口、Android/APK、分包、浏览器、原生 WebView、状态耗时、自动调度、独立源比对、双重启恢复及清理记录。原生 WebView 的亮暗画面仅包含模拟器合成连接配置。

## 复现

使用 JDK 21、Android SDK Platform 36 / Build Tools 36.0.0、可 `adb root` 的一次性 Android 35 arm64 模拟器。Node 与 Playwright **1.62.1**（含 Chromium）用于桌面浏览器和 WebView 调试观察；原生触摸由 `adb input touchscreen` 注入。状态对比需在 `dist/` 保留发布版 beta.5 ZIP。

```sh
python3 scripts/build.py
python3 scripts/verify_device.py --serial emulator-5590 --root-shell --report-dir docs/evidence/beta6-final
python3 scripts/verify_dual.py --serial emulator-5590 --report-dir docs/evidence/beta6-final
python3 scripts/verify_packed.py --serial emulator-5590 --report-dir docs/evidence/beta6-final --regression-only
python3 scripts/verify_ui_emulator.py --serial emulator-5590 --report-dir docs/evidence/beta6-final
python3 scripts/verify_virtual_restore.py --serial emulator-5590 --report-dir docs/evidence/beta6-final
python3 scripts/verify_package.py --rebuild
```

仅复测原生触控可给 `verify_ui_emulator.py` 加 `--webview-only`。原生测试 APK 只接受 `ranchu` 模拟器与随机路径的回环测试服务，退出时卸载；测试代码不进入正式模块。原生 `JavascriptInterface` 将命令交给受模拟器标识/Root/夹具状态保护的本机测试服务，调用实际发布 CLI，恢复写入命令在该传输层被拒绝。

## 验证边界

原生 Android WebView **124.0.6367.219** 的页面渲染、触摸和接口回调已实测；测试桥接与真实 KernelSU 管理器桥接仍有区别。模拟器没有安装 KernelSU/LSPosed 框架，早期恢复入口由测试调用，**未验证实际框架 Hook 注入**。没有用模拟器结果宣称所有系统/模块兼容，也未做屏幕阅读器验收。

真机整体兼容性和稳定前台的管理器 WebUI 验收仍待后续安排。真实恢复始终不覆盖用户手机的现用模块。

安装包 SHA-256：`465f3ecdb4c7e9fba33d33ee09c82d707004f63a307075813bcb29194f83dc36`（1,187,641B）。
