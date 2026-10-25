# 小狗便利店投屏 · AirPlay 视频直投独立实验版

版本 0.3.0-video-alpha1，包名 `cn.local.livingroomcast.airplayvideo`。与公开主版 0.1.8、原独立镜像包分别安装，不覆盖主版。此分支尚未公开发布。

## 用法

1. 先退出电视上的其他 AirPlay 接收应用，打开“视频直投测试”。两端使用同一家庭局域网。
2. 在 iPhone／iPad 的视频 App 内点 AirPlay，选择“小狗便利店投屏·直投测试”。本测试包请保持电视接收界面打开，Home／离开会停止接收。
3. 收到可播放的视频后会显示播放器。手机端可暂停、继续、拖动、结束；电视可使用播放器原生控制。失败时查看“连接详情”，反馈具体 App、发送设备／系统、接收电视／系统、实际包版本和错误代码。

本版专门验证 App 内视频投送，不用于控制中心整屏镜像、音乐专用 AirPlay 或照片幻灯片；错误入口会给出提示。独立接收器名称用于区分，但多个本地接收进程仍可能争用协议／发现资源，测试前请先退出其他接收应用。

## 通用实现范围

不是按 App 名逐个写播放器。先覆盖可被电视访问的 HTTP(S) 视频地址、普通 HLS，以及发送端通过反向 HTTP／FCUP 协商的 HLS 播放列表路径。复用 Media3 播放、音频输出和控制，原生引擎处理协商／代理。

这一通用路径仍不能保证“大部分 App 都可用”：某些 App 不开放 AirPlay、使用额外授权／私有链路或受 DRM 保护。当前实现不提供 FairPlay 视频 DRM 解密，不承诺平台会员内容、所有 Safari／相册内容或各 App 均可播放。真实苹果设备与目标 App 必须分别验收。

最低 Android 7.0／API 24，提供 ARM64 与 32 位 ARM 两个 ABI。32 位仅编译，不等于真机运行验收。接收端只接收发送端提供的媒体，不提供节目源。

## GitHub 研究与来源

- [UxPlay](https://github.com/FDH2/UxPlay)：现有原生引擎来源之一，上游非镜像 HLS 文档仍限定部分 YouTube 路径；不能把上游声明当作我们软件的验收。
- [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)，固定提交 `a46cca0a6a1274e9e33c0e750f0f1c1eb1d881ea`：研究其视频请求解析、Android 播放器接入、就绪反馈与缓存快照。采用并调整其 `0001`–`0004` 原生补丁，保留在 vendor/android-airplay-server；包括播放列表修复、缓存同步／直播列表刷新，以及通用发送请求兼容。
- [seancheung/airplayer](https://github.com/seancheung/airplayer)，固定提交 `a6a3ab01393cab69201a458d174e15a90e5cc188`：对照其 Media3 直投和 UxPlay 接入设计；本轮没有移入其整套应用或沿用其兼容性宣传。

原生桥、独立中文界面、请求边界检查与测试由本项目修改。原项目 GPLv3、UxPlay 派生代码的 LGPL/GPL、OpenSSL／libplist／PlayFair 等通知与完整源码保留。见主工程 LICENSE、mirror/AIRPLAY-NOTICES.md 及 vendor 中的许可证。

## 构建

使用主工程相同的 JDK 17／21、Android SDK 36、NDK 28.2.13676358、CMake 3.22.1 和 Python 3.12+。设置 JAVA_HOME、ANDROID_HOME，从根目录执行 `bash build-direct.sh`。输出 `direct/build/outputs/apk/debug/direct-debug.apk`。

测试与真机兼容范围以随包说明及 `docs/airplay-video-alpha1.md` 为准。此实验版不包含对主版横向彩条问题的已验收修复，也不改变公开 0.1.8 的安装包。
