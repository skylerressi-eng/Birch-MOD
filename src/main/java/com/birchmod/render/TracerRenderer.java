package com.birchmod.render;

import java.text.DecimalFormat;
import java.util.List;

import com.birchmod.config.BirchConfig;
import com.birchmod.route.RouteBuilder;
import com.birchmod.route.Stop;

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
 * Draws the foraging route in the world:
 *
 * <ul>
 *   <li>a green highlight on the block to mine — an actual log of the trunk,</li>
 *   <li>a tracer from the player to that block, and</li>
 *   <li>a blue line onward to the next tree, so the way on is visible before
 *       the tree you are standing at is down.</li>
 * </ul>
 *
 * Two stops are drawn by default. With {@code /route path true} the whole
 * planned loop is drawn instead.
 *
 * Tracers originate at the highlighted block, so line and marker always agree
 * on where the tree is.
 */
public class TracerRenderer {

    private static final DecimalFormat SEC_FMT = new DecimalFormat("#0");

    /** RGB components for the line and fill colours. */
    private static final int NEXT_R = 0x40, NEXT_G = 0xFF, NEXT_B = 0x40;   // green
    private static final int CHAIN_R = 0x40, CHAIN_G = 0xC0, CHAIN_B = 0xFF; // blue
    private static final int WAIT_R = 0xFF, WAIT_G = 0xAA, WAIT_B = 0x00;    // amber

    private static final float BOX_PADDING = 0.005f;

    /** Alpha of the solid block fill. Low enough to still see the wood. */
    private static final int FILL_ALPHA = 90;

    /** Stops drawn normally: the one to chop, and the one after it. */
    private static final int NEXT_TREE_STOPS = 2;

    private static final float TEXT_SCALE = 0.025f;
    private static final int FULL_BRIGHT = 0xF000F0;

    /**
     * Resolved once and reused, so the buffer we write to and the batch we end
     * are keyed by the same instance.
     */
    private static final RenderType LINES = RenderTypes.lines();
    private static final RenderType FILLED = RenderTypes.debugFilledBox();

    private static final VertexWriter LINE_WRITER = new VertexWriter(LINES);
    private static final VertexWriter FILL_WRITER = new VertexWriter(FILLED);

    /**
     * How quickly a smoothed point catches up to its target, per frame at 60fps.
     * Low enough to absorb a route reshuffle, high enough not to lag behind you.
     */
    private static final double SMOOTHING = 0.25;

    /** Snap rather than glide when the jump is this large — a different tree. */
    private static final double SNAP_DISTANCE = 24.0;

    private final RouteBuilder routeBuilder;

    /** Rendered positions, eased toward the route's actual positions. */
    private Vec3[] smoothed = new Vec3[0];
    private long lastFrameNanos = 0L;

    public TracerRenderer(RouteBuilder routeBuilder) {
        this.routeBuilder = routeBuilder;
    }

    /**
     * Ease the drawn stop positions toward where the route says they are.
     *
     * The route is rebuilt several times a second and its stops can move — a
     * tree is chopped, the order shifts, a target climbs the trunk. Drawing the
     * raw values makes the lines jump on those boundaries; easing them turns
     * each change into a short glide. Frame-rate independent, so it looks the
     * same at 30fps and 200.
     */
    private Vec3[] smoothPositions(List<Stop> route) {
        long now = System.nanoTime();
        double deltaSeconds = lastFrameNanos == 0L ? 1.0 / 60.0 : (now - lastFrameNanos) / 1.0e9;
        lastFrameNanos = now;
        deltaSeconds = Math.max(0.001, Math.min(0.25, deltaSeconds));

        // Exponential smoothing, expressed so the rate is independent of fps.
        double alpha = 1.0 - Math.pow(1.0 - SMOOTHING, deltaSeconds * 60.0);

        if (smoothed.length != route.size()) {
            smoothed = new Vec3[route.size()];
        }
        for (int i = 0; i < route.size(); i++) {
            Vec3 target = Vec3.atCenterOf(route.get(i).center());
            Vec3 current = smoothed[i];
            if (current == null || current.distanceTo(target) > SNAP_DISTANCE) {
                smoothed[i] = target;
            } else {
                smoothed[i] = current.add(target.subtract(current).scale(alpha));
            }
        }
        return smoothed;
    }

