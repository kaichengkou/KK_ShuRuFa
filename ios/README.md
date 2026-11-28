# KK输入法 · iOS

iOS 版是一个容器 App 加一个键盘扩展。中文用的是和安卓版同一份 Rime 引擎代码（`app/src/main/jni/kk_rime/kk_core.cc`）、同一套雾凇拼音词库和覆盖配置，键盘布局直接读安卓的 YAML（`app/src/main/assets/layouts`），所以两边打字行为一致，改一处两边都生效。

## 第一版有什么

- 中文全拼：Rime + 雾凇拼音，整句输入、KK 自己的按键纠错器、Lua 过滤器（拆分、表情排序、静音词），学习用户词。
- 俄语 ЙЦУКЕН、英语 QWERTY：句首自动大写、双击空格输入句号、单词补全（安卓的 6.7 万俄语词、5.4 万英语词表）。
- 和安卓相同的按键手势：上下左右滑出副字符、长按出候选字符、长按删除连删、空格左右拖动移动光标。
- 符号面板（symbols.yaml 全部分类，成对符号光标自动放中间）。
- 浅色 / 深色，回车键按输入框显示「发送」「搜索」等。
- 容器 App：启用步骤、试打框、设置（语言顺序、繁体、自动大写、双空格句号、补全、振动、按键音）。

## 关键设计：词库在构建时编译

iOS 键盘扩展的内存上限只有几十 MB，在手机上编译雾凇词库（安卓上要一分钟、占几百 MB）不可行。所以：

1. 构建时在 Mac 上用 `kk_rime_deploy`（同一份 Rime 代码的命令行版）把词库编译好；
2. 只把编译结果 `build/*.bin` 和配置、Lua、OpenCC 打进键盘（词库源文件不打包），约 76 MB；
3. 键盘启动时不做任何部署（`kk_rime_startup_prebuilt`），直接读这些文件；用户学到的词写在键盘自己的容器里。

在 Linux 上实测这条路径：免部署启动、`nihao → 你好`、`womenyiqiqukandianying → 我们一起去看电影`，常驻内存（堆）约 8 MB，词库文件是只读内存映射，不算进键盘的内存额度。

不需要「完全访问」就能打字。完全访问只用于振动，以及读到容器 App 里改的设置（没有时按默认设置）。

## 目录

| 路径 | 内容 |
| --- | --- |
| `RimeKit/` | Rime 的 C 接口（Swift 调用），以及构建时编译词库的 `kk_rime_deploy` |
| `Core/` | 两个目标共用的 Swift：布局解析、词表补全、文本规则、设置 |
| `Keyboard/` | 键盘扩展：自绘按键与手势、候选栏、符号面板、Rime 封装 |
| `App/` | 容器 App（SwiftUI） |
| `Tests/` | 单元测试（布局、词表、文本规则、设置），在模拟器上跑 |
| `scripts/` | `build_rimekit.sh` 编译 librime；`build_rime_data.sh` 编译词库 |
| `project.yml` | XcodeGen 工程描述（`.xcodeproj` 不入库） |

## 构建

GitHub Actions 的 `Build iOS`（`.github/workflows/ios.yml`）在 macOS 上完成全部步骤：编译 librime（iPhone + 模拟器）、编译词库、模拟器上跑测试、打出**未签名**的 `KKShuRuFa-unsigned.ipa`。

本地（需要 Mac + Xcode）：

```bash
git submodule update --init --depth 1 app/src/main/jni/librime app/src/main/jni/librime-lua \
  app/src/main/jni/librime-lua-deps app/src/main/jni/librime-octagram app/src/main/jni/snappy rime-data/rime-ice
git -C app/src/main/jni/librime submodule update --init --depth 1 deps/leveldb deps/marisa-trie deps/opencc deps/spdlog deps/yaml-cpp
ios/scripts/build_rimekit.sh
ios/scripts/build_rime_data.sh ios/build/host/kk_bridge/kk_rime_deploy ios/build
cd ios && brew install xcodegen && xcodegen && open KKShuRuFa.xcodeproj
```

## 装到 iPhone 上

iOS 只能装签过名的 App，有三条路：

- **Windows + 免费 Apple ID**：用 Sideloadly 给 Actions 里下载的未签名 IPA 签名并安装。免费签名 7 天过期，需要重签；App Group 可能被改掉，这时键盘照常工作，只是读不到 App 里改的设置。
- **Apple 开发者账号（每年 99 美元）**：在 Xcode 里填上 Team 直接装，或者把证书放进仓库 Secrets，让 Actions 直接出签名包、上传 TestFlight（还没做，需要账号后再加）。
- **有 Mac**：Xcode 用免费 Apple ID 直接装到手机上。

## 还没移植的（按计划顺序）

1. 拼音行的展开候选面板、回选（删掉刚上屏的词重新选）、延迟学习（`_kk_no_learning` + `learn`，C 接口已经有了）。
2. 俄语 / 英语的下一个词联想（`*_next.txt`，约 7 MB，要做成内存映射查找）和自动纠错。
3. 剪贴板、表情、光标面板、数学键盘、快捷面板。
4. AI 改写 / 云联想（需要完全访问才能联网，Key 存钥匙串）。
5. 语音：键盘扩展不能用麦克风，只能跳到容器 App 录音再回来，或者用系统听写。离线 SenseVoice/Whisper 模型太大，放不进键盘。
6. 小K 动画、纸张主题、按键尺寸调节。
7. 导入词库、古诗文词库这类需要重新编译词库的功能：要在容器 App 里编译后写进 App Group，键盘需要完全访问才能读。
