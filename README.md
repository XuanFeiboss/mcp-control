# MCP Control

一个运行在 **Minecraft 1.21.1 NeoForge** 服务端的 MCP（Model Context Protocol）桥接模组。它让外部 AI 通过 HTTP 连接游戏，观察并操作一名**真实在线玩家**，完成移动、挖掘、放置、战斗、使用物品、背包整理、合成、开箱、聊天和执行命令等正常玩家可以做的事。

模组不创建假玩家，也不会绕过游戏规则：所有操作都作用在真实 `ServerPlayer` 上，受原版游戏模式、领地/出生点保护、工具耐久、饥饿、冷却和服务器权限约束。

## 环境要求

- Minecraft **1.21.1**
- NeoForge **21.1.x**（默认 21.1.233）
- Java **21**
- 单人存档（内置服务端）或专用服务器均可；客户端不需要安装此模组

## 快速开始

1. 把 `mcpcontrol-1.21.1-1.0.0.jar` 放进 `mods/` 文件夹。
2. 启动游戏/服务器。模组会创建 `config/mcpcontrol.json`，并自动在 `127.0.0.1:25566` 启动 HTTP 端点。
3. 进服后执行 `/mcp status` 查看状态，执行 `/mcp token` 取得完整 Bearer Token。
4. 在 AI/MCP 客户端中配置：
   - URL：`http://127.0.0.1:25566/mcp`
   - Header：`Authorization: Bearer <token>`
5. 先让 AI 调用 `get_player_state`，确认它控制的是正确的玩家。

> 默认只绑定回环地址，外部机器无法直接访问。需要远程 AI 连接时，把 `bindAddress` 改为 `0.0.0.0`，并用防火墙限制来源；**不要把无 token 的端口暴露到公网**。

## 连接方式

### Streamable HTTP（推荐）

支持 MCP 2025-06-18 的 Streamable HTTP：

```text
POST http://127.0.0.1:25566/mcp
Authorization: Bearer <token>
Content-Type: application/json
```

`initialize` 响应会带 `Mcp-Session-Id`，后续请求需要带回该 Header。

### 旧版 HTTP + SSE

兼容只支持旧版 SSE 的客户端：

```text
GET  http://127.0.0.1:25566/sse?token=<token>
POST http://127.0.0.1:25566/message?sessionId=<id>&token=<token>
```

### 客户端示例

支持远程 MCP 的客户端（例如通过 `mcp-remote` 桥接的客户端）通常这样配置，具体参数以客户端文档为准：

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "http://127.0.0.1:25566/mcp",
        "--header",
        "Authorization: Bearer <token>"
      ]
    }
  }
}
```

也可以直接用 curl 验证：

```bash
curl -s -D - http://127.0.0.1:25566/mcp \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
```

## 工具清单

共 39 个工具，按用途分组。

### 观察

| 工具 | 作用 |
|---|---|
| `get_player_state` | 坐标、维度、血量、饥饿、经验、游戏模式、效果、手持物、移动状态 |
| `get_inventory` | 所有非空背包槽位：槽号、区域、物品、数量、耐久 |
| `get_block` | 单个方块：ID、状态属性、硬度、碰撞、流体、方块实体类型 |
| `get_nearby_blocks` | 半径内方块扫描，可按 block id 过滤（找矿、树、水、箱子） |
| `get_nearby_entities` | 半径内实体：类型、UUID、坐标、距离、血量 |
| `get_looking_at` | 准星射线拾取（5 格）：方块或实体 |
| `get_world_state` | 维度、游戏时间、天数、天气、难度、月相 |
| `get_server_info` | MOTD、MC/NeoForge 版本、人数、Tick 耗时 |
| `list_players` | 在线玩家、坐标、血量、模式、OP 等级、当前绑定 |
| `get_status` | HTTP 状态、掩码 token、绑定玩家、当前任务、工具数 |

### 移动与交互

| 工具 | 作用 |
|---|---|
| `move_to` | A* 寻路走到坐标；支持 1 格跳、1–3 格下落、游泳、梯子 |
| `look_at` | 转头看向坐标或实体 |
| `jump` | 原地跳一次 |
| `set_flags` | 设置潜行/疾跑 |
| `interact_block` | 右键方块：门、按钮、拉杆、工作台、床等 |
| `interact_entity` | 右键实体：村民、马、船、物品展示框 |
| `mount_entity` / `dismount` | 骑乘/离开载具 |

### 挖掘、放置与战斗

| 工具 | 作用 |
|---|---|
| `mine_block` | 自动走近、选择最佳工具、走原版挖掘管线、拾取掉落 |
| `place_block` | 靠坚固邻面放置方块，自动选物品/走近 |
| `attack_entity` | 走近目标，按 12 tick 冷却连续蓄力攻击 |
| `follow_entity` | 持续跟随实体，目标移动时重新寻路 |
| `use_item` | 使用主手/副手物品；`wait=false` 用于弓等蓄力物品 |
| `release_use_item` | 松开正在蓄力的弓/弩/三叉戟 |
| `stop_task` / `get_task` | 停止/查看当前动作 |

### 背包、容器与合成

| 工具 | 作用 |
|---|---|
| `find_item` | 统计某物品总量，列出所在槽位；不填物品则汇总全部 |
| `drop_item` | 从指定槽/手持槽丢弃物品 |
| `select_hotbar` | 选中 0–8 快捷栏 |
| `equip_item` | 装备头盔/胸甲/护腿/靴子/鞘翅 |
| `move_item` | 在 0–40 槽之间移动/交换（含护甲、副手） |
| `access_container` | 查看箱子/桶/熔炉/漏斗等内容物 |
| `transfer_items` | 容器 ↔ 背包取放物品 |
| `craft_item` | 按工作台配方直接合成（消耗背包材料，不需要放置工作台） |
| `list_recipes` | 按产物 id 列出配方与材料 |

### 服务器

| 工具 | 作用 |
|---|---|
| `chat` | 以被控玩家身份发聊天 |
| `run_command` | 以权限等级 2 执行命令（需 `allow_commands=true`） |
| `bind_player` / `unbind_player` | 指定/清除 AI 控制的玩家 |

## 游戏内指令

需要 OP（权限等级 2）：

```text
/mcp status          查看端点、token 掩码、玩家和任务
/mcp start           启动 HTTP 端点
/mcp stop            停止端点并取消当前任务
/mcp restart         重启端点
/mcp url             显示 MCP 地址
/mcp token           显示完整 token
/mcp token reset     重新生成 token
/mcp bind <player>   指定 AI 控制的玩家
/mcp unbind          清除绑定
/mcp tasks           查看当前动作
/mcp help            帮助
```

## 配置

`config/mcpcontrol.json`：

```json
{
  "port": 25566,
  "bindAddress": "127.0.0.1",
  "autoStart": true,
  "token": "<48 位十六进制，首次启动自动生成>",
  "defaultPlayer": "",
  "allowCommands": true,
  "announceActions": true,
  "walkSpeed": 0.22,
  "sprintSpeed": 0.30,
  "maxPathNodes": 12000
}
```

- `defaultPlayer`：未显式绑定时优先控制的玩家名；留空则只有一名在线玩家时自动选择。
- `allowCommands`：关闭后 `run_command` 直接返回错误。
- `announceActions`：动作开始时在玩家 ActionBar 显示提示。
- `maxPathNodes`：A* 最大展开节点，越大越能找到远路，但服务器开销越高。

## 工作原理

```text
MCP client
   │  Streamable HTTP / legacy SSE
   ▼
