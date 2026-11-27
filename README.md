# MCP Control

一个运行在 **Minecraft 1.21.1 NeoForge** 上的 MCP（Model Context Protocol）桥接模组，让外部 AI 通过 HTTP 连接游戏，**像正常玩家一样玩**：创建/载入/退出单人世界、打开并操作任何 GUI、截图看画面、按住真实按键移动、挖掘、放置、攻击、开箱、整理背包、聊天、执行命令。

模组不创建假玩家，也不绕过游戏规则。服务端玩家动作作用在真实 `ServerPlayer` 上（受游戏模式、领地保护、工具耐久、饥饿、冷却限制）；客户端动作走原版输入路径（`KeyMapping`、`MultiPlayerGameMode`、`GuiEventListener`），和玩家自己点鼠标、按键盘完全一样。

## 两种部署方式

| 部署 | 能做什么 | 说明 |
|---|---|---|
| **物理客户端**（推荐） | 全部 67 个工具：创建世界、操作菜单、截图、真实按键、服务端玩家动作 | 装在整合包/启动器的 `mods` 里，单人世界即可 |
| **专用服务器** | 39 个服务端玩家工具 | 不需要客户端装模组，控制真实在线玩家 |

客户端模式是 1.1.0 新增的能力：MCP 端点在**客户端第一个 tick**启动并一直存活（不随世界加载重启），所以 AI 在**主菜单**就能连上，`world_create` / `world_join` 进世界后，服务端的 39 个玩家工具会自动通过内置服务端可用。

## 环境要求

- Minecraft **1.21.1**
- NeoForge **21.1.x**（默认 21.1.233）
- Java **21**
- 客户端模式需要一台能渲染画面的物理客户端（无头服务器没有画面，只能用服务端工具）

## 快速开始（客户端）

1. 把 `mcpcontrol-1.21.1-1.1.1.jar` 放进 `mods/`，启动游戏到主菜单。
2. **一进游戏端点就自动启动**（不需要 `/mcp start`）：地址、端口、token 都写在 `config/mcpcontrol.json` 里。
3. 模组还会生成 `config/mcpcontrol-client.json`，内容是可直接粘进 AI 客户端的配置（url + Bearer token），改完配置重启游戏即可生效。
4. AI 侧配置 `http://127.0.0.1:25566/mcp`，Header `Authorization: Bearer <token>`。
5. 让 AI 按这个顺序开工：
   - `client_state` —— 看是否在主菜单、是否已进世界
   - `screenshot` —— 亲眼确认画面
   - `world_create`（或 `world_join`）—— 创建/载入世界
   - `screen_state` → `screen_click` —— 操作任何界面
   - `client_input` / `client_mine` / `client_place` / `client_interact` —— 真实按键与世界交互
   - `container_state` → `container_click` —— 箱子、工作台、熔炉、村民交易
   - `get_player_state` —— 世界加载后确认服务端玩家状态

## 快速开始（专用服务器）

1. 把 jar 放进服务端 `mods/`，启动服务器。
2. 玩家进服后执行 `/mcp status`、`/mcp token`。
3. AI 先调 `get_player_state` 确认控制的是正确玩家，再 `move_to` / `mine_block` / `place_block` / `craft_item` / `chat`。

> 默认只绑定回环地址。远程 AI 需把 `bindAddress` 改为 `0.0.0.0`，并用防火墙限制来源；**不要把无 token 的端口暴露到公网**。客户端与同机专用服务器默认都用 25566，同时开需要改其中一个的 `port`。

## 连接方式

### Streamable HTTP（推荐）

```text
POST http://127.0.0.1:25566/mcp
Authorization: Bearer <token>
Content-Type: application/json
```

`initialize` 响应带 `Mcp-Session-Id`，后续请求需要回传该 Header。

### 旧版 HTTP + SSE

```text
GET  http://127.0.0.1:25566/sse?token=<token>
POST http://127.0.0.1:25566/message?sessionId=<id>&token=<token>
```

