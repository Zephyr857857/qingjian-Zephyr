# 更新日志（Android 非官方 Fork）

本文件只记录**相对上游 `f7b9f1049d01d730963c5666c20842b3ff23fe62` 的修改**。上游自身变更见 [`CHANGELOG.md`](CHANGELOG.md)。
时间跨度：2026-11-18 ~ 2026-11-21。按类别整理，附对应提交。

---

## 2026-11-21 — 稳定版定稿

### Fixed · 九键候选
- **`996350c`** 九键单字输入不再被"无译词单字拼凑的整句"顶掉首选。
  - 根因：整句候选只按词数判定，缺少可信度校验，导致 1~2 个无译词单字拼成的整句排到首位。
  - 修复：`converting.rs` 开放 `credible()`；`nine_key/conversion.rs` 增加整句可信度门槛与拼音对齐；新增回归测试 `engine/tests/nine_key.rs`。

### Revert · 实验性排序改动（全部回退，最终行为等同 `996350c`）
- `41294ec` / `24ba4c8`：九键选过词前置、常用字选 3 次置顶 —— 已回退。
- `d3964bd` / `c223d59`：候选长按置顶 + 词频温和上调 —— 已回退。
- `855741f`：统一回退到 `996350c`，作为本 fork 的最终稳定版。

---

## 2026-11-20 ~ 11-21 — 候选栏交互

### Added · 26 键重输键与箭头双态
- **`77d14fc`** 中文 26 键（拼音/双拼）大写键改为重输键，英文 26 键保留大写键；候选栏右端箭头改为双态：有候选时展开更多候选，无候选时收起键盘。

### Added · 重输键清空候选
- **`2731a6d`** 重输键在联想候选状态下也能清空候选（`Key.Clear` 在 composing/completing 时执行 `engine.clear()`）。

### Fixed · 首字候选栏闪烁
- **`7b0bb95`** 首个字出候选栏时改为同步渲染位图，消除空白帧闪烁；后续帧仍走异步渲染。

### Performance · 按键链路提速（47ms → 15ms）
- **`91d21be`** 候选条渲染异步化 + 帧合并（`postOnAnimation`）+ 量宽缓存（`painter.rs` 中 `widths: HashMap<(String,u32), f32>`）；新增性能探针 `QjPerf.kt`。

### Fixed · 箭头收起键盘
- **`e057669`** 组句时候选栏右端箭头改为收起键盘，展开全部候选移至下滑手势。

---

## 2026-11-19 ~ 11-21 — 游戏键盘与译词

### Added · 悬浮游戏键盘
- **`019f6d8`** 游戏键盘支持自由缩放、透明背景、竖屏可用、游戏输入框轻引导；沿用输入法视图内悬浮化体验，未做独立悬浮窗。

### Fixed · 游戏键盘中文与译词/语言切换
- **`e48c582`**
  - 游戏输入框常声明不组句（TYPE_NULL），导致拼音字母直接上屏、中文打不出：游戏目标应用下强制走引擎组句。
  - 首次进游戏不悬浮：视图切换判断提前到 `onStartInput`，并用本次 `attribute.packageName` 判断，新视图本次即生效。
  - 译词丢失 / 学习语言切换不生效：随包打包全部释义表（`glossary-*.qj`），日/西/越/德切换即时生效，不再依赖设置页下载。

---

## 2026-11-18 — 功能开关与内存优化

### Added · 回车发送与候选震动
- **`0208c6f`** 新增「按回车键发送」功能开关；点击候选词默认震动反馈（无独立按钮）。涉及 `Settings`、`KeyboardSettingsActivity`、`Composer`、`CandidateBarView` 与中英文文案。

### Performance · 内存占用
- **`daff375`** 引入 jemalloc，让空闲页尽快归还系统，压低 RSS 尖峰；新增 `.cargo/config.toml` 与安卓端 JNI 初始化。
- **`8050b15`** 收敛常驻内存与构建产物体积：R8/ProGuard 规则、主题资源加载、渲染与文本层优化（共 12 个文件，+125 / -13）。

---

## 附：改动统计

| 类别 | 提交数 | 主要文件域 |
|---|---|---|
| 性能优化 | 3 | `Cargo.toml`、`.cargo/config.toml`、`painter.rs`、`CandidateBarView.kt`、`QjPerf.kt` |
| 功能新增 | 3 | `Settings.kt`、`GameFrame.kt`、`GameMode.kt`、`KeyboardLayout.kt` |
| 问题修复 | 5 | `Composer.kt`、`CandidateBarView.kt`、`nine_key/conversion.rs`、`converting.rs`、`build.gradle.kts`、`dicts.lock` |
| 回退 | 1（+4 被回退） | 排序相关 `ranking` / `engine` 文件 |

**合计（稳定版相对上游）**：11 个有效提交，32 个文件，+702 / -104 行。
