package com.birchmod.render;

import java.util.List;

import com.birchmod.config.BirchConfig;
import com.birchmod.route.RouteBuilder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

/**
 * Draws the foraging route in the world:
 *
 * <ul>
 *   <li>a green highlight box around the central block of each routed tree,</li>
 *   <li>a tracer from the player to the next tree, and</li>
 *   <li>chained tracers hopping from that block to each subsequent stop.</li>
 * </ul>
 *
 * Tracers originate at the highlighted centre block, so the line and the marker
 * always agree on where the tree is.
 */
public class TracerRenderer {

    /** Normalised RGBA components for the line colours. */
    private static final int NEXT_R = 0x55, NEXT_G = 0xFF, NEXT_B = 0x55; // green
    private static final int CHAIN_R = 0x40, CHAIN_G = 0xC0, CHAIN_B = 0xFF; // blue
    private static final int WAIT_R = 0xFF, WAIT_G = 0xAA, WAIT_B = 0x00; // amber

    private static final float BOX_PADDING = 0.02f;

    /**
     * Resolved once and reused, so the buffer we write to and the batch we end
     * are keyed by the same instance. Calling {@code RenderTypes.lines()} twice
     * risks writing to one buffer and flushing another, which leaves our
     * vertices for Minecraft to flush later and turns any mistake here into an
     * exception raised from the middle of its own main pass.
     */
    private static final RenderType LINES = RenderTypes.lines();

    private final RouteBuilder routeBuilder;

    public TracerRenderer(RouteBuilder routeBuilder) {
        this.routeBuilder = routeBuilder;
    }

    public void render(LevelRenderContext context) {
        BirchConfig config = BirchConfig.get();
        if (!config.routeEnabled) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null || client.options.hideGui) {
            return;
        }

        List<RouteBuilder.Stop> route = routeBuilder.getRoute();
        if (route.isEmpty()) {
            return;
        }

        Camera camera = client.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        PoseStack poseStack = context.poseStack();
        MultiBufferSource.BufferSource buffers = context.bufferSource();
        VertexConsumer lines = buffers.getBuffer(LINES);

        Matrix4f matrix = poseStack.last().pose();

        // Highlight every routed tree's centre block; the first one is green,
        // later stops fade to the chain colour so the order reads at a glance.
        for (RouteBuilder.Stop stop : route) {
            boolean isNext = stop.order() == 1;
            boolean waiting = stop.etaSeconds() > 0.0 && stop.tree().isDowned();

            int r = isNext ? NEXT_R : CHAIN_R;
            int g = isNext ? NEXT_G : CHAIN_G;
            int b = isNext ? NEXT_B : CHAIN_B;
            if (waiting && isNext) {
                r = WAIT_R;
                g = WAIT_G;
                b = WAIT_B;
            }
            int alpha = isNext ? 255 : 140;

            drawBox(lines, matrix, poseStack, stop.center(), cam, r, g, b, alpha);
        }

        if (config.tracersEnabled) {
            drawTracers(lines, matrix, poseStack, route, client, cam, config);
        }