### 客户端示例

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://127.0.0.1:25566/mcp",
               "--header", "Authorization: Bearer <token>"]
    }
  }
}
```

curl 自检：

```bash
curl -s -D - http://127.0.0.1:25566/mcp \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
```

## 工具清单（67 个）

### 客户端与界面（28 个，仅物理客户端）

| 工具 | 作用 |
|---|---|
| `client_state` | 是否在世界、坐标、血量、饥饿、手持物、当前界面、当前客户端任务 |
| `screen_state` | 列出当前界面的每个控件：序号、类型、文字、翻译键、输入值、坐标、启用/可见 |
| `screen_click` | 按序号或按文字/翻译键（中英文都能匹配）点击控件，真实鼠标点击控件中心 |
| `screen_click_xy` | 按 GUI 缩放坐标点击（文字匹配不灵时用） |
| `screen_set_text` | 往输入框写文字（默认第一个，如世界名、种子） |
| `screen_key` | 给界面发按键：ESC / ENTER / TAB / SPACE / BACKSPACE / DELETE / 方向键 |
| `screen_close` | 关闭当前界面（等同 ESC） |
| `world_create` | 走真实菜单创建并进入单人世界：标题 → 单人 → 创建新的世界 → 填名字/种子/模式/地形 → 创建 |
| `world_join` | 回标题界面并载入指定名字（或序号）的存档 |
| `world_leave` | 打开暂停菜单点「保存并退出」，回到主菜单 |
| `game_quit` | 保存存档并回标题，然后把整个游戏进程关掉；端点随进程消失，只想回主菜单用 `world_leave` |
| `game_pause` | 打开原版暂停菜单 |
| `game_respawn` | 死亡界面点「重生」 |
| `screenshot` | 截图并作为内联 PNG 图片返回；`include_gui=true` 含 HUD 与界面，`false` 只有世界画面；`save=true` 同时把 PNG 写进游戏目录（`path` 可指定文件或目录） |
| `client_input` | 按住移动键 N tick（WASD/跳/潜行/疾跑），可 `release` 松手 |
| `client_look` | 设定朝向：绝对 yaw/pitch、相对增量，或看向某个坐标 |
| `client_mine` | 用真实左键挖掘：先瞄准，再持续按住直到方块破碎（硬度/工具/保护照常生效） |
| `client_place` | 用真实右键放置手持方块，可先切换快捷栏 |
| `client_use` | 右键使用物品（食物、药水、桶、弓）；`hold_ticks` 支持蓄力 |
| `client_attack` | 瞄准并攻击实体（uuid 来自 `client_entities`，或按名字） |
| `client_interact` | 右键方块：开箱子、按按钮、拉拉杆、开门、和村民交易 |
| `client_select_hotbar` | 选 1–9 快捷栏（同按数字键） |
| `client_wait` | 按墙钟等待，不阻塞游戏 |
| `client_entities` | 半径内实体：uuid、名字、类型、距离、血量 |
| `client_block` | 客户端方块信息：id、是否空气/固体、硬度、距离 |
| `container_state` | 当前容器界面的槽位（序号、物品、数量、界面坐标）+ 光标物品 + 快捷栏 |
| `container_click` | 点容器槽位：普通拾取、Shift 快速移动、丢弃、与快捷栏交换、拖拽、双击收集 |
| `container_close` | 关闭容器界面（光标物品退回背包） |

### 服务端玩家（39 个，两端都可用，需已加载世界）

**观察**：`get_player_state`、`get_inventory`、`get_block`、`get_nearby_blocks`、`get_nearby_entities`、`get_looking_at`、`get_world_state`、`get_server_info`、`list_players`、`get_status`

**移动与交互**：`move_to`（A* 寻路，支持跳格/下落/游泳/梯子）、`look_at`、`jump`、`set_flags`、`interact_block`、`interact_entity`、`mount_entity`、`dismount`

**挖掘/放置/战斗**：`mine_block`、`place_block`、`attack_entity`、`follow_entity`、`use_item`、`release_use_item`、`stop_task`、`get_task`

**背包/容器/合成**：`find_item`、`drop_item`、`select_hotbar`、`equip_item`、`move_item`、`access_container`、`transfer_items`、`craft_item`、`list_recipes`

**服务器**：`chat`、`run_command`（`allow_commands=true` 时可用，权限等级 2）、`bind_player`、`unbind_player`

## 典型 AI 流程

```text
client_state                      # 在主菜单？已进世界？
screenshot                        # 看画面（内联 PNG）；save=true 可落盘
screen_state                      # 标题界面有哪些按钮
world_create {name:"AutoWorld", game_mode:"creative", seed:"12345"}
client_wait {seconds:2}           # 等世界加载
get_player_state                  # 服务端确认玩家位置/模式
client_input {forward:true, duration_ticks:60}
client_mine {x:12,y:64,z:-3}
client_interact {x:11,y:64,z:-3}  # 打开箱子
container_state
container_click {slot:27, click_type:"quick_move"}
container_close
screenshot {save:true}            # 再确认一次结果，并存一张 PNG
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
  "maxPathNodes": 12000,
  "clientControl": true,
  "allowWorldManagement": true,
  "screenshotMaxWidth": 1280,
  "publicUrl": "",
  "writeClientConfig": true
}
```

| 键 | 说明 |
|---|---|
| `clientControl` | 客户端是否注册 28 个界面/世界/截图/按键工具 |
| `allowWorldManagement` | 是否允许 `world_create` / `world_join` / `world_leave` 驱动菜单 |
| `screenshotMaxWidth` | 截图最大宽度（160–3840），超出会等比缩小以减小传输体积 |
| `publicUrl` | 对外公布的完整地址（如 `http://10.0.0.5:25566`）；留空按 `bindAddress:port` 生成。改这个可以不改端口就换公网/内网地址 |
| `writeClientConfig` | 每次启动时生成 `config/mcpcontrol-client.json`（mcp-remote 配置片段） |
| `defaultPlayer` | 未绑定时优先控制的玩家名；留空则只有一名在线玩家时自动选择 |
| `allowCommands` | 关闭后 `run_command` 直接返回错误 |
| `maxPathNodes` | A* 最大展开节点，越大越能绕远路，但服务器开销更高 |

