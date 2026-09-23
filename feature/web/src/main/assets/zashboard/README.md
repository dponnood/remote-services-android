# Zashboard 资源占位目录

此目录是未来放置 Zashboard 网页版构建资源的 APK 资源位置。当前仅包含 `index.html` 占位页，不含上游构建包，也不会连接路由器。

## 上游项目信息

- 源码：https://github.com/Zephyruso/zashboard
- 许可证：MIT License，详见本目录的 `LICENSE`。
- 运行方式：应用通过 AndroidX `WebViewAssetLoader` 在网页视图中加载此目录；OpenClash / Mihomo 接口流量应遵循 `feature/web` 中的本地回环网关约定。

替换占位页时，应将上游 `LICENSE` 和所需的第三方声明原样放在生成资源旁边。不要将接口密钥写入查询参数或提交到仓库的资源文件中。
