# beta.6 验证记录

本页保留首轮模拟器结果。原生 Android WebView 触控、完整复验及最新交付边界见 [模拟器最终验收](BETA6-FINAL.md)。

版本 `0.1.0-beta.6`，日期 2026-10-24。双地址、共用路径、备份库绑定和 WebUI 重做完成。验证使用一次性 Android 15 / API 35 arm64 模拟器、合成模块/APK/配置、本机回环 WebDAV；没有连接私人 NAS、安装到实体手机或覆盖其现用模块。

## 功能与结果

| 范围 | 结果 |
| --- | --- |
| 双入口专项 | 51 项通过；多个 SSID、主接口选择、未知网络默认入口、网络错误回退、认证/权限/服务/TLS/重定向错误不回退、错误库拒绝、条件初始化竞争保护、提交前身份复核 |
| 旧格式 Android 回归 | 73 项通过；格式 1/2、内容与权限、UID/GID、SQLite/WAL、现代六表与旧版三表 LSPosed、故障回退、缓存 inode 等 |
| 压缩分包 | 35 项通过；空文件、大块、增量、损坏/半写包、删除、恢复/导出、路径与解压边界 |
| 应用安装与数据 | 真实 PackageManager 的 base/split 安装、错包名拒绝、UID 映射、CE/DE/外部数据恢复和回退通过 |
| WebUI 逻辑 | 24 项通过；串行轮询、保留草稿、特殊字符、SSID 首尾空格、密码不回填、忙碌互斥、预检与明确确认、后台任务 |
| 浏览器与 CLI | 93 项通过；Chromium 调用模拟器中的实际发布 CLI，保存配置、读取 Wi-Fi、连接、备份、零增量、变化增量、历史快照及预检 |
| 视觉与键盘 | 浅/深色 × 320/375/414/768px × 三个分区无越界；320px 下 200% 字号无越界；跳至内容、键盘切换/焦点、原生标签、强制颜色模式通过；无页面 JS 错误 |
| 对比度 | 所测启用文本最小 5.244:1，输入边界最小 3.161:1；禁用文本不纳入阈值统计 |
| 独立内容比对 | 从 WebDAV 目录独立读取提交标记、分包和成员，验证 SHA-256/大小，再与模拟器源文件哈希、权限、UID/GID 比对通过 |
| 正式恢复 | 内网备份，外网零内容上传复用；移除源模块/APK/配置形成空目标，从正式恢复入口重新下载与安装 APK；两次实际模拟器重启后完成，126 个文件、最终 670 项断言通过 |

恢复准备耗时 1,521ms，没有预取；第一次启动恢复配置并暂停 LSPosed 模块，解锁恢复应用数据，第二次启动恢复六表中的启用状态、作用域及 BLOB。准备后移除 NAS 的 ADB reverse，两次启动阶段离线执行。内容、权限、UID/GID、链接/FIFO、缓存目录 inode 和恢复后模块脚本均核验。恢复与回退仅在模拟环境执行。

原始结果：[双地址](evidence/beta6/dual-endpoint.json)、[Android/APK 汇总](evidence/beta6/isolated-summary.json)、[压缩专项](evidence/beta6/packed-regression.json)、[WebUI](evidence/beta6/webui.json)、[浏览器](evidence/beta6/webui-browser.json)、[独立比对](evidence/beta6/nas-source-comparison.json)、[双重启正式恢复](evidence/beta6/virtual-two-boot-restore.json)。

## 响应速度与自动备份

同一模拟器、相同旧配置，beta.5 与 beta.6 交替各执行 15 次已预热 `status`，包含相同 ADB 往返。中位数 **912.06ms → 46.48ms**，减少 **94.9%**，约为原来的 1/19.62。首次生成缓存及缓存失效仍需启动 Java。

根因是旧版每次轮询都启动 Java，并且固定计时器没有串行保护。beta.6 读取原子发布的脱敏视图，按配置哈希和版本校验；恢复日志变化时刷新阶段。前端同一时刻只允许一个状态请求，空闲每 6 秒、工作时每 1.5 秒，页面隐藏停止轮询，可手动暂停。保存设置清除旧连接显示和恢复预检；轮询保留未保存输入。密码不回传，恢复日志仅暴露阶段和错误。

