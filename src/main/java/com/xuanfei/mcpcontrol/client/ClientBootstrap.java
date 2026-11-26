package com.xuanfei.mcpcontrol.client;

import com.xuanfei.mcpcontrol.McpControlMod;
import com.xuanfei.mcpcontrol.runtime.McpRuntime;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Client entry point. Starts the MCP endpoint on the first client tick (so the AI can connect while
 * the game sits at the title screen), ticks the client subsystems, and feeds finished frames to the
 * screenshot service.
 */
@EventBusSubscriber(modid = McpControlMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientBootstrap {

    private static ClientBridge bridge;
    private static boolean startAttempted;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ensureStarted();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && bridge != null) {
            McpRuntime.attachIntegratedServer(minecraft.getSingleplayerServer());
            bridge.tick();
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (bridge != null) {
            bridge.shots().onHudFrame();
        }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (bridge != null) {
            bridge.shots().onScreenFrame();
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (bridge != null && event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            bridge.shots().onWorldFrame();
        }
    }

    private static void ensureStarted() {
        if (startAttempted) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        startAttempted = true;
        bridge = new ClientBridge(minecraft);
        McpRuntime.startClient(bridge);
    }

    public static ClientBridge bridge() {
        return bridge;
    }
}
