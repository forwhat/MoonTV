# MoonTV Android 手机端

这个 APK 支持互相独立的两种运行模式：

- 本地模式：MoonTV 页面、搜索聚合、豆瓣接口、图片与 HLS 代理随 APK 一起运行，
  收藏、历史、追更、搜索历史和片头片尾配置保存在本机。
- 服务端模式：仍加载 APK 内置的 MoonTV 前端，用户配置的 MoonTV 服务仅作为
  API 和数据后端；登录、视频源和用户数据由该服务负责。

切换模式不会复制、合并或删除另一种模式的数据。

## 当前功能

- APK 内置当前 MoonTV React 界面，启动不下载网页代码，也不显示登录页。
- 本地引擎只监听回环地址：本地模式使用 `127.0.0.1:17890`，服务端模式使用
  `127.0.0.1:17891`。两个 Origin 的 Cookie、缓存和 localStorage 完全隔离，
  均不对局域网开放。
- 两种模式使用同一套 APK 内置前端。服务端模式通过本地回环代理访问远端业务
  API，不再直接加载远端页面代码；图片和 HLS 由 APK 本机代理，不占用远端
  MoonTV 服务的媒体带宽。
- 本地模式复用 MoonTV 管理面板，可管理订阅、配置文件、站点设置、视频源和分类；
  本地配置只写入应用私有存储。
- 收藏、历史、追更和设置优先保存在 WebView 本地存储，应用重启后继续保留。
- 用户菜单和服务端登录页均可随时切换运行模式，并提供“重新加载”入口。
- Android 返回键在首页和登录页只显示“退出/重新加载”，不会退回旧模式或登录前页面。
- 豆瓣热门、推荐、榜单和简介遵循与网页端相同的数据源配置；默认通过 MoonTV
  服务端接口请求，选择 CDN 或自定义代理时由 APK 前端直接请求对应地址。
- 视频源搜索按完成顺序实时返回；已知视频源和资源 ID 时优先只请求该源详情，
  其他备用源在后台继续搜索。
- 搜索同一局域网内具备 MediaRenderer 或 AVTransport 能力的 DLNA 电视，不依赖电视厂商品牌白名单。
- 将播放页当前视频地址、标题和进度投放到指定电视，并在标准报文启动失败时自动尝试兼容 MIME 和空元数据。
- HLS 主播放列表会额外解析并尝试最高带宽的实际子播放列表，不经过手机代理。
- 远端播放、暂停、前后 30 秒、停止以及进度轮询。
- 投屏期间使用前台服务和系统通知保持控制会话。
- 将电视端进度同步回 MoonTV 播放记录，避免手机本地播放器的旧进度覆盖远端进度。

