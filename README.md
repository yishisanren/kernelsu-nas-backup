# NAS 模块备份

通过 WebDAV 增量备份 KernelSU 与 LSPosed 模块环境，支持历史快照恢复和本机回退。当前版本：`0.1.0-beta.6`。

安装 ZIP、源码 ZIP 和 SHA-256 校验文件见仓库 Releases。beta.6 增加内外网双入口、多个 Wi-Fi 名称和全新 WebUI，继续使用 beta.5 的压缩分包备份库。beta.6 先做一次性模拟器全链路验证，真机升级与管理器 WebView 验收待用户连接 ADB 后进行。结果与边界见 [beta.6 验证记录](docs/BETA6.md)。

仓库及附件不包含私人 WebDAV 连接配置、真实备份数据或实机截图。验证记录中的连接信息已匿名化；请在手机 WebUI 中填写自己的地址和凭据。

## 安装和备份

1. 在 KernelSU 管理器中安装 `nas-module-backup-0.1.0-beta.6.zip`，按管理器提示重启。
2. WebUI 的“设置”中分别填写外网或默认地址、可选内网地址，以及唯一的共用备份路径。地址只含协议、主机和端口，路径填写已存在且可读写的 WebDAV 目录。
3. 填写共用凭据和内网 Wi-Fi 名称，每行一个。保存后点“检查连接”。旧版完整 URL 自动拆分为默认地址和共用路径，保存前不改写旧配置；原密码、vault 和历史数据保留。
4. 在“概览”展开“查看备份范围”，检查后点“开始备份”。原 ZIP 默认在主用户 Download 目录查找，附加路径在高级设置中填写。

开启自动备份后，每分钟检查模块及 LSPosed 状态，变化稳定约一分钟后备份；至少每 24 小时补查一次，失败约 5 分钟后重试。成功后清除失败退避，后续更新不必等待额外的五分钟冷却。

Wi-Fi 名称精确匹配时先用内网；连接拒绝、DNS 失败或超时才回退外网。未知或未匹配的 Wi-Fi 使用默认地址，不探测内网。认证、权限、HTTP 服务错误、重定向、证书或备份库校验错误直接报错。一次任务固定一个入口，重试时重新选择。

两入口使用同一份凭据、同一路径和格式 3 备份库，通过目录中的随机库标识核验映射；外网不可达时可先用已验证内网，另一入口显示待核验。SSID 无法读取时显示原因并走默认地址。两个地址都可编辑，但不能分别配置目录，切换入口不会另建一套增量数据。配置和选择细节见 [存储格式](docs/STORAGE.md)。

只采集已解锁 Android 用户的模块 APK 和应用配置；系统分身未启动或未解锁时直接跳过，不阻塞主用户备份。检查范围、完成记录和快照都会列出跳过的用户。分身解锁后，自动备份会重新检查并纳入它。被跳过用户的 LSPosed 数据库记录仍保留，但恢复时不会启用该用户的模块；旧版 LSPosed 无分用户启用开关，因此不恢复该用户的作用域。未备份的分身应用数据不能通过此快照恢复。

首次备份建立压缩分包基线。之后按 SHA-256 复用最近一次已提交快照引用的内容，只有新增内容写入新 ZIP 分包。文件超过 8 MiB 时按 8 MiB 分块，大文件变化时只上传变化块。每包原始内容最多 16 MiB、最多 1,024 项，压缩包硬上限 24 MiB，避免触及常见网关的 32 MiB PUT 限制。已经压缩的 APK/ZIP 不一定明显缩小，分包仍能减少 HTTP 请求。

分包不可变，增量备份不会重写旧包。删除文件只改变新快照，旧快照保留；每份快照包含完整文件清单、全部对象到分包的引用和包内索引，不需要手工串联合并增量。即使本机索引丢失，也能从 NAS 最近已提交快照恢复复用关系。上传后完整回读并校验 SHA-256，最后才发布快照提交标记。重试复用同名完整分包并修复半写包。

新版写入格式 3，仍可恢复格式 1/2。**从旧格式首次升级时会另建压缩基线，需要重新上传备份内容；旧 NAS 备份保留。**之后只传增量。全程最多 4 路并行下载分包或旧内容对象，校验包、条目和拼接后的整文件；中断后复用本机已校验内容。格式详见 [存储格式](docs/STORAGE.md)，实测速率见 [速度对比](docs/PERFORMANCE.md)。

