# AirPlay 模块来源、许可与修改

本目录为小狗便利店投屏1.0.0内使用的 GPL-3.0-only AirPlay库，源于已单独交付的0.4.0-upstream-alpha1对照版；对照版保留在airplay-upstream-comparison分支。是上游项目的修改版，保留 jqssun/android-airplay-server 和下列作者原始声明；并非上游官方发行。上游版本 v0.0.36，提交 a46cca0a6a1274e9e33c0e750f0f1c1eb1d881ea。完整上游源树和依赖源码归档在 `vendor/sources/`，准确哈希在 `vendor/sources.json`；构建改动记录在 README 和准备脚本。

| 组件 | 固定版本/提交 | 许可/来源 |
|---|---|---|
| android-airplay-server | v0.0.36 / a46cca0 | GPL-3.0, https://github.com/jqssun/android-airplay-server |
| UxPlay | df67c212a433cf6dda3676dd40c097900d24e645 | GPL-3.0, https://github.com/FDH2/UxPlay |
| FFmpeg | 38b88335f99e76ed89ff3c93f877fdefce736c13 | 本次最小配置为 LGPL-2.1-or-later，https://github.com/FFmpeg/FFmpeg |
| libplist | f41b1ea67045e0c09339974d83e389972d84f166 | LGPL-2.1-or-later, https://github.com/libimobiledevice/libplist |
| openssl-cmake | 4edd36a8dab5f85a8f92650b5bdf6e0cab13aab8 | MIT, https://github.com/viaduck/openssl-cmake |
| OpenSSL | 3.4.4 | Apache-2.0, https://github.com/openssl/openssl |
| Oboe | 1.9.3 | Apache-2.0, https://github.com/google/oboe |
| Media3 | 1.11.1 | Apache-2.0, https://github.com/androidx/media |
| AndroidX core/lifecycle/media | 1.16.0 / 2.9.4 / 1.7.0 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin/coroutines | 2.2.20 / 1.9.0 | Apache-2.0, https://github.com/JetBrains/kotlin / https://github.com/Kotlin/kotlinx.coroutines |

UxPlay 包括原作者注明的 Playfair、llhttp 等组件，许可文件保留在源码归档与 APK 的 assets/licenses。上游解码器选择逻辑注明参考 Moonlight Android，继续保留该来源注释；没有加入 Moonlight 的视频串流功能。根目录其他 Java 运行时/Gradle/desugar 声明见 THIRD_PARTY_NOTICES.md。

2026-10-27 小狗便利店投屏 contributors 的修改：独立持久配对身份（使用上游随机 MAC 后备逻辑，不读取硬件 MAC）、替换 UI、关闭开机/后台/连接自动拉起、移除视频下载、增加诊断与会话检查、移植适用的请求边界修复、补齐 LifecycleService 的 super 调用和 Media3 opt-in，提前释放直投播放器并隔离旧实例回调。1.0.0将本模块转为库、移除独立Launcher，统一“小狗便利店投屏”发现名；增加DLNA宿主切换与返回前释放，不改原生接收/音频/GL算法。用本地固定源码归档替换网络原生依赖下载，NDK 28.2 / SDK36 / AGP8.11.1 替代上游 NDK27 / SDK37 / AGP9.4.1；Oboe 改源码构建。音频时间线、编解码、GL/解码器算法保持上游版本。

源码发行包不含任何签名私钥、配对密钥或第三方视频内容。FFmpeg 和 libplist 为静态链接；所需源文件、补丁及重新构建/重新链接说明均随同提供。完整 GPL 文本见根 LICENSE，依赖许可副本在 assets/licenses 及原始归档。