    public void render(LevelRenderContext context) {
        BirchConfig config = BirchConfig.get();
        if (!config.routeEnabled || !LINE_WRITER.isUsable()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null || client.options.hideGui) {
            return;
        }

        List<Stop> planned = routeBuilder.getRoute();
        if (planned.isEmpty()) {
            return;
        }

        // The tree to chop, and the one after it. Two stops is the whole point:
        // one box says what to mine, and one blue line says where you are going
        // when it is down. Drawing only the current tree leaves nothing
        // pointing onward, and drawing the whole loop fills a dense grove with
        // lines to trees you are not going to next. The planner still looks
        // further ahead than this; that lookahead is what lets a stop still
        // regrowing be stepped over, and it is separate from what gets drawn.
        List<Stop> route = planned.subList(0, stopsToDraw(config.showFullPath, planned.size()));

        Camera camera = client.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        PoseStack poseStack = context.poseStack();
        MultiBufferSource.BufferSource buffers = context.bufferSource();
        Matrix4f matrix = poseStack.last().pose();
        float width = lineWidth(config);
        Vec3[] points = smoothPositions(route);

        // Solid fill first, so the outline drawn after it reads on top.
        if (config.filledHighlight && FILL_WRITER.supportsFill()) {
            VertexConsumer fill = buffers.getBuffer(FILLED);
            for (Stop stop : route) {
                int[] rgb = colourFor(stop);
                fillBox(fill, matrix, poseStack, stop.center(), cam,
                        rgb[0], rgb[1], rgb[2], FILL_ALPHA, width);
            }
            buffers.endBatch(FILLED);
        }

        VertexConsumer lines = buffers.getBuffer(LINES);

        for (Stop stop : route) {
            int[] rgb = colourFor(stop);
            int alpha = stop.order() == 1 ? 255 : 170;
            drawBox(lines, matrix, poseStack, stop.center(), cam, rgb[0], rgb[1], rgb[2], alpha, width);
        }

        if (config.tracersEnabled) {
            drawTracers(lines, matrix, poseStack, route, points, client, cam, config, width, context);
        }

        buffers.endBatch(LINES);

        if (config.showRouteLabels) {
            drawLabels(buffers, poseStack, client, camera, cam, route);
        }
    }

    /**
     * How many stops to draw.
     *
     * Worth having on its own because both wrong answers have shipped. Drawing
     * one stop leaves nothing pointing onward — the line to the next tree comes
     * from having a next tree to draw it to. Drawing the whole loop fills a
     * dense grove with lines to trees you are not going to next.
     */
    static int stopsToDraw(boolean showFullPath, int planned) {
        if (planned <= 0) {
            return 0;
        }
        return showFullPath ? planned : Math.min(NEXT_TREE_STOPS, planned);
    }

    /** Green when ready to chop, amber while regrowing, blue for later stops. */
    private int[] colourFor(Stop stop) {
        boolean waiting = stop.isWaiting();
        if (stop.order() == 1) {
            return waiting
                    ? new int[]{WAIT_R, WAIT_G, WAIT_B}
                    : new int[]{NEXT_R, NEXT_G, NEXT_B};
        }
        return new int[]{CHAIN_R, CHAIN_G, CHAIN_B};
    }

    private float lineWidth(BirchConfig config) {
        float width = (float) config.lineWidth;
        return (Float.isFinite(width) && width > 0.0f) ? width : 4.0f;
    }

    // ---- Tracers ----

    private void drawTracers(VertexConsumer lines,
                             Matrix4f matrix,
                             PoseStack poseStack,
                             List<Stop> route,
                             Vec3[] points,
                             Minecraft client,
                             Vec3 cam,
                             BirchConfig config,
                             float width,
                             LevelRenderContext context) {
        // The eye position must be sampled at the same partial tick the camera
        // was. Using the raw tick position against an interpolated camera makes
        // the line's origin wobble every single frame.
        float partial = partialTick(context);
        Vec3 eye = client.player.getEyePosition(partial);
        // Start just below eye level so the line does not sit in the crosshair.
        Vec3 start = new Vec3(eye.x, eye.y - 0.35, eye.z);

        int[] rgb = colourFor(route.get(0));
        drawLine(lines, matrix, poseStack, start, points[0], cam,
                rgb[0], rgb[1], rgb[2], 255, width);

        if (!config.chainTracers) {
            return;
        }
        // Onward from the block you are mining to the next tree, so the way on
        // is visible before you have finished the tree you are standing at.
        // With the full path off this is a single line to a single tree.
        for (int i = 0; i < points.length - 1; i++) {
            drawLine(lines, matrix, poseStack, points[i], points[i + 1], cam,
                    CHAIN_R, CHAIN_G, CHAIN_B, 190, width);
        }
    }

