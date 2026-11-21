package com.ieum.ieum.community.photo;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * 올린 사진을 새로 그려서 JPG로 저장한다 (COM-02).
 *
 * - 사진 속 정보(EXIF: 촬영 위치 GPS, 기기, 찍은 때 등)는 하나도 옮기지 않는다. 픽셀만 새 그림에 그린다.
 * - 휴대폰 사진의 '돌려 보기'(EXIF 방향)는 픽셀에 반영한 뒤 버린다. 그래야 정보를 지워도 사진이 눕지 않는다.
 * - 긴 변 1600px(목록용은 480px)로 줄이고, 투명한 부분은 흰 바탕으로 채운다.
 * - 너무 큰 그림(4,000만 화소 초과)은 열기 전에 거절한다. 작은 파일로 메모리를 터뜨리는 공격을 막으려고.
 */
public final class PhotoProcessor {

    public static final int MAX_SIDE = 1600;
    public static final int THUMB_SIDE = 480;
    static final long MAX_PIXELS = 40_000_000L;
    static final int MIN_SIDE = 16;
    private static final Set<String> FORMATS = Set.of("jpeg", "jpg", "png", "gif", "bmp");

    public record Result(byte[] photo, byte[] thumb, int width, int height) {
    }

    private PhotoProcessor() {
    }

    public static Result process(byte[] data) {
        BufferedImage src = read(data);
        int orientation = exifOrientation(data);
        BufferedImage upright = orient(src, orientation);
        BufferedImage big = fit(upright, MAX_SIDE);
        BufferedImage small = fit(big, THUMB_SIDE);
        try {
            return new Result(jpeg(big, 0.85f), jpeg(small, 0.8f), big.getWidth(), big.getHeight());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PHOTO_INVALID);
        }
    }

    private static BufferedImage read(byte[] data) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (in == null || !readers.hasNext()) throw new BusinessException(ErrorCode.PHOTO_INVALID);
            ImageReader reader = readers.next();
            try {
                if (!FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw new BusinessException(ErrorCode.PHOTO_INVALID);
                reader.setInput(in, true, true);   // 메타데이터는 읽지도 않는다
                long w = reader.getWidth(0);
                long h = reader.getHeight(0);
                if (w < MIN_SIDE || h < MIN_SIDE || w * h > MAX_PIXELS) throw new BusinessException(ErrorCode.PHOTO_INVALID);
                BufferedImage img = reader.read(0);
                if (img == null) throw new BusinessException(ErrorCode.PHOTO_INVALID);
                return img;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof BusinessException be) throw be;
            throw new BusinessException(ErrorCode.PHOTO_INVALID);   // 깨진 파일, CMYK JPG 등
        }
    }

    /** JPG 의 EXIF 방향 값(1~8). 없거나 읽을 수 없으면 1 (그대로) */
    static int exifOrientation(byte[] d) {
        try {
            if (d.length < 4 || (d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xD8) return 1;
            int i = 2;
            while (i + 4 <= d.length) {
                if ((d[i] & 0xFF) != 0xFF) return 1;
                int marker = d[i + 1] & 0xFF;
                if (marker == 0xDA || marker == 0xD9) return 1;   // 그림 데이터 시작: 더 없음
                int len = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
                int seg = i + 4;
                if (marker == 0xE1 && len >= 8 && seg + 6 <= d.length
                        && d[seg] == 'E' && d[seg + 1] == 'x' && d[seg + 2] == 'i' && d[seg + 3] == 'f') {
                    int t = seg + 6;   // TIFF 머리
                    boolean le = d[t] == 'I';
                    int ifd = t + u32(d, t + 4, le);
                    int count = u16(d, ifd, le);
                    for (int k = 0; k < count; k++) {
                        int e = ifd + 2 + k * 12;
                        if (e + 12 > d.length) return 1;
                        if (u16(d, e, le) == 0x0112) {
                            int v = u16(d, e + 8, le);
                            return v >= 1 && v <= 8 ? v : 1;
                        }
                    }
                    return 1;
                }
                i += 2 + len;
            }
        } catch (RuntimeException ignored) {
            // 망가진 EXIF 는 무시
        }
        return 1;
    }

    private static int u16(byte[] d, int i, boolean le) {
        return le ? (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8) : ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
    }

    private static int u32(byte[] d, int i, boolean le) {
        return le ? (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8) | ((d[i + 2] & 0xFF) << 16) | ((d[i + 3] & 0xFF) << 24)
                : ((d[i] & 0xFF) << 24) | ((d[i + 1] & 0xFF) << 16) | ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
    }

    /** EXIF 방향대로 돌리고, 흰 바탕의 RGB 그림으로 */
    static BufferedImage orient(BufferedImage src, int o) {
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = o >= 5;
        AffineTransform t = new AffineTransform();
        switch (o) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.translate(0, h); t.scale(1, -1); }
            case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(-Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(-Math.PI / 2); }
            default -> { }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(src, t, null);
        g.dispose();
        return out;
    }

    /** 긴 변이 max 를 넘으면 반씩 줄여 가며(계단 현상 줄이기) 맞춘다 */
    static BufferedImage fit(BufferedImage img, int max) {
        int w = img.getWidth();
        int h = img.getHeight();
        double scale = Math.min(1.0, (double) max / Math.max(w, h));
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        BufferedImage cur = img;
        while (cur.getWidth() != tw || cur.getHeight() != th) {
            int nw = Math.max(tw, cur.getWidth() / 2);
            int nh = Math.max(th, cur.getHeight() / 2);
            BufferedImage next = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(cur, 0, 0, nw, nh, null);
            g.dispose();
            cur = next;
        }
        return cur;
    }

    static byte[] jpeg(BufferedImage img, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), p);   // 메타데이터 없이
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
