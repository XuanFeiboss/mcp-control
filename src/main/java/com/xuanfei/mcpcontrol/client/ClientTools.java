package com.xuanfei.mcpcontrol.client;

import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.mcp.BaseTool;
import com.xuanfei.mcpcontrol.mcp.JsonUtil;
import com.xuanfei.mcpcontrol.mcp.ToolContext;
import com.xuanfei.mcpcontrol.mcp.ToolError;
import com.xuanfei.mcpcontrol.mcp.ToolRegistry;
import com.xuanfei.mcpcontrol.mcp.ToolResult;
import com.xuanfei.mcpcontrol.mcp.ToolSchemas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The client-side tool group: menus, world lifecycle, screenshots, virtual movement, direct block and
 * entity interaction, and container slot clicking. All tools are prefixed {@code client_} or belong to
 * a client-only family ({@code screen_*}, {@code world_*}, {@code container_*}).
 */
public final class ClientTools {

    private ClientTools() {
    }

    public static void register(ToolRegistry registry, ClientBridge bridge) {
        registerState(registry, bridge);
        registerScreen(registry, bridge);
        registerWorld(registry, bridge);
        registerAction(registry, bridge);
        registerContainer(registry, bridge);
    }

    // ------------------------------------------------------------------ reads

    private static void registerState(ToolRegistry registry, ClientBridge bridge) {
        registry.register(new BaseTool("client_state", "Client state",
                "Describes the game client: whether a world is loaded, the player's position/rotation/health/hotbar, "
                        + "the open screen and any running client action. Call this first on the client.",
                ToolSchemas.schema(new JsonObject()), true, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject data = bridge.read(() -> {
                    JsonObject object = bridge.players().state();
                    JsonObject screen = new JsonObject();
                    screen.addProperty("open", bridge.screens().screen() != null);
                    screen.addProperty("class", bridge.screens().screenClass());
                    if (bridge.screens().screen() != null) {
                        screen.addProperty("title", Ref.text(Ref.callOpt(bridge.screens().screen(), "getTitle")));
                    }
                    object.add("screen_summary", screen);
                    object.add("world", bridge.worlds().state());
                    object.add("task", bridge.tasks().status());
                    return object;
                }, "client state");
                return CompletableFuture.completedFuture(ToolResult.ok("Client state", data));
            }
        });

