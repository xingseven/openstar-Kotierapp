本次更新内容：

- App Logo 更换为新的 Kotier 图标。
- 在线节点列表新增三点菜单，可为每个节点单独选择设备图标。
- 节点图标与添加节点页面共用 WiFi、手机、台式机、笔记本、服务器、NAS 选项，并会保存选择结果。
- 检测到新版本时显示完整更新说明，并提供“下载更新”和“浏览器下载安装包”两个入口。
- 设置页项目网站更新为 https://kotier.wotty.app。
- GitHub 项目地址统一更新为 https://github.com/sevencnup/wotty-kotierapp。
- 更新检查接口切换到 https://kotier.wotty.app/version.json，同时保留旧版客户端使用的 kotier.openstars.org 兼容入口。
- Release 构建改用固定正式签名，不再使用 Debug keystore。
- 更新检查增加 Android versionCode 比较，同版本名的修复包也能被识别为更新。
- 更新包本地缓存按 versionCode 区分，避免同版本名修复包复用旧缓存。

如果遇到签名不一致，请手动下载安装包并更新。

下载地址：[点击浏览器下载最新安装包](https://kotier.wotty.app/download/kotier-v4.2.46-4066-release.apk)
