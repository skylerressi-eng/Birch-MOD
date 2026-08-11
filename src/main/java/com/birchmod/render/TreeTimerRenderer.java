package com.birchmod.render;

import java.text.DecimalFormat;

import com.birchmod.config.BirchConfig;
import com.birchmod.tracking.TreeRegenTracker;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

/**
 * Draws the per-tree regeneration countdown, and marks logs left behind on
 * trees that were chopped into but not finished.
 *
 * The countdown is timed against the tree's own measured history where it has
 * one, so each label reflects the tree it is attached to rather than a global
 * average. Its colour ramps from red through orange and yellow to green as the
 * tree approaches regrowth.
 */
public class TreeTimerRenderer {

    private static final DecimalFormat SEC_FMT = new DecimalFormat("#0.0");

    /** Standard nameplate scale: 1 block ≈ 40 font pixels. */
    private static final float TEXT_SCALE = 0.025f;

    /** Height above the tree base to float the label. */
    private static final double LABEL_HEIGHT = 1.5;

    /** Full-bright lightmap coordinate. */
    private static final int FULL_BRIGHT = 0xF000F0;

    // ARGB. The ramp reads at a glance: the redder the label, the longer to go.
    private static final int COLOR_FAR = 0xFFFF4040;    // red    — lots of time
    private static final int COLOR_MID = 0xFFFF9020;    // orange — some time
    private static final int COLOR_NEAR = 0xFFFFE040;   // yellow — little time
    private static final int COLOR_IMMINENT = 0xFF40FF40; // green — about to pop

    /** Leftover logs on an unfinished tree. */
    private static final int LEFTOVER_R = 0xFF, LEFTOVER_G = 0x30, LEFTOVER_B = 0x30;

    private static final float BOX_PADDING = 0.01f;

    private static final RenderType LINES = RenderTypes.lines();
    private static final VertexWriter LINE_WRITER = new VertexWriter(LINES);

    private final TreeRegenTracker regenTracker;

    public TreeTimerRenderer(TreeRegenTracker regenTracker) {
        this.regenTracker = regenTracker;
    }

    public void render(LevelRenderContext context) {
        BirchConfig config = BirchConfig.get();
        if (!config.regenTimerEnabled) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.options.hideGui) {
            return;
        }

        Camera camera = client.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.position();
        PoseStack poseStack = context.poseStack();
        MultiBufferSource.BufferSource buffers = context.bufferSource();
        Font font = client.font;

        double maxRangeSq = config.worldTimerRange * config.worldTimerRange;

        drawLeftoverLogs(context, client, cameraPos, poseStack, buffers, maxRangeSq, config);

        if (!config.worldTimersEnabled) {
            return;
        }

        var downed = regenTracker.getDownedTrees();
        if (downed.isEmpty()) {
            return;
        }

        boolean drewLabel = false;

        for (TreeRegenTracker.Tree tree : downed) {
            double remaining = regenTracker.getRemainingSeconds(tree);
            if (Double.isNaN(remaining)) {
                continue;
            }
            if (!inRange(tree.base, cameraPos, maxRangeSq)) {
                continue;
            }

            double expected = regenTracker.getExpectedRegenSeconds(tree);
            String label;
            int color;

            if (remaining <= 0.0) {
                // The estimate has run out but the logs are not back yet, so say
                // so rather than claiming the tree is ready.
                label = "due";
                color = COLOR_IMMINENT;
            } else {
                label = SEC_FMT.format(remaining) + "s";
                color = colorFor(remaining, expected);
            }

            drawLabel(poseStack, buffers, font, camera, cameraPos, tree.base, label, color);
            drewLabel = true;
        }

