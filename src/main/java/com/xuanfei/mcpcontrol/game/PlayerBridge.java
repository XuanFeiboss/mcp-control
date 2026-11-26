package com.xuanfei.mcpcontrol.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.config.McpConfig;
import com.xuanfei.mcpcontrol.mcp.JsonUtil;
import com.xuanfei.mcpcontrol.mcp.ToolError;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Resolves which real player the AI is controlling. */
public final class PlayerBridge {

    private UUID boundUuid;
    private String boundName;

    public synchronized void bind(ServerPlayer player) {
        this.boundUuid = player.getUUID();
        this.boundName = player.getScoreboardName();
    }

    public synchronized void unbind() {
        this.boundUuid = null;
        this.boundName = null;
    }

    /** Drops the binding when the bound player disconnects. */
    public synchronized void onLogout(ServerPlayer player) {
        if (boundUuid != null && boundUuid.equals(player.getUUID())) {
            this.boundUuid = null;
            this.boundName = null;
        }
    }

    public synchronized String boundName() {
        return boundName;
    }

    public synchronized ServerPlayer resolve(MinecraftServer server, McpConfig config) {
        if (server == null) {
            throw new ToolError("The Minecraft server is not running yet.");
        }
        List<ServerPlayer> online = server.getPlayerList().getPlayers();

        if (boundUuid != null) {
            for (ServerPlayer player : online) {
                if (player.getUUID().equals(boundUuid)) {
                    return player;
                }
            }
            throw new ToolError("Bound player '" + boundName + "' is not online. Join with that account or call bind_player.");
        }

        if (config.defaultPlayer != null && !config.defaultPlayer.isBlank()) {
            for (ServerPlayer player : online) {
                if (player.getScoreboardName().equalsIgnoreCase(config.defaultPlayer)) {
                    return player;
                }
            }
        }

        if (online.size() == 1) {
            return online.get(0);
        }
        if (online.isEmpty()) {
            throw new ToolError("No player is online. Join the world first (or set default_player in the config).");
        }
        throw new ToolError("Multiple players are online. Call bind_player with one of: " + names(online));
    }

    public synchronized JsonObject status(MinecraftServer server, McpConfig config) {
        JsonObject object = new JsonObject();
        object.addProperty("bound_player", boundName == null ? "" : boundName);
        if (server == null) {
            object.addProperty("server", "not running");
            return object;
        }
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        object.addProperty("online_players", online.size());
        JsonArray players = new JsonArray();
        for (ServerPlayer player : online) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", player.getScoreboardName());
            entry.addProperty("uuid", player.getUUID().toString());
            entry.addProperty("dimension", player.serverLevel().dimension().location().toString());
            entry.add("position", JsonUtil.vec(player.position()));
            entry.addProperty("health", JsonUtil.round(player.getHealth()));
            entry.addProperty("gamemode", player.gameMode.getGameModeForPlayer().getName());
            entry.addProperty("op_level", server.getProfilePermissions(player.getGameProfile()));
            players.add(entry);
        }
        object.add("players", players);
        return object;
    }

    private static String names(List<ServerPlayer> players) {
        List<String> names = new ArrayList<>();
        for (ServerPlayer player : players) {
            names.add(player.getScoreboardName());
        }
        return String.join(", ", names);
    }
}
