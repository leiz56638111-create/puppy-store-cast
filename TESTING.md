# 1.0.0 验证入口

构建：`bash build-local.sh`，另运行`./gradlew :app:assembleDebugAndroidTest`。

专用API31 ARM64模拟器：`python3 tests/run_airplay_video.py --serial emulator-5560 --adb /path/to/adb --output verification/airplay`，默认运行AirPlayIntegrationTest；加`--test cn.local.livingroomcast.CombinedModeTest`检查一图标、模式往返、Home/恢复。测试工具会在专用模拟器建立AndroidWifi。

DLNA真实媒体回归的Instrumentation为PlaybackInstrumentation、QualityInstrumentation、StandbyInstrumentation；合成媒体服务见tests/serve_playback_media.py。JDK协议/格式/画质检查分别为tests/test_protocol.py、test_format_probe.py、test_quality.py，需JAVA_HOME。

原生库的地址/HLS真实输出、H.264注入、音频格式/Oboe启动与发现检查，不能替代真实iPhone/iPad加密握手、原生ALAC/RTP听辨、每个App兼容、电视花屏、游戏延迟或长期同步测试。测试计数与最终APK哈希记录在交付验收JSON；历史测试保留在下文，涉及旧mirror的记录不代表1.0.0当前内核。

---

# 测试说明

## 本次开源整理

2026-10-19：采用 GPL-3.0-only，整理公开说明和构建资料，不改变接收协议、播放器逻辑、版本号、界面或本地签名私钥。


本次在排除原签名私钥和本机配置的源码副本中复验：Gradle Wrapper 首次下载与校验通过，自动生成本地调试密钥，assembleDebug / lintDebug 通过（0 个错误、10 个警告）；29 项协议检查、10 项格式检查通过。复用了已缓存的 Android 构建依赖，未复验全新机器首次下载全部 Maven 依赖。未重新执行安卓模拟器或真机播放验收。

## 可独立运行的核心检查

依赖 Python 3 和 JDK 17 / 21，无需 Android 模拟器。设置 JAVA_HOME 后，逐个执行：

```sh
python3 tests/test_protocol.py
python3 tests/test_format_probe.py
```

协议测试使用本地回环服务和测试控制器，覆盖 SSDP、XML / SOAP / GENA、动作参数、事件订阅等，历史结果为 29 项通过。会临时使用本机 UDP 1900；若已有服务占用，请在空闲测试环境运行。

格式检查使用合成响应，覆盖 HLS / DASH / MP4 / FLV、误标格式、HTML / JSON 错误页、重定向与有限读取，历史结果为 10 项通过。所有测试令牌均为虚构文本，不是平台凭据。

输出写入 build/，不应提交生成的日志。

## 安卓手工回归

`tests/test_android.py` 保留了开发阶段的 Android 11 / API 30 模拟器回归脚本，历史结果为 22 项通过。它需要专用模拟器、ADB、外部合成 HLS 服务和开发时的目录结构，**并非下载源码后即可直接运行的通用测试**。它没有进入默认核心测试流程，也不能代替真实电视验收。

复测建议：

1. 在目标电视确认设备发现、收到地址、画面与声音。
2. 检查暂停、继续、换视频、停止、返回与退出。
3. 使用有权限的媒体检查 HLS / DASH，及误标成 MP4 的 HLS。
4. 用合成 HTTP 服务检查 503 暂时故障、401 失效地址和错误页，不在公共 Issue 中提供真实令牌。
5. 单独记录连续两小时播放、网络中断恢复、声画同步和 4K / HDR 结果。

## 已有真机反馈

2026-10-15，用户在索尼 XR-85X95EK 上报告 Z视介、小红书、抖音可以投屏。发送设备为安卓平板，具体 App 版本未记录。属于该组合下的用户反馈，不代表所有版本、设备或直播频道都兼容。

## 截图

README 中的等待页已更新为 0.1.6-test 在 1920×1080 安卓模拟器上的实际截图。IP 地址为模拟器地址，不是真实家庭设备地址。

## 0.1.4-test 画质控制 · 2026-10-20