McpHttpServer（纯 java.base socket 实现，无 com.sun.net.httpserver）
   ▼
McpProtocol（initialize / tools/list / tools/call）
   ▼
ToolRegistry（39 个工具）
   ▼
ToolContext（玩家解析、服务器线程调度、任务等待）
   ▼
TaskManager（单任务 tick 驱动）
   ├── PathFinder（A*）
   ├── PathFollower（逐 tick 小步移动）
   └── MoveTask / MineTask / PlaceTask / AttackTask / FollowTask / UseItemTask
   ▼
真实 ServerPlayer（原版游戏模式、物品、方块、实体交互）
```

- HTTP 层不依赖 `jdk.httpserver`，避免 Minecraft 模块层未解析该模块导致 `NoClassDefFoundError`。
- 长时间动作由 `ServerTickEvent.Post` 驱动，HTTP 请求线程等待结果；超时会自动 `stop`。
- 玩家掉线、死亡或换维度时任务自动取消。

## 安全边界

- 默认 `127.0.0.1` + Bearer token。
- `/mcp`、`/sse` 需要 token；`/message` 还要求不可猜测的 session id。
- 命令工具默认开启，权限等级 2；如不需要请设 `allowCommands=false`。
- 这是**服务端控制**模组，不是客户端外挂；需要服务器管理员安装。
- 建议先在创造模式/测试存档验证寻路与挖掘，再用于正式存档。

## 已知限制

- 不模拟原版 GUI 点击：村民交易、附魔台、铁砧、创造模式物品搜索没有逐屏操作；合成通过配方管理器直接完成。
- 不渲染画面；AI 通过方块/实体/准星射线数据“看”世界。
- 寻路是方块级 A*，不做复杂跑酷、脚手架搭桥或水中长距离游泳优化。
- 同一时间只允许一个长动作；需要并行操作时先 `stop_task`。
- 服务端逐 tick `teleportTo` 移动可能触发部分反作弊插件；正式服请先加白名单/测试。
- 未在真实 Minecraft 客户端中启动验证；已使用 1.21.1 官方映射 API 全量编译，并完成 MCP HTTP 协议回归测试。

## 构建

```bash
# 确保 Gradle 本身运行在 JDK 21 上，例如：
export JAVA_HOME=/path/to/jdk-21
./gradlew build
```

需要能访问 `maven.neoforged.net`（ModDevGradle 会下载 NeoForm 运行时）。如果你的网络无法访问该域名，可使用仓库中已附带的预编译 jar，或按 `build.gradle` 配置代理镜像。当前环境实测会在 `net.neoforged:neoform-runtime:2.0.18` 处因该域名连接被重置而失败；这不影响已附带的预编译 jar。

产物：

```text
build/libs/mcpcontrol-1.21.1-1.0.0.jar
```

## 故障排查

| 现象 | 处理 |
|---|---|
| `401 unauthorized` | Header 是否 `Authorization: Bearer <token>`；token 是否被 `/mcp token reset` 改过 |
| `Unknown MCP session` | 重新 `initialize`，或客户端没有回传 `Mcp-Session-Id` |
| `No player is online` | 先进入世界；多人服可用 `/mcp bind <name>` |
| `Multiple players are online` | 设置 `defaultPlayer` 或调用 `bind_player` |
| `Another action is already running` | 先 `stop_task` 或 `get_task` 查看 |
| 端口被占用 | 修改 `port` 后 `/mcp restart` |
| 寻路失败 | 目标可能在方块内/悬崖/岩浆包围；换附近坐标 |
| 挖掘没掉落 | 工具不对、方块被保护、背包满；看返回的 `tool`/`drops` |

## 许可

MIT License，作者 XuanFei。