DLNA 实现使用 [UPnPCast v1.3.0](https://github.com/yinnho/UPnPCast/releases/tag/v1.3.0)，源码固定在 `android/vendor/upnpcast`，仅为采集诊断添加网络响应观察器。
应用代码通过 `DlnaController` 隔离第三方接口，后续可以按电视兼容性替换实现。

投屏失败时，可在弹窗点击“复制排障信息”或“查看详情”。报告包含 APK/Android
版本、电视描述与控制地址、每次兼容重试、SOAP 操作、HTTP 状态、电视返回的
UPnP 错误码/描述、网络异常和 HLS 子列表解析结果。报告仅在本机内存生成，
不会自动上传；不记录账号密码、Cookie 或请求正文，URL 的用户信息、查询参数
和片段会隐藏。设备名称、局域网地址、视频标题及 URL 路径保留用于排查。

0.3.2 修正 SOAP 多行模板在 XML 声明前残留空白的问题，发送按 UTF-8 字节计算
的 Content-Length；排障报告同时包含请求头摘要和 XML 声明起始位置检查。

## 构建

要求：

- Android Studio 或 JDK 17
- Android SDK 36

```bash
pnpm android:web:build
cd android
./gradlew assembleDebug
```

生成文件位于：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

安装到已开启 USB 调试的手机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

正式分发前需要配置独立的 release 签名；不要把签名文件和密码提交到仓库。

## 使用

1. 安装后默认进入本地 MoonTV 首页，无需登录或填写地址。
2. 搜索并打开视频；搜索和视频流仍需要互联网，但 MoonTV 页面本身不依赖远端服务。
3. 如需使用服务端账号与数据，在右上角用户菜单选择“运行模式”，切换到服务端模式
   并填写服务地址；登录使用 APK 内置登录界面，通过本地代理访问服务端。
4. 本地模式下可从用户菜单进入“管理面板”，维护订阅、配置文件、视频源和分类。
5. 点击播放器控制栏中的投屏图标，选择电视。手机和电视必须在同一个局域网。
6. 投屏成功后，底部控制条和系统通知都可以控制电视播放。

如果电视没有被发现，先确认电视已开启“DLNA 投屏/媒体投放”，并关闭路由器的访客网络或 AP 隔离。

## 当前边界

- 本地模式和服务端模式不做自动同步；需要跨设备数据时应使用服务端模式。
- 本机播放通过 APK 内的 HLS 代理解决跨域和相对分片地址；页面和媒体流均不经过
  远端 MoonTV 服务，服务端模式也只把账号、配置和用户数据等业务 API 发往远端。
- DLNA 仍发送原始播放源 URL；成功后由电视直接拉流，手机不需要持续转发。
- 兼容重试只调整 DLNA 控制报文，不会转换视频。电视仍必须能直接访问该地址，并支持对应容器、编码和 HLS 特性。
- 网页端的去广告 HLS Loader、请求头、Cookie 和弹幕不会自动传到电视。对于依赖这些能力的源，需要后续增加手机本地 HLS 代理。
- 自动下一集暂未接管电视端播放结束事件；切集后需要再次点击投屏。
- UPnPCast 自带的本地文件服务器不是完整的 HLS 代理，不能解决带鉴权请求头、动态 Cookie 或广告片段过滤问题。
- Android 17 / targetSdk 37 起，SSDP 需要新增 `ACCESS_LOCAL_NETWORK` 运行时权限；当前工程 targetSdk 为 36，不应提前声明该权限。

## 代码结构

- `MainActivity.kt`：运行模式切换、返回/重载处理、设备选择和网页消息桥。
- `LocalMoonServer.kt`：APK 内部静态页面、本地管理接口、远端 API 代理、流式搜索、豆瓣、详情及图片/HLS 代理。
- `src/android-web/`：复用 MoonTV 页面组件的 Android 静态前端入口。
- `scripts/build-android-web.mjs`：生成 APK 内置前端资源。
- `DlnaSessionService.kt`：后台会话、状态轮询、通知和播放控制。
- `DlnaController.kt`：项目内部的 DLNA 抽象。
- `UpnpCastController.kt`：UPnPCast v1.3.0 适配实现。
- `src/app/play/usePlayEngine.ts`：向 APK 提供当前媒体上下文，并接收电视端进度。

## Android TV 版

`tvapp` 是独立的电视安装包，包名为 `com.moontv.tv`。它直接加载 MoonTV
网页，所以界面、登录、收藏和播放记录仍由原服务端提供；电视端只负责
Leanback 启动入口、沉浸式全屏、遥控器焦点导航和播放器按键，不包含 DLNA。
同时保留普通 Android 桌面入口，便于厂商电视桌面和平板侧载后直接找到应用。

默认服务地址为 `https://moontv-58u.pages.dev`。在电视遥控器上按菜单键可以
修改地址或清理网页缓存；页面加载失败时也可以从错误页修改。

```bash
cd android
./gradlew :tvapp:assembleDebug
```

生成文件位于：

```text
android/tvapp/build/outputs/apk/debug/tvapp-debug.apk
```

安装到已开启调试的 Android TV：

```bash
adb install -r tvapp/build/outputs/apk/debug/tvapp-debug.apk
```

遥控器默认行为：方向键移动焦点；播放器获得焦点后，左右键快退/快进，
确认键播放/暂停，上键进入或退出全屏，下键进入播放器控制栏。进入全屏后
仍可使用相同按键；返回键优先退出全屏，然后返回上一页。
