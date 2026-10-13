package com.birchmod.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import net.minecraft.client.renderer.rendertype.RenderType;

import org.joml.Matrix4f;

/**
 * Writes vertices that match whatever format a {@link RenderType} actually
 * declares.
 *
 * Every element of a vertex format must be written before the next vertex
 * begins. Miss one and the vertex is left partial, which poisons the shared
 * buffer and kills the game when Minecraft flushes it — a failure that surfaces
 * far from the code that caused it and cannot be caught there. Assuming a
 * format is how that happened once already: 26.1 moved the lines pipeline to
 * {@code POSITION_COLOR_NORMAL_LINE_WIDTH} and the old code never set the
 * width.
 *
 * So nothing here is assumed. The format and draw mode are read from the
 * pipeline at construction, each element is written only if the format declares
 * it, and anything unrecognised simply is not drawn.
 */
public final class VertexWriter {

    private final VertexFormat.Mode mode;

    private final boolean hasColor;
    private final boolean hasNormal;
    private final boolean hasLineWidth;
    private final boolean hasUv0;
    private final boolean hasUv2;
    private final boolean usable;

    public VertexWriter(RenderType type) {
        VertexFormat format = null;
        VertexFormat.Mode resolvedMode = null;
        try {
            format = type.pipeline().getVertexFormat();
            resolvedMode = type.pipeline().getVertexFormatMode();
        } catch (Throwable ignored) {
            // Fall through to the unusable state below.
        }

        this.mode = resolvedMode;
        this.usable = format != null && resolvedMode != null;
        this.hasColor = usable && format.contains(VertexFormatElement.COLOR);
        this.hasNormal = usable && format.contains(VertexFormatElement.NORMAL);
        this.hasLineWidth = usable && format.contains(VertexFormatElement.LINE_WIDTH);
        this.hasUv0 = usable && format.contains(VertexFormatElement.UV0);
        this.hasUv2 = usable && format.contains(VertexFormatElement.UV2);
    }

    /** False when the pipeline could not be inspected; callers must not draw. */
    public boolean isUsable() {
        return usable;
    }

    /** Whether this render type draws solid geometry we know how to emit. */
    public boolean supportsFill() {
        return usable && (mode == VertexFormat.Mode.QUADS || mode == VertexFormat.Mode.TRIANGLES);
    }

    /** Quads need four vertices per face, triangles need six. */
    public boolean isQuads() {
        return mode == VertexFormat.Mode.QUADS;
    }

    /**
     * Emit one complete vertex, writing exactly the elements this format
     * declares and nothing else.
     */
    public void vertex(VertexConsumer consumer,
                       Matrix4f matrix,
                       PoseStack.Pose pose,
                       float x, float y, float z,
                       int r, int g, int b, int a,
                       float nx, float ny, float nz,
                       float lineWidth) {
        if (!usable) {
            return;
        }
        VertexConsumer v = consumer.addVertex(matrix, x, y, z);
        if (hasColor) {
            v = v.setColor(r, g, b, a);
        }
        if (hasUv0) {
            v = v.setUv(0.0f, 0.0f);
        }
        if (hasUv2) {
            v = v.setUv2(240, 240); // full brightness
        }
        if (hasNormal) {
            v = v.setNormal(pose, nx, ny, nz);
        }
        if (hasLineWidth) {
            v.setLineWidth(lineWidth);
        }
    }
}