## 工作原理

```text
MCP client
   │  Streamable HTTP / legacy SSE
   ▼
McpHttpServer（纯 java.base socket，无 com.sun.net.httpserver）
   ▼
McpProtocol（initialize / tools/list / tools/call）
   ▼
ToolRegistry（67 个工具）
   ├── 服务端工具 39 个 → ToolContext → TaskManager（ServerTickEvent 驱动）→ 真实 ServerPlayer
   └── 客户端工具 28 个 → ClientBridge → ClientTaskManager（ClientTickEvent 驱动）
                          ├── WorldDriver   标题/选档/建世界/暂停/重生 状态机
                          ├── ScreenDriver  Screen#children + GuiEventListener 真实点击
                          ├── InputDriver   KeyMapping#setDown 真实按键
                          ├── PlayerDriver  MultiPlayerGameMode 挖掘/放置/攻击/使用
                          ├── ContainerDriver handleInventoryMouseClick 槽位操作
                          └── ShotService   Screenshot.takeScreenshot + AWT 缩放 + base64
```

- 客户端界面内部成员通过 `Ref` 反射按名字查找，映射细节变动时返回可读错误而不是崩溃。
- 截图只在渲染线程取帧：无界面时用 `RenderGuiEvent.Post`，有界面时用 `ScreenEvent.Render.Post`，纯世界画面用 `RenderLevelStageEvent.AFTER_LEVEL`。
- 所有动作都由 tick 驱动、HTTP 线程阻塞等待结果；超时自动取消。
- 玩家掉线、死亡、换维度、退回主菜单时相关任务自动取消，端点保持存活。

## 安全边界

- 默认 `127.0.0.1` + Bearer token；`/mcp`、`/sse` 需要 token，`/message` 还要不可猜测的 session id。
- `run_command` 默认开启且需要权限等级 2，不需要请设 `allowCommands=false`。
- 客户端工具等于“把鼠标键盘交给 AI”，只建议在自己的单人存档使用。
- 建议先在创造模式测试存档跑通流程，再用于正式存档。

## 已知限制

- 界面交互是**启发式**的：按钮靠显示文字与翻译键匹配（内置中英文关键词）。原版界面没问题；非原版/魔改界面若没有可读文字，需要先用 `screen_state` 看控件，再用 `screen_click_xy` 点坐标。
- 世界创建/载入按原版 `CreateWorldScreen` / `SelectWorldScreen` 的布局推进；大改版的界面需要相应调整关键词。
- 专用服务器没有画面与菜单，只有 39 个服务端工具；`client_*` / `screen_*` / `world_*` 会返回明确错误。
- 同一时间只允许一个长动作（服务端或客户端各一个）；并行前先 `stop_task`。
- 服务端逐 tick 移动可能触发部分反作弊插件，正式服请先加白名单测试。
- 仅针对 1.21.1 + NeoForge 21.1.x 构建与适配。

## 构建

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew build
```

产物：

```text
build/libs/mcpcontrol-1.21.1-1.1.1.jar
```

ModDevGradle 首次构建需要访问 `maven.neoforged.net`（下载 NeoForm 运行时与 Minecraft 依赖）。离线环境可把已有 Gradle 缓存一起带走，或改用可达的 Maven 镜像。

## 故障排查

| 现象 | 处理 |
|---|---|
| 端点没起来 | 确认 `autoStart=true`；客户端模式下第一个 tick 就会启动，日志里搜 `MCP endpoint ready` |
| `401 unauthorized` | Header 是否为 `Authorization: Bearer <token>`；token 是否被 `/mcp token reset` 改过 |
| `Unknown MCP session` | 重新 `initialize`，或客户端没有回传 `Mcp-Session-Id` |
| `This is a dedicated server...` | 客户端工具只在物理客户端安装时可用 |
| `No world is loaded` | 先 `world_create` / `world_join`，或用 `client_state` 看当前界面 |
| `A screen is open ...` | 界面打开时移动键被忽略，先 `screen_close` |
| `No clickable widget matched` | 用 `screen_state` 看真实控件文字/序号，再 `screen_click {index:...}` |
| 截图超时 | 窗口最小化或无焦点时取不到帧，恢复窗口后重试 |
| 端口被占用 | 改 `port` 后 `/mcp restart` |
| 寻路失败 | 目标在方块内/悬崖/岩浆包围，换附近坐标 |

## 许可

MIT License，作者 XuanFei。

> 本项目的代码与文档由 AI（豆包）编写和维护，遵循 MIT 协议发布；使用前请自行在测试存档验证。
