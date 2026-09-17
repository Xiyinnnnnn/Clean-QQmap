# Clean-QQmap

使用腾讯地图/导航 SDK 实现的国内导航 App，**无广告**。
驾车 / 骑行 / 步行，真实 GPS，无模拟导航，语音走系统 TTS。

## 依赖

| 依赖 | 版本 |
|---|---|
| com.tencent.map:tencent-map-nav-sdk | 5.3.4.0 |
| com.tencent.map:tencent-map-vector-sdk | 4.4.5.8 |
| com.tencent.map:tencent-map-nav-surport | 1.0.2.8 |
| com.google.code.gson:gson | 2.8.5 |
| com.android.support:appcompat-v7 | 28.0.0 |
| com.android.support.constraint:constraint-layout | 1.0.2 |

JDK 17 · Android SDK 34 · Gradle 8.7 · minSdk 24 · targetSdk 30 · arm64-v8a

## Key 申请

1. 到 [腾讯位置服务](https://lbs.qq.com/) 注册并实名认证。
2. 控制台 →「应用管理」→ 创建应用 → 添加 Key。
3. 勾选服务：**地图 SDK / 导航 SDK / 定位 / WebServiceAPI**。
4. Key 绑定「包名 + 签名 SHA1」：
   - 包名：`com.xiyin.navi`
   - SHA1：`keytool -list -v -keystore 你的.jks -alias 你的别名`
5. 装 App 后首次启动填入 Key（长按底部状态行可更换）。

## 免责声明

本项目为个人学习交流项目，非官方产品，与腾讯公司无关。
使用腾讯地图/导航 SDK 须遵守[腾讯位置服务条款](https://lbs.qq.com/)。
因使用本软件产生的一切后果由使用者自行承担。

## 许可

[MIT](LICENSE)
