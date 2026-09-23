# core:logging

`core:logging` 是远程服务的本地诊断边界，供 App 和设置页使用。

## 数据与容量

- `AndroidLogRepository` 写入应用私有目录 `filesDir/remote-services/logs/app.jsonl`，默认上限 512 KiB。
- 超过上限时只保留末尾较新的完整记录；清除操作只删除本地日志，不影响服务、Cookie 或 Keystore 凭据。
- 每条记录包含 UTC 时间、级别、事件代码、脱敏消息、有限上下文和可选异常类型。
- `LogSanitizer` 在写入前移除凭据键值、URL、私网/本地 IPv4、IPv6（包括 `::1`、`fc00::/7`、`fe80::/10`）和控制字符；上下文值最多 256 字符，消息最多 2,048 字符。

## 错误反馈

`FeedbackService.prepare()` 仅在用户点“错误反馈”后调用，生成最多 200 条脱敏日志的 JSON payload；设置页随后展示数量、大小和预览，用户再次确认后才调用 `upload()`。

默认地址是 `https://app.dponnood.xin/feedback`，但由 `FeedbackConfig.endpoint` 配置。`HttpsFeedbackClient` 只接受 HTTPS，使用 Android/Java 默认证书链，不安装绕过校验的 TrustManager；请求限制为 512 KiB、10 秒连接超时、15 秒读取超时，临时网络/5xx/408/429 最多重试 3 次。匿名安装 UUID 仅存于应用私有 SharedPreferences。

## 事件代码约定

建议使用稳定的大写代码，例如：

- `WEB_LOAD_FAILED`
- `ROUTE_RESOLVE_FAILED`
- `UPDATE_CHECK_FAILED`
- `UPDATE_INSTALL_FAILED`
- `FEEDBACK_UPLOAD_START`
- `FEEDBACK_UPLOAD_SUCCESS`
- `FEEDBACK_UPLOAD_FAILURE`

事件消息不能直接拼接服务 URL、Cookie、Authorization、令牌或密码；即使误传，写入前仍会经过脱敏。