在“快照”中选择历史快照并点“导出 ZIP”，可将该快照及所有依赖内容合成一个独立 ZIP，保存在 `/data/adb/nas_module_backup/exports/<快照ID>.zip`，完成后界面显示路径。该目录仅 Root 可读，可通过 Root 文件管理器取出。将 ZIP 解压到空 WebDAV 根目录，连接该目录即可按常规历史快照恢复，不依赖原备份目录或之前的增量包。导出需要额外本地空间，ZIP 不会自动上传到 NAS，也不是 KernelSU 安装包。导出文件不自动清理。

HTTPS 验证系统信任的证书。HTTP 必须明确开启；不支持跳过 TLS 验证。地址须为最终 WebDAV 地址，不跟随重定向。账号须对填写的目录有读、写、创建目录、列目录权限；无需删除 NAS 历史内容的权限。连接设置仅保存到手机 Root 私有目录，不进入 NAS 快照。

非空内容对象通过完整字节范围下载，避免反向代理向 HTML 文件注入脚本。206 响应必须覆盖清单声明的完整范围；所有响应仍校验大小和 SHA-256。服务端不支持 Range 而返回 200 时，也必须通过相同校验。

## 备份内容

| 内容 | 处理 |
| --- | --- |
| KernelSU 模块 | 原 ZIP 可找到时保留，同时保存完整已安装目录、权限、UID/GID、符号链接、启用状态及相关扩展属性；包含待重启安装/更新的版本 |
| 外部配置 | 自动识别模块脚本引用的 `/data/adb` 顶层配置目录，以及同名目录；支持手动补充路径 |
| KernelSU 模块设置 | 保存 `/data/adb/ksu/module_configs`；不复制 Root 授权数据库或整个 `/data/adb` |
| KernelSU 挂载引擎 | 保存并恢复指向已备份模块的 metamodule 入口，覆盖当前手机的 NoMount 连接关系 |
| LSPosed 应用 | 保存主 APK 与所有 split APK、各 Android 用户下的 CE/DE 配置和 Android/data 内容 |
| LSPosed 状态 | 一致性导出启用状态、作用域和配置 BLOB；适配当前手机的六表结构、数据库版本 101 |
| LSPosed 文件 | 保存 `/data/adb/lspd/modules` 和数据库指向的 UUID 数据目录，包含 Android 17 的 apexdata 路径 |
| SQLite | 使用一致性读事务或 VACUUM INTO，将 WAL 中数据纳入快照，不直接拼接正在使用的数据库文件 |

不保留可重新生成的应用 cache、code_cache、lib，模块 oat/.git，以及运行时 socket。FIFO、overlay whiteout 和常见虚拟终端设备节点保留；拒绝未知设备节点。普通文件按内容备份；硬链接关系会变成同内容文件，稀疏文件可能占用更多空间。模块二进制内部使用的额外路径无法靠脚本扫描穷尽，需要在范围检查后补充。

## 恢复出厂后恢复

先让 KernelSU / Root 正常工作，重新安装本模块并连接 NAS。需要恢复到与快照一致的设备型号、系统构建、ABI 和 KernelSU 版本。原快照使用的 Android 用户必须已经存在；例如用户 10 的配置不能直接写进用户 0。

1. 刷新历史快照，选择快照并检查恢复条件。
2. 确认范围后点击恢复。下载与校验全部内容，安装模块 APK，重建新的 APK 路径与 UID 映射，准备配置和本机回退记录。
3. 界面提示后手动重启。启动早期应用系统配置及离线 LSPosed 数据库，暂时禁用 LSPosed 模块以等待应用数据恢复。
4. 解锁涉及的 Android 用户，后台恢复模块应用数据，保留 Android 新建目录的加密策略。
5. 界面提示后再重启，离线恢复 LSPosed 原启用状态，让全部模块环境生效。确认可用后清理本机暂存和回退副本。

不会自动重启，也不在 LSPosed 运行期间直接覆盖数据库。恢复不会删除其他不在快照内的模块或普通应用。恢复范围内的配置会替换为快照版本；原数据保留用于回退。原 ZIP 保留在备份中，但恢复使用目录快照，不重复运行第三方模块安装脚本。

LSPosed 可能保留已卸载应用的数据库记录。这类记录没有可备份的 APK；其配置和作用域仍保留，恢复时保持禁用，预检会列出缺失安装包，需自行重新安装后再启用。

如果已安装的模块 APK 与快照不同，当前版本会停止恢复，避免应用数据迁移使回退失效。先在干净环境恢复，或自行处理该应用版本。普通作用域目标应用（例如聊天应用）不属于本工具的备份范围，需要另行安装；作用域按包名和用户恢复。

