# AirPlay 上游对照实验

独立安装包 `cn.local.livingroomcast.airplayreference`，版本 `0.4.0-upstream-alpha1`。不覆盖公开 0.1.8、旧视频实验 alpha4 或上游原包。打开接收，Home／退出停止，不开机自启。发现名“小狗便利店投屏·上游对照”。此模块不依赖旧 `:mirror`，一个进程只运行一个原生内核。

完整采用 [jqssun/android-airplay-server v0.0.36](https://github.com/jqssun/android-airplay-server/tree/v0.0.36) 的 JNI、UxPlay 接收、原生音频引擎、FFmpeg AAC/ALAC 后备解码、Oboe 输出、时间线缓冲、解码器选择、持久 Surface/GL 渲染、Media3 URL 播放、NSD、音量与 DACP 服务逻辑。保留上游默认自动分辨率、申请最高 60fps、AAC 开、ALAC 广播关、自适应音频缓冲、预约呈现关；申请帧率不代表实测显示帧率。

修改：独立身份（包含每次安装持久保存的独立接收器 MAC）、中文原生界面、触摸与遥控器焦点、进程内脱敏连接详情、退出停止、主线程视频控制、停服代次校验与播放器回调代次隔离；接续已有请求/会话防护补丁。原始 Compose/Hilt 界面、开机自启、后台接收、下载导出视频不进入此实验。`0010` / `0011` 补丁保留请求长度/类型检查、同会话新地址、延迟旧会话停止/控制拒绝，普通 master.m3u8 不误走反向协商。上游 0001–0009 均保留；接收/播放能力未故意削减。

这不是官方 APK 改名或导入 `.so`：所有原生依赖均由随附的固定源码构建。来源、许可证及改动见 [NOTICES.md](NOTICES.md) 与 `vendor/sources.json`。

## 构建

需要 Python 3.12+、Git、JDK 17/21、Android SDK 36 / build-tools 35.0.0、NDK 28.2.13676358、CMake 3.22.1、Make、Perl，配置 `JAVA_HOME`、`ANDROID_HOME` 后，在项目根执行：

```sh
bash build-reference.sh
```

Gradle 8.13 / AGP 8.11.1 / Kotlin 2.2.20。Java 依赖首次构建仍需下载。`prepare-sources.py` 校验归档哈希，展开源码并按顺序应用补丁；不下载原生二进制。OpenSSL 3.4.4 从本地归档校验后构建，FFmpeg 仅启用 AAC/ALAC 解码，无网络。Oboe 1.9.3 从源码构建。APK 含 arm64-v8a、armeabi-v7a；32 位只编译，未真机运行。开发者自行生成的本地签名不能覆盖作者签名的同包名版本。

## 验证

```sh
./gradlew :reference:assembleDebugAndroidTest
python3 tests/run_reference_video.py --serial emulator-5560 --adb /path/to/adb --output verification/reference
```

只使用专用模拟器；测试合成 MP4/HLS、反向 HTTP/FCUP、原生方向键、真实 Surface 像素、H.264 回调注入、音频引擎开关及退出。测试不包含真实苹果的加密握手、实际 App 兼容、真实网络抖动、ALAC 听辨、长期音画同步或电视花屏验收。真机按相册视频、同一 B站 App 内 AirPlay、同视频控制中心镜像顺序，与 alpha4、上游原包轮流比较。