    private float partialTick(LevelRenderContext context) {
        try {
            return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    // ---- Labels ----

    /** Numbered stops with their wait, so the order of the route is legible. */
    private void drawLabels(MultiBufferSource.BufferSource buffers,
                            PoseStack poseStack,
                            Minecraft client,
                            Camera camera,
                            Vec3 cam,
                            List<Stop> route) {
        Font font = client.font;

        for (Stop stop : route) {
            BlockPos pos = stop.center();
            double x = pos.getX() + 0.5 - cam.x;
            double y = pos.getY() + 1.1 - cam.y;
            double z = pos.getZ() + 0.5 - cam.z;

            String label = stop.etaSeconds() <= 0.5
                    ? stop.order() + " • READY"
                    : stop.order() + " • " + SEC_FMT.format(stop.etaSeconds()) + "s";

            int[] rgb = colourFor(stop);
            int argb = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];

            poseStack.pushPose();
            poseStack.translate(x, y, z);
            poseStack.mulPose(camera.rotation());
            poseStack.scale(-TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);

            font.drawInBatch(label, -font.width(label) / 2.0f, 0.0f, argb, false,
                    poseStack.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH, 0, FULL_BRIGHT);

            poseStack.popPose();
        }
        buffers.endBatch();
    }

    // ---- Geometry ----

    private void drawLine(VertexConsumer lines,
                          Matrix4f matrix,
                          PoseStack poseStack,
                          Vec3 from,
                          Vec3 to,
                          Vec3 cam,
                          int r, int g, int b, int a, float width) {
        segment(lines, matrix, poseStack,
                (float) (from.x - cam.x), (float) (from.y - cam.y), (float) (from.z - cam.z),
                (float) (to.x - cam.x), (float) (to.y - cam.y), (float) (to.z - cam.z),
                r, g, b, a, width);
    }

    /**
     * Emit one line segment in camera-relative coordinates.
     *
     * Everything is validated before the first vertex is started: a vertex left
     * half-written poisons the shared buffer and kills the game when Minecraft
     * flushes it, far from here and impossible to catch.
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

        if (!finite(x1, y1, z1) || !finite(x2, y2, z2) || !finite(nx, ny, nz)) {
            return;
        }

        PoseStack.Pose pose = poseStack.last();
        LINE_WRITER.vertex(lines, matrix, pose, x1, y1, z1, r, g, b, a, nx, ny, nz, width);
        LINE_WRITER.vertex(lines, matrix, pose, x2, y2, z2, r, g, b, a, nx, ny, nz, width);
    }

    private boolean finite(float a, float b, float c) {
        return Float.isFinite(a) && Float.isFinite(b) && Float.isFinite(c);
    }

    /** Wireframe cube around a block, drawn as twelve edges. */
    private void drawBox(VertexConsumer lines,
                         Matrix4f matrix,
                         PoseStack poseStack,
                         BlockPos pos,
                         Vec3 cam,
                         int r, int g, int b, int a, float width) {
        float x0 = (float) (pos.getX() - cam.x) - BOX_PADDING;
        float y0 = (float) (pos.getY() - cam.y) - BOX_PADDING;
        float z0 = (float) (pos.getZ() - cam.z) - BOX_PADDING;
        float x1 = (float) (pos.getX() + 1 - cam.x) + BOX_PADDING;
        float y1 = (float) (pos.getY() + 1 - cam.y) + BOX_PADDING;
        float z1 = (float) (pos.getZ() + 1 - cam.z) + BOX_PADDING;

        // Bottom
        segment(lines, matrix, poseStack, x0, y0, z0, x1, y0, z0, r, g, b, a, width);
        segment(lines, matrix, poseStack, x1, y0, z0, x1, y0, z1, r, g, b, a, width);
        segment(lines, matrix, poseStack, x1, y0, z1, x0, y0, z1, r, g, b, a, width);
        segment(lines, matrix, poseStack, x0, y0, z1, x0, y0, z0, r, g, b, a, width);
        // Top
        segment(lines, matrix, poseStack, x0, y1, z0, x1, y1, z0, r, g, b, a, width);
        segment(lines, matrix, poseStack, x1, y1, z0, x1, y1, z1, r, g, b, a, width);
        segment(lines, matrix, poseStack, x1, y1, z1, x0, y1, z1, r, g, b, a, width);
        segment(lines, matrix, poseStack, x0, y1, z1, x0, y1, z0, r, g, b, a, width);
        // Verticals
        segment(lines, matrix, poseStack, x0, y0, z0, x0, y1, z0, r, g, b, a, width);
        segment(lines, matrix, poseStack, x1, y0, z0, x1, y1, z0, r, g, b, a, width);
        segment(lines, matrix, poseStack, x1, y0, z1, x1, y1, z1, r, g, b, a, width);
        segment(lines, matrix, poseStack, x0, y0, z1, x0, y1, z1, r, g, b, a, width);
    }

    /** Solid translucent cube, so the block to mine reads at a glance. */
    private void fillBox(VertexConsumer fill,
                         Matrix4f matrix,
                         PoseStack poseStack,
                         BlockPos pos,
                         Vec3 cam,
                         int r, int g, int b, int a, float width) {
        float x0 = (float) (pos.getX() - cam.x);
        float y0 = (float) (pos.getY() - cam.y);
        float z0 = (float) (pos.getZ() - cam.z);
        float x1 = x0 + 1.0f;
        float y1 = y0 + 1.0f;
        float z1 = z0 + 1.0f;

        if (!finite(x0, y0, z0) || !finite(x1, y1, z1)) {
            return;
        }

        PoseStack.Pose pose = poseStack.last();

        // Down / up
        face(fill, matrix, pose, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, 0, -1, 0, r, g, b, a, width);
        face(fill, matrix, pose, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0, 1, 0, r, g, b, a, width);
        // North / south
        face(fill, matrix, pose, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, 0, 0, -1, r, g, b, a, width);
        face(fill, matrix, pose, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, 0, 0, 1, r, g, b, a, width);
        // West / east
        face(fill, matrix, pose, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, -1, 0, 0, r, g, b, a, width);
        face(fill, matrix, pose, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1, 0, 0, r, g, b, a, width);
    }

    /**
     * One face. Quads take the four corners directly; triangle pipelines get the
     * same corners split into two triangles.
     */
    private void face(VertexConsumer fill,
                      Matrix4f matrix,
                      PoseStack.Pose pose,
                      float ax, float ay, float az,
                      float bx, float by, float bz,
                      float cx, float cy, float cz,
                      float dx, float dy, float dz,
                      float nx, float ny, float nz,
                      int r, int g, int b, int a, float width) {
        if (FILL_WRITER.isQuads()) {
            FILL_WRITER.vertex(fill, matrix, pose, ax, ay, az, r, g, b, a, nx, ny, nz, width);
            FILL_WRITER.vertex(fill, matrix, pose, bx, by, bz, r, g, b, a, nx, ny, nz, width);
            FILL_WRITER.vertex(fill, matrix, pose, cx, cy, cz, r, g, b, a, nx, ny, nz, width);
            FILL_WRITER.vertex(fill, matrix, pose, dx, dy, dz, r, g, b, a, nx, ny, nz, width);
            return;
        }
        FILL_WRITER.vertex(fill, matrix, pose, ax, ay, az, r, g, b, a, nx, ny, nz, width);
        FILL_WRITER.vertex(fill, matrix, pose, bx, by, bz, r, g, b, a, nx, ny, nz, width);
        FILL_WRITER.vertex(fill, matrix, pose, cx, cy, cz, r, g, b, a, nx, ny, nz, width);

        FILL_WRITER.vertex(fill, matrix, pose, ax, ay, az, r, g, b, a, nx, ny, nz, width);
        FILL_WRITER.vertex(fill, matrix, pose, cx, cy, cz, r, g, b, a, nx, ny, nz, width);
        FILL_WRITER.vertex(fill, matrix, pose, dx, dy, dz, r, g, b, a, nx, ny, nz, width);
    }
}
