# 局域网快传 LanDrop

电视上装一个，手机浏览器打开一个地址就能把 APK / 图片 / 视频传过去，传完直接在电视上安装。不用数据线、不用网盘、不用扫码登录。

## 用法

1. 电视上打开「局域网快传」，屏幕会显示 `http://192.168.x.x:8888`
2. 手机连**同一个 Wi-Fi**，浏览器输入这个地址
3. 选文件 → 自动上传，进度条走完就出现在电视的文件列表里
4. 电视上选「安装」即可安装 APK（首次需要允许「安装未知来源应用」）

## 特性

- **零第三方依赖**：HTTP 服务是自己用 `ServerSocket` 写的，没有 NanoHTTPD / OkHttp
- **原始流上传**：手机端用 `PUT` 直接推字节流，服务端按 `Content-Length` 落盘，省掉 multipart 解析
- **前台服务 + WakeLock**：熄屏、切后台都能继续接收
- **TV 原生**：声明 `LEANBACK_LAUNCHER`，`touchscreen required=false`，遥控器可操作，minSdk 21
- 端口固定 `8888`，文件存在应用私有目录 `Android/data/top.ldxw.landrop/files/received`

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

## License

MIT
