# 当前任务

## 目标

- 将 Windows 更新从安装器/WinSparkle 模型改为单文件 exe 自更新。
- 发布 `1.1.12`：Windows 主资产为 `MD-Preview-windows-x64.exe`，应用内下载新版 exe、校验 SHA-256、退出替换并重启。
- 同步官网、README、README_zh、CHANGELOG 和发布 workflow。

## 非目标

- 不引入长期驻留的 updater helper、安装器或 DLL。
- 不处理 Windows Authenticode 证书签名；当前仓库/签名链路没有 Windows 代码签名证书。

## 验收场景

- [x] Windows release 只需要单文件 `MD-Preview-windows-x64.exe` 作为主下载。
- [x] Windows 应用内更新不依赖 `WinSparkle.dll`、NSIS 安装器或 `appcast-windows.xml`。
- [x] 更新点击后使用 GitHub Release asset URL 和 `sha256:` digest，临时 PowerShell 脚本等待旧进程退出后替换 exe 并重启。
- [x] `./scripts/verify.sh` 通过。
- [x] `v1.1.12` GitHub Release 完成，Release asset 包含 macOS DMG、Windows EXE、Linux tarball、`appcast.xml`。

## 执行记录

- [x] 已确认 v1.1.11 的 WinSparkle/安装器模型不符合“单文件”目标。
- [x] 已撤掉 Windows WinSparkle 和安装器链路，改为自研单文件自替换。
- [x] 已同步官网、README、README_zh、CHANGELOG 和 GitHub release workflow。

## 验证记录

```text
命令：cargo test
结果：通过。9/9 tests passed。

命令：cargo check --target x86_64-pc-windows-gnu
结果：通过。Windows cfg 下单文件自更新代码编译通过。

命令：scripts/verify-windows-self-update.sh
结果：通过。确认 Windows update IPC 携带单 exe URL 和 sha256 digest；源码不依赖 WinSparkle；release workflow 不再包含 Setup。

命令：./scripts/verify.sh
结果：通过。guard、cargo test、macOS Sparkle 验证、Windows self-update 验证、iOS build/parse、Android debug/release、mobile renderer/release readiness 均通过。

命令：GitHub Actions / Release v1.1.12
结果：通过。Release、CI、Pages workflows 均 success。

命令：gh api repos/vorojar/md-preview/releases/tags/v1.1.12
结果：通过。Release assets 精确为 `MD-Preview-macOS-universal.dmg`、`MD-Preview-windows-x64.exe`、`MD-Preview-linux-x64.tar.gz`、`appcast.xml`；Windows EXE 带 `sha256:` digest。

命令：./release-sign.sh v1.1.12
结果：通过。macOS DMG 和内层 app 已签名、公证、staple，并上传覆盖 Release 资产；`appcast.xml` 已生成上传。

命令：xcrun stapler validate target/MD-Preview-macOS-universal.dmg；codesign --verify --deep --strict；syspolicy_check distribution
结果：通过。DMG staple 有效，app 签名有效，Apple distribution preflight 通过。

命令：curl https://vorojar.github.io/md-preview/
结果：通过。官网桌面下载指向 v1.1.12，Windows 安装说明为单文件 EXE。
```

## 风险和假设

- Windows 运行中的 exe 不能直接覆盖自己，因此更新仍需一个临时 PowerShell 脚本作为替换动作；它不会长期驻留，也不会作为发布依赖。
- 当前使用 GitHub Releases API 返回的 SHA-256 digest 做下载校验。
