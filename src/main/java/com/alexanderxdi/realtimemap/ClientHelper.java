package com.alexanderxdi.realtimemap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.blaze3d.platform.NativeImage;

import java.awt.image.BufferedImage;

public class ClientHelper {
    private static boolean loggedOnce = false;

    public static BufferedImage getBlockSprite(String blockRegistryName) {
        try {
            ResourceLocation blockId = ResourceLocation.tryParse(blockRegistryName);
            if (blockId == null) return null;

            Block block = BuiltInRegistries.BLOCK.get(blockId);
            if (block == null) return null;

            BlockState state = block.defaultBlockState();
            BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
            if (dispatcher == null) return null;

            BakedModel model = dispatcher.getBlockModel(state);
            if (model == null) return null;

            TextureAtlasSprite sprite = model.getParticleIcon();
            if (sprite == null) return null;

            SpriteContents contents = sprite.contents();
            if (contents == null) return null;

            int w = contents.width();
            int h = contents.height();
            if (w <= 0 || h <= 0) return null;

            NativeImage image = contents.getOriginalImage();
            if (image == null) return null;

            if (!loggedOnce) {
                loggedOnce = true;
                RealTimeMap.LOGGER.warn("RealTimeMap Debug: ClientHelper.getBlockSprite first call for {}. Dimensions: {}x{}", blockRegistryName, w, h);
            }

            int nonZeroAlphaCount = 0;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    int pixel = image.getPixelRGBA(x, y);
                    // NativeImage.getPixelRGBA возвращает ABGR (AABBGGRR в little-endian)
                    // Конвертируем в ARGB для BufferedImage
                    int a = (pixel >> 24) & 0xFF;
                    if (a > 0) nonZeroAlphaCount++;
                    int b = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int r = pixel & 0xFF;
                    int argb = (a << 24) | (r << 16) | (g << 8) | b;
                    img.setRGB(x, y, argb);
                }
            }

            // Логируем количество непрозрачных пикселей для первых нескольких блоков
            if (Math.random() < 0.05) {
                RealTimeMap.LOGGER.warn("RealTimeMap Debug: Block {} sprite processed. Non-zero alpha pixels: {}/{} ({}%)", 
                    blockRegistryName, nonZeroAlphaCount, w * h, (nonZeroAlphaCount * 100) / (w * h));
            }

            return img;
        } catch (Throwable e) {
            RealTimeMap.LOGGER.error("RealTimeMap Debug: Error getting block sprite for {}: ", blockRegistryName, e);
            return null;
        }
    }
}