“回退本次恢复”会安排下次启动恢复原系统配置，解锁后恢复原应用数据并卸载本次新安装的模块 APK。再次重启使原模块生效。恢复应用中断时，下次启动会根据日志回退系统配置；不要在回退完成前清理暂存。

## 已知边界

- 既有真机适配记录：PME110 / OP61C1L1、Android 17/API 37、KernelSU 3.3.1-110-gf1014f27、LSPosed 2.2.1 (7927)。其他组合未作兼容性承诺。
- 未对现用手机执行恢复出厂，也未覆盖现用模块进行整机重启恢复。验证范围和实测结果见 `docs/VALIDATION.md`。
- Android Keystore/硬件密钥绑定的配置无法只靠复制文件恢复。恢复后可能需要重新登录或重新设置。
- 跨文件的应用业务事务无法在应用运行时保证整体同一时刻；单个 SQLite 数据库按一致性快照处理，检测到安装状态或源文件变化时失败重试。
- 快照包含模块代码和配置，NAS 账号应只供可信设备使用。本版提供 HTTPS 传输与 SHA-256 完整性检查，没有额外的客户端加密或签名。
- 不自动删除历史快照或执行垃圾回收。卸载本模块保留 `/data/adb/nas_module_backup` 下的设置和回退记录，NAS 内容也保留。
- 若恢复后第三方模块导致无法正常开机，应先使用 KernelSU 的安全模式禁用模块，恢复开机后再执行回退。不能把一次隔离测试视为所有模块都能无条件迁移。

## 源码和构建

需要 JDK 21、Android SDK Platform 36、Build Tools 36.0.0、Python 3.9+。设置 `JAVA_HOME` 与 `ANDROID_HOME`，执行：

```sh
python3 scripts/build.py
node tests/webui_test.cjs
```

首次构建从 Maven Central 下载固定依赖，后续按 `dependencies.lock.json` 校验。发布 dex 与测试 dex 分开，安装包不包含测试类。ZIP 使用固定时间戳；同一工具链和源码可重现。依赖与许可证见 `THIRD_PARTY.md`。

隔离测试：`python3 scripts/verify_device.py --serial <ADB序列号>`。它使用手机 `/data/local/tmp/nasbackup-*` 专用目录和本机回环 WebDAV，安装并卸载专用无代码测试 APK，不恢复出厂、不更改现用模块。真实数据的完整备份测试是单独入口，不由该脚本执行。

模拟器压缩回归与性能对比：先构建，再运行 `python3 scripts/verify_packed.py --serial emulator-5580 --report-dir docs/evidence/packed-20261024`。只接受 root 一次性模拟器，基线从仓库 `v0.1.0-beta.4` 构建；源码 ZIP 用户需先拉取仓库标签。默认两种实验延迟、各 3 次；不访问私人 NAS。两次实际模拟器重启恢复另运行 `scripts/verify_virtual_restore.py`。

主要实现：`Inventory.java` 采集范围与元数据；`Dav.java` WebDAV；`Packs.java` 压缩分包、复用和并行下载；`Engine.java` 快照及导出；`Lsp.java` LSPosed 逻辑导出恢复；`Restore.java` 分阶段恢复与回退；`Main.java` CLI、锁与调度；`module/webroot` 中文界面。

beta.6 另外提供 `scripts/verify_dual.py`、`scripts/verify_ui_emulator.py` 和 `tests/webui_browser.cjs`。只接受一次性 root 模拟器；浏览器测试需本地安装 Playwright/Chromium，状态性能对比需保留已发布的 beta.5 ZIP 在 `dist/`。模拟器 Root 由 adbd 提供，KernelSU 版本探针由测试 PATH 中的合成命令提供；没有冒充实际 KernelSU/LSPosed 框架验收。复现命令见 [beta.6 验证记录](docs/BETA6.md)。

## 机制参考

- [KernelSU 模块开发指南](https://kernelsu.org/guide/module.html)
- [KernelSU WebUI](https://kernelsu.org/guide/module-webui.html)
- [KernelSU 模块安装与更新实现](https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/module.rs)
- [LSPosed 上游备份逻辑](https://github.com/LSPosed/LSPosed/blob/master/app/src/main/java/org/lsposed/manager/util/BackupUtils.java)
- [Android 加密偏好配置的备份限制](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences)

当前 LSPosed 分支的数据库结构以本机只读探针结果为准；没有依赖不稳定的 lspctl 自动化接口。
