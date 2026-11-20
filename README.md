# KK输入法

自用的安卓输入法。中文用 [Rime](https://github.com/rime/librime) 引擎加[雾凇拼音](https://github.com/iDvel/rime-ice)词库，另有俄语 ЙЦУКЕН 和英文键盘，外观和手感照着 One UI 三星键盘做。

## 现在能用的

- **中文全拼**：雾凇拼音整句输入、中英混输、按键纠错（可关）、繁体输出；黄脸 emoji 照常出现，其他 emoji 排在前五个词之后。
- **联想**：上屏后候选栏推荐常接的词（从雾凇拼音词库提取的词语接续），点了还会继续联想。
- **拼音模式**：选中候选后上屏带声调的拼音（nǐ hǎo），多音字按你打的拼音判断。
- **长按候选词**：删除它的学习记录，下次同样的拼音不再把它排前面。
- **自定义词库**：设置里增删改自己的词（雾凇拼音的 custom_phrase）。
- **俄语、英语键盘**：句首自动大写、双击空格句号；重音模式下元音上滑出 а́ е́ и́。
- **四向滑动、长按拖动选择**；空格左右滑切语言、长按拖光标。
- **删除键**：长按匀速连删，上滑清空（打拼音时只清拼音），下滑撤销。
- **!#1**：数字九宫格加一列常用标点，“符号”进入完整的分类符号面板；数学键盘 7 页，Unicode/LaTeX 两种输出。
- **快捷面板**（工具栏最右）：繁体、emoji、联想、纠错、拼音模式、重音模式、LaTeX、主题、振动、按键音等开关和各面板入口；再点一次按钮收起。
- **光标面板**：圆形方向盘，按住连续移动，中间切换选择模式；全选、复制、剪切、粘贴、按词跳、行首行尾、撤销。
- **剪贴板**：复制后候选栏直接出现“粘贴”提示；面板是两列卡片，标出链接、验证码、号码，长按固定或删除。
- **设置**：分类页面；7 种主体色（含系统取色）；9 类事件的振动各自选效果、强度、时长；键位编辑可改任意键的滑动字符和长按选项。
- **布局都是 YAML**：设置里可以导出、修改、重新加载。

## 安装

GitHub Actions 每次提交都会编译 APK（Actions 页面里的 `kk-shurufa-debug`）。装好后打开 KK输入法，按页面上的两步启用并切换。第一次打中文前会部署词库，约一分钟，候选栏会显示进度。

## 自己编译

```bash
git clone --recursive https://github.com/kaichengkou/KK_ShuRuFa
cd KK_ShuRuFa
./gradlew assembleDebug          # 默认只编 arm64；-PbuildAbi=all 编全部架构
```

需要 JDK 17+、Android SDK 35、NDK 27.2.12479018、CMake 3.22.1。首次编译会下载 Boost 源码。

## 目录

| 路径 | 内容 |
| --- | --- |
| `app/src/main/java/com/kk/shurufa/ime` | 输入法服务：按键处理、Rime 交互、各面板、计算器 |
| `app/src/main/java/com/kk/shurufa/keyboard` | 自绘键盘、手势、弹出层、候选栏、布局加载 |
| `app/src/main/java/com/kk/shurufa/settings` | 设置界面 |
| `app/src/main/assets/layouts` | 所有键盘布局（YAML） |
| `app/src/main/jni` | librime 及依赖的编译脚本、JNI 桥 |
| `rime-data` | 雾凇拼音（子模块）和本项目的覆盖配置 |
| `docs/PLAN.md` | 开发计划 |
