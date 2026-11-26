package com.xuanfei.mcpcontrol.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Drives world lifecycle through the real menus: create a new single-player world, load an existing
 * one, return to the title screen, open the pause menu and respawn after death.
 *
 * <p>Every step is a genuine click on a vanilla widget, so the result is exactly what a player would
 * get (including the game's own validation, e.g. duplicate world names).</p>
 */
public final class WorldDriver {

    private static final String[] SINGLEPLAYER = {"单人游戏", "单人", "Singleplayer", "menu.singleplayer"};
    private static final String[] CREATE_BUTTON = {"创建新的世界", "创建新的世界", "Create New World", "selectWorld.create"};
    private static final String[] SAVE_AND_QUIT = {"保存并退出", "保存并回到标题", "Save and Quit", "menu.savegame",
            "断开连接", "Disconnect", "menu.disconnect"};
    private static final String[] RESPAWN = {"重生", "Respawn", "deathScreen.respawn"};
    private static final String[] PLAY_SELECTED = {"进入选中的世界", "进入选中的世界", "Play Selected World",
            "selectWorld.select", "进入世界"};

    private final Minecraft minecraft;
    private final ScreenDriver screens;

    public WorldDriver(Minecraft minecraft, ScreenDriver screens) {
        this.minecraft = minecraft;
        this.screens = screens;
    }

    public JsonObject state() {
        JsonObject object = new JsonObject();
        object.addProperty("in_world", minecraft.level != null);
        object.addProperty("singleplayer", minecraft.getSingleplayerServer() != null);
        object.addProperty("screen", screens.screenClass());
        if (minecraft.level != null) {
            object.addProperty("dimension", minecraft.level.dimension().location().toString());
            if (minecraft.player != null) {
                object.addProperty("position", String.format(Locale.ROOT, "%.1f, %.1f, %.1f",
                        minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ()));
                object.addProperty("game_mode", String.valueOf(Ref.callOpt(minecraft.gameMode, "getPlayerMode")));
            }
        }
        Object server = minecraft.getSingleplayerServer();
        if (server != null) {
            Object worldData = Ref.callOpt(server, "getWorldData");
            if (worldData != null) {
                Object levelName = Ref.callOpt(worldData, "getLevelName");
                if (levelName != null) {
                    object.addProperty("world_name", String.valueOf(levelName));
                }
            }
        }
        return object;
    }

    // ------------------------------------------------------------------ create

    public ClientTask createTask(String worldName, String gameMode, String seed, String worldType) {
        return new ClientTask() {
            private int stage;
            private int attempts;
            private int modeClicks;
            private int typeClicks;
            private String lastScreen = "";
            private boolean seedApplied;
            private String note = "";

            @Override
            public String name() {
                return "world_create";
            }

            @Override
            public int timeoutTicks() {
                return 20 * 120;
            }

            @Override
            public ClientTask.ClientOutcome tick(Minecraft mc) {
                if (mc.level != null) {
                    JsonObject data = state();
                    return ClientTask.ClientOutcome.success("World '" + worldName + "' created and entered.", data);
                }
                Screen screen = mc.screen;
                if (screen == null) {
                    attempts++;
                    int limit = stage >= 5 ? 900 : 60;
                    if (attempts > limit) {
                        return ClientTask.ClientOutcome.failure("No screen to operate: the game is neither in a world "
                                + "nor on a menu. Use screen_state to inspect, or world_leave to return to the title screen.");
                    }
                    return null;
                }
                String type = screen.getClass().getSimpleName();
                if (!type.equals(lastScreen)) {
                    lastScreen = type;
                    attempts = 0;
                }
                attempts++;
                if (attempts > 240) {
                    return ClientTask.ClientOutcome.failure("Stuck on screen '" + type + "' for "
                            + (attempts / 20) + "s. Widgets: " + brief(screen));
                }

                switch (type) {
                    case "TitleScreen" -> {
                        if (screens.clickByText(screen, SINGLEPLAYER) == null) {
                            return attempts > 20
                                    ? ClientTask.ClientOutcome.failure("Could not find the 'Singleplayer' button on the title screen.")
                                    : null;
                        }
                        return null;
                    }
                    case "SelectWorldScreen", "WorldSelectScreen" -> {
                        if (screens.clickByText(screen, CREATE_BUTTON) == null) {
                            return attempts > 20
                                    ? ClientTask.ClientOutcome.failure("Could not find the 'Create New World' button.")
                                    : null;
                        }
                        stage = 0;
                        return null;
                    }
                    case "CreateWorldScreen" -> {
                        return configure(mc, screen);
                    }
                    case "LevelLoadingScreen", "ReceivingLevelScreen", "DownloadingTerrainScreen", "ProgressScreen" -> {
                        return null;
                    }
                    default -> {
                        return null;
                    }
                }
            }

            private ClientTask.ClientOutcome configure(Minecraft mc, Screen screen) {
                if (stage == 0) {
                    ScreenDriver.Widget field = screens.findEditField(screen, 0);
                    if (field == null) {
                        return attempts > 40
                                ? ClientTask.ClientOutcome.failure("The world name field was not found on the creation screen. Widgets: " + brief(screen))
                                : null;
                    }
                    screens.setText(field, worldName);
                    stage = 1;
                    return null;
                }
                if (stage == 1 && !gameMode.isBlank()) {
                    ScreenDriver.Widget modeButton = findModeButton(screen);
                    if (modeButton == null) {
                        note = "game_mode button not found; using the default.";
                        stage = 2;
                        return null;
                    }
                    if (matchesKeyword(modeButton.text(), gameModeKeywords(gameMode))) {
                        stage = 2;
                        return null;
                    }
                    if (modeClicks++ >= 8) {
                        note = "could not reach game mode '" + gameMode + "' (current: " + modeButton.text() + ").";
                        stage = 2;
                        return null;
                    }
                    screens.click(modeButton);
                    return null;
                }
                if (stage <= 2 && (!seed.isBlank() || !worldType.isBlank())) {
                    if (hasWorldTabWidgets(screen)) {
                        stage = 3;
                        return null;
                    }
                    ScreenDriver.Widget tab = screens.findByText(screen, "世界", "World", "selectWorld.tab.world");
                    if (tab != null) {
                        screens.click(tab);
                    }
                    stage = 3;
                    return null;
                }
                if (stage == 3) {
                    if (!seed.isBlank()) {
                        ScreenDriver.Widget field = screens.findEditField(screen, 0);
                        if (field != null && hasWorldTabWidgets(screen)) {
                            screens.setText(field, seed);
                            seedApplied = true;
                        } else if (!seedApplied) {
                            note = (note.isEmpty() ? "" : note + " ") + "seed could not be entered on this screen.";
                        }
                    }
                    stage = 4;
                    return null;
                }
                if (stage == 4 && !worldType.isBlank()) {
                    ScreenDriver.Widget typeButton = screens.findByText(screen,
                            "世界类型", "World Type", "selectWorld.mapType", "selectWorld.worldType");
                    if (typeButton == null) {
                        stage = 5;
                        return null;
                    }
                    if (matchesKeyword(typeButton.text(), worldTypeKeywords(worldType))) {
                        stage = 5;
                        return null;
                    }
                    if (typeClicks++ >= 8) {
                        note = (note.isEmpty() ? "" : note + " ")
                                + "could not reach world type '" + worldType + "' (current: " + typeButton.text() + ").";
                        stage = 5;
                        return null;
                    }
                    screens.click(typeButton);
                    return null;
                }
                if (stage <= 5) {
                    ScreenDriver.Widget confirm = screens.findByText(screen, CREATE_BUTTON);
                    if (confirm == null) {
                        return attempts > 60
                                ? ClientTask.ClientOutcome.failure("The 'Create New World' confirm button was not found. Widgets: " + brief(screen))
                                : null;
                    }
                    screens.click(confirm);
                    stage = 6;
                    return null;
                }
                if (stage == 6 && attempts > 30) {
                    JsonObject data = state();
                    data.addProperty("note", note);
                    return ClientTask.ClientOutcome.failure("Clicked create but the world did not load within "
                            + (attempts / 20) + "s. Widgets: " + brief(screen), data);
                }
                return null;
            }

            private ScreenDriver.Widget findModeButton(Screen screen) {
                ScreenDriver.Widget direct = screens.findByText(screen,
                        "游戏模式", "Game Mode", "selectWorld.gameMode");
                return direct;
            }
        };
    }

    // ------------------------------------------------------------------ join

    public ClientTask joinTask(String worldName, int index) {
        return new ClientTask() {
            private int clicks;
            private int attempts;
            private String lastScreen = "";
            private int targetRow = -1;

            @Override
            public String name() {
                return "world_join";
            }

            @Override
            public int timeoutTicks() {
                return 20 * 120;
            }

            @Override
            public ClientTask.ClientOutcome tick(Minecraft mc) {
                if (mc.level != null) {
                    return ClientTask.ClientOutcome.success("World loaded.", state());
                }
                Screen screen = mc.screen;
                if (screen == null) {
                    attempts++;
                    return attempts > 60
                            ? ClientTask.ClientOutcome.failure("No screen to operate; the game is not in a world and shows no menu.")
                            : null;
                }
                String type = screen.getClass().getSimpleName();
                if (!type.equals(lastScreen)) {
                    lastScreen = type;
                    attempts = 0;
                }
                attempts++;
                if (attempts > 240) {
                    return ClientTask.ClientOutcome.failure("Stuck on screen '" + type + "'. Widgets: " + brief(screen));
                }
                if (type.equals("TitleScreen")) {
                    screens.clickByText(screen, SINGLEPLAYER);
                    return null;
                }
                if (!type.contains("World") && !type.contains("Select")) {
                    return null;
                }
                if (clicks >= 2) {
                    return null;
                }
                List<Object> entries = entries(screen);
                if (entries.isEmpty()) {
                    return attempts > 40
                            ? ClientTask.ClientOutcome.failure("No world entries found on the selection screen. Widgets: " + brief(screen))
                            : null;
                }
                if (targetRow < 0) {
                    targetRow = resolveRow(entries);
                    if (targetRow < 0) {
                        JsonObject data = new JsonObject();
                        data.addProperty("worlds", namesOf(entries));
                        return ClientTask.ClientOutcome.failure("World '" + worldName + "' was not found. "
                                + "Pass the exact world name or use index. Available: " + namesOf(entries), data);
                    }
                }
                if (clicks == 0) {
                    int[] point = rowPoint(screen, targetRow);
                    if (point == null) {
                        return ClientTask.ClientOutcome.failure("Could not locate the row for world '"
                                + worldName + "' in the list. Use screen_state and screen_click_xy instead.");
                    }
                    Object entry = entries.get(targetRow);
                    Ref.callOpt(entry, "mouseClicked", (double) point[0], (double) point[1], 0);
                    Ref.callOpt(screens.widgets(screen).isEmpty() ? null : screens.findByType(screen, "SelectionList"),
                            "setSelected", entry);
                    clicks++;
                    return null;
                }
                ScreenDriver.Widget play = screens.findByText(screen, PLAY_SELECTED);
                if (play != null) {
                    screens.click(play);
                    clicks++;
                    return null;
                }
                return attempts > 120
                        ? ClientTask.ClientOutcome.failure("The world was selected but no 'Play Selected World' "
                                + "button appeared. Widgets: " + brief(screen))
                        : null;
            }

            private int resolveRow(List<Object> entries) {
                if (index >= 0) {
                    return index < entries.size() ? index : -1;
                }
                if (worldName.isBlank()) {
                    return 0;
                }
                for (int i = 0; i < entries.size(); i++) {
                    String name = entryName(entries.get(i));
                    if (!name.isEmpty() && name.equalsIgnoreCase(worldName)) {
                        return i;
                    }
                }
                for (int i = 0; i < entries.size(); i++) {
                    String name = entryName(entries.get(i));
                    if (!name.isEmpty() && name.toLowerCase(Locale.ROOT).contains(worldName.toLowerCase(Locale.ROOT))) {
                        return i;
                    }
                }
                return -1;
            }
        };
    }

    // ------------------------------------------------------------------ leave / pause / respawn

    public ClientTask leaveTask(boolean save) {
        return new ClientTask() {
            private int stage;
            private int attempts;

            @Override
            public String name() {
                return "world_leave";
            }

            @Override
            public int timeoutTicks() {
                return 20 * 60;
            }

            @Override
            public ClientTask.ClientOutcome tick(Minecraft mc) {
                if (mc.level == null) {
                    return ClientTask.ClientOutcome.success("Already at the title screen.", state());
                }
                attempts++;
                if (attempts > 600) {
                    return ClientTask.ClientOutcome.failure("The world did not close within 30s.");
                }
                Screen screen = mc.screen;
                if (stage == 0) {
                    if (screen == null) {
                        if (!openPauseMenu(mc)) {
                            return ClientTask.ClientOutcome.failure("Could not open the pause menu.");
                        }
                        return null;
                    }
                    stage = 1;
                    return null;
                }
                if (screen == null) {
                    stage = 1;
                    return null;
                }
                if (screens.clickByText(screen, SAVE_AND_QUIT) != null) {
                    stage = 2;
                    return null;
                }
                if (attempts > 80) {
                    return ClientTask.ClientOutcome.failure("Could not find a save-and-quit button on screen '"
                            + screen.getClass().getSimpleName() + "'. Widgets: " + brief(screen));
                }
                return null;
            }
        };
    }

    public ClientTask pauseTask() {
        return new ClientTask() {
            @Override
            public String name() {
                return "game_pause";
            }

            @Override
            public int timeoutTicks() {
                return 20 * 10;
            }

            @Override
            public ClientTask.ClientOutcome tick(Minecraft mc) {
                if (mc.level == null) {
                    return ClientTask.ClientOutcome.failure("Not in a world; there is nothing to pause.");
                }
                if (mc.screen != null) {
                    return ClientTask.ClientOutcome.success("A screen is already open.", state());
                }
                return openPauseMenu(mc)
                        ? ClientTask.ClientOutcome.success("Pause menu opened.", state())
                        : ClientTask.ClientOutcome.failure("Could not open the pause menu.");
            }
        };
    }

    public ClientTask respawnTask() {
        return new ClientTask() {
            private int attempts;

            @Override
            public String name() {
                return "game_respawn";
            }

            @Override
            public int timeoutTicks() {
                return 20 * 20;
            }

            @Override
            public ClientTask.ClientOutcome tick(Minecraft mc) {
                Screen screen = mc.screen;
                attempts++;
                if (screen == null) {
                    return attempts > 5
                            ? ClientTask.ClientOutcome.success("No death screen is open.", state())
                            : null;
                }
                if (!screen.getClass().getSimpleName().contains("Death")) {
                    return ClientTask.ClientOutcome.failure("The current screen is '"
                            + screen.getClass().getSimpleName() + "', not a death screen.");
                }
                if (screens.clickByText(screen, RESPAWN) != null) {
                    return ClientTask.ClientOutcome.success("Respawn requested.");
                }
                return attempts > 40
                        ? ClientTask.ClientOutcome.failure("Could not find the respawn button. Widgets: " + brief(screen))
                        : null;
            }
        };
    }

    private boolean openPauseMenu(Minecraft mc) {
        Object pause = Ref.newInstanceIfPossible("net.minecraft.client.gui.screens.PauseScreen", true);
        if (pause == null) {
            pause = Ref.newInstanceIfPossible("net.minecraft.client.gui.screens.PauseScreen");
        }
        if (pause instanceof Screen screen) {
            mc.setScreen(screen);
            return true;
        }
        Object result = Ref.callOpt(mc, "pauseGame", false);
        return result != null;
    }

    // ------------------------------------------------------------------ helpers

    private List<Object> entries(Screen screen) {
        List<Object> result = new ArrayList<>();
        List<Object> lists = new ArrayList<>();
        for (Object child : screens.children(screen)) {
            String type = child.getClass().getSimpleName();
            if (type.contains("WorldListEntry") || type.endsWith("Entry")) {
                result.add(child);
            }
            if (type.contains("SelectionList")) {
                lists.add(child);
            }
        }
        if (result.isEmpty()) {
            for (Object list : lists) {
                Object rows = Ref.callOpt(list, "children");
                if (rows instanceof List<?> rowList) {
                    for (Object row : rowList) {
                        String type = row.getClass().getSimpleName();
                        if (type.endsWith("Entry")) {
                            result.add(row);
                        }
                    }
                }
            }
        }
        return result;
    }

    private String entryName(Object entry) {
        for (String method : new String[]{"getLevelName", "getLevelId", "getName", "getWorldName", "getDisplayName"}) {
            Object value = Ref.callOpt(entry, method);
            if (value instanceof String string && !string.isBlank()) {
                return string;
            }
        }
        Object summary = Ref.callOpt(entry, "getLevelSummary");
        if (summary != null) {
            for (String method : new String[]{"getLevelName", "getLevelId", "getDisplayName"}) {
                Object value = Ref.callOpt(summary, method);
                if (value instanceof String string && !string.isBlank()) {
                    return string;
                }
            }
        }
        Object narration = Ref.callOpt(entry, "getNarration");
        return Ref.text(narration);
    }

    private String namesOf(List<Object> entries) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(i).append(':').append(entryName(entries.get(i)));
        }
        return builder.toString();
    }

    /** Finds the pixel position of a list row (GUI-scaled coordinates). */
    private int[] rowPoint(Screen screen, int row) {
        ScreenDriver.Widget list = null;
        for (ScreenDriver.Widget widget : screens.widgets(screen)) {
            if (widget.type().contains("SelectionList") || widget.type().contains("WorldSelectionList")) {
                list = widget;
                break;
            }
        }
        if (list == null) {
            return null;
        }
        Object rowTop = Ref.callOpt(list.handle(), "getRowTop", row);
        int y;
        if (rowTop instanceof Number number) {
            y = number.intValue() + 12;
        } else {
            y = list.y() + 40 + row * 36;
        }
        int x = list.x() + Math.max(1, list.width()) / 2;
        return new int[]{x, y};
    }

    private boolean hasWorldTabWidgets(Screen screen) {
        return screens.findByText(screen, "世界类型", "World Type", "selectWorld.mapType", "selectWorld.worldType") != null;
    }

    private static boolean matchesKeyword(String text, String[] keywords) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String[] gameModeKeywords(String mode) {
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "creative" -> new String[]{"创造", "Creative"};
            case "adventure" -> new String[]{"冒险", "Adventure"};
            case "spectator" -> new String[]{"旁观", "Spectator"};
            case "hardcore" -> new String[]{"极限", "Hardcore"};
            default -> new String[]{"生存", "Survival"};
        };
    }

    private static String[] worldTypeKeywords(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "flat", "superflat" -> new String[]{"超平坦", "Superflat"};
            case "large_biomes", "largebiomes" -> new String[]{"大型生物群系", "Large Biomes"};
            case "amplified" -> new String[]{"放大化", "Amplified"};
            case "single_biome", "singlebiome" -> new String[]{"单一生物群系", "Single Biome"};
            default -> new String[]{"默认", "Default"};
        };
    }

    private String brief(Screen screen) {
        StringBuilder builder = new StringBuilder();
        int limit = 0;
        for (ScreenDriver.Widget widget : screens.widgets(screen)) {
            if (++limit > 12) {
                builder.append(" ...");
                break;
            }
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(widget.type());
            if (!widget.text().isBlank()) {
                builder.append(": ").append(widget.text());
            }
        }
        return builder.toString();
    }
}