- 构建、Android Lint 通过；8 项档位模型检查、29 项协议检查、10 项格式识别检查通过。
- 原生安卓回归 26 项通过：实际解码尺寸、遥控器入口、360p / 1080p 切换、暂停保留选择、4K 档位与 3840×2160 解码、恢复自动、固定片源提示、新投屏重置、过期会话拒绝及停止清理。
- 测试环境为 Android 11 / API 30 ARM64 模拟器；画面是合成纯色 H.264，2fps，非真实直播节目。4K 解码不代表在索尼真机上验证了 4K / 60fps 或 HDR。
- 真实 Z视介、小红书、抖音是否提供多分辨率投屏地址仍待用户复测。

### 重现原生画质回归

需要 Android Emulator、ADB、Python 3 和系统 FFmpeg（带 libx264）。生成测试素材的工具和测试 runner 均在仓库中，素材不入库。

```sh
python3 tests/make_quality_media.py .test-media
python3 -m http.server 18099 --bind 127.0.0.1 --directory .test-media
```

保持上述媒体服务运行，在另一个终端完成本地签名生成与应用构建，再编译测试程序：

```sh
bash build-local.sh
./gradlew assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w cn.local.livingroomcast.test/cn.local.livingroomcast.QualityInstrumentation
```

此 runner 使用 Android Emulator 的 10.0.2.2 访问宿主媒体服务；不是可直接用于真实电视的测试脚本。只连接一个测试模拟器，或用 adb -s 指定目标。模拟器需联网；历史 1920×1080 / density 320 配置中已完成遥控器与截图检查。若测试设备不支持给定 4K 格式，该档位应被过滤，runner 将跳过 4K 解码断言并记录结果。

## 0.1.5-test 覆盖层优化 · 2026-10-20

构建与 Android Lint 通过，38 项原生安卓回归检查通过。新增覆盖：正常播放立即隐藏、周期更新不再弹出、方向键唤出、连续操作刷新两秒计时、两秒后收起、暂停保留、恢复播放收起、菜单超过两秒不关闭、新会话不被旧弹窗回调唤出、报错提示持续可见。原有清晰度选择及会话隔离也在同一 runner 中通过。

测试发现并修复旧清晰度弹窗异步关闭回调会再次唤出新视频控制层的问题。测试场景还包含合成 HTTP 404 错误，确保失败状态仍能看见信息。

本次产品逻辑只改 MainActivity 的显示与弹窗生命周期；其他应用代码仅更新版本号（0.1.5-test / versionCode 6）。没有继续排查真实 App 清晰度问题，也没有重跑未改变的协议 / 格式独立检查。原生测试环境及合成媒体条件与前节相同，仍不能代替索尼真机验收。

## 0.1.6-test 后台待机 · 2026-10-20

构建与 Android Lint 通过（0 错误、11 警告）。Android 11 / API 30 ARM64 模拟器中，28 项后台待机检查与 38 项原生画质 / 信息层回归检查通过；29 项协议、10 项格式、8 项档位模型独立检查通过。

后台检查通过真实 HTTP / SOAP 与局域网组播 SSDP 执行，覆盖：Home 后无绑定客户端时仍能发现、收到后台 Play 后等待可见播放界面、不授权时不隐形播放、后台请求可被 Pause / Stop 取消、手动打开后播放、Home 结束当前视频而继续待机、授权后自动打开、关闭 Activity 后继续发现、通知退出关闭端口并移除常驻通知。

另做进程回收模拟：待机进程被终止后，系统重建前台服务，HTTP 设备描述恢复（3 项检查）。这是模拟器上的进程终止，不是强行停止、关机或真实电视厂商的后台清理验收。

复现待机检查时，先按前节准备 10.0.2.2:18099 的合成媒体服务，然后选择独立 runner（默认仍为原画质 runner）：

```sh
./gradlew assembleDebug assembleDebugAndroidTest -PnativeRunner=cn.local.livingroomcast.StandbyInstrumentation
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w cn.local.livingroomcast.test/cn.local.livingroomcast.StandbyInstrumentation
```

此测试会在专用模拟器中临时允许 / 禁止本应用的 SYSTEM_ALERT_WINDOW AppOp，结束时恢复默认值；不要直接对日常使用的真实设备执行。真实索尼电视的授权入口、后台自动打开、长时间待机、待机功耗、关屏 / 深度休眠、升级后的厂商限制仍待实测。没有继续排查此前暂缓的真实 App 单档清晰度问题。


