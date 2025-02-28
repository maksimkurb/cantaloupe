package edu.illinois.library.cantaloupe.processor;

import edu.illinois.library.cantaloupe.image.Dimension;
import edu.illinois.library.cantaloupe.image.Orientation;
import edu.illinois.library.cantaloupe.image.Rectangle;
import edu.illinois.library.cantaloupe.image.ScaleConstraint;
import edu.illinois.library.cantaloupe.operation.Color;
import edu.illinois.library.cantaloupe.operation.ColorTransform;
import edu.illinois.library.cantaloupe.operation.Crop;
import edu.illinois.library.cantaloupe.image.Format;
import edu.illinois.library.cantaloupe.operation.Operation;
import edu.illinois.library.cantaloupe.operation.OperationList;
import edu.illinois.library.cantaloupe.operation.ReductionFactor;
import edu.illinois.library.cantaloupe.operation.ScaleByPixels;
import edu.illinois.library.cantaloupe.operation.Sharpen;
import edu.illinois.library.cantaloupe.operation.redaction.Redaction;
import edu.illinois.library.cantaloupe.operation.overlay.ImageOverlay;
import edu.illinois.library.cantaloupe.operation.overlay.Position;
import edu.illinois.library.cantaloupe.operation.Rotate;
import edu.illinois.library.cantaloupe.operation.Scale;
import edu.illinois.library.cantaloupe.operation.Transpose;
import edu.illinois.library.cantaloupe.operation.overlay.StringOverlay;
import edu.illinois.library.cantaloupe.operation.overlay.Overlay;
import edu.illinois.library.cantaloupe.processor.codec.ImageReader;
import edu.illinois.library.cantaloupe.processor.codec.ImageReaderFactory;
import edu.illinois.library.cantaloupe.processor.resample.ResampleFilter;
import edu.illinois.library.cantaloupe.processor.resample.ResampleOp;
import edu.illinois.library.cantaloupe.util.Stopwatch;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;

/**
 * <p>Collection of methods for operating on {@link BufferedImage}s.</p>
 *
 * <h1>{@link BufferedImage} Cliff's Notes</h1>
 *
 * <p>{@link BufferedImage}s can have a variety of different internal data
 * layouts that are partially characterized by their {@link
 * BufferedImage#getType() type}, which corresponds more than anything to
 * <em>display hardware</em>, not necessarily source image data. All of the
 * standard types are limited to 8 bits per sample; larger sample sizes require
 * {@link BufferedImage#TYPE_CUSTOM}.</p>
 */
public final class Java2DUtil {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(Java2DUtil.class);

    /**
     * See the inline documentation in {@link #scale} for a rationale for
     * choosing this.
     */
    private static final Scale.Filter DEFAULT_DOWNSCALE_FILTER =
            Scale.Filter.BOX;

    /**
     * See the inline documentation in {@link #scale} for a rationale for
     * choosing this.
     */
    private static final Scale.Filter DEFAULT_UPSCALE_FILTER =
            Scale.Filter.BICUBIC;

    /**
     * Redacts regions from the given image.
     *
     * @param image           Image to redact.
     * @param fullSize        Full pre-crop image size.
     * @param appliedCrop     Crop already applied to {@literal image}.
     * @param preScale        Two-element array of scale factors already
     *                        applied to {@literal image}.
     * @param reductionFactor Reduction factor already applied to
     *                        {@literal image}.
     * @param scaleConstraint Scale constraint.
     * @param redactions      Regions of the image to redact.
     */
    static void applyRedactions(final BufferedImage image,
                                final Dimension fullSize,
                                final Crop appliedCrop,
                                final double[] preScale,
                                final ReductionFactor reductionFactor,
                                final ScaleConstraint scaleConstraint,
                                final Set<Redaction> redactions) {
        if (image != null && !redactions.isEmpty()) {
            final Stopwatch watch = new Stopwatch();

            final Graphics2D g2d = image.createGraphics();
            // High quality is not needed for drawing solid rectangles.
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_SPEED);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);

            g2d.setColor(java.awt.Color.BLACK);

