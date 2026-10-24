# 小狗便利店投屏 · AirPlay 屏幕镜像实验

本分支已将 alpha2 镜像引擎改为 Android library，合入原 DLNA 应用 **0.1.8-test**（`cn.local.livingroomcast`）。只有一个安装图标，主界面点击“AirPlay 镜像（测试）”进入，返回后恢复 DLNA。历史独立 alpha2 包和分支仍保留。

## 范围

- 先在接收端主界面进入 AirPlay 镜像（测试），再在 iPhone / iPad 控制中心 → 屏幕镜像 → 小狗便利店投屏。
- 同一家庭局域网下的画面与声音接收，请求最高 1920×1080 / 30fps 的镜像参数；实际尺寸由发送端决定。
- 按源画面比例显示，竖屏保留黑边；支持触摸和遥控器查看连接详情、播放中恢复声音、重新接收、退出。
- Android 7.0 / API 24 以上；本次 APK 提供 arm64-v8a 和 armeabi-v7a。
- 请保持界面打开。Home 或切换应用会停止 AirPlay 服务；“返回视频投屏”恢复 DLNA 接收。重新打开应用默认进入 DLNA，需再次选择 AirPlay 入口。不提供后台自动唤起或开机自启。

不承诺视频 App 内的 AirPlay 按钮、单独音乐播放、DRM 内容、4K、低延迟游戏或跨网投屏。接收端触摸操作不会反向控制 iPhone。没有配对 PIN 的第一版仅用于可信家庭网络，不要做公网端口映射。

## 验证边界

本地已完成 49 项单元测试与 37 项 Android 11 ARM64 模拟器检查，包括真实 mDNS 发现、RTSP /info 和 OPTIONS 响应、配对密钥保存、合成 H.264 解码到 Surface、AAC 输出到 AudioTrack、画面比例与裁剪、真实触摸、Home 停服和重新打开恢复。

用户反馈 alpha1 基础投屏可以工作；iPad 的 YouTube 与相册视频切入 AirPlay 播放时，偶发有画面无声，断连重连恢复，并偶发自动加速。此反馈未区分 iPhone 的详细结果，型号及系统小版本也未提供。

alpha2 修复本地已复现的音频重置失效、查询连接误停媒体，以及视频突发数据按解码速度快放；增加音频协商／输出计数和“恢复声音”按钮。重复重置、静音保留、重新协商、视频节奏、控制连接与按钮路径通过模拟器检查，详见 [CHANGELOG.md](CHANGELOG.md)。本次修复是否消除用户的真实故障，仍待相同设备／操作复测；尚无完整的声画同步、长时间、旋转、休眠及多种苹果系统验收。

## 构建（合并版本）

需要 JDK 17/21、Python 3.12+、Android SDK Platform 36、NDK 28.2.13676358、CMake 3.22.1、make 和 Perl。设置 JAVA_HOME、ANDROID_HOME 后，从工程根目录执行：

```sh
bash build-local.sh
```

脚本使用 vendor/sources 中带 SHA-256 的官方 OpenSSL 3.0.22／libplist 2.6.0 完整源码，从源构建两种 ARM 架构的原生依赖。合并版输出为 `app/build/outputs/apk/debug/app-debug.apk`；镜像为 library，不单独生成安装包。macOS 已验证构建，Linux 工具链路径受支持但未实测，Windows 原生构建仍待适配。

单元检查与设备检查：

```sh
./gradlew :mirror:testDebugUnitTest :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w cn.local.livingroomcast.test/androidx.test.runner.AndroidJUnitRunner
```

Android 检查需要专用安卓设备或模拟器；部分编码／解码断言依赖平台 H.264／AAC 支持。原生 AirPlay 与合并模式检查源代码从 mirror/src/androidTest 与 app/src/androidTest 共同编入宿主测试包。真实苹果发送端仍需单独验收。

## 来源

镜像引擎及部分测试基于 AirTV / UxPlay，保留原作者及 GPL / LGPL 等通知，见 [AIRPLAY-NOTICES.md](AIRPLAY-NOTICES.md)。本项目新增中文界面及生命周期修改，原创部分 GPL-3.0-only。
