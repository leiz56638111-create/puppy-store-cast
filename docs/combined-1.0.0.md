# 1.0.0 DLNA / AirPlay 合并

2026-10-27，用户反馈独立上游对照版成功，明确要求与之前DLNA版本合并成1.0.0。本分支combined-1.0.0，从已验对照版e3a6f80派生；保留原对照分支/安装包。后按用户授权整理为GitHub v1.0.0发布，APK不变；发布入口见README。

`reference`模块转为`airplay`库，主包改依赖该库，移除旧mirror运行依赖。保留NativeBridge的JNI命名、上游接收/原生音频/解码器/GL/NSD、默认参数、会话与播放器修复；主要改名/版本和宿主生命周期。主应用沿用cn.local.livingroomcast、原签名，versionCode10、versionName1.0.0，一入口/一图标。DLNA继续最低API23，AirPlay按API24+与ARM检查后进入。统一Media3为1.11.1，以避免宿主与AirPlay解析/播放版本冲突。

默认DLNA；进入AirPlay前解绑/停止DLNA，AirPlay页在返回时先释放自身，再恢复宿主DLNA；保留快速往返只触发onResume时的重绑定。Home停止AirPlay，从图标重新打开默认DLNA。DLNA原有后台待机、触摸、进度、倍速、画质与成功后立即隐藏信息层均保留；不承诺两协议同时后台待机。

上游对照获用户整体可用反馈，未单独列举App/机型/系统/时长，不把该反馈扩写成全部App或长期同步验收。1.0.0合并后的真实Apple/Sony/实际App仍需复测；不宣称历史B站/无声/横条已逐项修好，不新增DRM、Chromecast/IJK或4K60/HDR保证。

自动验证以work/combined-1.0.0-*及交付验收JSON为准：覆盖升级保留DLNA标识，运行同一主APK的AirPlay回归、反复模式切换、DLNA播放/进度/倍速/画质/后台检查；另检查APK只有上游native引擎与一个Launcher。原AirPlay接收库libairplay_native.so两ARM ABI逐字节等于对照版；OpenSSL按同一固定源码/配置重新构建，不声明libcrypto文件可逐字节重复。

第一次模式回归失败时模拟器只有CELLULAR/eth0、没有Wi-Fi；接收端按现有政策拒绝将蜂窝网络当家庭局域网。测试工具建立AndroidWifi后重跑，不为模拟器修改产品网络策略。构建过程中补齐库对外接口的LifecycleService/StateFlow依赖可见性。
