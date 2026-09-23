# feature:settings

设置页由 `SettingsScreen` 提供，宿主负责导航和更新动作。

## 接入契约

宿主传入：

- `currentVersionLabel`：建议格式 `versionName (versionCode X)`；
- `UpdateHostState`；
- `LogRepository` 与 `FeedbackService`；
- `onCheckUpdates`：必须调用现有 `UpdateHostCoordinator.manualCheck()`，因此不受每日启动检查节流影响；
- `onDownloadUpdate` 和 `onBack`。

页面包含在线升级、本地日志查看/清除、错误反馈三组操作。手动检测按钮在 `ui.checking` 时禁用并显示进度；结果明确显示当前版本、新版本或“未检测到更新/检测失败”。

错误反馈流程是双确认边界：第一次点“错误反馈”才读取最近日志并生成脱敏 payload；页面展示日志数量、大小和预览；只有再点“确认上传”才通过 HTTPS 发送。上传期间显示进度并禁用取消，完成后显示成功或失败消息。
