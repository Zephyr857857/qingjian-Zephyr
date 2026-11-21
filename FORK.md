# 青简 Qingjian —— Android 非官方修改版

> ⚠️ **非官方 Fork**：本仓库是 [qingjian-team/qingjian](https://github.com/qingjian-team/qingjian) 的个人修改版本，**与青简官方团队无关**，不代表、也不获得官方背书。本 fork 由 [Zephyr857857](https://github.com/Zephyr857857) 维护。官方发布渠道仅为[官网](https://qingjian.app/download)与[上游 GitHub Releases](https://github.com/qingjian-team/qingjian/releases)。

本分支在**上游 Android 预览版**基础上，针对**手机端输入法日常使用体验**做了一系列修复与优化：降低运行内存与按键延迟、修复译词/多语言切换、修复游戏键盘、补齐候选栏交互细节。修改集中于 Android 端，核心拼音/引擎逻辑仅做一处九键候选排序修正。

---

## 许可与合规（License & Compliance）

- 本项目沿用上游许可证：**GPL-3.0-or-later**。完整条款见 [`LICENSE`](LICENSE)，请勿删除或修改原版权声明。
- 依据 GPLv3 第 5(a) 条，30 个改动源文件均已添加修改声明头：`Modified by Zephyr857857 on 2026-11-21 (upstream: qingjian-team/qingjian @ 996350c). See FORK.md.`（Kotlin/Rust/Gradle 用 `//`，TOML/ProGuard 用 `#`，XML 在声明后用 `<!-- -->`）。两个自动生成锁文件 `Cargo.lock`、`tools/release/dicts.lock` 未加（会被工具重写）。
- 依据 GPLv3 第 6 条，本仓库提供**完整可编译源码**；发布 APK 二进制时，请同时附带对应源码或指向本仓库对应 tag 的源码链接。
- 依据 GPLv3 第 11 条，"青简 / Qingjian"名称与标识的权利不随许可证授予，故本仓库明确标识为非官方 fork；如需正式分发，建议改用独立名称与图标。
- 随包词库数据（`*.qj`，来源见 [`tools/release/dicts.lock`](tools/release/dicts.lock)）来自上游独立词库仓库，可能有其单独授权，分发前请自行核实。

**基线**：上游提交 `f7b9f1049d01d730963c5666c20842b3ff23fe62`
**最终稳定版**：`996350c396f6e3a0ba892973268c63ab4f48e32d`（后续实验性改动已全部回退）
**有效改动**：11 个提交，32 个文件，+702 / -104 行。详见 [`CHANGELOG.fork.md`](CHANGELOG.fork.md)。

---

## 本版改动一览

### 性能优化
- **运行内存**：收敛常驻内存、裁减构建产物；用 jemalloc 让空闲页尽快归还，压低 RSS 尖峰。
- **按键跟手度**：九键按键链路从约 **47ms 降到 15ms**（异步位图渲染 + 帧合并 + 量宽缓存）。

### 功能新增
- 「按回车键发送」开关。
- 点击候选词默认震动反馈（无独立开关）。
- 游戏键盘支持自由缩放、竖屏可用、游戏输入框轻引导（类搜狗小米定制版悬浮体验，未做独立悬浮窗）。

### 问题修复
- 译词丢失：随包补全 6 本释义表，学习语言切换（日/西/越/德）即时生效。
- 游戏键盘中文打不出、首次进游戏不悬浮。
- 组句后候选栏右端箭头误展开全部候选：改为「有候选展开更多、无候选收起键盘」。
- 首个字出候选栏时闪烁：改为同步渲染位图，消除空白帧。
- 中文 26 键大写键改为重输键（英文 26 键保留大写），重输键在联想候选态也能清空候选。
- 九键单字输入被"无译词单字拼凑的整句"顶掉首选：加整句可信度门槛与拼音对齐。

---

## 构建（Android / arm64）

环境要求：JDK 20、Gradle 8.14、Android SDK、NDK 29、Rust stable（含 `aarch64-linux-android` target）。

```bash
# 1. 拉取词库数据（按 dicts.lock 生成 data/generated/）
bash tools/release/data-fetch.sh

# 2. 构建 release APK
RUSTUP_TOOLCHAIN=stable \
RUSTFLAGS="-L native=<NDK兼容库目录>" \
ANDROID_HOME=<你的Android SDK> \
./gradlew :app:assembleRelease
```

产物：`apps/android/app/build/outputs/apk/release/app-release-unsigned.apk`，用 `apksigner` 签名后安装。
> 公开发布请务必使用自己的正式签名密钥，不要用临时测试签名。

---

## 已知限制
- Android 仍为上游**预览版**，功能少于桌面端。
- 本修改版仅提供 arm64-v8a 构建。
- 未做独立悬浮窗，游戏键盘为输入法视图内的悬浮化体验。
- 上游新增的置顶词/词频调节实验已回退，排序行为与稳定版一致。
