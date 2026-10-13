package com.birchmod.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

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
 * So nothing here is assumed. The format and topology are read off the render
 * type, each element is written only if the format declares it, and anything
 * unrecognised simply is not drawn.
 *
 * <h2>26.2</h2>
 * The format now comes straight off the render type rather than out of its
 * pipeline, draw mode became {@link PrimitiveTopology}, and elements are asked
 * for by their semantic name instead of by a constant — {@code VertexFormat}
 * holds a list of records now, so there is nothing to compare against. The
 * names are Mojang's own, from {@link DefaultVertexFormat}, rather than string
 * literals of my own invention.
 */
public final class VertexWriter {

    private final PrimitiveTopology topology;

    private final boolean hasColor;
    private final boolean hasNormal;
    private final boolean hasLineWidth;
    private final boolean hasUv0;
    private final boolean hasUv2;
    private final boolean usable;

    public VertexWriter(RenderType type) {
        VertexFormat format = null;
        PrimitiveTopology resolved = null;
        try {
            format = type.format();
            resolved = type.primitiveTopology();
        } catch (Throwable ignored) {
            // Fall through to the unusable state below.
        }

        this.topology = resolved;
        this.usable = format != null && resolved != null;
        this.hasColor = declares(format, DefaultVertexFormat.COLOR_SEMANTIC_NAME);
        this.hasNormal = declares(format, DefaultVertexFormat.NORMAL_SEMANTIC_NAME);
        this.hasLineWidth = declares(format, DefaultVertexFormat.LINE_WIDTH_SEMANTIC_NAME);
        this.hasUv0 = declares(format, DefaultVertexFormat.UV0_SEMANTIC_NAME);
        this.hasUv2 = declares(format, DefaultVertexFormat.UV2_SEMANTIC_NAME);
    }

    private static boolean declares(VertexFormat format, String semanticName) {
        if (format == null) {
            return false;
        }
        try {
            return format.contains(semanticName);
        } catch (Throwable ignored) {
            // A format that will not answer is one we do not write to.
            return false;
        }
    }

    /** False when the format could not be inspected; callers must not draw. */
    public boolean isUsable() {
        return usable;
    }

    /** Whether this render type draws solid geometry we know how to emit. */
    public boolean supportsFill() {
        return usable
                && (topology == PrimitiveTopology.QUADS || topology == PrimitiveTopology.TRIANGLES);
    }

    /** Quads need four vertices per face, triangles need six. */
    public boolean isQuads() {
        return topology == PrimitiveTopology.QUADS;
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
