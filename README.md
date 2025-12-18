# 🚀 SmartAgent

<div align="center">

<!-- 如果有 Logo，请取消下面注释并替换链接 -->
<!-- <img src="assets/icon/icon.png" width="120" height="120" alt="SmartAgent Logo" /> -->

**更智能、更懂你的 Android WireGuard 客户端**

基于 Flutter 与 Android 原生混合开发，提供规则驱动的隧道自动切换与应用级分流。



</div>

---

## 📖 简介

**SmartAgent** 解决了官方客户端在复杂网络场景下的痛点。它引入了 **“环境感知”** 与 **“应用分流”** 机制，能够根据你的 Wi-Fi SSID 或 IP 环境自动启停隧道，或仅让特定 App 走代理通道，让网络连接始终保持最佳状态，无需人工频繁切换。

## ✨ 功能特性

### 🤖 智能环境感知
- **自动切换**：根据当前连接的 Wi-Fi SSID 或 IPv4/IPv6 网络环境，自动匹配并切换至指定的 WireGuard 隧道。
- **灵活规则**：支持自定义规则列表，支持拖拽排序优先级与一键启停规则。

### ⚡ 精细化应用分流
- **应用级代理**：支持白名单/黑名单模式，精准控制哪些 App 走隧道流量。
- **无感更新**：分流配置采用版本化管理，变更应用列表时自动重启隧道，并记录“分流变更”日志。

### 🛡️ 隧道管理与核心
- **配置管理**：支持导入/新建/编辑 WireGuard 配置文件。
- **智能命名**：自动识别配置中的 `# NAME` 注释作为隧道显示名，否则自动使用文件名（去除后缀）。
- **原生性能**：底层基于 WireGuard 原生协议，保证高速、低延迟的连接体验。

### 📊 监控与交互
- **实时看板**：可视化面板显示上传/下载速率、握手时间与隧道状态。
- **完整日志**：提供详细的事件日志（连接状态、规则触发、分流变更）与自动清理能力。
- **快捷操作**：集成 Android QS Tile（下拉快捷开关），支持桌面 Widget 状态同步。


## 🛠️ 技术架构

SmartAgent 采用 **Flutter (UI)** + **Kotlin (Native)** 的混合架构，兼顾开发效率与系统级网络控制能力。

### 目录结构

```text
├── lib/                  # Flutter 侧：UI 构建与 API 桥接
│   ├── screens/          # 页面视图 (Dashboard, Rules, Settings)
│   └── models.dart       # 数据模型 (AgentRule, AppRule, VpnState)
├── android/              # Android 侧：原生业务逻辑
│   └── .../com/smart/
│       ├── SmartAgentVpnService.kt       # VPN Service 核心，负责隧道启停与流量拦截
│       ├── SmartRuleManager.kt # 监听网络变更 (Wi-Fi/IP) 并触发规则匹配
│       ├── SmartConfigDb.kt    # 基于 Room/SQLite 的配置、规则与日志持久化
│       └── FlutterBridge.kt    # MethodChannel/EventChannel 通信桥梁
└── test/                 # Flutter 单元测试入口
