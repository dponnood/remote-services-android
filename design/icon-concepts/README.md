# “远程服务”图标概念候选

本目录包含第一轮 6 个图标概念预览，全部用于方向选择，不代表最终图标。概念由 imagegen 内置工具生成，并以 PNG 形式保存，未直接接入 Android 资源。

第一轮已选择 06「极简抽象连接符号」进入细化比较；用户最终选择 C「蓝青橙缎带环」。A/B/C 三个细化候选及联系表如下：

- `variant-A.png`：蓝青连续环，中心留白最大，适合单色化。
- `variant-B.png`：蓝绿紫三角环，轮廓最几何，色彩对比最强。
- `variant-C.png`：蓝青橙缎带环，暖色节点更醒目，需继续验证深色启动器。
- 联系表：`selected-06-variants-contact-sheet.png`。

三套候选都按自适应图标安全区留出外圈。C 已按几何路径重绘并接入 Android 资源；A/B 仍然只是比较稿，不得作为正式启动图标。

| 编号 | 方向 | 识别语义 | 适配观察 |
| --- | --- | --- | --- |
| 01 | 家庭网络与远程连接 | 家庭/路由器连接云端，并带安全锁 | 语义完整但细节较多，需重绘简化 |
| 02 | 服务器与无线信号 | 服务器机柜 + Wi-Fi 信号 | 小尺寸识别强，适合 NAS/运维定位 |
| 03 | 服务卡片与连接节点 | 两张服务卡片和连续节点路径 | 最贴合服务管理首页，可演化为几何标志 |
| 04 | 内外网双通道 | 家庭路径与云路径汇聚到中心节点 | 能表达自动选路与回退，视觉记忆点明确 |
| 05 | 安全隧道 | 盾牌内的通道连接远端服务 | 安全感强，小尺寸需要减少透视细节 |
| 06 | 极简抽象连接符号 | 三个圆角环/条带形成连接标志 | 适合自适应图标和单色主题，需确认语义偏好 |

## C 正式重绘与资源

- 可维护 SVG 源：`design/icon-concepts/selected-06-C/remote-service-ribbon.svg`。
- 几何栅格脚本：`design/icon-concepts/selected-06-C/render-icon.ps1`；PNG 由该脚本从同一组路径生成，未把 AI 位图当作正式图标源。
- Adaptive Icon：`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml`。
- 前景、启动画面和 Android 13+ 单色前景：`app/src/main/res/drawable/ic_remote_service_foreground.xml`、`ic_remote_service_splash.xml`、`ic_remote_service_monochrome.xml`。
- 各密度 PNG：`app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher*.png`。
- 512 发布图与深浅启动器/圆形裁切预览：`design/icon-concepts/selected-06-C/rendered/`。
- `AndroidManifest.xml` 已改用 `@mipmap/ic_launcher` / `@mipmap/ic_launcher_round`；旧占位 drawable 已移除。
- 主体几何范围约为 22..86/108，适配 Android adaptive icon 安全区；颜色为深海军蓝背景、青、蓝、橙三条缎带和低强度白色高光。

## 生成信息

- 生成日期：2026-09-21
- 模式：内置 imagegen（预览资产）
- 输出：1024×1024 PNG（概念图，部分方案含透明边缘）
- 约束：无文字、无第三方 Logo、无水印；适配 48dp 小尺寸时需二次简化
