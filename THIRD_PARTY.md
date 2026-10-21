# 第三方组件

运行时打包以下固定依赖。下载源为 Maven Central，SHA-256 固定在 dependencies.lock.json。

| 组件 | 版本 | 许可证 | 上游 |
| --- | --- | --- | --- |
| OkHttp | 4.12.0 | Apache-2.0 | https://github.com/square/okhttp |
| Okio JVM | 3.6.0 | Apache-2.0 | https://github.com/square/okio |
| Kotlin Standard Library | 1.9.10 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| JetBrains Annotations | 13.0 | Apache-2.0 | https://github.com/JetBrains/java-annotations |

Android 平台 API 与 SQLite 由手机系统提供，不打包 Android framework。编译使用 Android SDK Build Tools 36.0.0 的 D8 和 Android 36 公共 API。测试 APK 不包含应用代码或权限，仅用于验证 Android 安装会话，验证后卸载。

Copyright Square, Inc. (OkHttp / Okio); Copyright JetBrains s.r.o. (Kotlin / Annotations). Apache 2.0 许可证随模块放在 licenses/APACHE-2.0.txt。
