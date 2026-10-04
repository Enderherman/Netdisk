package top.enderherman.netdisk.common.utils;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/** 图片缩略图不依赖外部进程；无法解码时保留原件且不声称封面存在。 */
@Slf4j
public final class ScaleFiler {
    private ScaleFiler() { }

    public static boolean createThumbnail(Path source, Path target, int maxDimension) {
        ImageReader reader = null;
        try (ImageInputStream stream = ImageIO.createImageInputStream(source.toFile())) {
            if (stream == null) return false;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return false;
            reader = readers.next();
            reader.setInput(stream, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width <= 0 || height <= 0 || (long) width * height > 40_000_000L) return false;
            ImageReadParam params = reader.getDefaultReadParam();
            int sampling = Math.max(1, Math.max(width, height) / 1200);
            params.setSourceSubsampling(sampling, sampling, 0, 0);
            BufferedImage image = reader.read(0, params);
            double scale = Math.min(1d, (double) maxDimension / Math.max(image.getWidth(), image.getHeight()));
            BufferedImage thumbnail = new BufferedImage(Math.max(1, (int) Math.round(image.getWidth() * scale)),
                    Math.max(1, (int) Math.round(image.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = thumbnail.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, thumbnail.getWidth(), thumbnail.getHeight());
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(image, 0, 0, thumbnail.getWidth(), thumbnail.getHeight(), null);
            } finally { graphics.dispose(); }
            return ImageIO.write(thumbnail, "jpg", target.toFile());
        } catch (IOException | RuntimeException ex) {
            log.warn("缩略图生成失败，保留原件：{}", source, ex);
            try { Files.deleteIfExists(target); }
            catch (IOException cleanup) { log.warn("缩略图临时文件清理失败：{}", target, cleanup); }
            return false;
        } finally {
            if (reader != null) reader.dispose();
        }
    }
}
