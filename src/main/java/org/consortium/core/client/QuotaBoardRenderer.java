package org.consortium.core.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.consortium.core.terminal.DeliveryTerminalBlockEntity;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.WeakHashMap;

import static org.consortium.core.client.BoardLayout.BACKGROUND;
import static org.consortium.core.client.BoardLayout.BODY_TOP;
import static org.consortium.core.client.BoardLayout.CHECK_BOX;
import static org.consortium.core.client.BoardLayout.GREEN;
import static org.consortium.core.client.BoardLayout.HEADER;
import static org.consortium.core.client.BoardLayout.HEADER_H;
import static org.consortium.core.client.BoardLayout.HEADER_LINE_1_Y;
import static org.consortium.core.client.BoardLayout.HEADER_LINE_2_Y;
import static org.consortium.core.client.BoardLayout.ICON;
import static org.consortium.core.client.BoardLayout.LINE_HEIGHT;
import static org.consortium.core.client.BoardLayout.MUTED;
import static org.consortium.core.client.BoardLayout.PAD;
import static org.consortium.core.client.BoardLayout.PX_PER_BLOCK;
import static org.consortium.core.client.BoardLayout.TEXT;
import static org.consortium.core.client.BoardLayout.TEXT_X;
import static org.consortium.core.client.BoardLayout.TRACK;
import static org.consortium.core.client.BoardLayout.WHITE;
import static org.consortium.core.client.BoardLayout.WIDE_BAR_BOTTOM;
import static org.consortium.core.client.BoardLayout.WIDE_BAR_LEFT;
import static org.consortium.core.client.BoardLayout.WIDE_BAR_RIGHT;
import static org.consortium.core.client.BoardLayout.WIDE_BAR_TOP;
import static org.consortium.core.client.BoardLayout.wideCounterRight;

/**
 * Draws the quota board over the Display Panel rectangle of a formed Delivery Station (specification v0.2, 2.3).
 * The terminal's block entity is the anchor: {@link #getRenderBoundingBox} is its cached world box, a formed
 * terminal joins the global block entity list ({@link #shouldRenderOffScreen}) so the screen shows whenever the box
 * passes the frustum test, whatever section the camera occludes.
 *
 * <p>Transform: rotate around the terminal so local +Z faces the viewer, +X is the viewer's right and +Y is up, then
 * move the origin to the bottom-left-front corner of the rectangle, 0.004 blocks in front of the panel faces (the
 * sign convention). Pixel space is 64 px per block with y down (the sign text transform). Emission order per frame,
 * one shared-buffer switch: every quad with {@code RenderType.textBackground()}, then every text with
 * {@code Font.DisplayMode.POLYGON_OFFSET} (coplanar with the panel without z-fighting, as the text display does),
 * then the item icons (fixed buffers, drawn at the end of the frame, clipped behind the panel by its depth write).
 * The screen glows: {@code FULL_BRIGHT}. The renderer owns the per-entity cache; {@code render()} allocates nothing
 * but pose pushes once the entry exists.
 */
public final class QuotaBoardRenderer implements BlockEntityRenderer<DeliveryTerminalBlockEntity> {
    private static final float PX = 1.0F / PX_PER_BLOCK;
    /** In front of the panel faces, like the sign text (0.005) and the item frame map (0.0078). */
    private static final float FACE_OFFSET = 0.004F;
    /** Depth layers in pixels toward the viewer. */
    private static final float Z_BACKGROUND = 0.0F;
    private static final float Z_BAND = 0.5F;
    private static final float Z_FILL = 1.0F;
    private static final float Z_TEXT = 1.0F;
    private static final float Z_ICON = 2.0F;
    private static final int LIGHT = LightTexture.FULL_BRIGHT;

    private final Font font;
    private final ItemRenderer itemRenderer;
    /** Identity keys: an entry vanishes with the client entity when its chunk unloads. */
    private final Map<DeliveryTerminalBlockEntity, BoardRenderCache> cache = new WeakHashMap<>();

    public QuotaBoardRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public AABB getRenderBoundingBox(DeliveryTerminalBlockEntity be) {
        return be.renderBounds();
    }

    /**
     * A formed terminal is rendered from the global list. The finer rule "only when the box spans two sections"
     * would save nothing (a handful of terminals exist server-wide) and a flip of this value is only picked up at
     * the next section compile, so the coarse rule is the safe one.
     */
    @Override
    public boolean shouldRenderOffScreen(DeliveryTerminalBlockEntity be) {
        return be.hasScreen();
    }

    @Override
    public int getViewDistance() {
        return 64;
    }

