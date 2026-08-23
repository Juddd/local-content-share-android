# 内容中转 Android

Local Content Share 的原生 Android 客户端，支持文字、文件、链接、Markdown 记事本、本地优先离线队列、稳定 UUID、结构化 SSE 和浏览器设备中心。

## 配置服务器

首次启动必须输入完整服务地址，例如：

```text
http://192.168.1.10:8084/
```

地址永久保存在 App 私有设置中，升级和重启后不会丢失。源码不包含任何个人 NAS 地址。

## 构建

需要 Android SDK 36、JDK 17+ 和 Gradle 8.11.1：

```bash
gradle :app:assembleDebug
```

Release 签名通过环境变量提供，不要把密钥或密码提交到仓库：

```text
CONTENT_TRANSFER_KEYSTORE
CONTENT_TRANSFER_STORE_PASSWORD
CONTENT_TRANSFER_KEY_ALIAS
CONTENT_TRANSFER_KEY_PASSWORD
```

## 设备中心

设置中的“设备中心”可查看网页浏览器设备并进行重命名、远程关闭并锁定和解除锁定。浏览器身份使用服务端随机 Cookie，不使用浏览器指纹。

服务端源码与 Docker 镜像位于 [Juddd/local-content-share](https://github.com/Juddd/local-content-share)。
