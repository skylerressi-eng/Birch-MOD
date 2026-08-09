package com.birchmod.render;

import java.text.DecimalFormat;

import com.birchmod.config.BirchConfig;
import com.birchmod.tracking.TreeRegenTracker;

import com.mojang.blaze3d.vertex.PoseStack;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

/**
 * Draws a floating countdown above every downed birch tree, so you can see at a
 * glance which tree comes back next.
 *
 * The label is billboarded (always faces the camera) and drawn in see-through
 * mode so it stays readable through terrain.
 */
public class TreeTimerRenderer {

    private static final DecimalFormat SEC_FMT = new DecimalFormat("#0.0");

    /** Standard nameplate scale: 1 block ≈ 40 font pixels. */
    private static final float TEXT_SCALE = 0.025f;

    /** Height above the tree base to float the label. */
    private static final double LABEL_HEIGHT = 1.5;

    /** Full-bright lightmap coordinate. */
    private static final int FULL_BRIGHT = 0xF000F0;

    // ARGB
    private static final int COLOR_PENDING = 0xFFFFAA00;
    private static final int COLOR_READY = 0xFF55FF55;

    private final TreeRegenTracker regenTracker;

    public TreeTimerRenderer(TreeRegenTracker regenTracker) {
        this.regenTracker = regenTracker;
    }

    public void render(LevelRenderContext context) {
        BirchConfig config = BirchConfig.get();
        if (!config.regenTimerEnabled || !config.worldTimersEnabled) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.options.hideGui) {
            return;
        }

        var downed = regenTracker.getDownedTrees();
        if (downed.isEmpty()) {
            return;
        }

        Camera camera = client.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.position();
        PoseStack poseStack = context.poseStack();
        MultiBufferSource.BufferSource buffers = context.bufferSource();
        Font font = client.font;

        for (TreeRegenTracker.Tree tree : downed) {
            double remaining = regenTracker.getSecondsUntilRegen(tree);
            if (remaining < 0.0) {
                continue;
            }

            boolean ready = remaining == 0.0;
            String label = ready ? "READY" : SEC_FMT.format(remaining) + "s";
            int color = ready ? COLOR_READY : COLOR_PENDING;

            drawLabel(poseStack, buffers, font, camera, cameraPos, tree.base, label, color);
        }

        // Flush the batch so the labels actually reach the screen.
        buffers.endBatch();
    }

    private void drawLabel(PoseStack poseStack,
                           MultiBufferSource buffers,
                           Font font,
                           Camera camera,
                           Vec3 cameraPos,
                           BlockPos base,
                           String label,
                           int color) {
        // World position relative to the camera — the render origin is the camera.
        double x = base.getX() + 0.5 - cameraPos.x;
        double y = base.getY() + LABEL_HEIGHT - cameraPos.y;
        double z = base.getZ() + 0.5 - cameraPos.z;

        poseStack.pushPose();
        poseStack.translate(x, y, z);
        // Billboard: adopt the camera's orientation.
        poseStack.mulPose(camera.rotation());
        // Negative X/Y flips the text the right way up in world space.
        poseStack.scale(-TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);

        Matrix4f matrix = poseStack.last().pose();
        float halfWidth = font.width(label) / 2.0f;

        font.drawInBatch(
                label,
                -halfWidth,
                0.0f,
                color,
                false,
                matrix,
                buffers,
                Font.DisplayMode.SEE_THROUGH,
                0,
                FULL_BRIGHT);

        poseStack.popPose();
    }
}
