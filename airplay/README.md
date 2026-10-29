# AirPlay 接收库 · 1.0.1

由用户反馈可用的独立对照版0.4.0-upstream-alpha1提取成库。固定上游jqssun/android-airplay-server v0.0.36 / a46cca0，来源见NOTICES.md，完整源档与哈希见vendor/sources.json。原对照包和分支airplay-upstream-comparison保留，未被覆盖。

宿主`:app`使用原包名cn.local.livingroomcast、原签名和versionCode11，版本1.0.1；本库没有Launcher，整个APK只有一个启动图标。首次默认DLNA；点AirPlay先停止DLNA。AirPlay按Home保留服务和发现，再开图标恢复AirPlay；左上方切到“视频App”明确停止AirPlay并恢复DLNA。“退出接收”停止待机。播放中Home结束当前会话，释放音视频，再继续等待下一次投送。原`:mirror`/`:direct`不进入最终APK。

原生音频时间线、FFmpeg AAC/ALAC后备、Oboe、解码器选择、持久Surface/GL、JNI与NSD核心沿用对照版。默认自动分辨率、最高申请60fps、AAC开、ALAC广播关、自适应音频缓冲与预约呈现关；申请值不等于实测显示帧率。请求/会话检查、旧播放器代次与首帧诊断保留。1.0.1新增后台待机、模式恢复、有效媒体请求拉起、无显示时的音频输出门控、延后地址播放器和15秒未展示超时。不会因普通发现探测自动拉起。前台服务恢复处理空Intent，并尊重用户退出／切换模式；没有开机自启或下载功能。原生协议／编解码／GL源码不变。

构建主应用：在项目根配置JAVA_HOME/ANDROID_HOME，执行`bash build-local.sh`。独立检查本库可执行`bash build-airplay-library.sh`。需要Python3.12+、Git、Make、Perl、JDK17/21、SDK36、NDK28.2.13676358和CMake3.22.1。原生源码离线校验/展开，Java依赖首次构建仍需网络。`build-airplay-library.sh`产出AAR，不能直接安装；主APK在app/build/outputs/apk/debug/app-debug.apk。

自动回归：`./gradlew :app:assembleDebugAndroidTest`后在专用API31 ARM64模拟器运行`python3 tests/run_airplay_video.py --serial emulator-5560 --adb /path/to/adb --output verification/airplay`。默认选择AirPlayIntegrationTest；`--test cn.local.livingroomcast.CombinedModeTest`验证宿主切换。回调注入与模拟器不替代真实Apple加密握手/实际App及长同步验收。

原对照版用户已确认整体可用，但未提供逐App、时长、机型及声画同步明细；不能据此宣称所有App/所有电视、4K60/HDR、横条花屏或历史无声均已解决。

后台使用、授权与实际设备待测边界见[1.0.1说明](../docs/1.0.1.md)。
