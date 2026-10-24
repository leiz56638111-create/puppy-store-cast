# 镜像实验模块来源与修改

本模块复用 AirTV 的镜像接收代码，不是从头实现 AirPlay 协议。

- AirTV: https://github.com/0xb1ob/air-tv ，提交 80e958d380e1c9cc609d037c439faec0826bc7b7；其 Kotlin 接收器、解码器、JNI、UxPlay 派生 C 核心和测试保留于本模块。上游 GPLv3 许可证、README 位于 vendor/。保留 com.airtv.receiver 源码命名空间以保持 JNI 方法对应，合并版安装包名为 cn.local.livingroomcast，历史独立包名为 cn.local.livingroomcast.mirror。
- 内含 UxPlay / ShairPlay 派生代码：各文件保留原始版权及 LGPL-2.1-or-later 声明；PlayFair 的 GPL 声明与 llhttp MIT 许可保留。上游协议引擎来源见 vendor/AirTV-README.upstream.md 及源码文件。
- OpenSSL 3.0.22：Apache-2.0，使用 vendor/sources 中的官方完整源代码重建 libcrypto，没有沿用 AirTV 的旧预编译库。源文件 SHA-256 与官方 GitHub Release digest 一致。
- libplist 2.6.0 / libcnary：LGPL-2.1-or-later 等上游声明，完整官方发布源代码在 vendor/sources；保留 COPYING / COPYING.LESSER。
- Kotlin standard library 2.2.20 及 JetBrains annotations：Apache-2.0；仅在测试包中使用的 AndroidX Test / JUnit 保持各自许可。

修改：合并版 library 接入、单一启动入口及模式切换，保留 Android 6.0 的 DLNA 安装并对 AirPlay 原生引擎进行 API 24／ARM 保护；音频解码／输出重建及诊断、按视频时间戳呈现、控制查询连接与媒体会话分离；中文独立实验界面、单独安装标识、请求 1080p/30fps、触摸与遥控操作、Home 后停止接收、连接详情、通知入口、UTF-8 发现测试及会话计数清理。未实现视频 App 内 AirPlay 投送、ALAC 专用播放器或 DRM 内容支持。实验 UI 不把模拟器结果描述为真实苹果设备兼容认证。

APK 内 assets/licenses 保留 GPL、LGPL、Apache、OpenSSL、libplist、PlayFair 及 llhttp 许可证；完整源归档包含 vendor/sources 的官方源码及本模块重建脚本。上游其他作者和许可信息以相应源码头为准。项目原创修改 GPL-3.0-only。