        if (drewLabel) {
            // Flush the batch so the labels actually reach the screen.
            buffers.endBatch();
        }
    }

    /**
     * Colour by how much of the wait is left, so the ramp means the same thing
     * whether a tree takes ten seconds or two minutes.
     */
    private int colorFor(double remaining, double expected) {
        if (expected <= 0.0) {
            return COLOR_NEAR;
        }
        double fraction = remaining / expected;
        if (fraction > 0.60) {
            return COLOR_FAR;
        }
        if (fraction > 0.35) {
            return COLOR_MID;
        }
        if (fraction > 0.15) {
            return COLOR_NEAR;
        }
        return COLOR_IMMINENT;
    }

    /**
     * Outline the logs still standing on a tree that was chopped into and left
     * unfinished, so a half-cleared trunk is easy to spot and finish.
     *
     * Only trees the tracker has watched lose logs are marked; an untouched
     * tree, or one that was already short when first seen, never is.
     */
    private void drawLeftoverLogs(LevelRenderContext context,
                                  Minecraft client,
                                  Vec3 cameraPos,
                                  PoseStack poseStack,
                                  MultiBufferSource.BufferSource buffers,
                                  double maxRangeSq,
                                  BirchConfig config) {
        if (!config.highlightLeftoverLogs || !LINE_WRITER.isUsable()) {
            return;
        }

        VertexConsumer lines = null;
        Matrix4f matrix = poseStack.last().pose();
        float width = (float) config.lineWidth;
        if (!Float.isFinite(width) || width <= 0.0f) {
            width = 4.0f;
        }

        for (TreeRegenTracker.Tree tree : regenTracker.getAllTrees()) {
            if (!tree.isPartiallyChopped()) {
                continue;
            }
            long[] wood = tree.getWoodPositions();
            if (wood.length == 0 || !inRange(tree.base, cameraPos, maxRangeSq)) {
                continue;
            }

            if (lines == null) {
                lines = buffers.getBuffer(LINES);
            }
            // Every log the tracker can still see, wherever in the tree's
            // footprint it stands — a leftover on a side trunk is exactly the
            // one that gets walked past, so a base-column outline missed it.
            for (long packed : wood) {
                drawBox(lines, matrix, poseStack,
                        BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed),
                        cameraPos, LEFTOVER_R, LEFTOVER_G, LEFTOVER_B, 220, width);
            }
        }

        if (lines != null) {
            buffers.endBatch(LINES);
        }
    }

    private boolean inRange(BlockPos pos, Vec3 cameraPos, double maxRangeSq) {
        double dx = pos.getX() + 0.5 - cameraPos.x;
        double dy = pos.getY() + LABEL_HEIGHT - cameraPos.y;
        double dz = pos.getZ() + 0.5 - cameraPos.z;
        return dx * dx + dy * dy + dz * dz <= maxRangeSq;
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

    /** Wireframe cube around one block, drawn as twelve edges. */
    private void drawBox(VertexConsumer lines,
                         Matrix4f matrix,
                         PoseStack poseStack,
                         int bx, int by, int bz,
                         Vec3 cam,
                         int r, int g, int b, int a, float width) {
        float x0 = (float) (bx - cam.x) - BOX_PADDING;
        float y0 = (float) (by - cam.y) - BOX_PADDING;
        float z0 = (float) (bz - cam.z) - BOX_PADDING;
        float x1 = (float) (bx + 1 - cam.x) + BOX_PADDING;
        float y1 = (float) (by + 1 - cam.y) + BOX_PADDING;
        float z1 = (float) (bz + 1 - cam.z) + BOX_PADDING;

        segment(lines, matrix, poseStack, x0, y0, z0, x1, y0, z0, r, g, b, a, width);
        segment(lines, matrix, poseStack, x1, y0, z0, x1, y0, z1, r, g, b, a, width);
        segment(lines, matrix, poseStack, x1, y0, z1, x0, y0, z1, r, g, b, a, width);
        segment(lines, matrix, poseStack, x0, y0, z1, x0, y0, z0, r, g, b, a, width);

        segment(lines, matrix, poseStack, x0, y1, z0, x1, y1, z0, r, g, b, a, width);
        segment(lines, matrix, poseStack, x1, y1, z0, x1, y1, z1, r, g, b, a, width);
        segment(lines, matrix, poseStack, x1, y1, z1, x0, y1, z1, r, g, b, a, width);
        segment(lines, matrix, poseStack, x0, y1, z1, x0, y1, z0, r, g, b, a, width);

        segment(lines, matrix, poseStack, x0, y0, z0, x0, y1, z0, r, g, b, a, width);
        segment(lines, matrix, poseStack, x1, y0, z0, x1, y1, z0, r, g, b, a, width);
        segment(lines, matrix, poseStack, x1, y0, z1, x1, y1, z1, r, g, b, a, width);
        segment(lines, matrix, poseStack, x0, y0, z1, x0, y1, z1, r, g, b, a, width);
    }

    /**
     * Emit one line segment. Everything is validated before the first vertex is
     * started: a half-written vertex poisons the shared buffer and kills the
     * game when Minecraft flushes it, far from here and impossible to catch.
     */
    private void segment(VertexConsumer lines,
                         Matrix4f matrix,
                         PoseStack poseStack,
                         float x1, float y1, float z1,
                         float x2, float y2, float z2,
                         int r, int g, int b, int a, float width) {
        float nx = x2 - x1;
        float ny = y2 - y1;
        float nz = z2 - z1;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0e-4f) {
            return;
        }
        nx /= length;
        ny /= length;
        nz /= length;

        if (!Float.isFinite(x1) || !Float.isFinite(y1) || !Float.isFinite(z1)
                || !Float.isFinite(x2) || !Float.isFinite(y2) || !Float.isFinite(z2)
                || !Float.isFinite(nx) || !Float.isFinite(ny) || !Float.isFinite(nz)) {
            return;
        }

        PoseStack.Pose pose = poseStack.last();
        LINE_WRITER.vertex(lines, matrix, pose, x1, y1, z1, r, g, b, a, nx, ny, nz, width);
        LINE_WRITER.vertex(lines, matrix, pose, x2, y2, z2, r, g, b, a, nx, ny, nz, width);
    }
}
