package com.alexanderxdi.realtimemap;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TextureAtlasGenerator {
    private static final Map<Integer, Integer> idToColor = new ConcurrentHashMap<>();
    private static byte[] atlasBytes;
    private static boolean needsRebuild = true;
    private static int atlasVersion = 0;

    public static synchronized void registerBlockColor(int blockId, String hexColor) {
        if (hexColor == null) return;
        try {
            int color = (int) Long.parseLong(hexColor.replace("#", ""), 16);
            if (!idToColor.containsKey(blockId) || idToColor.get(blockId) != color) {
                idToColor.put(blockId, color);
                needsRebuild = true;
            }
        } catch (NumberFormatException e) {
            RealTimeMap.LOGGER.error("RealTimeMap: Invalid color format: {}", hexColor);
        }
    }

    public static synchronized void generate() {
        if (!needsRebuild && atlasBytes != null) return;

        int maxId = idToColor.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        int count = maxId + 1;

        if (count <= 0) {
            BufferedImage empty = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            atlasBytes = toBytes(empty);
            needsRebuild = false;
            return;
        }

        // Фиксированная сетка 32x32 плитки для стабильности индексов
        int sideCount = Math.max(32, (int) Math.ceil(Math.sqrt(count)));
        int atlasSize = sideCount * 16;
        
        BufferedImage atlas = new BufferedImage(atlasSize, atlasSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        
        // Очистка фона (прозрачность)
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, atlasSize, atlasSize);
        g.setComposite(AlphaComposite.SrcOver);

        for (int i = 0; i < count; i++) {
            Integer color = idToColor.get(i);
            if (color == null) continue;
            
            int x = (i % sideCount) * 16;
            int y = (i / sideCount) * 16;
            
            int alpha = 0xFF000000;
            g.setColor(new Color(color | alpha, true));
            g.fillRect(x, y, 16, 16);
        }
        g.dispose();

        atlasBytes = toBytes(atlas);
        needsRebuild = false;
        atlasVersion++;
        RealTimeMap.LOGGER.info("RealTimeMap: Generated atlas v{} with {} maxId", atlasVersion, maxId);
    }

    private static byte[] toBytes(BufferedImage image) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    public static byte[] getAtlasBytes() {
        if (needsRebuild || atlasBytes == null) generate();
        return atlasBytes;
    }

    public static int getAtlasVersion() {
        return atlasVersion;
    }

    public static int getAtlasSize() {
        int maxId = idToColor.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        return Math.max(32, (int) Math.ceil(Math.sqrt(maxId + 1)));
    }
}
