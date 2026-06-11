# 当前任务

## 目标

- 修复 macOS 更新流程中 Sparkle `Autoupdate` 被系统“隐私与安全性 / App Management”阻止的问题。
- 保留更新提示能力，但默认点击 `Update` 时打开 GitHub DMG 下载，避免再次触发被拦截的原生安装器。
- 修复首页 Recent 项过多时顶掉顶部空状态说明的问题，并保持 Recent 不出现内部滚动条。
- 发布 `v1.1.17`，确认 GitHub Release、签名公证、Gatekeeper、appcast 和线上资产正常。

## 非目标

- 不在本次继续强推 Sparkle 原生自动替换安装；该路径只保留给受控环境变量测试。
- 不改变 Windows 自更新逻辑。
- 不改变 Markdown 渲染、搜索、锚点跳转和移动端功能。

## 验收场景

- [x] 空状态有 Recent 时，图标、说明、Open File 和 Recent 都可见，Recent 不使用内部滚动条。
- [x] 更新按钮显示为 `↻ Update`，更新 label 不会变成 `Update Update`。
- [x] macOS 默认 `nativeUpdater: false`，点击更新按钮发送 `open-url:<DMG download url>`，不发送 `check-updates`。
- [x] Sparkle installer 只在 `MD_PREVIEW_ENABLE_SPARKLE_INSTALLER=1` 时启用。
- [x] macOS bundle 写入 `SUEnableInstallerLauncherService=true`。
- [x] `v1.1.17` GitHub Release 完成，Release asset 包含 macOS DMG、Windows EXE、Linux tarball、`appcast.xml`。
- [x] macOS DMG 和内层 app 已签名、公证、staple，并通过 Gatekeeper 校验。

## 执行记录

- [x] 将 macOS Sparkle installer 默认关闭，保留 `MD_PREVIEW_ENABLE_SPARKLE_INSTALLER=1` 测试开关。
- [x] macOS 更新按钮默认打开 GitHub Release DMG 下载地址。
- [x] 新增 macOS update fallback 的 Node 验证，防止回退到被阻止的 `check-updates` 路径。
- [x] 在 `bundle.sh` 中添加 `SUEnableInstallerLauncherService`。
- [x] 调整 `↻ Update` 按钮结构和 label 更新逻辑。
- [x] 调整空状态 Recent 布局，去掉内部滚动条。
- [x] 版本号更新到 `1.1.17`。
- [x] 发布 `v1.1.17` 并更新 Release notes，说明旧版如遇 “Autoupdate was blocked” 需手动安装一次 DMG。

## 验证记录

```text
命令：cargo fmt --check
结果：通过。

命令：./scripts/verify.sh
结果：通过。guard、cargo test、anchor navigation、Sparkle update、Windows self-update、iOS build/parse、Android debug/release、mobile renderer/release readiness 均通过。

命令：scripts/release.sh v1.1.17
结果：GitHub Actions、master/tag 推送和 Release 创建通过；第一次 Apple notary 在签名阶段因 NSURLErrorDomain Code=-1001 超时中断。

命令：./release-sign.sh v1.1.17
结果：重试通过。内层 .app 和外层 DMG 均 signed、notarized、stapled；签名后 DMG 已覆盖上传到 GitHub Release；appcast.xml 已生成并上传。

命令：gh release view v1.1.17 -R vorojar/md-preview --json url,assets
结果：通过。Release asset 包含 appcast.xml、MD-Preview-linux-x64.tar.gz、MD-Preview-macOS-universal.dmg、MD-Preview-windows-x64.exe。

命令：xcrun stapler validate target/MD-Preview-macOS-universal.dmg
结果：通过。The validate action worked。

命令：codesign --verify --deep --strict --verbose=2 target/MD\ Preview.app
结果：通过。app valid on disk，satisfies Designated Requirement。

命令：spctl -a -t open --context context:primary-signature target/MD-Preview-macOS-universal.dmg
结果：通过。

命令：curl -fsSL https://github.com/vorojar/md-preview/releases/latest/download/appcast.xml
结果：通过。线上 appcast 指向 MD Preview 1.1.17、v1.1.17 macOS DMG，并包含 sparkle:edSignature。
```

## 风险和假设

- 已发布的旧版 `1.1.16` 内置更新按钮仍可能走旧 Sparkle 路径；若用户遇到系统阻止，需要从 `v1.1.17` Release 手动下载 DMG 安装一次。安装 `v1.1.17` 后，后续点击 `Update` 默认打开 DMG 下载，不再调用被阻止的安装器。
- GitHub Actions 当前有 Node.js 20 deprecation annotation 和 windows-latest 重定向 notice，不影响本次 release，但需要后续升级 workflow。