        buffers.endBatch(LINES);
    }

    private void drawTracers(VertexConsumer lines,
                             Matrix4f matrix,
                             PoseStack poseStack,
                             List<RouteBuilder.Stop> route,
                             Minecraft client,
                             Vec3 cam,
                             BirchConfig config) {
        // Start the tracer just below eye level so it does not blind the player.
        Vec3 eye = client.player.getEyePosition();
        Vec3 start = new Vec3(eye.x, eye.y - 0.35, eye.z);

        RouteBuilder.Stop first = route.get(0);
        Vec3 firstCenter = Vec3.atCenterOf(first.center());

        boolean waiting = first.etaSeconds() > 0.0 && first.tree().isDowned();
        int r = waiting ? WAIT_R : NEXT_R;
        int g = waiting ? WAIT_G : NEXT_G;
        int b = waiting ? WAIT_B : NEXT_B;

        drawLine(lines, matrix, poseStack, start, firstCenter, cam, r, g, b, 255);

        if (!config.chainTracers) {
            return;
        }
        // Chain onward: each tracer pings off the previous tree's centre block.
        for (int i = 0; i < route.size() - 1; i++) {
            Vec3 from = Vec3.atCenterOf(route.get(i).center());
            Vec3 to = Vec3.atCenterOf(route.get(i + 1).center());
            drawLine(lines, matrix, poseStack, from, to, cam, CHAIN_R, CHAIN_G, CHAIN_B, 120);
        }
    }

    /** A world-space line segment between two absolute points. */
    private void drawLine(VertexConsumer lines,
                          Matrix4f matrix,
                          PoseStack poseStack,
                          Vec3 from,
                          Vec3 to,
                          Vec3 cam,
                          int r, int g, int b, int a) {
        segment(lines, matrix, poseStack,
                (float) (from.x - cam.x), (float) (from.y - cam.y), (float) (from.z - cam.z),
                (float) (to.x - cam.x), (float) (to.y - cam.y), (float) (to.z - cam.z),
                r, g, b, a);
    }

    /**
     * Emit one line segment in camera-relative coordinates.
     *
     * <p>Every element of the vertex format must be written. On 26.1 the lines
     * pipeline uses {@code POSITION_COLOR_NORMAL_LINE_WIDTH}, so omitting
     * {@link VertexConsumer#setLineWidth} leaves a partial vertex in the shared
     * buffer and Minecraft throws {@code Missing elements in vertex: LineWidth}
     * later, when it flushes the main pass. That failure surfaces far from this
     * code and cannot be caught here, so the setters must stay complete.
     *
     * <p>Takes primitives rather than Vec3 because this runs twelve times per
     * highlighted tree per frame.
     */
    private void segment(VertexConsumer lines,
                         Matrix4f matrix,
                         PoseStack poseStack,
                         float x1, float y1, float z1,
                         float x2, float y2, float z2,
                         int r, int g, int b, int a) {
        // The LINES render type uses the normal as the segment direction.
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

        // Validate before touching the buffer. A vertex that is started but not
        // finished poisons the shared buffer, and Minecraft then dies flushing
        // it at the end of the pass — far away from here and impossible to
        // catch. Nothing may go wrong between the first addVertex and the last
        // setter, so every check happens first.
        if (!Float.isFinite(x1) || !Float.isFinite(y1) || !Float.isFinite(z1)
                || !Float.isFinite(x2) || !Float.isFinite(y2) || !Float.isFinite(z2)
                || !Float.isFinite(nx) || !Float.isFinite(ny) || !Float.isFinite(nz)) {
            return;
        }

        float width = (float) BirchConfig.get().lineWidth;
        if (!Float.isFinite(width) || width <= 0.0f) {
            width = 2.0f;
        }
        PoseStack.Pose pose = poseStack.last();

        lines.addVertex(matrix, x1, y1, z1)
                .setColor(r, g, b, a)
                .setNormal(pose, nx, ny, nz)
                .setLineWidth(width);
        lines.addVertex(matrix, x2, y2, z2)
                .setColor(r, g, b, a)
                .setNormal(pose, nx, ny, nz)
                .setLineWidth(width);
    }

    /** Wireframe cube around a block, drawn as twelve edges. */
    private void drawBox(VertexConsumer lines,
                         Matrix4f matrix,
                         PoseStack poseStack,
                         BlockPos pos,
                         Vec3 cam,
                         int r, int g, int b, int a) {
        double x0 = pos.getX() - cam.x - BOX_PADDING;
        double y0 = pos.getY() - cam.y - BOX_PADDING;
        double z0 = pos.getZ() - cam.z - BOX_PADDING;
        double x1 = pos.getX() + 1 - cam.x + BOX_PADDING;
        double y1 = pos.getY() + 1 - cam.y + BOX_PADDING;
        double z1 = pos.getZ() + 1 - cam.z + BOX_PADDING;

        // Bottom face
        edge(lines, matrix, poseStack, x0, y0, z0, x1, y0, z0, r, g, b, a);
        edge(lines, matrix, poseStack, x1, y0, z0, x1, y0, z1, r, g, b, a);
        edge(lines, matrix, poseStack, x1, y0, z1, x0, y0, z1, r, g, b, a);
        edge(lines, matrix, poseStack, x0, y0, z1, x0, y0, z0, r, g, b, a);

        // Top face
        edge(lines, matrix, poseStack, x0, y1, z0, x1, y1, z0, r, g, b, a);
        edge(lines, matrix, poseStack, x1, y1, z0, x1, y1, z1, r, g, b, a);
        edge(lines, matrix, poseStack, x1, y1, z1, x0, y1, z1, r, g, b, a);
        edge(lines, matrix, poseStack, x0, y1, z1, x0, y1, z0, r, g, b, a);

        // Verticals
        edge(lines, matrix, poseStack, x0, y0, z0, x0, y1, z0, r, g, b, a);
        edge(lines, matrix, poseStack, x1, y0, z0, x1, y1, z0, r, g, b, a);
        edge(lines, matrix, poseStack, x1, y0, z1, x1, y1, z1, r, g, b, a);
        edge(lines, matrix, poseStack, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    private void edge(VertexConsumer lines,
                      Matrix4f matrix,
                      PoseStack poseStack,
                      double x1, double y1, double z1,
                      double x2, double y2, double z2,
                      int r, int g, int b, int a) {
        // Box corners are already camera-relative.
        segment(lines, matrix, poseStack,
                (float) x1, (float) y1, (float) z1,
                (float) x2, (float) y2, (float) z2,
                r, g, b, a);
    }
}
