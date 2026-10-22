# 发布到 Steam 创意工坊

核对日期：2026-10-22。官方依据：[SPW 发布 Mod](https://saltplayer.com/workshop/publishing)。

本项目使用官方 Gradle 任务生成 `.spmod`，可以通过 Steam 版 SPW 的内置发布器上传。GitHub 开源与 Steam 工坊分发可以同时进行。

## 发布前

1. 下载 GitHub Releases 中的 `.spmod`，或运行 `scripts/build.ps1` 构建。
2. 在正式 SPW 中本地安装并启用，授权曲库读取与宿主修改，试导入测试歌单，检查追加、停用以及重启后的顺序。
3. 本版仅支持 Windows 版 SPW 1.19.0 的指定构建，说明中保留版本与权限限制。
4. 准备小于 1 MB 的 PNG、JPG 或 GIF 封面。

## 显示入口并上传

1. 启动 Steam，登录拥有 Salt Player 的账号。
2. 在 Steam 库右键 Salt Player → 属性 → 通用 → 启动选项，添加 `--workshop-publisher`；已有启动参数时保留原参数。
3. 从 Steam 启动 SPW，进入「创意工坊 → 创意工坊创作中心」。Steam 必须初始化成功。
4. 选择「发布新 Mod」，上传 `.spmod` 和封面，填写标题、简介，确认条款后发布，等待 Steam 确认完成。
5. 打开条目页面，按提示接受协议并设置可见范围。新条目默认私有；检查完成后再公开。

后续更新从「已上传 Mod」选择原条目，替换插件包并填写更新说明。上传结果未确认时先检查 Steam 条目，避免重复创建。移除启动参数并完全退出、重启后，发布入口会隐藏。

## 简介参考

按 TXT 文件中本地音频完整路径的行顺序，新建或追加歌单。支持中文路径、去重和逐行结果；插件运行离线，不修改音频，不上传清单。需授予曲库读取与宿主修改权限；宿主修改用于适配歌单内部接口。仅适配 Windows 版 SPW 1.19.0 指定构建，兼容检查失败会禁用导入。

源码与 MIT 许可证：https://github.com/vanvan-program/spw-txt-playlist

当前正式播放器验收尚待完成；上传前应先完成上面的试用检查。此仓库发布不代表已上传 Steam 创意工坊。
