# 局域网快传 LanDrop

电视上装一个，手机浏览器打开一个地址就能把 APK / 图片 / 视频传过去，传完直接在电视上安装。不用数据线、不用网盘、不用扫码登录。

## 用法

1. 电视上打开「局域网快传」，屏幕会显示 `http://192.168.x.x:8888`
2. 手机连**同一个 Wi-Fi**，浏览器输入这个地址
3. 选文件 → 自动上传，进度条走完就出现在电视的文件列表里
4. 电视上选「安装」即可安装 APK（首次需要允许「安装未知来源应用」）

## 特性

- **二维码直达**：电视屏幕上直接显示上传页二维码，手机扫码即开，不用手输 IP
- **整个文件夹**：手机端可以选目录，按 `目录/子目录/文件` 相对路径原样落到电视上
- **断点续传**：4 MB 分块上传，每块带 `offset`；断网/息屏/关页面后重新选同一个文件，会从已收到的字节继续，不会重传
- **零第三方依赖**（QR 除外）：HTTP 服务是自己用 `ServerSocket` 写的，没有 NanoHTTPD / OkHttp；二维码用 `zxing-core`
- **原始流上传**：`PUT` 直接推字节流，服务端按 `Content-Length` 落盘，省掉 multipart 解析
- **前台服务 + WakeLock**：熄屏、切后台都能继续接收
- **TV 原生**：声明 `LEANBACK_LAUNCHER`，`touchscreen required=false`，遥控器可操作，minSdk 21
- 端口固定 `8888`，文件存在应用私有目录 `Android/data/top.ldxw.landrop/files/received`

## 检查更新与加速地址

- 电视端「检查更新」按 `update.json` 比对 `versionCode`，有新版本会弹框，下载后用系统安装器安装
- 更新地址固定为 GitHub Releases：`https://github.com/ldxw/lan-drop/releases/latest/download/update.json`
- **加速地址**可在手机上传页的「电视设置」里改（预设：直连 / gh-proxy.com / ghfast.top / ghproxy.net，也可自定义），保存后立即生效，电视端不用碰遥控器
- 默认加速地址：`https://gh-proxy.com/`；留空表示直连 GitHub
- 手机端「测试连通」会真的去拉一次 `update.json` 并回报耗时

## 接口（页面自己用，也可脚本调用）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 上传页面 |
| GET | `/api/files` | 递归列出已收文件（相对路径） |
| GET | `/api/stat?name=a/b.apk` | 查已收到多少字节，续传用 |
| PUT | `/upload?name=a/b.apk&offset=N` | 从第 N 字节写；不带 offset 或 offset=0 则从头写 |
| GET | `/d?name=a/b.apk` | 下载 |
| POST | `/install?name=a/b.apk` | 让电视弹安装框 |
| POST | `/delete?name=a/b.apk` | 删除（空目录会自动清掉） |
| GET | `/api/settings` | 读取电视设置（加速地址、自动检查） |
| POST | `/api/settings?proxy=..&autoCheck=1` | 修改电视设置 |
| GET | `/api/ping?url=<加速地址>` | 测加速地址能否拉到 update.json |

## 签名

`keystore/landrop-release.p12` 提交在仓库里，密码放在 GitHub 仓库 secret `KEYSTORE_PASSWORD`。
CI 每次构建都用同一把证书，所以新版本可以**直接覆盖安装**，记录不丢。
本地没有设置 `KEYSTORE_PASSWORD` 时会自动回退到 debug 签名（仅供自己编译验证）。

证书 SHA-256：`61:2E:AD:A8:90:F1:B1:B8:DA:A5:E0:24:77:9E:A6:4A:5F:00:5A:2E:15:43:65:82:C2:93:EB:B0:E7:98:CF:3B`

## 编译

推送到 `main` 分支由 GitHub Actions 自动构建，产物在 Actions 的 `landrop-apk` artifact；打 `v*` tag 会发 Release。

本地编译需要 JDK 17 + Android SDK 34：

```bash
gradle assembleRelease
```

## 说明

- 只在局域网内监听，没有鉴权，**不要把这个端口映射到公网**
- 电视上的「安装」走系统安装器，会弹确认框，这是 Android 的限制
- 接收目录是应用私有的，卸载应用会一起删掉
- 续传是按「相对路径 + 已收到字节数」判断的，改名再传会被当成新文件

## License

MIT