    @Override
    public void render(DeliveryTerminalBlockEntity be, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        Level level = be.getLevel();
        if (level == null || !be.hasScreen()) {
            return;
        }
        BoardRenderCache entry = entry(be);
        BoardLayout.Metrics m = entry.metrics;
        Direction facing = be.screenFacing();
        int height = be.screenHeight();
        int left = be.screenLeft();
        int w = m.widthPx();
        int h = m.heightPx();
        int page = m.pageAt(level.getGameTime());
        int first = m.firstLine(page);
        int last = m.lastLine(page);
        int offset = m.contentOffset();
        boolean wide = m.variant() == BoardLayout.Variant.WIDE;

        poseStack.pushPose();
        // The pose arrives at the terminal's min corner. Rotate around the block centre so local +Z = facing (toward
        // the viewer), +X = the viewer's right, +Y = up; then to the bottom-left-front corner of the rectangle.
        poseStack.translate(0.5F, 0.0F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        poseStack.translate(-0.5F - left, 1.0F, 0.5F + FACE_OFFSET);

        // Pixel space: top-left origin, x right, y down, z toward the viewer.
        poseStack.pushPose();
        poseStack.translate(0.0F, height, 0.0F);
        poseStack.scale(PX, -PX, PX);
        Matrix4f pose = poseStack.last().pose();
        // Glyphs are emitted at z 0 of their matrix: a second matrix puts the text one pixel in front of the bands
        // (pushPose copies the matrix, so the reference stays valid after the pop).
        poseStack.pushPose();
        poseStack.translate(0.0F, 0.0F, Z_TEXT);
        Matrix4f textPose = poseStack.last().pose();
        poseStack.popPose();

        // (1) every quad, one buffer request
        VertexConsumer quads = buffers.getBuffer(RenderType.textBackground());
        fill(quads, pose, 0, 0, w, h, Z_BACKGROUND, BACKGROUND);
        fill(quads, pose, 0, 0, w, HEADER_H, Z_BAND, HEADER);
        for (int i = first; i < last; i++) {
            BoardRenderCache.Row row = entry.rows.get(i);
            int y = m.rowY(i - first);
            if (wide) {
                int x0 = w - PAD - WIDE_BAR_LEFT;
                int x1 = w - PAD - WIDE_BAR_RIGHT;
                int y0 = y + offset + WIDE_BAR_TOP;
                int y1 = y + offset + WIDE_BAR_BOTTOM;
                fill(quads, pose, x0, y0, x1, y1, Z_BAND, TRACK);
                int filled = BoardLayout.barFill(x1 - x0, row.ratio());
                if (filled > 0) {
                    fill(quads, pose, x0, y0, x0 + filled, y1, Z_FILL, row.state().color);
                }
                if (row.state() == BoardLayout.State.DONE) {
                    checkMark(quads, pose, (x0 + x1) / 2.0F - CHECK_BOX / 2.0F, (y0 + y1) / 2.0F - CHECK_BOX / 2.0F, Z_FILL, WHITE);
                }
            } else {
                int x0 = TEXT_X;
                int x1 = w - PAD;
                int y0 = y + 21;
                int y1 = y + 23;
                fill(quads, pose, x0, y0, x1, y1, Z_BAND, TRACK);
                int filled = BoardLayout.barFill(x1 - x0, row.ratio());
                if (filled > 0) {
                    fill(quads, pose, x0, y0, x0 + filled, y1, Z_FILL, row.state().color);
                }
                if (row.state() == BoardLayout.State.DONE) {
                    checkMark(quads, pose, w - PAD - 9, y + 3, Z_FILL, GREEN);
                }
            }
        }
        // 'quads' must not be touched after the first drawInBatch: the type switch closes its buffer.

        // (2) every text
        text(entry.title, PAD, HEADER_LINE_1_Y, TEXT, textPose, buffers);
        if (entry.status != null) {
            text(entry.status, w - PAD - entry.statusWidth, HEADER_LINE_1_Y, entry.statusColor, textPose, buffers);
        }
        if (entry.day != null) {
            text(entry.day, PAD, HEADER_LINE_2_Y, TEXT, textPose, buffers);
        }
        if (entry.pageLabels.length > 0 && page < entry.pageLabels.length) {
            text(entry.pageLabels[page], w - PAD - entry.pageLabelWidths[page], HEADER_LINE_2_Y, TEXT, textPose, buffers);
        }
        if (entry.message != null) {
            text(entry.message, (w - entry.messageWidth) / 2.0F, BODY_TOP + (m.bodyHeight() - LINE_HEIGHT) / 2.0F, MUTED, textPose, buffers);
        }
        for (int i = first; i < last; i++) {
            BoardRenderCache.Row row = entry.rows.get(i);
            int y = m.rowY(i - first);
            if (wide) {
                text(row.label(), TEXT_X, y + offset + 4, TEXT, textPose, buffers);
                text(row.counter(), wideCounterRight(w) - row.counterWidth(), y + offset + 4, row.state().color, textPose, buffers);
            } else {
                text(row.label(), TEXT_X, y + 2, TEXT, textPose, buffers);
                text(row.counter(), TEXT_X, y + 12, row.state().color, textPose, buffers);
            }
        }
        poseStack.popPose(); // back to block units, y up

        // (3) icons in block units with a positive Y scale (the flipped text pose would draw them upside down)
        for (int i = first; i < last; i++) {
            BoardRenderCache.Row row = entry.rows.get(i);
            int y = m.rowY(i - first);
            float iconX = PAD + 2;
            float iconY = wide ? y + offset : y + 4;
            float cx = (iconX + ICON / 2.0F) * PX;
            float cy = height - (iconY + ICON / 2.0F) * PX;
            poseStack.pushPose();
            poseStack.translate(cx, cy, Z_ICON * PX);
            poseStack.scale(ICON * PX, ICON * PX, ICON * PX); // renderStatic centres the model on the origin
            itemRenderer.renderStatic(row.icon(), ItemDisplayContext.GUI, LIGHT, OverlayTexture.NO_OVERLAY, poseStack, buffers, level, 0);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    private BoardRenderCache entry(DeliveryTerminalBlockEntity be) {
        // Compared field by field so a frame with an up-to-date entry allocates nothing (review 2026-09-16).
        BoardRenderCache entry = cache.get(be);
        if (entry == null || !entry.stamp.matches(ClientBoard.generation(), be.syncRevision(), be.screenWidth(), be.screenHeight(), be.screenFacing())) {
            BoardRenderCache.Stamp stamp = new BoardRenderCache.Stamp(ClientBoard.generation(), be.syncRevision(), be.screenWidth(),
                    be.screenHeight(), be.screenFacing());
            entry = BoardRenderCache.build(font, stamp, ClientBoard.snapshot());
            cache.put(be, entry);
        }
        return entry;
    }

    private void text(FormattedCharSequence text, float x, float y, int color, Matrix4f pose, MultiBufferSource buffers) {
        font.drawInBatch(text, x, y, color, false, pose, buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LIGHT);
    }

    /**
     * Axis-aligned quad in pixel space. TL, BL, BR, TR is the glyph order: counter-clockwise from +Z after the
     * (S, -S, S) scale, so the default cull keeps it. POSITION_COLOR_LIGHTMAP: the light is mandatory on every vertex.
     */
    private static void fill(VertexConsumer buf, Matrix4f pose, float x0, float y0, float x1, float y1, float z, int argb) {
        buf.addVertex(pose, x0, y0, z).setColor(argb).setLight(LIGHT);
        buf.addVertex(pose, x0, y1, z).setColor(argb).setLight(LIGHT);
        buf.addVertex(pose, x1, y1, z).setColor(argb).setLight(LIGHT);
        buf.addVertex(pose, x1, y0, z).setColor(argb).setLight(LIGHT);
    }

    /** A check mark in a 7 x 7 box: a 3 x 2 stroke down-right, then a 6 x 2 stroke up-right, both rotated quads. */
    private static void checkMark(VertexConsumer buf, Matrix4f pose, float x, float y, float z, int argb) {
        stroke(buf, pose, x + 0.5F, y + 3.5F, x + 2.5F, y + 5.5F, 2.0F, z, argb);
        stroke(buf, pose, x + 2.5F, y + 5.5F, x + 6.5F, y + 1.5F, 2.0F, z, argb);
    }

    /** A line segment of the given width as one quad, wound like {@link #fill} (clockwise in y-down pixel space). */
    private static void stroke(VertexConsumer buf, Matrix4f pose, float x0, float y0, float x1, float y1, float width, float z, int argb) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len == 0) {
            return;
        }
        float nx = -dy / len * width / 2.0F;
        float ny = dx / len * width / 2.0F;
        float ax = x0 + nx, ay = y0 + ny;
        float bx = x0 - nx, by = y0 - ny;
        float cx = x1 - nx, cy = y1 - ny;
        float ex = x1 + nx, ey = y1 + ny;
        // Signed area in pixel space (y down): fill() emits a negative one, so flip a positive quad.
        float area = (ax * by - bx * ay) + (bx * cy - cx * by) + (cx * ey - ex * cy) + (ex * ay - ax * ey);
        if (area > 0) {
            buf.addVertex(pose, ex, ey, z).setColor(argb).setLight(LIGHT);
            buf.addVertex(pose, cx, cy, z).setColor(argb).setLight(LIGHT);
            buf.addVertex(pose, bx, by, z).setColor(argb).setLight(LIGHT);
            buf.addVertex(pose, ax, ay, z).setColor(argb).setLight(LIGHT);
        } else {
            buf.addVertex(pose, ax, ay, z).setColor(argb).setLight(LIGHT);
            buf.addVertex(pose, bx, by, z).setColor(argb).setLight(LIGHT);
            buf.addVertex(pose, cx, cy, z).setColor(argb).setLight(LIGHT);
            buf.addVertex(pose, ex, ey, z).setColor(argb).setLight(LIGHT);
        }
    }
}
