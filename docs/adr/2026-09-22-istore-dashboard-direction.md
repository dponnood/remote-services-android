# ADR：iStoreOS/NAS 风格主页面与远程网络菜单

## 状态

提案，等待研究笔记和第一版 UI 合并后进入实现阶段。

## 背景

当前首页以服务卡片为中心，系统信息和远程线路配置分散在服务编辑页。iStoreOS 基于 OpenWrt/LuCI，既有 QuickStart API 和 ubus/RPC 能提供设备状态，也有网页端完成权限、CSRF 和 UCI 提交。

## 决策

### 导航

采用四个一级入口：

1. **首页**：iStore/NAS 风格动态仪表盘。
2. **服务**：保留服务卡片、分组、登录和 WebView 多窗口。
3. **远程网络**：独立管理 LAN/WAN 地址、可信 Wi-Fi、自动选路、探测与回退策略。
4. **设置**：升级、日志、错误反馈和应用偏好。

在手机上使用单栏；宽窗口使用两列卡片，不强制锁定方向。

### 首页数据层

增加独立的系统信息契约，不让 Compose 直接解析 JSON：

- `DeviceIdentity`：型号、主机名、固件、内核、架构。
- `RuntimeMetrics`：运行时间、负载、CPU 占用、温度、内存。
- `StorageMetrics`：挂载点、总量、已用、可用、文件系统。
- `NetworkMetrics`：接口、地址、链路状态、上下行速率、累计流量。
- `ServiceHealth`：已配置服务的线路和可达性。
- 每个字段携带 `availableAt`、`route`、`source` 和 `error`，没有数据时显示“未获取”，不使用虚构默认值。

优先读取公开 QuickStart 只读接口；设备不提供该接口时降级到 LuCI/ubus 可用字段；两者都不可用则只展示服务健康状态和“打开网页管理”入口。

### 编辑能力

第一阶段不在 App 内复制全部 LuCI 表单。所有写操作先通过同一已认证 WebView 打开精确的 LuCI/iStore 编辑页，保证和网页端行为一致；原生写操作必须逐项确认 API、CSRF、权限和回滚后再增加。

### 安全

- 只对用户配置的精确 origin 发起请求。
- 只从当前 WebView/会话获得 Cookie，不把 Cookie 或密码写入日志。
- 写操作二次确认，显示目标、当前值和失败提示。
- LAN 失败只回退 WAN 一次；避免循环和频繁轮询。

## 研究依据（待研究笔记补充精确版本）

- OpenWrt ubus：`system board`、`system info`、`network.interface.*`、`iwinfo`、`service` 等对象。
- iStore API：`/cgi-bin/luci/admin/store/installed`、`status`、`get_block_devices`，以及 QuickStart `/cgi-bin/luci/istore/system/*`、`network/*` 只读端点。
- iStore 安装/升级/卸载与 UCI 写入必须保留 CSRF token、session 和网页端权限语义。

## 验收

- 无 iStore 服务时首页能明确提示配置入口，不显示 0%/0°C 等假数据。
- API 不可达、部分字段缺失、LAN/WAN 切换、登录失效和 WebView 崩溃都有可解释状态。
- 竖屏、宽屏、字体放大、深色模式、减少动态效果均不丢失数据。
- 所有真实写操作都能在网页端完成，并能从 App 返回首页继续查看状态。