        registry.register(new BaseTool("client_entities", "Nearby entities",
                "Lists living entities around the player with uuid, name, type, distance and health. "
                        + "Use the uuid with client_attack.",
                entitySchema(), true, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                double radius = JsonUtil.optionalDouble(copy, "radius", 16.0, 1.0, 64.0);
                JsonObject data = bridge.read(() -> bridge.players().entitiesJson(radius), "entities");
                return CompletableFuture.completedFuture(ToolResult.ok("Entities within " + radius + " blocks", data));
            }
        });

        registry.register(new BaseTool("client_block", "Inspect block",
                "Reads one block from the client level: registry id, air/solid flags, hardness and the player's "
                        + "distance to it. Use it before client_mine / client_interact.",
                ToolSchemas.schema(positionProps(), "x", "y", "z"), true, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                BlockPos pos = JsonUtil.requiredBlockPos(copy);
                JsonObject data = bridge.read(() -> bridge.players().blockInfo(pos), "block info");
                return CompletableFuture.completedFuture(ToolResult.ok("Block at " + JsonUtil.formatPos(pos), data));
            }
        });
    }

    // ------------------------------------------------------------------ screens

    private static void registerScreen(ToolRegistry registry, ClientBridge bridge) {
        registry.register(new BaseTool("screen_state", "Describe screen",
                "Lists every widget of the open screen (buttons, text fields, tabs, list rows) with index, type, "
                        + "text, translation key, value, bounds and enabled/visible flags. This is how the AI 'sees' menus.",
                ToolSchemas.schema(new JsonObject()), true, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject data = bridge.read(() -> bridge.screens().describe(), "screen state");
                String text = data.has("open") && data.get("open").getAsBoolean()
                        ? "Screen: " + data.get("screen").getAsString()
                        : "No screen is open.";
                return CompletableFuture.completedFuture(ToolResult.ok(text, data));
            }
        });

        registry.register(new BaseTool("screen_click", "Click a widget",
                "Clicks a widget on the open screen by widget index or by matching its text/translation key "
                        + "(case-insensitive, works with Chinese and English labels). This is a real mouse click at the widget centre.",
                clickSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                int index = JsonUtil.optionalInt(copy, "index", -1, -1, 10000);
                String text = JsonUtil.optionalString(copy, "text", "");
                return CompletableFuture.completedFuture(bridge.read(() -> {
                    if (bridge.screens().screen() == null) {
                        throw new ToolError("No screen is open. Use client_state to see the current situation.");
                    }
                    ScreenDriver.Widget widget = index >= 0
                            ? findByIndex(bridge, index)
                            : bridge.screens().findByText(bridge.screens().screen(), text);
                    if (widget == null) {
                        JsonObject data = bridge.screens().describe();
                        throw new ToolError("No clickable widget matched '" + text + "'. Available widgets are in the error data.");
                    }
                    boolean clicked = bridge.screens().click(widget);
                    JsonObject data = new JsonObject();
                    data.add("widget", widget.toJson());
                    data.addProperty("clicked", clicked);
                    data.add("screen_after", bridge.screens().describe());
                    return clicked
                            ? ToolResult.ok("Clicked '" + (widget.text().isEmpty() ? widget.type() : widget.text()) + "'", data)
                            : ToolResult.error("The widget '" + widget.text() + "' rejected the click "
                            + "(disabled, invisible or covered). Widget data is in the payload.", data);
                }, "screen click"));
            }
        });

        registry.register(new BaseTool("screen_click_xy", "Click at coordinates",
                "Forwards a mouse click in GUI-scaled coordinates (the same numbers screen_state reports). "
                        + "Use this when automatic text matching is not enough.",
                xySchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                double x = JsonUtil.requiredDouble(copy, "x");
                double y = JsonUtil.requiredDouble(copy, "y");
                return CompletableFuture.completedFuture(bridge.read(() -> {
                    if (bridge.screens().screen() == null) {
                        throw new ToolError("No screen is open.");
                    }
                    boolean clicked = bridge.screens().clickRaw(x, y, 0);
                    JsonObject data = bridge.screens().describe();
                    return clicked
                            ? ToolResult.ok("Clicked at " + JsonUtil.round(x) + ", " + JsonUtil.round(y), data)
                            : ToolResult.error("Nothing accepted the click at " + x + ", " + y, data);
                }, "screen click"));
            }
        });

        registry.register(new BaseTool("screen_set_text", "Fill a text field",
                "Writes text into a text field of the open screen (default: the first field, e.g. the world name "
                        + "on the world creation screen). Focus is given to the field.",
                textSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                String value = JsonUtil.requiredString(copy, "value");
                int index = JsonUtil.optionalInt(copy, "index", 0, 0, 64);
                return CompletableFuture.completedFuture(bridge.read(() -> {
                    if (bridge.screens().screen() == null) {
                        throw new ToolError("No screen is open.");
                    }
                    ScreenDriver.Widget field = bridge.screens().findEditField(bridge.screens().screen(), index);
                    if (field == null) {
                        throw new ToolError("Text field #" + index + " was not found; the screen has "
                                + bridge.screens().editFields(bridge.screens().screen()).size() + " field(s).");
                    }
                    boolean ok = bridge.screens().setText(field, value);
                    JsonObject data = new JsonObject();
                    data.add("widget", field.toJson());
                    data.addProperty("value", value);
                    return ok ? ToolResult.ok("Set field #" + index + " to '" + value + "'", data)
                            : ToolResult.error("The field rejected the value.", data);
                }, "set text"));
            }
        });

        registry.register(new BaseTool("screen_key", "Press a key",
                "Sends a key press to the open screen. Supported names: ESC, ENTER, TAB, SPACE, BACKSPACE, DELETE, "
                        + "UP, DOWN, LEFT, RIGHT. ESC closes most screens.",
                ToolSchemas.schema(singleKey(), "key"), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                String key = JsonUtil.requiredString(copy, "key");
                int code = keyCode(key);
                if (code < 0) {
                    return CompletableFuture.completedFuture(ToolResult.error("Unknown key '" + key
                            + "'. Use ESC, ENTER, TAB, SPACE, BACKSPACE, DELETE, UP, DOWN, LEFT or RIGHT."));
                }
                JsonObject data = bridge.read(() -> {
                    if (bridge.screens().screen() == null) {
                        throw new ToolError("No screen is open.");
                    }
                    boolean pressed = bridge.screens().pressKey(code);
                    JsonObject object = new JsonObject();
                    object.addProperty("pressed", pressed);
                    object.add("screen_after", bridge.screens().describe());
                    return object;
                }, "key press");
                return CompletableFuture.completedFuture(ToolResult.ok("Sent key " + key, data));
            }
        });

        registry.register(new BaseTool("screen_close", "Close screen",
                "Closes the current screen exactly like pressing ESC (inventory, chest, pause menu, custom menus).",
                ToolSchemas.schema(new JsonObject()), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return CompletableFuture.completedFuture(bridge.read(() -> {
                    if (bridge.screens().screen() == null) {
                        return ToolResult.ok("No screen is open.");
                    }
                    String before = bridge.screens().screenClass();
                    bridge.screens().close();
                    JsonObject data = new JsonObject();
                    data.addProperty("closed", before);
                    data.addProperty("screen_after", bridge.screens().screenClass());
                    return ToolResult.ok("Closed " + before, data);
                }, "close screen"));
            }
        });
    }

    // ------------------------------------------------------------------ world lifecycle

    private static void registerWorld(ToolRegistry registry, ClientBridge bridge) {
        registry.register(new BaseTool("world_create", "Create a world",
                "Creates and enters a new single-player world by driving the real menus: title screen -> Singleplayer -> "
                        + "Create New World -> fill name/seed/game mode -> Create. Requires being at the title screen.",
                createSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                if (!context.config.allowWorldManagement) {
                    return CompletableFuture.completedFuture(ToolResult.error(
                            "World management is disabled in config/mcpcontrol.json (allowWorldManagement=false)."));
                }
                JsonObject copy = arguments.deepCopy();
                String name = JsonUtil.requiredString(copy, "name");
                String gameMode = JsonUtil.optionalString(copy, "game_mode", "");
                String seed = JsonUtil.optionalString(copy, "seed", "");
                String worldType = JsonUtil.optionalString(copy, "world_type", "");
                int timeout = JsonUtil.optionalInt(copy, "timeout_seconds", 120, 10, 300);
                ClientTask task = bridge.worlds().createTask(name, gameMode, seed, worldType);
                return bridge.runAsync(task, timeout);
            }
        });

        registry.register(new BaseTool("world_join", "Load a world",
                "Returns to the title screen if needed, opens Singleplayer, then loads the world whose name matches "
                        + "(or the row given by index). Use screen_state if the list layout is unusual.",
                joinSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                if (!context.config.allowWorldManagement) {
                    return CompletableFuture.completedFuture(ToolResult.error(
                            "World management is disabled in config/mcpcontrol.json (allowWorldManagement=false)."));
                }
                JsonObject copy = arguments.deepCopy();
                String name = JsonUtil.optionalString(copy, "name", "");
                int index = JsonUtil.optionalInt(copy, "index", -1, -1, 1000);
                int timeout = JsonUtil.optionalInt(copy, "timeout_seconds", 120, 10, 300);
                if (name.isBlank() && index < 0) {
                    throw new ToolError("Pass either 'name' or 'index'.");
                }
                ClientTask task = bridge.worlds().joinTask(name, index);
                return bridge.runAsync(task, timeout);
            }
        });

        registry.register(new BaseTool("world_leave", "Leave the world",
                "Opens the pause menu and clicks Save and Quit to Title (or Disconnect on a server), returning to "
                        + "the main menu. Progress is saved by the game as usual.",
                ToolSchemas.schema(new JsonObject()), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                if (!context.config.allowWorldManagement) {
                    return CompletableFuture.completedFuture(ToolResult.error(
                            "World management is disabled in config/mcpcontrol.json (allowWorldManagement=false)."));
                }
                return bridge.runAsync(bridge.worlds().leaveTask(true), 60);
            }
        });

        registry.register(new BaseTool("game_pause", "Open the pause menu",
                "Opens the vanilla pause menu (the same as pressing ESC in a world).",
                ToolSchemas.schema(new JsonObject()), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return bridge.runAsync(bridge.worlds().pauseTask(), 10);
            }
        });

        registry.register(new BaseTool("game_respawn", "Respawn",
                "Clicks the Respawn button on the death screen.",
                ToolSchemas.schema(new JsonObject()), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return bridge.runAsync(bridge.worlds().respawnTask(), 20);
            }
        });

        registry.register(new BaseTool("screenshot", "Screenshot",
                "Captures the game window and returns it as an inline PNG image. include_gui=true also shows HUD and "
                        + "open menus; include_gui=false shows only the rendered world.",
                screenshotSchema(), true, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                boolean includeGui = JsonUtil.optionalBoolean(copy, "include_gui", true);
                int configured = context.config.screenshotMaxWidth;
                int maxWidth = JsonUtil.optionalInt(copy, "max_width", configured, 160, 3840);
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        byte[] png = bridge.shots().capture(includeGui, maxWidth, 5000L);
                        String base64 = bridge.shots().toBase64(png);
                        JsonObject data = new JsonObject();
                        data.addProperty("bytes", png.length);
                        data.addProperty("base64_length", base64.length());
                        data.addProperty("include_gui", includeGui);
                        data.addProperty("max_width", maxWidth);
                        List<ToolResult.ImageBlock> images = new ArrayList<>();
                        images.add(new ToolResult.ImageBlock("image/png", base64));
                        return ToolResult.image("Screenshot captured (" + png.length + " bytes).", data, images);
                    } catch (Throwable throwable) {
                        String message = throwable.getMessage() == null
                                ? throwable.getClass().getSimpleName()
                                : throwable.getMessage();
                        return ToolResult.error("Screenshot failed: " + message);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ player actions

    private static void registerAction(ToolRegistry registry, ClientBridge bridge) {
        registry.register(new BaseTool("client_input", "Hold movement keys",
                "Holds vanilla movement keys for a number of ticks (20 ticks = 1s): forward/back/left/right, jump, "
                        + "sneak and sprint. Release by calling again with all flags false, or use the 'release' flag. "
                        + "Movement is real key input, so collisions, hunger and sprinting behave normally.",
                inputSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                boolean release = JsonUtil.optionalBoolean(copy, "release", false);
                boolean forward = JsonUtil.optionalBoolean(copy, "forward", false);
                boolean back = JsonUtil.optionalBoolean(copy, "back", false);
                boolean left = JsonUtil.optionalBoolean(copy, "left", false);
                boolean right = JsonUtil.optionalBoolean(copy, "right", false);
                boolean jump = JsonUtil.optionalBoolean(copy, "jump", false);
                boolean sneak = JsonUtil.optionalBoolean(copy, "sneak", false);
                boolean sprint = JsonUtil.optionalBoolean(copy, "sprint", false);
                int ticks = JsonUtil.optionalInt(copy, "duration_ticks", 20, 1, 600);
                if (release || !(forward || back || left || right || jump || sneak || sprint)) {
                    bridge.read(() -> {
                        bridge.input().release();
                        return true;
                    }, "release input");
                    return CompletableFuture.completedFuture(ToolResult.ok("Movement keys released."));
                }
                return bridge.runAsync(bridge.players().moveTask(forward, back, left, right,
                        jump, sneak, sprint, ticks), Math.max(5, ticks / 20 + 5));
            }
        });

        registry.register(new BaseTool("client_look", "Look around",
                "Sets the view rotation. Provide absolute yaw/pitch (degrees), or delta_yaw/delta_pitch for relative "
                        + "turns, or x/y/z to look at a point in the world.",
                lookSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                boolean hasPoint = copy.has("x") && copy.has("y") && copy.has("z");
                return CompletableFuture.completedFuture(bridge.read(() -> {
                    if (bridge.minecraft().level == null || bridge.minecraft().player == null) {
                        throw new ToolError("No world is loaded.");
                    }
                    if (hasPoint) {
                        Vec3 target = JsonUtil.requiredVec(copy);
                        bridge.players().lookAt(target);
                        return ToolResult.ok("Looking at " + JsonUtil.formatVec(target));
                    }
                    float yaw = bridge.minecraft().player.getYRot();
                    float pitch = bridge.minecraft().player.getXRot();
                    boolean relative = JsonUtil.optionalBoolean(copy, "relative", false);
                    float wantedYaw = copy.has("yaw")
                            ? (float) copy.get("yaw").getAsDouble()
                            : (relative ? yaw + (float) JsonUtil.optionalDouble(copy, "delta_yaw", 0.0, -1080, 1080) : yaw);
                    float wantedPitch = copy.has("pitch")
                            ? (float) copy.get("pitch").getAsDouble()
                            : (relative ? pitch + (float) JsonUtil.optionalDouble(copy, "delta_pitch", 0.0, -360, 360) : pitch);
                    bridge.players().look(wantedYaw, wantedPitch);
                    JsonObject data = bridge.players().state();
                    return ToolResult.ok("Rotation set to yaw " + JsonUtil.round(wantedYaw)
                            + ", pitch " + JsonUtil.round(wantedPitch), data);
                }, "look"));
            }
        });

        registry.register(new BaseTool("client_mine", "Mine a block",
                "Breaks the block at the given coordinates through normal left-click mining: aims at it, then holds "
                        + "the attack button until the block breaks (hardness, tool and protection all apply).",
                ToolSchemas.schema(positionProps(), "x", "y", "z"), false, true) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                BlockPos pos = JsonUtil.requiredBlockPos(copy);
                return bridge.runAsync(bridge.players().mineTask(pos), 45);
            }
        });

        registry.register(new BaseTool("client_place", "Place a block",
                "Places the held block against a neighbour face at the given coordinates (right-click path). "
                        + "Optionally selects a hotbar slot (1-9) first.",
                placeSchema(), false, true) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                BlockPos pos = JsonUtil.requiredBlockPos(copy);
                String faceName = JsonUtil.optionalString(copy, "face", "up");
                Direction face = directionOf(faceName);
                if (face == null) {
                    return CompletableFuture.completedFuture(ToolResult.error("Unknown face '" + faceName
                            + "'. Use up, down, north, south, east or west."));
                }
                int hotbar = JsonUtil.optionalInt(copy, "hotbar", -1, -1, 9);
                return bridge.runAsync(bridge.players().placeTask(pos, face,
                        hotbar < 1 ? -1 : hotbar - 1), 15);
            }
        });

        registry.register(new BaseTool("client_use", "Use the held item",
                "Right-clicks with the held item (food, potions, bows, buckets, tools with an action). hold_ticks "
                        + "keeps the button down, which is required for charged items such as bows.",
                useSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                boolean offhand = JsonUtil.optionalBoolean(copy, "offhand", false);
                int holdTicks = JsonUtil.optionalInt(copy, "hold_ticks", 1, 1, 200);
                return bridge.runAsync(bridge.players().useTask(offhand, holdTicks), 20);
            }
        });

        registry.register(new BaseTool("client_attack", "Attack an entity",
                "Swings at an entity (uuid from client_entities, or a name within 32 blocks). Aims first, then "
                        + "performs a normal attack with the current held item.",
                attackSchema(), false, true) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                String uuid = JsonUtil.optionalString(copy, "uuid", "");
                String name = JsonUtil.optionalString(copy, "name", "");
                double maxDistance = JsonUtil.optionalDouble(copy, "max_distance", 3.5, 1.0, 8.0);
                String resolved = bridge.read(() -> {
                    if (!uuid.isBlank()) {
                        return uuid;
                    }
                    if (name.isBlank()) {
                        throw new ToolError("Pass 'uuid' (from client_entities) or 'name'.");
                    }
                    for (Entity entity : bridge.players().entities(32)) {
                        if (entity.getName().getString().equalsIgnoreCase(name)) {
                            return entity.getUUID().toString();
                        }
                    }
                    throw new ToolError("No entity named '" + name + "' within 32 blocks.");
                }, "resolve target");
                return bridge.runAsync(bridge.players().attackTask(resolved, maxDistance), 20);
            }
        });

        registry.register(new BaseTool("client_interact", "Use a block",
                "Right-clicks a block: opens chests/crafting tables/furnaces, presses buttons, flips levers, "
                        + "uses doors, trades with villagers, etc. Afterwards screen_state shows the opened menu.",
                interactSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                BlockPos pos = JsonUtil.requiredBlockPos(copy);
                boolean offhand = JsonUtil.optionalBoolean(copy, "offhand", false);
                return bridge.runAsync(bridge.players().interactTask(pos, offhand), 20);
            }
        });

        registry.register(new BaseTool("client_select_hotbar", "Select hotbar slot",
                "Selects hotbar slot 1-9, like pressing the number key.",
                hotbarSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                int slot = JsonUtil.requiredInt(copy, "slot");
                if (slot < 1 || slot > 9) {
                    return CompletableFuture.completedFuture(ToolResult.error("Slot must be between 1 and 9."));
                }
                JsonObject data = bridge.read(() -> {
                    bridge.players().selectHotbar(slot - 1);
                    return bridge.players().state();
                }, "hotbar");
                return CompletableFuture.completedFuture(ToolResult.ok("Selected hotbar slot " + slot, data));
            }
        });

        registry.register(new BaseTool("client_wait", "Wait",
                "Waits on the wall clock without blocking the game (use it between actions, e.g. after opening a "
                        + "world). 0.05-30 seconds.",
                waitSchema(), true, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                double seconds = JsonUtil.optionalDouble(copy, "seconds", 1.0, 0.05, 30.0);
                try {
                    Thread.sleep((long) (seconds * 1000));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.completedFuture(ToolResult.error("Interrupted."));
                }
                return CompletableFuture.completedFuture(ToolResult.ok("Waited " + seconds + "s."));
            }
        });
    }

    // ------------------------------------------------------------------ containers

    private static void registerContainer(ToolRegistry registry, ClientBridge bridge) {
        registry.register(new BaseTool("container_state", "Container contents",
                "Lists the open container menu's slots (index, item, count, GUI coordinates) plus the carried item "
                        + "and the player hotbar. Use the slot index with container_click.",
                ToolSchemas.schema(new JsonObject()), true, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject data = bridge.read(() -> bridge.containers().state(), "container state");
                String text = data.has("open") && data.get("open").getAsBoolean()
                        ? "Container open with " + data.get("slot_count").getAsInt() + " slots."
                        : "No container is open (screen: " + data.get("screen").getAsString() + ").";
                return CompletableFuture.completedFuture(ToolResult.ok(text, data));
            }
        });

        registry.register(new BaseTool("container_click", "Click a slot",
                "Clicks a container slot through the normal inventory mouse handler: normal pickup, quick move "
                        + "(shift-click), throw, swap with hotbar, drag/split, double-click collect.",
                containerClickSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                int slot = JsonUtil.requiredInt(copy, "slot");
                int button = JsonUtil.optionalInt(copy, "button", 0, 0, 2);
                String type = JsonUtil.optionalString(copy, "click_type", "normal");
                CompletableFuture<ToolResult> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        String message = bridge.read(() -> bridge.containers().click(slot, button, type),
                                "container click");
                        JsonObject data = bridge.read(() -> bridge.containers().state(), "container state");
                        return ToolResult.ok(message, data);
                    } catch (Throwable throwable) {
                        String error = throwable.getMessage() == null
                                ? throwable.getClass().getSimpleName() : throwable.getMessage();
                        return ToolResult.error("Container click failed: " + error);
                    }
                });
                return future;
            }
        });

        registry.register(new BaseTool("container_close", "Close container",
                "Closes the open container screen (equivalent to ESC), returning items in the cursor to the inventory.",
                ToolSchemas.schema(new JsonObject()), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return CompletableFuture.completedFuture(bridge.read(() -> {
                    boolean closed = bridge.containers().close();
                    JsonObject data = bridge.containers().state();
                    return closed ? ToolResult.ok("Container closed.", data)
                            : ToolResult.error("No container screen was open.", data);
                }, "close container"));
            }
        });
    }

    // ------------------------------------------------------------------ schemas

    private static JsonObject positionProps() {
        JsonObject properties = new JsonObject();
        ToolSchemas.integerPosition(properties);
        return properties;
    }

    private static JsonObject singleKey() {
        JsonObject properties = new JsonObject();
        properties.add("key", ToolSchemas.enumeration("Key name",
                "ESC", "ENTER", "TAB", "SPACE", "BACKSPACE", "DELETE", "UP", "DOWN", "LEFT", "RIGHT"));
        return properties;
    }

    private static JsonObject entitySchema() {
        JsonObject properties = new JsonObject();
        properties.add("radius", ToolSchemas.number("Search radius in blocks (default 16)", 1, 64));
        return ToolSchemas.schema(properties);
    }

    private static JsonObject clickSchema() {
        JsonObject properties = new JsonObject();
        properties.add("index", ToolSchemas.integer("Widget index from screen_state", 0, 10000));
        properties.add("text", ToolSchemas.string("Text or translation key substring to match (Chinese or English)"));
        return ToolSchemas.schema(properties);
    }

    private static JsonObject xySchema() {
        JsonObject properties = new JsonObject();
        properties.add("x", ToolSchemas.number("GUI-scaled X", 0, 10000));
        properties.add("y", ToolSchemas.number("GUI-scaled Y", 0, 10000));
        return ToolSchemas.schema(properties, "x", "y");
    }

    private static JsonObject textSchema() {
        JsonObject properties = new JsonObject();
        properties.add("value", ToolSchemas.string("Text to put in the field"));
        properties.add("index", ToolSchemas.integer("Field index (0 = first, default 0)", 0, 64));
        return ToolSchemas.schema(properties, "value");
    }

    private static JsonObject createSchema() {
        JsonObject properties = new JsonObject();
        properties.add("name", ToolSchemas.string("World (save) name"));
        properties.add("game_mode", ToolSchemas.enumeration("Game mode to cycle to",
                "survival", "creative", "adventure", "spectator", "hardcore"));
        properties.add("seed", ToolSchemas.string("Optional world seed"));
        properties.add("world_type", ToolSchemas.enumeration("Optional world type",
                "default", "flat", "large_biomes", "amplified", "single_biome"));
        properties.add("timeout_seconds", ToolSchemas.integer("Maximum time to wait (default 120)", 10, 300));
        return ToolSchemas.schema(properties, "name");
    }

    private static JsonObject joinSchema() {
        JsonObject properties = new JsonObject();
        properties.add("name", ToolSchemas.string("Exact (or partial) world name as shown in the list"));
        properties.add("index", ToolSchemas.integer("Row index in the world list (0 = first)", 0, 1000));
        properties.add("timeout_seconds", ToolSchemas.integer("Maximum time to wait (default 120)", 10, 300));
        return ToolSchemas.schema(properties);
    }

    private static JsonObject screenshotSchema() {
        JsonObject properties = new JsonObject();
        properties.add("include_gui", ToolSchemas.bool("Include HUD and open menus (default true)"));
        properties.add("max_width", ToolSchemas.integer("Downscale to this width in pixels (default 1280)", 160, 3840));
        return ToolSchemas.schema(properties);
    }

    private static JsonObject inputSchema() {
        JsonObject properties = new JsonObject();
        properties.add("forward", ToolSchemas.bool("Hold W"));
        properties.add("back", ToolSchemas.bool("Hold S"));
        properties.add("left", ToolSchemas.bool("Hold A"));
        properties.add("right", ToolSchemas.bool("Hold D"));
        properties.add("jump", ToolSchemas.bool("Hold jump"));
        properties.add("sneak", ToolSchemas.bool("Hold sneak"));
        properties.add("sprint", ToolSchemas.bool("Hold sprint"));
        properties.add("duration_ticks", ToolSchemas.integer("How long to hold, in ticks (default 20 = 1s)", 1, 600));
        properties.add("release", ToolSchemas.bool("Release every movement key instead"));
        return ToolSchemas.schema(properties);
    }

    private static JsonObject lookSchema() {
        JsonObject properties = new JsonObject();
        properties.add("yaw", ToolSchemas.number("Absolute yaw in degrees (0 = south, 90 = west)", -360, 360));
        properties.add("pitch", ToolSchemas.number("Absolute pitch in degrees (-90 = up, 90 = down)", -90, 90));
        properties.add("delta_yaw", ToolSchemas.number("Relative yaw change", -1080, 1080));
        properties.add("delta_pitch", ToolSchemas.number("Relative pitch change", -360, 360));
        properties.add("relative", ToolSchemas.bool("Treat delta_* as relative to the current rotation"));
        properties.add("x", ToolSchemas.number("Look-at X", -30000000, 30000000));
        properties.add("y", ToolSchemas.number("Look-at Y", -2048, 2048));
        properties.add("z", ToolSchemas.number("Look-at Z", -30000000, 30000000));
        return ToolSchemas.schema(properties);
    }

    private static JsonObject placeSchema() {
        JsonObject properties = new JsonObject();
        ToolSchemas.integerPosition(properties);
        properties.add("face", ToolSchemas.enumeration("Face of the target block to click",
                "up", "down", "north", "south", "east", "west"));
        properties.add("hotbar", ToolSchemas.integer("Hotbar slot 1-9 to select first", 1, 9));
        return ToolSchemas.schema(properties, "x", "y", "z");
    }

    private static JsonObject useSchema() {
        JsonObject properties = new JsonObject();
        properties.add("offhand", ToolSchemas.bool("Use the off-hand item instead"));
        properties.add("hold_ticks", ToolSchemas.integer("Hold the use button for N ticks (bows need ~20)", 1, 200));
        return ToolSchemas.schema(properties);
    }

    private static JsonObject attackSchema() {
        JsonObject properties = new JsonObject();
        properties.add("uuid", ToolSchemas.string("Entity uuid from client_entities"));
        properties.add("name", ToolSchemas.string("Entity name within 32 blocks (alternative to uuid)"));
        properties.add("max_distance", ToolSchemas.number("Refuse to attack beyond this distance (default 3.5)", 1, 8));
        return ToolSchemas.schema(properties);
    }

    private static JsonObject interactSchema() {
        JsonObject properties = new JsonObject();
        ToolSchemas.integerPosition(properties);
        properties.add("offhand", ToolSchemas.bool("Right-click with the off-hand item"));
        return ToolSchemas.schema(properties, "x", "y", "z");
    }

    private static JsonObject hotbarSchema() {
        JsonObject properties = new JsonObject();
        properties.add("slot", ToolSchemas.integer("Hotbar slot 1-9", 1, 9));
        return ToolSchemas.schema(properties, "slot");
    }

    private static JsonObject waitSchema() {
        JsonObject properties = new JsonObject();
        properties.add("seconds", ToolSchemas.number("Seconds to wait (0.05-30)", 0.05, 30));
        return ToolSchemas.schema(properties);
    }

    private static JsonObject containerClickSchema() {
        JsonObject properties = new JsonObject();
        properties.add("slot", ToolSchemas.integer("Menu slot index from container_state", 0, 200));
        properties.add("button", ToolSchemas.integer("Mouse button: 0 = left, 1 = right", 0, 2));
        properties.add("click_type", ToolSchemas.enumeration("Click type",
                "normal", "quick_move", "swap", "throw", "quick_craft", "pickup_all", "clone"));
        return ToolSchemas.schema(properties, "slot");
    }

    // ------------------------------------------------------------------ helpers

    private static ScreenDriver.Widget findByIndex(ClientBridge bridge, int index) {
        List<ScreenDriver.Widget> widgets = bridge.screens().widgets(bridge.screens().screen());
        return index >= 0 && index < widgets.size() ? widgets.get(index) : null;
    }

    private static Direction directionOf(String name) {
        if (name == null) {
            return null;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "up" -> Direction.UP;
            case "down" -> Direction.DOWN;
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }

    private static int keyCode(String name) {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "ESC", "ESCAPE" -> 256;
            case "ENTER", "RETURN" -> 257;
            case "TAB" -> 258;
            case "BACKSPACE" -> 259;
            case "SPACE" -> 32;
            case "DELETE" -> 261;
            case "UP" -> 265;
            case "DOWN" -> 264;
            case "LEFT" -> 263;
            case "RIGHT" -> 262;
            default -> -1;
        };
    }
}
