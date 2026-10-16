# HBuilderX 运行旧布局的核对

2026-10-14 实测：

- HBuilderX 配置中原来只有 `/Users/lxfzzw/uni-halo-qh/uni-halo`；该目录首页仍包含每日一句/倒数日的旧布局。
- 新源码位于 `/Users/lxfzzw/Documents/ChatGPT/halo小程序/uni-halo-qh/uni-halo`。新版新增 uh-page-header 和 uh-icon，修改未推送 GitHub。
- 已通过 HBuilderX CLI 导入正确源码目录，并执行不上传的微信小程序本地发行编译。正确项目的常规 dev/build 输出已更新，两者首页 WXML 都有新版组件，未包含旧每日一句布局。
- 编译产物曾出现带 2/3 后缀的重复文件，已移动到 `.codex/artifacts/uni-halo-ui/` 下备份，清理后正式包检查通过。
- 正式包预览项目名改为“宥枫璟-新版UI”，用于与旧窗口区分；正式源码 AppID 没有更改。
- 自动打开新微信项目失败：开发者工具 CLI 返回 AppID code 10；独立游客副本也被同一 CLI 拒绝，所以不据此断言正式 AppID 已失效。未确认模拟器最终切换成功，当前旧窗口不代表新编译产物。

打开正确 build/dev 目录后，可在首页 WXML 搜索 `uh-icon` 验证新版代码。`UI-BUILD-INFO.txt` 记录源码绝对路径，避免只凭相同的项目名区分两份项目。