小型夹具的浏览器“开始备份”到完成确认分别为：首次 3.192 秒 / 549B，一个未变化快照 3.197 秒 / 0B，修改配置后 3.206 秒 / 269B（一个新包）。包含启动保护间隔及轮询确认，不能外推为真实 NAS 大备份吞吐。压缩传输算法沿用 beta.5，既有传输对比保留在 [PERFORMANCE.md](PERFORMANCE.md)。

实际运行同一个生产 daemon，未在各阶段重启它：首次观察约 63.9 秒自动提交，模块版本更新后约 123.7 秒提交（302B），每日到期且签名不变约 61.9 秒提交（0B）。每日测试仅将本机上次成功时间设为一天前，系统时间未修改，没有声称连续观察了 24 小时。成功后调度时间由真实成功写回。

本轮修正成功后仍保留五分钟失败退避的问题：失败保留退避，成功清零；上述连续安装/更新/每日测试覆盖这一行为。日志见 [状态耗时原始样本](evidence/beta6/status-performance.json) 和 [自动调度结果](evidence/beta6/automatic-backup.json)。

## 配置与复现

旧 `url` 读取时无损拆分为外网或默认 origin 与唯一目录；保存前原配置字节不改写，保存后保留密码、vault、范围选项和原备份。beta.5 格式 3 库继续复用，不因入口切换重传基线。仅增加目录库标识，格式 1/2/3 内容布局不变。协议和边界见 [STORAGE.md](STORAGE.md)。

先设置 JDK 21、Android SDK Platform 36 / Build Tools 36.0.0，准备一次性可 `adb root` 的 Android 35 模拟器。以下序列号只是示例；所有新脚本都拒绝实体手机：

```sh
python3 scripts/build.py
python3 scripts/verify_device.py --serial emulator-5586 --root-shell --report-dir docs/evidence/beta6
python3 scripts/verify_dual.py --serial emulator-5586 --report-dir docs/evidence/beta6
python3 scripts/verify_packed.py --serial emulator-5586 --report-dir docs/evidence/beta6 --regression-only
python3 scripts/verify_ui_emulator.py --serial emulator-5586 --report-dir docs/evidence/beta6
python3 scripts/verify_virtual_restore.py --serial emulator-5586 --report-dir docs/evidence/beta6
```

UI 测试需 Node、Playwright 和已安装 Chromium；状态对比需将发布版 beta.5 安装 ZIP 放在 `dist/`。仅复测浏览器和状态缓存可加 `--skip-auto`。UI 脚本要求测试状态、模块目录不存在，结束清理自己创建的模块、配置、测试版本命令和服务；正式恢复脚本要求没有测试 APK 和 LSPosed 数据库，结束清理自身夹具。测试 APK 无代码、无权限，实际由 PackageManager 安装。

界面示例来自合成配置：[浅色概览](evidence/beta6/webui-light-overview.png)、[深色概览](evidence/beta6/webui-dark-overview.png)、[浅色设置](evidence/beta6/webui-light-settings.png)、[深色设置](evidence/beta6/webui-dark-settings.png)。均为浏览器截图，包含的地址和 Wi-Fi 是模拟器夹具。

## 验证边界

模拟器使用 adbd Root，UI 测试仅在测试 PATH 中提供合成 `su -v` 版本探针；实际 KernelSU 启动钩子由测试调用，没有安装 KernelSU/LSPosed 框架，**未验证真实 Hook 注入**。设备型号/构建、Root 与 LSPosed 版本等恢复门禁仍保留。浏览器不是管理器内的 Android WebView；未做实际屏幕阅读器验收。

真机完整验收另行安排。后续真机只做安装与备份验证，不覆盖现用模块做恢复。模拟器中的成功不能替代所有设备、系统和第三方模块的兼容性承诺。

历史上 beta.5 在另一轮已完成实体手机与真实局域网 NAS 的备份、增量、自动触发及独立回读；这项历史结果不计入 beta.6 真机验收。私人连接配置、真实备份、设备私有截图不随本次源码和安装包发布。
