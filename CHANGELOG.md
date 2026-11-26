# Changelog

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
