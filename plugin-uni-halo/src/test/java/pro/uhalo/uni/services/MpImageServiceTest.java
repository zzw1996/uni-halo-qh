package pro.uhalo.uni.services;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 图片转码：微信 uploadimg 要求 jpg/png 且小于 1MB，这里是「压缩阶梯」的护栏。
 *
 * <p>钉住的行为：大图必定压到 1MB 以内且仍可被读回、透明图不会报错（JPEG 无透明通道）、
 * 解不开的格式给出<b>可读原因</b>（webp 等，生成阶段据此换成标题卡片）。</p>
 */
class MpImageServiceTest {

    @Test
    void hugeImageShouldBeCompressedUnderOneMegabyte() throws Exception {
        var source = png(3200, 2400, false);

        var jpeg = MpImageService.toJpeg(source, MpImageService.CONTENT_IMAGE_MAX_BYTES, 1080);

        assertThat(jpeg.length).isLessThanOrEqualTo(MpImageService.CONTENT_IMAGE_MAX_BYTES);
        var decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isLessThanOrEqualTo(1080);
    }

    @Test
    void transparentImageShouldConvertWithoutError() throws Exception {
        var source = png(1400, 900, true);

        var jpeg = MpImageService.toJpeg(source, MpImageService.CONTENT_IMAGE_MAX_BYTES, 1080);

        assertThat(jpeg.length).isPositive();
        assertThat(ImageIO.read(new ByteArrayInputStream(jpeg))).isNotNull();
    }

    @Test
    void smallImageShouldStaySmallAndKeepSize() throws Exception {
        var source = png(400, 300, false);

        var jpeg = MpImageService.toJpeg(source, MpImageService.CONTENT_IMAGE_MAX_BYTES, 1080);

        assertThat(ImageIO.read(new ByteArrayInputStream(jpeg)).getWidth()).isEqualTo(400);
    }

    @Test
    void unsupportedFormatShouldFailWithReadableReason() {
        var garbage = new byte[] {0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42,
            0x50, 0x56, 0x50, 0x38, 0x20};  // RIFF....WEBP 头：ImageIO 解不开

        assertThatThrownBy(() ->
            MpImageService.toJpeg(garbage, MpImageService.CONTENT_IMAGE_MAX_BYTES, 1080))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("格式不受支持");
    }

    @Test
    void enforcedLimitShouldBeRespectedForVeryTightBudget() throws Exception {
        var source = png(2000, 1500, false);

        // 极小的上限：必须走到最后一段阶梯（强制 720 宽 + 0.5 质量）才可能通过
        var jpeg = MpImageService.toJpeg(source, 60 * 1024, 1080);

        assertThat(jpeg.length).isLessThanOrEqualTo(60 * 1024);
        assertThat(ImageIO.read(new ByteArrayInputStream(jpeg))).isNotNull();
    }

    @Test
    void shortUrlShouldKeepOnlyFileName() {
        assertThat(MpImageService.shortUrl("https://site.example/upload/2026/10/a.webp?x=1"))
            .isEqualTo("a.webp");
    }

    private static byte[] png(int width, int height, boolean alpha) throws Exception {
        var image = new BufferedImage(width, height,
            alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setPaint(new GradientPaint(0, 0, new Color(0x5B6EE8), width, height,
                new Color(0x9A4EE0)));
            graphics.fillRect(0, 0, width, height);
            if (alpha) {
                // 左上角留一块透明区域：验证 JPEG 转换会铺白底而不是变黑
                graphics.setComposite(java.awt.AlphaComposite.Clear);
                graphics.fillRect(0, 0, width / 3, height / 3);
            }
        } finally {
            graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