## 0.1.8-test 合并版验证边界（2026-10-24）

将 alpha2 原生镜像库加入 0.1.7 DLNA 宿主，保留 APK 身份并进行互斥模式切换。52 个镜像单元检查、Android 12 ARM64 的 40 个镜像／合并检查、61 个播放控制、38 个画质／HUD、28 个后台待机回归，以及 29／10／8 个协议／格式／画质模型检查通过。镜像测试源码与原 DLNA 自定义 Instrumentation 入口共同保留。

新增实际 Home 键离开镜像后停止服务，启动主入口恢复 DLNA；重复切换确认旧 HTTP 端点与通知释放，并保留 UUID。修复快速返回时 onStart 不触发而未重绑定的问题，以 onResume 恢复；旧故障证据在本地开发工作记录中保留。AirPlay／RAOP 发现使用真实组播与原生套接字；音视频数据为合成流，未替代真实苹果设备握手与长时间同步验收。

主应用最低 API 23；镜像库依赖 API 24 ARM 原生组件，由宿主入口与镜像 Activity 的运行条件检查保护。API 23 保护仅单元验证，Android 6.0 与 32 位 ARM 实际运行待测。两种接收模式不同时运行；AirPlay 仍限前台，后台待机为 DLNA 能力。

较小触摸屏 960×540、density 240 下另复测 3 个合并生命周期／模式检查与 61 个播放控制检查。主等待页通过原 ScrollView 保留完整入口，小屏需向下滚动显示 AirPlay 按钮；镜像横向按钮行也可滚动。

最终打包给宿主显式指定与镜像库相同的 NDK 28.2.13676358，移除原生调试信息中的本机路径。两个 ABI 去除调试信息前后所有 SHF_ALLOC 段（实际加载的代码与数据）一致；最终 APK 另重跑镜像／模式与播放控制检查，交付校验拒绝本机路径或签名私钥。


## 1.0.1 夜色界面与 AirPlay 后台待机（2026-10-29）

本轮将旧的“Home停AirPlay／图标恢复DLNA”断言改为：Home保留AirPlay、图标恢复当前模式、显式切换才停止旧协议。AirPlayIntegrationTest覆盖实际MP4／HLS／反向HLS、镜像持久Surface、遥控器、Bonjour，以及无授权手动恢复、有授权自动打开、后台镜像显示重挂、音频输出门控、未展示超时、Stop取消、通知退出。CombinedModeTest覆盖版本／单Launcher、模式恢复与重复切换。

先执行 `bash build-local.sh`，再构建 `:app:assembleDebugAndroidTest`，使用专用模拟器运行：

```sh
python3 tests/run_airplay_video.py --serial emulator-5560 --adb /path/to/adb --output verification/airplay-1.0.1
python3 tests/run_airplay_video.py --serial emulator-5560 --adb /path/to/adb --output verification/modes-1.0.1 --test cn.local.livingroomcast.CombinedModeTest
```

测试会改变专用模拟器内本应用的悬浮窗AppOp，不应直接用于日常真实设备。AirPlay后台镜像为回调注入，不是完整iPhone握手；自动拉起结果仍需在Sony和目标Android版本验证。DLNA原生Playback／Quality／Standby入口保留。版本交付的实际测试数与证据见验收记录。


### 1.0.1 交付检查结果

Android 12 / API31 ARM64：17 个 AirPlay 检查、4 个合并模式／遥控导航检查，以及 DLNA 61 个播放控制、38 个画质／信息层、28 个后台待机检查通过。960×540／density240 小屏另通过 4 个模式／遥控导航与 61 个播放控制检查。

29 个协议、10 个格式、8 个画质模型及 2 个 AirPlay 单元检查通过；两个模块 Lint 均 0 错误（app 14 警告、airplay 25 警告）。已实际覆盖升级 1.0.0→1.0.1，保留 DLNA UUID 与已有 AirPlay 身份文件；模拟器内终止进程后，系统恢复待机，明确退出后不自动恢复。该进程终止不是系统设置中的“强制停止”。

原生六个共享库与 1.0.0 逐字节一致。后台镜像使用回调注入和实际 Surface 出图；真实 Apple／Sony、长时间待机、声画同步、耗电、32 位与 API23 运行仍待真机，不将模拟器结果写成全部 App 已兼容。