            for (final Redaction redaction : redactions) {
                final Rectangle redactionRegion = redaction.getResultingRegion(
                        fullSize, scaleConstraint, appliedCrop);
                final double rfScale = reductionFactor.getScale();
                redactionRegion.setX(redactionRegion.x() * rfScale);
                redactionRegion.setY(redactionRegion.y() * rfScale);
                redactionRegion.setWidth(redactionRegion.width() * rfScale);
                redactionRegion.setHeight(redactionRegion.height() * rfScale);
                redactionRegion.scaleX(preScale[0]);
                redactionRegion.scaleY(preScale[1]);

                if (!redactionRegion.isEmpty()) {
                    LOGGER.trace("applyRedactions(): applying {} at {},{}/{}x{}",
                            redaction,
                            redactionRegion.intX(),
                            redactionRegion.intY(),
                            redactionRegion.intWidth(),
                            redactionRegion.intHeight());
                    g2d.fill(redactionRegion.toAWTRectangle());
                } else {
                    LOGGER.trace("applyRedactions(): {} is outside crop area; skipping",
                            redaction);
                }
            }
            g2d.dispose();
            LOGGER.trace("applyRedactions() executed in {}", watch);
        }
    }

    /**
     * Applies the given overlay to the given image. The overlay may be a
     * {@link StringOverlay string} or an {@link ImageOverlay image}.
     *
     * @param baseImage Image to apply the overlay on top of.
     * @param overlay   Overlay to apply to the base image.
     */
    static void applyOverlay(final BufferedImage baseImage,
                             final Overlay overlay) {
        if (overlay instanceof ImageOverlay) {
            BufferedImage overlayImage = getOverlayImage((ImageOverlay) overlay);
            if (overlayImage != null) {
                overlayImage(baseImage, overlayImage,
                        overlay.getPosition(), overlay.getInset());
            }
        } else if (overlay instanceof StringOverlay) {
            overlayString(baseImage, (StringOverlay) overlay);
        }
    }

    /**
     * @param inImage {@link BufferedImage#TYPE_CUSTOM Custom}-type image to
     *                convert.
     * @return New image of the closest non-custom type, or the input image if
     *         it is not of custom type.
     */
    public static BufferedImage convertCustomToRGB(BufferedImage inImage) {
        BufferedImage outImage = inImage;
        if (inImage.getType() == BufferedImage.TYPE_CUSTOM) {
            final Stopwatch watch = new Stopwatch();
            outImage = new BufferedImage(
                    inImage.getWidth(), inImage.getHeight(),
                    inImage.getColorModel().hasAlpha() ?
                            BufferedImage.TYPE_INT_ARGB :
                            BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = outImage.createGraphics();
            g2d.drawImage(inImage, 0, 0, null);
            g2d.dispose();
            LOGGER.trace("convertCustomToRGB(): executed in {}", watch);
        }
        return outImage;
    }

    /**
     * @param inImage Image to convert.
     * @return New image of type {@link BufferedImage#TYPE_INT_ARGB}, or the
     *         input image if it is not indexed.
     */
    static BufferedImage convertIndexedToARGB(BufferedImage inImage) {
        BufferedImage outImage = inImage;
        if (inImage.getType() == BufferedImage.TYPE_BYTE_INDEXED) {
            final Stopwatch watch = new Stopwatch();
            outImage = new BufferedImage(
                    inImage.getWidth(), inImage.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = outImage.createGraphics();
            g2d.drawImage(inImage, 0, 0, null);
            g2d.dispose();
            LOGGER.trace("convertIndexedToARGB(): executed in {}", watch);
        }
        return outImage;
    }

    /**
     * @param inImage Image to convert.
     * @return        New image with a linear RGB color space, or the input
     *                image if it is already in that space.
     */
    static BufferedImage convertColorToLinearRGB(BufferedImage inImage) {
        BufferedImage outImage = inImage;

        final ColorSpace linearCS =
                ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB);
        if (!linearCS.equals(inImage.getColorModel().getColorSpace())) {
            final Stopwatch watch = new Stopwatch();

            // Create the destination image.
            ComponentColorModel cm = new ComponentColorModel(
                    linearCS, false, false,
                    Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
            WritableRaster raster = cm.createCompatibleWritableRaster(
                    inImage.getWidth(), inImage.getHeight());
            outImage = new BufferedImage(
                    cm, raster, cm.isAlphaPremultiplied(), null);

            RenderingHints hints = new RenderingHints(null);
            ColorConvertOp op    = new ColorConvertOp(
                    inImage.getColorModel().getColorSpace(),
                    linearCS,
                    hints);
            op.filter(inImage, outImage);
            LOGGER.trace("convertColorToLinearRGB(): executed in {}", watch);
        }
        return outImage;
    }

    /**
     * @param inImage Image to convert.
     * @return        New image with sRGB color space, or the input image if it
     *                is already in that space.
     */
    static BufferedImage convertColorToSRGB(BufferedImage inImage) {
        BufferedImage outImage = inImage;

        final ColorSpace rgbCS = ColorSpace.getInstance(ColorSpace.CS_sRGB);
        if (!rgbCS.equals(inImage.getColorModel().getColorSpace())) {
            final Stopwatch watch = new Stopwatch();

            // Create the destination image.
            outImage = new BufferedImage(
                    inImage.getWidth(), inImage.getHeight(),
                    inImage.getColorModel().hasAlpha() ?
                            BufferedImage.TYPE_4BYTE_ABGR :
                            BufferedImage.TYPE_3BYTE_BGR);

            RenderingHints hints = new RenderingHints(null);
            ColorConvertOp op    = new ColorConvertOp(
                    inImage.getColorModel().getColorSpace(),
                    rgbCS,
                    hints);
            op.filter(inImage, outImage);
            LOGGER.trace("convertColorToSRGB(): executed in {}", watch);
        }
        return outImage;
    }

    /**
     * @param image      Image to crop.
     * @param region     Crop region in source image coordinates.
     * @param copyRaster Whether to copy the underlying raster. If {@code
     *                   true}, the crop will create a whole new image.
     *                   Otherwise, the returned image will share the raster of
     *                   the given image.
     * @return           Cropped image, or the input image if the given region
     *                   is a no-op.
     */
    static BufferedImage crop(BufferedImage image,
                              final Rectangle region,
                              final boolean copyRaster) {
        if (copyRaster) {
            image = cropPhysically(image, region);
        } else {
            image = cropVirtually(image, region);
        }
        return image;
    }

    /**
     * Creates an entirely new image of the given region dimensions, drawing
     * only the needed region into it.
     *
     * @see #cropVirtually(BufferedImage, Rectangle)
     * @deprecated  TODO: can this be removed?
     */
    private static BufferedImage cropPhysically(final BufferedImage inImage,
                                                final Rectangle region) {
        BufferedImage outImage = inImage;

        if (inImage.getWidth() != region.intWidth() ||
                inImage.getHeight() != region.intHeight()) {
            final Stopwatch watch = new Stopwatch();

            outImage = new BufferedImage(
                    region.intWidth(), region.intHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = outImage.createGraphics();
            g2d.drawImage(inImage, 0, 0,
                    region.intWidth(), region.intHeight(),
                    region.intX(), region.intY(),
                    region.intX() + region.intWidth(),
                    region.intY() + region.intHeight(), null);
            g2d.dispose();

            LOGGER.trace("cropPhysically(): cropped {}x{} image to {}x{} in {}",
                    inImage.getWidth(), inImage.getHeight(),
                    region.intWidth(), region.intHeight(), watch);
        }
        return outImage;
    }

    /**
     * Creates an image that appears to have the given region dimensions, but
     * wraps the raster of the given image.
     *
     * @see #cropPhysically(BufferedImage, Rectangle)
     */
    private static BufferedImage cropVirtually(final BufferedImage inImage,
                                               final Rectangle region) {
        BufferedImage outImage = inImage;

        final Dimension inSize = new Dimension(
                inImage.getWidth(), inImage.getHeight());

        if (!inSize.equals(region.size())) {
            final Stopwatch watch = new Stopwatch();

            outImage = inImage.getSubimage(
                    region.intX(), region.intY(),
                    region.intWidth(), region.intHeight());

            LOGGER.trace("cropVirtually(): cropped {}x{} image to {}x{} in {}",
                    inImage.getWidth(), inImage.getHeight(),
                    region.intWidth(), region.intHeight(), watch);
        }
        return outImage;
    }

    /**
     * <p>Crops the given image taking into account a reduction factor. (In
     * other words, the dimensions of the input image have already been halved
     * {@code reductionFactor} times but the given region is relative to the
     * full-sized image.</p>
     *
     * <p>This method should only be invoked if {@link
     * Crop#hasEffect(Dimension, OperationList)} returns {@code true}.</p>
     *
     * @param inImage         Image to crop.
     * @param crop            Crop operation. Clients should call {@link
     *                        Operation#hasEffect(Dimension, OperationList)}
     *                        before invoking.
     * @param rf              Number of times the dimensions of {@code inImage}
     *                        have already been halved relative to the full-
     *                        sized version.
     * @param scaleConstraint Scale constraint.
     * @return                Cropped image, or the input image if the given
     *                        region is a no-op. Note that the image is simply
     *                        a wrapper around the same data buffer.
     */
    static BufferedImage crop(final BufferedImage inImage,
                              final Crop crop,
                              final ReductionFactor rf,
                              final ScaleConstraint scaleConstraint) {
        final Dimension inSize = new Dimension(
                inImage.getWidth(), inImage.getHeight());
        final Rectangle roi = crop.getRectangle(inSize, rf, scaleConstraint);
        return crop(inImage, roi, false);
    }

    /**
     * @return Overlay image.
     */
    static BufferedImage getOverlayImage(ImageOverlay overlay) {
        ImageReader reader = null;
        try (InputStream is = overlay.openStream()) {
            reader = new ImageReaderFactory().newImageReader(Format.get("png"), is);
            return reader.read(0);
        } catch (IOException e) {
            LOGGER.warn("{} (skipping overlay)", e.getMessage());
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
        return null;
    }

    /**
     * @param baseImage    Image to overlay the image onto.
     * @param overlayImage Image to overlay.
     * @param position     Position of the overlaid image.
     * @param inset        Inset in pixels.
     */
    private static void overlayImage(final BufferedImage baseImage,
                                     final BufferedImage overlayImage,
                                     final Position position,
                                     final int inset) {
        if (overlayImage == null) {
            return;
        }
        final Stopwatch watch = new Stopwatch();

        final Graphics2D g2d = baseImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(baseImage, 0, 0, null);

        if (Position.REPEAT.equals(position)) {
            int startX = Math.round(baseImage.getWidth() / 2f);
            int startY = Math.round(baseImage.getHeight() / 2f);
            while (startX >= 0) {
                startX -= overlayImage.getWidth();
            }
            while (startY >= 0) {
                startY -= overlayImage.getHeight();
            }
            for (int x = startX; x < baseImage.getWidth(); x += overlayImage.getWidth()) {
                for (int y = startY; y < baseImage.getHeight(); y += overlayImage.getHeight()) {
                    g2d.drawImage(overlayImage, x, y, null);
                }
            }
        } else if (Position.SCALED.equals(position)) {
            // We want to scale the overlay to be the size of the image but also respect the inset value.
            // The inset value will be respected on all sides of the overlay so we want our scale to be the
            // size of the base image MINUS the size of the inset * 2 in both width and height
            int calculatedOverlayWidth = baseImage.getWidth() - (inset * 2);
            int calculatedOverlayHeight = baseImage.getHeight() - (inset * 2);
            ScaleByPixels scale = new ScaleByPixels(
                    (calculatedOverlayWidth > 0) ? calculatedOverlayWidth : baseImage.getWidth(),
                    (calculatedOverlayHeight > 0) ? calculatedOverlayHeight : baseImage.getHeight(),
                    ScaleByPixels.Mode.ASPECT_FIT_INSIDE);
            ScaleConstraint sc = new ScaleConstraint(1, 1);
            ReductionFactor rf = new ReductionFactor(1);
            BufferedImage scaledOverlay = Java2DUtil.scale(overlayImage, scale, sc, rf, false);
            int xOffset = inset;
            int yOffset = inset;
            if (scaledOverlay.getWidth() < baseImage.getWidth()) {
                xOffset = Math.round((baseImage.getWidth() - scaledOverlay.getWidth()) / 2f);
            }
            if (scaledOverlay.getHeight() < baseImage.getHeight()) {
                yOffset = Math.round((baseImage.getHeight() - scaledOverlay.getHeight()) / 2f);
            }
            g2d.drawImage(Java2DUtil.scale(overlayImage, scale, sc, rf, false),
                    xOffset, yOffset, null);
        } else {
            int overlayX, overlayY;
            switch (position) {
                case TOP_LEFT:
                    overlayX = inset;
                    overlayY = inset;
                    break;
                case TOP_RIGHT:
                    overlayX = baseImage.getWidth() -
                            overlayImage.getWidth() - inset;
                    overlayY = inset;
                    break;
                case BOTTOM_LEFT:
                    overlayX = inset;
                    overlayY = baseImage.getHeight() -
                            overlayImage.getHeight() - inset;
                    break;
                // case BOTTOM_RIGHT: will be handled in default:
                case TOP_CENTER:
                    overlayX = (baseImage.getWidth() -
                            overlayImage.getWidth()) / 2;
                    overlayY = inset;
                    break;
                case BOTTOM_CENTER:
                    overlayX = (baseImage.getWidth() -
                            overlayImage.getWidth()) / 2;
                    overlayY = baseImage.getHeight() -
                            overlayImage.getHeight() - inset;
                    break;
                case LEFT_CENTER:
                    overlayX = inset;
                    overlayY = (baseImage.getHeight() -
                            overlayImage.getHeight()) / 2;
                    break;
                case RIGHT_CENTER:
                    overlayX = baseImage.getWidth() -
                            overlayImage.getWidth() - inset;
                    overlayY = (baseImage.getHeight() -
                            overlayImage.getHeight()) / 2;
                    break;
                case CENTER:
                    overlayX = (baseImage.getWidth() -
                            overlayImage.getWidth()) / 2;
                    overlayY = (baseImage.getHeight() -
                            overlayImage.getHeight()) / 2;
                    break;
                default: // bottom right
                    overlayX = baseImage.getWidth() -
                            overlayImage.getWidth() - inset;
                    overlayY = baseImage.getHeight() -
                            overlayImage.getHeight() - inset;
                    break;
            }
            g2d.drawImage(overlayImage, overlayX, overlayY, null);
        }
        g2d.dispose();

        LOGGER.trace("overlayImage() executed in {}", watch);
    }

    /**
     * <p>Overlays a string onto an image.</p>
     *
     * <p>There are two main layout strategies, depending on {@link
     * StringOverlay#isWordWrap()}:</p>
     *
     * <ol>
     *     <li>With the word wrap strategy, the font size is fixed, the
     *     available width is the full width of the image, and the height is
     *     whatever needed to accommodate the wrapped lines.</li>
     *     <li>With the other strategy, the largest font size that enables the
     *     string to fit entirely within the image is used, down to a
     *     configured minimum size.</li>
     * </ol>
     *
     * @param baseImage Image to overlay a string onto.
     * @param overlay   Overlay to apply to the image.
     */
    private static void overlayString(final BufferedImage baseImage,
                                      final StringOverlay overlay) {
        if (!overlay.hasEffect()) {
            return;
        }
        final Stopwatch watch = new Stopwatch();

        final Graphics2D g2d = baseImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
        g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);

        // Graphics2D.drawString() doesn't understand newlines. Each line must
        // be drawn separately.
        Font font                 = overlay.getFont();
        float fontSize            = font.getSize();
        final int inset           = overlay.getInset();
        final int padding         = getBoxPadding(overlay);
        final int availableWidth  = baseImage.getWidth() - (inset * 2) - (padding * 2);
        final int availableHeight = baseImage.getHeight() - (inset * 2) - (padding * 2);
        boolean fits              = false;
        String[] lines;
        int[] lineWidths;
        int maxLineWidth, lineHeight, totalHeight;

        if (overlay.isWordWrap()) {
            g2d.setFont(font);
            final FontMetrics fm = g2d.getFontMetrics();
            lines = edu.illinois.library.cantaloupe.util.StringUtils
                    .wrap(overlay.getString(), fm, availableWidth)
                    .toArray(String[]::new);
            lineHeight   = fm.getHeight();
            totalHeight  = lineHeight * lines.length;
            lineWidths   = getLineWidths(lines, fm);
            maxLineWidth = Arrays.stream(lineWidths).max().orElse(0);

            if (totalHeight <= availableHeight) {
                fits = true;
            }
        } else {
            lines = StringUtils.split(overlay.getString(), "\n");
            // Starting at the initial font size, loop through smaller sizes
            // down to the minimum in order to find the largest that will fit
            // entirely within the image.
            while (true) {
                g2d.setFont(font);
                final FontMetrics fm = g2d.getFontMetrics();
                lineHeight   = fm.getHeight();
                totalHeight  = lineHeight * lines.length;
                lineWidths   = getLineWidths(lines, fm);
                maxLineWidth = Arrays.stream(lineWidths).max().orElse(0);

                // Will the overlay fit inside the image?
                if (maxLineWidth <= availableWidth &&
                        totalHeight <= availableHeight) {
                    fits = true;
                    break;
                } else {
                    if (fontSize - 1 >= overlay.getMinSize()) {
                        fontSize -= 1;
                        font = font.deriveFont(fontSize);
                    } else {
                        break;
                    }
                }
            }
        }

        if (!fits) {
            LOGGER.trace("overlayString(): {}-point ({}x{}) text won't fit in {}x{} image",
                    fontSize,
                    maxLineWidth + inset,
                    totalHeight + inset,
                    baseImage.getWidth(),
                    baseImage.getHeight());
            g2d.dispose();
            return;
        }

        LOGGER.trace("overlayString(): using {}-point font ({} min; {} max)",
                fontSize, overlay.getMinSize(),
                overlay.getFont().getSize());

        g2d.drawImage(baseImage, 0, 0, null);

        final Rectangle bgBox = getBoundingBox(overlay, inset,
                lineWidths, lineHeight,
                new Dimension(baseImage.getWidth(), baseImage.getHeight()));

        // Draw the background if it is not transparent.
        if (overlay.getBackgroundColor().getAlpha() > 0) {
            g2d.setPaint(overlay.getBackgroundColor().toColor());
            g2d.fillRect(bgBox.intX(), bgBox.intY(),
                    bgBox.intWidth(), bgBox.intHeight());
        }

        // Draw each line individually.
        for (int i = 0; i < lines.length; i++) {
            double x, y;
            switch (overlay.getPosition()) {
                case TOP_LEFT:
                    x = bgBox.x() + padding;
                    y = bgBox.y() + lineHeight * i + padding;
                    break;
                case TOP_RIGHT:
                    x = bgBox.x() + maxLineWidth - lineWidths[i] + padding;
                    y = bgBox.y() + lineHeight * i + padding;
                    break;
                case BOTTOM_LEFT:
                    x = bgBox.x() + padding;
                    y = bgBox.y() + lineHeight * i + padding;
                    break;
                // BOTTOM_RIGHT is handled in default:
                case TOP_CENTER:
                    x = bgBox.x() + (bgBox.width() - lineWidths[i]) / 2.0;
                    y = bgBox.y() + lineHeight * i + padding;
                    break;
                case BOTTOM_CENTER:
                    x = bgBox.x() + (bgBox.width() - lineWidths[i]) / 2.0;
                    y = bgBox.y() + lineHeight * i + padding;
                    break;
                case LEFT_CENTER:
                    x = bgBox.x() + padding;
                    y = bgBox.y() + lineHeight * i + padding;
                    break;
                case RIGHT_CENTER:
                    x = bgBox.x() + maxLineWidth - lineWidths[i] + padding;
                    y = bgBox.y() + lineHeight * i + padding;
                    break;
                case CENTER:
                    x = bgBox.x() + (bgBox.width() - lineWidths[i]) / 2.0;
                    y = bgBox.y() + lineHeight * i + padding;
                    break;
                default: // bottom right
                    x = bgBox.x() + maxLineWidth - lineWidths[i] + padding;
                    y = bgBox.y() + lineHeight * i + padding;
                    break;
            }

            // This is arbitrary fudge, but it seems to work OK.
            y += lineHeight * 0.73;

            // Draw the text outline.
            if (overlay.getStrokeWidth() > 0.001) {
                final FontRenderContext frc = g2d.getFontRenderContext();
                final GlyphVector gv = font.createGlyphVector(frc, lines[i]);
                final Shape shape = gv.getOutline(Math.round(x), Math.round(y));
                g2d.setStroke(new BasicStroke(overlay.getStrokeWidth()));
                g2d.setPaint(overlay.getStrokeColor().toColor());
                g2d.draw(shape);
            }

            // Draw the string.
            g2d.setPaint(overlay.getColor().toColor());
            g2d.drawString(lines[i], Math.round(x), Math.round(y));
        }
        g2d.dispose();
        LOGGER.trace("overlayString() executed in {}", watch);
    }

    private static int[] getLineWidths(String[] lines, FontMetrics fm) {
        final int[] lineWidths = new int[lines.length];
        for (int i = 0; i < lines.length; i++) {
            lineWidths[i] = fm.stringWidth(lines[i]);
        }
        return lineWidths;
    }

    private static Rectangle getBoundingBox(final StringOverlay overlay,
                                            final int inset,
                                            final int[] lineWidths,
                                            final int lineHeight,
                                            final Dimension imageSize) {
        // If the overlay background is visible, add some padding between the
        // text and the margin.
        final int padding = getBoxPadding(overlay);
        final double boxWidth = NumberUtils.max(lineWidths) + padding * 2;
        final double boxHeight = lineHeight * lineWidths.length + padding * 2;
        double boxX, boxY;
        switch (overlay.getPosition()) {
            case TOP_LEFT:
                boxX = inset;
                boxY = inset;
                break;
            case TOP_CENTER:
                boxX = (imageSize.width() - boxWidth) / 2.0;
                boxY = inset;
                break;
            case TOP_RIGHT:
                boxX = imageSize.width() - boxWidth - inset - padding;
                boxY = inset;
                break;
            case LEFT_CENTER:
                boxX = inset;
                boxY = (imageSize.height() - boxHeight) / 2.0;
                break;
            case RIGHT_CENTER:
                boxX = imageSize.width() - boxWidth - inset - padding;
                boxY = (imageSize.height() - boxHeight) / 2.0;
                break;
            case CENTER:
                boxX = (imageSize.width() - boxWidth) / 2.0;
                boxY = (imageSize.height() - boxHeight) / 2.0;
                break;
            case BOTTOM_LEFT:
                boxX = inset;
                boxY = imageSize.height() - boxHeight - inset - padding;
                break;
            case BOTTOM_CENTER:
                boxX = (imageSize.width() - boxWidth) / 2.0;
                boxY = imageSize.height() - boxHeight - inset - padding;
                break;
            default: // bottom right
                boxX = imageSize.width() - boxWidth - inset - padding;
                boxY = imageSize.height() - boxHeight - inset - padding;
                break;
        }
        return new Rectangle(boxX, boxY, boxWidth, boxHeight);
    }

    private static int getBoxPadding(StringOverlay overlay) {
        return (overlay.getBackgroundColor().getAlpha() > 0) ? 5 : 0;
    }

    /**
     * @param width      Width of the new image.
     * @param height     Height of the new image.
     * @param colorModel Color model of the new image.
     * @param alpha      Whether the resulting image should have alpha.
     * @return           New image with the given color model and dimensions.
     */
    private static BufferedImage newImage(final int width,
                                          final int height,
                                          ColorModel colorModel,
                                          final boolean alpha) {
        final boolean isAlphaPremultiplied = colorModel.isAlphaPremultiplied();

        // Create a compatible ColorModel.
        if (colorModel instanceof ComponentColorModel) {
            int[] componentSizes = colorModel.getComponentSize();
            // If the array does not contain an alpha element but we need it,
            // add it.
            if (!colorModel.hasAlpha() && alpha) {
                int[] tmp = new int[componentSizes.length + 1];
                System.arraycopy(componentSizes, 0, tmp, 0, componentSizes.length);
                tmp[tmp.length - 1] = tmp[0];
                componentSizes = tmp;
            }
            colorModel = new ComponentColorModel(colorModel.getColorSpace(),
                    componentSizes, alpha, isAlphaPremultiplied,
                    colorModel.getTransparency(), colorModel.getTransferType());
        }

        WritableRaster raster =
                colorModel.createCompatibleWritableRaster(width, height);
        return new BufferedImage(colorModel, raster, isAlphaPremultiplied, null);
    }

    /**
     * Reduces an image's sample/component size to 8 bits if greater. This
     * involves copying it into a new {@link BufferedImage}, which is expensive.
     *
     * @param inImage Image to reduce.
     * @return        Reduced image, or the input image if it already is 8 bits
     *                or less.
     */
    static BufferedImage reduceTo8Bits(final BufferedImage inImage) {
        BufferedImage outImage = inImage;
        final ColorModel inColorModel = inImage.getColorModel();

        if (inColorModel.getComponentSize(0) > 8) {
            final Stopwatch watch = new Stopwatch();

            int type;
            if (inColorModel.getNumComponents() > 1) {
                type = inColorModel.hasAlpha() ?
                        BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            } else {
                type = BufferedImage.TYPE_BYTE_GRAY;
            }

            outImage = new BufferedImage(
                    inImage.getWidth(), inImage.getHeight(), type);
            final ColorConvertOp op = new ColorConvertOp(
                    inColorModel.getColorSpace(),
                    outImage.getColorModel().getColorSpace(), null);
            outImage.createGraphics().drawImage(inImage, op, 0, 0);
            LOGGER.trace("reduceTo8Bits(): executed in {}", watch);
        }
        return outImage;
    }

    /**
     * Creates a new image without alpha, paints the given background color
     * into it, draws the RGB channels of the given image into it, and returns
     * it.
     *
     * @param inImage Image with alpha.
     * @return        Flattened image, or the input image if it has no alpha.
     */
    public static BufferedImage removeAlpha(final BufferedImage inImage,
                                            final Color bgColor) {
        BufferedImage outImage = inImage;
        if (inImage.getColorModel().hasAlpha()) {
            final Stopwatch watch = new Stopwatch();

            outImage = new BufferedImage(inImage.getWidth(),
                    inImage.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = outImage.createGraphics();

            if (bgColor != null) {
                g2d.setBackground(bgColor.toColor());
                g2d.clearRect(0, 0, inImage.getWidth(), inImage.getHeight());
            }

            g2d.drawImage(inImage, 0, 0, null);
            g2d.dispose();
            LOGGER.trace("removeAlpha(): executed in {}", watch);
        }
        return outImage;
    }

    /**
     * Alternative to {@link #rotate(BufferedImage, Rotate)} for orientations
     * other than {@link Orientation#ROTATE_0} when there is no
     * {@link Rotate} operation present.
     *
     * @param inImage     Image to rotate.
     * @param orientation Orientation.
     * @return            Rotated image, or the input image if the given
     *                    orientation is a no-op.
     */
    static BufferedImage rotate(final BufferedImage inImage,
                                final Orientation orientation) {
        return rotate(inImage, new Rotate(orientation.getDegrees()));
    }

    /**
     * @param inImage Image to rotate.
     * @param rotate  Rotate operation.
     * @return        Rotated image, or the input image if the given
     *                rotation is a no-op.  The image has alpha.
     */
    static BufferedImage rotate(final BufferedImage inImage,
                                final Rotate rotate) {
        BufferedImage outImage = inImage;
        if (rotate.hasEffect()) {
            final Stopwatch watch = new Stopwatch();
            final double radians = Math.toRadians(rotate.getDegrees());
            final int sourceWidth = inImage.getWidth();
            final int sourceHeight = inImage.getHeight();
            final int canvasWidth = (int) Math.round(Math.abs(sourceWidth *
                    Math.cos(radians)) + Math.abs(sourceHeight *
                    Math.sin(radians)));
            final int canvasHeight = (int) Math.round(Math.abs(sourceHeight *
                    Math.cos(radians)) + Math.abs(sourceWidth *
                    Math.sin(radians)));

            // note: operations happen in reverse order of declaration
            AffineTransform tx = new AffineTransform();
            // 3. translate the image to the center of the "canvas"
            tx.translate(canvasWidth / 2f, canvasHeight / 2f);
            // 2. rotate it
            tx.rotate(radians);
            // 1. translate the image so that it is rotated about the center
            tx.translate(-sourceWidth / 2f, -sourceHeight / 2f);

            final int compSize      = inImage.getColorModel().getComponentSize(0);
            final int numComponents = inImage.getColorModel().getNumComponents();

            // AffineTransformOp should be faster, but the G2D drawing method
            // is more compatible.
            if (compSize > 8 || numComponents < 3) {
                if (compSize > 8) {
                    outImage = newImage(canvasWidth, canvasHeight,
                            inImage.getColorModel(), true);
                } else {
                    outImage = new BufferedImage(
                            canvasWidth, canvasHeight,
                            BufferedImage.TYPE_INT_ARGB);
                }

                final Graphics2D g2d = outImage.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_SPEED);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

                g2d.drawImage(inImage, tx, null);
                g2d.dispose();
            } else {
                // informal test with 6000^2 image:
                // NN 14 sec, bilinear 15 sec, bicubic 16 sec
                // We'll use NN assuming that anyone doing non-right-angle
                // rotation does not require super high quality.
                AffineTransformOp op = new AffineTransformOp(
                        tx, AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
                outImage = new BufferedImage(canvasWidth, canvasHeight,
                        BufferedImage.TYPE_INT_ARGB);
                op.filter(inImage, outImage);
            }
            LOGGER.trace("rotate(): executed in {}", watch);
        }
        return outImage;
    }

    /**
     * <p>Scales an image, taking an already-applied reduction factor into
     * account. In other words, the dimensions of the input image have already
     * been halved {@link ReductionFactor#factor} times but the given scale is
     * relative to the full-sized image.</p>
     *
     * <p>If one or both target dimensions would end up being less than three
     * pixels, an empty image (with the correct dimensions) is returned.</p>
     *
     * @param inImage         Image to scale.
     * @param scale           Requested size ignoring any reduction factor. If
     *                        no resample filter is set, a default will be
     *                        used. {@link
     *                        Operation#hasEffect(Dimension, OperationList)}
     *                        should be called before invoking.
     * @param scaleConstraint Scale constraint.
     * @param reductionFactor Reduction factor that has already been applied to
     *                        {@literal inImage}.
     * @param isLinear        Whether the provided image is in a linear color
     *                        space.
     * @return                Scaled image, or the input image if the given
     *                        arguments would result in a no-op.
     */
    static BufferedImage scale(BufferedImage inImage,
                               final Scale scale,
                               final ScaleConstraint scaleConstraint,
                               final ReductionFactor reductionFactor,
                               final boolean isLinear) {
        /*
        This method uses resampling code derived from
        com.mortennobel.imagescaling (see
        https://blog.nobel-joergensen.com/2008/12/20/downscaling-images-in-java/)
        as an alternative to the resampling available in Graphics2D.
        While the performance of Graphics2D.drawImage() is OK, the quality,
        even using RenderingHints.VALUE_INTERPOLATION_BILINEAR, is horrible.
        BufferedImage.getScaledInstance() is the opposite: great quality but
        very slow.

        Subjective quality of downscale from 2288x1520 to 200x200:
        Lanczos3 > Box > Bicubic > Mitchell > Triangle > Bell > Hermite >
            BSpline > Graphics2D

        Approximate time-to-complete of same (milliseconds, 2.3GHz i7):
        Triangle: 19
        Box: 20
        Hermite: 24
        Bicubic: 25
        Mitchell: 30
        BSpline: 53
        Graphics2D: 70
        Bell: 145
        Lanczos3: 238

        Subjective quality of upscale from 2288x1520 to 3000x3000:
        Lanczos3 > Bicubic > Mitchell > Graphics2D = Triangle = Hermite >
            Bell > BSpline > Box

        Approximate time-to-complete of same (milliseconds, 2.3GHz i7):
        Triangle: 123
        Hermite: 142
        Box: 162
        Bicubic: 206
        Mitchell: 224
        BSpline: 230
        Graphics2D: 268
        Lanczos3: 355
        Bell: 468
        */

        final Dimension sourceSize = new Dimension(
                inImage.getWidth(), inImage.getHeight());
        final Dimension targetSize = scale.getResultingSize(
                sourceSize, reductionFactor, scaleConstraint);

        // ResampleFilter requires both target dimensions to be at least 3
        // pixels. (OpenSeadragon has been known to request smaller.)
        // If one or both are less than that, then given that the result is
        // virtually guaranteed to end up unrecognizable anyway, we will skip
        // the scaling step and return a fake image with the target dimensions.
        // The only alternatives would be to use a different resampler, set a
        // 3x3 floor, or error out.
        BufferedImage scaledImage = inImage;
        if (sourceSize.intWidth() >= 3 && sourceSize.intHeight() >= 3 &&
                targetSize.intWidth() >= 3 && targetSize.intHeight() >= 3) {
            if (!targetSize.equals(sourceSize)) {
                final Stopwatch watch = new Stopwatch();

                final ResampleOp resampleOp = new ResampleOp(
                        targetSize.intWidth(), targetSize.intHeight(),
                        isLinear);

                // Try to use the requested resample filter.
                ResampleFilter filter = null;
                if (scale.getFilter() != null) {
                    filter = scale.getFilter().toResampleFilter();
                }
                // No particular filter requested, so select a default.
                if (filter == null) {
                    if (targetSize.width() < sourceSize.width() ||
                            targetSize.height() < sourceSize.height()) {
                        filter = DEFAULT_DOWNSCALE_FILTER.toResampleFilter();
                    } else {
                        filter = DEFAULT_UPSCALE_FILTER.toResampleFilter();
                    }
                }
                resampleOp.setFilter(filter);

                scaledImage = resampleOp.filter(inImage, null);

                LOGGER.trace("scale(): scaled {}x{} image to {}x{} using " +
                                "a {} filter in {}",
                        sourceSize.intWidth(), sourceSize.intHeight(),
                        targetSize.intWidth(), targetSize.intHeight(),
                        filter.getName(), watch);
            }
        } else {
            // Dummy image.
            scaledImage = new BufferedImage(
                    targetSize.intWidth(),
                    targetSize.intHeight(),
                    BufferedImage.TYPE_INT_ARGB);
        }
        return scaledImage;
    }

    /**
     * @param inImage Image to sharpen.
     * @param sharpen Sharpen operation.
     * @return        Sharpened image.
     */
    static BufferedImage sharpen(final BufferedImage inImage,
                                 final Sharpen sharpen) {
        BufferedImage sharpenedImage = inImage;
        if (sharpen.hasEffect()) {
            if (inImage.getWidth() > 2 && inImage.getHeight() > 2) {
                final Stopwatch watch = new Stopwatch();

                final ResampleOp resampleOp = new ResampleOp(
                        inImage.getWidth(), inImage.getHeight(), false);
                resampleOp.setUnsharpenMask((float) sharpen.getAmount());
                sharpenedImage = resampleOp.filter(inImage, null);

                LOGGER.trace("sharpen(): sharpened by {} in {}",
                        sharpen.getAmount(), watch);
            } else {
                LOGGER.trace("sharpen(): image must be at least 3 " +
                        "pixels on a side; skipping");
            }
        }
        return sharpenedImage;
    }

    /**
     * @param inImage        Image to filter.
     * @param colorTransform Operation to apply.
     * @return               Filtered image, or the input image if the given
     *                       operation is a no-op.
     */
    static BufferedImage transformColor(final BufferedImage inImage,
                                        final ColorTransform colorTransform) {
        BufferedImage outImage = inImage;
        final Stopwatch watch = new Stopwatch();

        switch (colorTransform) {
            case GRAY:
                outImage = convertIndexedToARGB(outImage);
                grayscale(outImage);
                break;
            case BITONAL:
                outImage = convertIndexedToARGB(outImage);
                binarize(outImage);
                break;
        }
        if (outImage != inImage) {
            LOGGER.trace("transformColor(): transformed {}x{} image in {}",
                    inImage.getWidth(), inImage.getHeight(), watch);
        }
        return outImage;
    }

    /**
     * Grayscales the given image's pixels.
     */
    private static void grayscale(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb  = image.getRGB(x, y);
                int alpha = (argb >> 24) & 0xff;
                int red   = (argb >> 16) & 0xff;
                int green = (argb >> 8) & 0xff;
                int blue  = argb & 0xff;
                int luma  = (int) (0.21 * red + 0.71 * green + 0.07 * blue);
                argb      = (alpha << 24) | (luma << 16) | (luma << 8) | luma;
                image.setRGB(x, y, argb);
            }
        }
    }

    /**
     * Binarizes the given image's pixels.
     *
     * @see <a href="https://bostjan-cigan.com/java-image-binarization-using-otsus-algorithm/">
     *     Java Image Binarization Using Otsu's Algorithm</a>
     */
    private static void binarize(BufferedImage image) {
        int red;
        int newPixel;
        int threshold = otsuThreshold(image);

        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                red = new Color(image.getRGB(x, y)).getRed();
                int alpha = new Color(image.getRGB(x, y)).getAlpha();
                if (red > threshold) {
                    newPixel = 255;
                } else {
                    newPixel = 0;
                }
                Color color = new Color(newPixel, newPixel, newPixel, alpha);
                image.setRGB(x, y, color.getARGB());
            }
        }
    }

    /**
     * @return Histogram of a grayscale image.
     */
    private static int[] histogram(BufferedImage input) {
        int[] histogram = new int[256];
        for (int x = 0; x < input.getWidth(); x++) {
            for (int y = 0; y < input.getHeight(); y++) {
                int red = new Color(input.getRGB(x, y)).getRed();
                histogram[red]++;
            }
        }
        return histogram;
    }

    /**
     * @return Binary threshold using Otsu's method.
     */
    private static int otsuThreshold(BufferedImage image) {
        int[] histogram = histogram(image);
        int total = image.getHeight() * image.getWidth();

        float sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += i * histogram[i];
        }

        float sumB = 0;
        int wB = 0, wF;

        float varMax = 0;
        int threshold = 0;

        for (int i = 0; i < 256; i++) {
            wB += histogram[i];
            if (wB == 0) {
                continue;
            }
            wF = total - wB;

            if (wF == 0) {
                break;
            }

            sumB += (float) (i * histogram[i]);
            float mB = sumB / wB;
            float mF = (sum - sumB) / wF;

            float varBetween = (float) wB * (float) wF * (mB - mF) * (mB - mF);

            if (varBetween > varMax) {
                varMax = varBetween;
                threshold = i;
            }
        }
        return threshold;
    }

    /**
     * @param inImage   Image to transpose.
     * @param transpose Operation to apply.
     * @return          Transposed image.
     */
    static BufferedImage transpose(final BufferedImage inImage,
                                   final Transpose transpose) {
        final Stopwatch watch = new Stopwatch();
        AffineTransform tx = AffineTransform.getScaleInstance(-1, 1);
        switch (transpose) {
            case HORIZONTAL:
                tx.translate(-inImage.getWidth(null), 0);
                break;
            case VERTICAL:
                tx.translate(0, -inImage.getHeight(null));
                break;
        }
        AffineTransformOp op = new AffineTransformOp(tx,
                AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
        BufferedImage outImage = op.filter(inImage, null);

        LOGGER.trace("transpose(): executed in {}", watch);
        return outImage;
    }

    private Java2DUtil() {}

}
