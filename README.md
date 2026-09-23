# 远程服务 | Remote Services

远程服务是一款面向家庭网络与自托管用户的 Android 手机控制台，用来集中管理 iStoreOS / OpenWrt 服务、查看路由器状态，并操作 OpenClash。它不替代路由器，也不要求把管理流量交给第三方云平台：手机直接连接你配置的路由器和服务地址。

它主要解决手机管理软路由时的三类麻烦：

- **入口太分散**：把服务列表、可自定义的状态卡片和网页管理入口放在同一个适配手机的界面，减少来回找书签、切换页面和在小屏网页中操作。
- **内外网地址难切换**：服务可配置内网与公网地址；应用依据可信 Wi-Fi 和连通性选择可用线路，内网不可达时回退公网，避免每次离家或回家都手工改网址。
- **状态与控制割裂**：在兼容的 iStoreOS / OpenWrt 与 OpenClash 环境中，通过设备提供的 LuCI、ubus 和 Clash 兼容接口读取状态，并在应用内查看策略组、切换节点和测试单个节点延迟。

连接设置与可保存的登录凭据留在本机，凭据由 Android Keystore 保护；是否能复用网页登录状态取决于服务端认证方式，并非对任意网页都能保证免登录。只要手机还能访问路由器内网，查看和管理本地服务不需要互联网；在线更新和可选日志反馈需要互联网。版本 0.3.7 已在手机上安装并验证；不同路由固件、接口版本和认证方式可能导致具体功能表现不同。

Remote Services is an Android control panel for home-network and self-hosted services. It brings iStoreOS / OpenWrt service access, router status, and OpenClash controls into one phone-friendly app. It does not replace your router or route management traffic through a third-party cloud: the phone connects directly to the router and service endpoints you configure.

It targets three common pain points when managing a router from a phone:

- **Scattered entry points:** keep service links, customizable status cards, and web administration pages together instead of hunting through bookmarks and switching between cramped mobile pages.
- **Changing LAN/WAN addresses:** configure private and public addresses for a service; the app uses trusted Wi-Fi and reachability checks to choose a route, falling back to the public endpoint when the LAN endpoint cannot be reached.
- **Disconnected status and controls:** on compatible iStoreOS / OpenWrt and OpenClash setups, read status through available LuCI, ubus, and Clash-compatible APIs, then inspect proxy groups, switch nodes, and test an individual node's latency.

Connection settings and any saved credentials stay on the device; Android Keystore protects credentials. Reusing a web login depends on the service's authentication method, so universal password-free access is not guaranteed. Router access still works without internet when the phone can reach the LAN; online updates and optional log feedback require internet. Version 0.3.7 has been installed and verified on a phone; exact behavior can vary with router firmware, API availability, and authentication method.

## 功能概览 | Features

- iStoreOS / OpenWrt 服务列表与内嵌网页操作。
- 根据可信 Wi-Fi 与连通性在内网、公网地址间选择线路；网络不可达时可回退到另一线路。
- 系统概览及可自定义卡片；通过设备可用的 LuCI / ubus / OpenClash 接口读取状态。
- OpenClash 策略组节点查看、切换，以及逐节点延迟测试。测速采用 Mihomo 单节点 delay API，不批量测试自动策略组。
- 本地保存连接设置；凭据使用 Android Keystore 保护。
- 可选日志反馈和在线更新。发送反馈前由用户确认；在线更新会校验 APK 后交给 Android 系统安装器确认。
- 无互联网时，只要手机仍能访问路由器内网，本地服务管理仍可用；在线更新和日志反馈需要互联网。

## 项目结构 | Modules

| 路径 | 职责 |
| --- | --- |
| `app` | Android 启动、依赖组装及系统集成 |
| `core/model` | 服务配置与跨模块模型 |
| `core/database` | 本地配置和卡片偏好 |
| `core/security` | Keystore 凭据保护 |
| `core/network` | 网络选择、路由与 OpenClash API |
| `core/update` | 更新清单、下载与安装流程 |
| `core/logging` | 结构化日志 |
| `feature/services` | 服务侧栏、主页、卡片仓库和控制卡片 |
| `feature/web` | 安全 WebView 与网页会话 |
| `feature/settings`, `feature/update` | 设置、日志反馈和更新界面 |
| `adapter/luci` | LuCI / iStoreOS 登录与接口适配 |

## 构建与测试 | Build and test

需要 JDK 17、Android SDK Platform 36 和 Android 36 Build Tools。最低 Android 版本为 API 26；应用以 API 36 编译和设定目标版本。

Requires JDK 17, Android SDK Platform 36, and Android 36 Build Tools. Minimum Android version is API 26; the app compiles and targets API 36.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug :app:assembleDebug
```

Release APK 必须由维护者使用独立保管的签名密钥构建；签名密钥和密码不在仓库中。开发者可以先构建 Debug APK。不要复用维护者的生产签名材料。

Release APKs must be signed by the maintainer with separately protected signing material, which is not included in this repository. Developers can build a Debug APK first. Never reuse the maintainer's production signing keys.

## 发布包 | Downloads

签名安装包和版本说明发布在 GitHub Releases。在线更新节点由维护者单独托管；请只从可信发布页安装 APK，并核对发布页提供的 SHA-256。

Signed APKs and release notes are published in GitHub Releases. The maintainer also hosts the in-app update feed separately. Install APKs only from a trusted release page and verify the published SHA-256.

## 隐私与网络 | Privacy and networking

- 服务地址、Wi-Fi 规则和其他设置保存在本机。
- 登录凭据由 Android Keystore 加密保护。解锁应用和 WebView 行为依 Android 系统版本及页面认证方式而异。
- 应用内的日志反馈仅在用户预览并主动提交后上传。此发行版的默认接收地址是维护者的 `app.dponnood.xin` 服务；反馈可能包含设备/应用诊断信息，请提交前检查内容。
- 内网连接只在用户配置的服务地址上发起。不要把路由器口令、控制器密钥、反馈日志或签名材料提交到 GitHub Issue。
- 此仓库不包含 Zashboard 的构建产物；`feature/web/src/main/assets/zashboard` 目前只是占位页和上游许可说明。

Service endpoints and Wi-Fi rules are stored locally. Credentials are protected with Android Keystore. Log feedback is uploaded only after the user reviews and explicitly submits it; the current build's default recipient is the maintainer's `app.dponnood.xin` service, and diagnostic information may be included. Review feedback before sending. Do not post router passwords, controller secrets, feedback logs, or signing material in GitHub issues.

## 许可 | License

本仓库原创代码和资源采用 MIT License，详见 [`LICENSE`](LICENSE)。依赖库和明确标注的第三方文件仍受各自许可证约束。

Original code and assets in this repository are provided under the MIT License; see [`LICENSE`](LICENSE). Dependencies and explicitly identified third-party materials remain subject to their respective licenses.
