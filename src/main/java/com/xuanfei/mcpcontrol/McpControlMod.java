package com.xuanfei.mcpcontrol;

import com.xuanfei.mcpcontrol.command.McpCommand;
import com.xuanfei.mcpcontrol.runtime.McpRuntime;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.level.ServerPlayer;

/**
 * Entry point. The HTTP endpoint starts with the integrated/dedicated server so that a real
 * player (not a fake player) is always the thing being controlled.
 */
@Mod(McpControlMod.MOD_ID)
public final class McpControlMod {

    public static final String MOD_ID = "mcpcontrol";

    public McpControlMod(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
    }

    private void onServerStarted(ServerStartedEvent event) {
        McpRuntime.startServer(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        McpRuntime.serverStopping(event.getServer());
    }

    private void onServerTick(ServerTickEvent.Post event) {
        McpRuntime.tick(event.getServer());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        McpCommand.register(event.getDispatcher());
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            McpRuntime.onPlayerLeave(player);
        }
    }
}
