# Changelog

## 1.1.1 — 2026-11-26

配置化连接信息，进游戏即连。

### Added

- `publicUrl`：对外公布的完整 MCP 地址，留空按 `bindAddress`+`port` 生成；改它就能在不改端口的情况下换内网/公网地址。
- `writeClientConfig`：每次启动生成 `config/mcpcontrol-client.json`，内含可直接粘到 AI 客户端的 `mcpServers` 片段（url + Bearer token）。
- `status` 增加 `public_url`、`client_config` 字段；日志启动行直接打印可用的 MCP 地址。

### Changed

- `/mcp url`、`/mcp start`、`/mcp restart` 使用配置里的完整地址。
- `McpRuntime.VERSION` / `mod_version` 升到 1.1.1。

### Notes

- 物理客户端在第一个客户端 tick 就会启动端点（主菜单即可连接），无需 `/mcp start`；地址、端口、token 全部写在 `config/mcpcontrol.json`。

## 1.1.0 — 2026-11-26

客户端控制版本：同一个 jar 装在物理客户端后，AI 能像玩家一样操作游戏窗口；装在专用服务器时行为与 1.0.0 完全一致。

### Added — 客户端（27 个新工具）

- **世界生命周期**：`world_create`（走真实菜单创建并进入单人世界，支持名字/种子/游戏模式/地形）、`world_join`（按名字或序号载入存档）、`world_leave`（保存并退回主菜单）、`game_pause`、`game_respawn`。
- **界面交互**：`screen_state` 列出当前界面全部控件（序号/类型/文字/翻译键/输入值/坐标/启用状态）、`screen_click`（按序号或中英文文字真实点击控件）、`screen_click_xy`、`screen_set_text`、`screen_key`、`screen_close`。
- **截图**：`screenshot` 把帧缓冲转 PNG 并以 MCP `image` 内容块内联返回，可按 `include_gui` 选择含界面或纯世界画面，并按 `max_width` 等比缩小。
- **真实输入**：`client_input`（`KeyMapping#setDown` 按住 WASD/跳/潜行/疾跑 N tick）、`client_look`、`client_mine`（`startDestroyBlock`/`continueDestroyBlock` 持续挖掘）、`client_place`（`useItemOn`）、`client_use`、`client_attack`、`client_interact`、`client_select_hotbar`、`client_wait`。
- **容器**：`container_state`（槽位/光标物品/快捷栏）、`container_click`（`handleInventoryMouseClick` 支持拾取/Shift 快速移动/丢弃/交换/拖拽/双击）、`container_close`。
- 客户端端点随**第一个客户端 tick**启动并跨世界存活，因此 AI 可在主菜单连接；进世界后 39 个服务端工具通过内置服务端自动可用。
- 界面成员通过 `Ref` 反射按名查找，映射差异时返回可读错误而不是崩溃。

### Changed

- `ToolResult` 支持内联图片内容块（`ImageBlock`）。
- `ToolContext` 的 `server` 改为可空：客户端在主菜单时服务端工具返回明确错误，而不是抛出空指针。
- `McpRuntime` 改为双端生命周期：专用服随服务器启动/停止；物理客户端长期存活，仅在世界切换时 attach/detach 集成服务器。
- 新增配置 `clientControl`、`allowWorldManagement`、`screenshotMaxWidth`。
- `initialize` 的 instructions 重写为“客户端工具组 + 玩家工具组 + 典型启动流程”。

### Fixed

- 退回主菜单时不再关闭客户端端点（旧逻辑会把端点一起停掉）。

### Tool count

- 服务端 39 + 客户端 27 = 66。

### Known limitations

- 界面按钮靠显示文字/翻译键匹配（内置中英文关键词），魔改界面需 `screen_state` + `screen_click_xy` 兜底。
- 世界创建/载入按原版 `CreateWorldScreen` / `SelectWorldScreen` 布局推进。
- 专用服务器没有画面与菜单，只有 39 个服务端工具。
- 已用 1.21.1 官方映射类全量编译通过（0 错误），但本环境无图形客户端，未做真机启动验证。

## 1.0.0 — 2026-11-26

首个可运行版本。

### Added

- MCP Streamable HTTP 端点 `POST/GET/DELETE /mcp`，带 `Mcp-Session-Id`。
- 兼容旧版 HTTP+SSE：`GET /sse` + `POST /message?sessionId=...`。
- Bearer Token 鉴权（Header 或 query），`/health` 健康检查，CORS/OPTIONS。
- 纯 `java.base` socket HTTP 实现，不依赖 `jdk.httpserver`。
- 真实玩家绑定：`bind_player`、`unbind_player`、`defaultPlayer` 回退、唯一在线玩家自动选择。
- 单任务 tick 调度器：玩家掉线/死亡/换维度自动取消，超时取消，任务互斥。
- A* 寻路：8 方向 + 上下台阶/下落、水/梯子、危险方块规避、对角切角保护。
- 移动、挖掘、放置、攻击、跟随、使用物品任务。
- 39 个 MCP 工具：观察、移动、挖掘/放置/战斗、背包/容器/合成、聊天/命令/绑定/状态。
- `/mcp` 游戏内运维命令。
- 自动生成 `config/mcpcontrol.json` 与 48 位 token。
- README、构建脚本、预编译 jar。

### Known limitations

- 不含原版 GUI 逐屏点击（村民交易、附魔台、铁砧等）。
- 寻路为方块级 A*，不做复杂跑酷/搭桥。
- 未在真实 Minecraft 客户端中启动验证；已通过 1.21.1 官方映射 API 编译和 HTTP 协议回归。
