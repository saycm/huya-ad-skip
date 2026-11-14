# 虎牙广告跳过模块

面向 LSPosed 的虎牙广告过滤模块，基于 libxposed API 102 开发，适配虎牙 **13.5.44**（versionCode `117129`）。模块拦截开屏广告、推荐列表广告和自动推广弹窗；用户点击奖励广告入口时，直接完成虎牙原有的奖励流程。

## 功能

- 拦截开屏广告，正常进入首页。
- 过滤推荐列表中的广告卡片，保留普通内容顺序。
- 拦截自动推广弹窗和直播间外普通广告请求。
- 跳过“看广告赚虎粮”等奖励广告：点击后不打开视频广告页，直接执行原完成、关闭和发奖回调。
- 奖励到账继续由虎牙原有业务流程处理；“直接领取”“看广告立即膨胀”等原有领奖选项可能保留。

## 兼容性

| 项目 | 版本或范围 |
| --- | --- |
| 目标应用 | 虎牙 13.5.44，versionCode `117129` |
| 模块版本 | 1.11，versionCode `12` |
| 模块包名 | `io.github.saycm.huya.skipad` |
| 作用域 | `com.duowan.kiwi` |
| Android | Android 10 及以上（API 29+） |
| 框架 | LSPosed，libxposed API 102 |

## 安装

1. 从 [Releases](https://github.com/saycm/huya-ad-skip/releases) 下载 APK。
2. 安装模块，并在 LSPosed 中启用。
3. 将作用域设置为虎牙 `com.duowan.kiwi`。
4. 强制停止虎牙并重新打开。

## 下载与源码

- [最新版本与安装包](https://github.com/saycm/huya-ad-skip/releases)
- [完整变更记录](CHANGELOG.md)

## 版本变动

### 1.11

- 拦截推荐页广告的动态卡片回填路径，避免广告在列表创建后写回普通卡片。
- 补充沉浸式推荐页及带页头页尾列表的广告过滤。
- 从首个 Activity 获取虎牙动态补丁后的 ClassLoader 并安装业务 Hook，修复 API 102 版本中 Hook 安装成功但未命中业务类的问题。

### 1.10

- 模块迁移至 libxposed API 102。
- 使用 `onPackageReady` 获取虎牙最终 ClassLoader 并安装 Hook。
- 使用 API 102 拦截链接口重写广告拦截逻辑。
- 使用 `META-INF/xposed` 模块元数据，移除传统 Xposed 清单声明。

### 1.9

- Application ID 更新为 `io.github.saycm.huya.skipad`。
- 适配虎牙 13.5.44（versionCode `117129`）。
- 覆盖开屏、推荐列表、自动推广和奖励广告流程。
- 最低支持 Android 10（API 29）。

### 1.8

- 增加开屏广告、推荐列表广告和自动推广弹窗拦截。
- 屏蔽直播间外普通广告请求。
- 保留奖励广告直接完成原领奖流程的能力。

完整版本记录见 [CHANGELOG.md](CHANGELOG.md)。

## 构建与测试

构建需要 Python 3、JDK 17、Android SDK Platform 36 和 Build Tools 36.0.0。

```powershell
$env:ANDROID_HOME = "C:\Android\Sdk"
$env:JAVA_HOME = "C:\Java\jdk-17"
python tools\build.py
```

运行回归测试：

```powershell
python tests\test_huya_api102_metadata.py
python tests\test_huya_loader_lifecycle.py
python tests\test_huya_display_ads.py
```

## 许可证

本项目采用 [MIT License](LICENSE)。
