package com.xuanfei.mcpcontrol.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Captures the game framebuffer and returns a PNG for the AI. Frames are grabbed from the render
 * thread (GUI frame for menus, level frame for the world), because the framebuffer is only complete there.
 */
public final class ShotService {

    private static final Logger LOGGER = LoggerFactory.getLogger("MCP-Control/Screenshot");

    private final Minecraft minecraft;
    private volatile Pending pending;
    private volatile String lastError = "";

    public ShotService(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    private record Pending(boolean includeGui, int maxWidth, CompletableFuture<byte[]> future) {
    }

    /** Blocks the calling (HTTP) thread until the next suitable frame is captured. */
    public byte[] capture(boolean includeGui, int maxWidth, long timeoutMs) throws Exception {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        this.pending = new Pending(includeGui, Math.max(160, maxWidth), future);
        try {
            return future.get(Math.max(500L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            this.pending = null;
            throw new IllegalStateException("No frame was captured within " + timeoutMs + "ms. "
                    + "The game window may be minimised or still loading.");
        }
    }

    /** Called at the end of the HUD render pass, when no menu is open. */
    public void onHudFrame() {
        Pending current = pending;
        if (current == null || !current.includeGui() || minecraft.screen != null) {
            return;
        }
        captureNow(current);
    }

    /** Called after a screen finished rendering, so the framebuffer really contains the menu. */
    public void onScreenFrame() {
        Pending current = pending;
        if (current == null || !current.includeGui() || minecraft.screen == null) {
            return;
        }
        captureNow(current);
    }

    /** Called after the world render pass, before the GUI is drawn. */
    public void onWorldFrame() {
        Pending current = pending;
        if (current == null || current.includeGui()) {
            return;
        }
        captureNow(current);
    }

    private void captureNow(Pending current) {
        pending = null;
        try {
            Object target = minecraft.getMainRenderTarget();
            if (target == null) {
                current.future().completeExceptionally(new IllegalStateException("The main render target is not ready."));
                return;
            }
            Consumer<Object> callback = image -> {
                try {
                    byte[] png = toPng(image);
                    byte[] scaled = scale(png, current.maxWidth());
                    current.future().complete(scaled);
                } catch (Throwable throwable) {
                    lastError = String.valueOf(throwable);
                    current.future().completeExceptionally(throwable);
                }
            };
            if (!invokeScreenshot(target, callback)) {
                current.future().completeExceptionally(new IllegalStateException(
                        "Screenshot.takeScreenshot(RenderTarget, Consumer) is not present in this Minecraft version."));
            }
        } catch (Throwable throwable) {
            lastError = String.valueOf(throwable);
            LOGGER.warn("Screenshot failed", throwable);
            current.future().completeExceptionally(throwable instanceof Exception exception
                    ? exception
                    : new IllegalStateException(String.valueOf(throwable), throwable));
        }
    }

    private static boolean invokeScreenshot(Object target, Consumer<Object> callback) throws Exception {
        Class<?> screenshot = Ref.findClass("net.minecraft.client.Screenshot");
        if (screenshot == null) {
            return false;
        }
        for (Method method : screenshot.getMethods()) {
            if (!method.getName().equals("takeScreenshot") || method.getParameterCount() != 2) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (!parameters[0].isInstance(target) || !Consumer.class.isAssignableFrom(parameters[1])) {
                continue;
            }
            method.setAccessible(true);
            method.invoke(null, target, callback);
            return true;
        }
        return false;
    }

    private static byte[] toPng(Object nativeImage) throws Exception {
        if (nativeImage == null) {
            throw new IllegalStateException("The screenshot callback received no image.");
        }
        Object bytes = Ref.callOpt(nativeImage, "asByteArray");
        if (bytes instanceof byte[] array && array.length > 0) {
            return array;
        }
        Object legacy = Ref.callOpt(nativeImage, "getBytes");
        if (legacy instanceof byte[] array && array.length > 0) {
            return array;
        }
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        Ref.callOpt(nativeImage, "writeToStream", stream);
        if (stream.size() > 0) {
            return stream.toByteArray();
        }
        File temporary = File.createTempFile("mcpcontrol-shot", ".png");
        try {
            Ref.callOpt(nativeImage, "writeToFile", temporary);
            return Files.readAllBytes(temporary.toPath());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }

    private static byte[] scale(byte[] png, int maxWidth) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(png));
            if (source == null || source.getWidth() <= maxWidth) {
                return png;
            }
            int width = maxWidth;
            int height = Math.max(1, (int) Math.round(source.getHeight() * (maxWidth / (double) source.getWidth())));
            BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = resized.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(resized, "png", output);
            return output.toByteArray();
        } catch (Throwable ignored) {
            // AWT is unavailable or the image could not be decoded: return the original frame.
            return png;
        }
    }

    public String toBase64(byte[] png) {
        return Base64.getEncoder().encodeToString(png);
    }

    public JsonObject status() {
        JsonObject object = new JsonObject();
        object.addProperty("pending", pending != null);
        object.addProperty("last_error", lastError);
        object.addProperty("render_target", minecraft.getMainRenderTarget() != null);
        return object;
    }
}
