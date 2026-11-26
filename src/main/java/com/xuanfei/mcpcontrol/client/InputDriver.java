package com.xuanfei.mcpcontrol.client;

import net.minecraft.client.Minecraft;

/**
 * Virtual key states for the local player. The states are re-applied every client tick, because the
 * vanilla key map is cleared whenever a screen opens, and cleared again when the AI releases the keys.
 */
public final class InputDriver {

    private boolean forward;
    private boolean back;
    private boolean left;
    private boolean right;
    private boolean jump;
    private boolean sneak;
    private boolean sprint;

    public void set(boolean forward, boolean back, boolean left, boolean right,
                    boolean jump, boolean sneak, boolean sprint) {
        this.forward = forward;
        this.back = back;
        this.left = left;
        this.right = right;
        this.jump = jump;
        this.sneak = sneak;
        this.sprint = sprint;
    }

    public void release() {
        set(false, false, false, false, false, false, false);
    }

    public boolean any() {
        return forward || back || left || right || jump || sneak || sprint;
    }

    public boolean forward() {
        return forward;
    }

    public boolean sprinting() {
        return sprint;
    }

    public boolean sneaking() {
        return sneak;
    }

    /** Applies (or clears) the vanilla key states. Safe to call every tick. */
    public void apply(Minecraft minecraft) {
        if (minecraft == null || minecraft.options == null) {
            return;
        }
        boolean guiOpen = minecraft.screen != null;
        minecraft.options.keyUp.setDown(!guiOpen && forward);
        minecraft.options.keyDown.setDown(!guiOpen && back);
        minecraft.options.keyLeft.setDown(!guiOpen && left);
        minecraft.options.keyRight.setDown(!guiOpen && right);
        minecraft.options.keyJump.setDown(!guiOpen && jump);
        minecraft.options.keyShift.setDown(!guiOpen && sneak);
        minecraft.options.keySprint.setDown(!guiOpen && sprint);
    }
}
