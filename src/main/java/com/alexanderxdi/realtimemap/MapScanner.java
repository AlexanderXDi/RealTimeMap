package com.alexanderxdi.realtimemap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MapScanner {
    private static final Map<String, Integer> blockToId = new HashMap<>();
    private static final List<String> idToBlock = new ArrayList<>();

    static {
        getOrAddBlockId("air", "#000000");
        getOrAddBlockId("minecraft:air", "#000000");
    }

    public static void loadDictionaryFromDb() {
        synchronized (blockToId) {
            MapDatabase.loadDictionary(entry -> {
                if (!blockToId.containsKey(entry.key())) {
                    blockToId.put(entry.key(), entry.id());
                    while (idToBlock.size() <= entry.id()) idToBlock.add(null);
                    idToBlock.set(entry.id(), entry.key());
                    if (entry.hexColor() != null) {
                        TextureAtlasGenerator.registerBlockColor(entry.id(), entry.hexColor());
                    }
                }
            });
        }
    }

    private static int getOrAddBlockId(String blockId, String hexColor) {
        synchronized (blockToId) {
            if (!blockToId.containsKey(blockId)) {
                int id = idToBlock.size();
                blockToId.put(blockId, id);
                idToBlock.add(blockId);
                MapDatabase.saveDictionary(id, blockId, hexColor);
                TextureAtlasGenerator.registerBlockColor(id, hexColor);
                return id;
            }
            int id = blockToId.get(blockId);
            TextureAtlasGenerator.registerBlockColor(id, hexColor);
            return id;
        }
    }

    public static String getHexColor(ServerLevel level, net.minecraft.core.BlockPos pos, BlockState state) {
        int color = state.getMapColor(level, pos).col;
        return String.format("#%06X", (color & 0xFFFFFF));
    }

    public static List<String> getDictionary() {
        synchronized (blockToId) {
            return new ArrayList<>(idToBlock);
        }
    }

    public static Map<String, Object> getChunkData(ServerLevel level, int chunkX, int chunkZ, String mode) {
        Map<String, Object> data = new HashMap<>();
        boolean is3d = mode != null && mode.toLowerCase().startsWith("3d");
        List<Integer> blocks = new ArrayList<>();

        int minHeight = level.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos nPos = new BlockPos.MutableBlockPos();
        
        net.minecraft.world.level.chunk.ChunkAccess chunkAccess = level.getChunkSource().getChunk(chunkX, chunkZ, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        if (!(chunkAccess instanceof net.minecraft.world.level.chunk.LevelChunk chunk)) {
            throw new RuntimeException("Chunk not loaded");
        }

        int totalFound = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int worldX = (chunkX << 4) + x;
                int worldZ = (chunkZ << 4) + z;
                
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                int endY = minHeight;
                if ("3d_surface_20".equalsIgnoreCase(mode)) {
                    endY = Math.max(minHeight, surfaceY - 20);
                } else if (!is3d) {
                    endY = surfaceY;
                }

                for (int y = surfaceY; y >= endY; y--) {
                    pos.set(worldX, y - 1, worldZ);
                    BlockState state = chunk.getBlockState(pos);
                    if (state.isAir()) continue;

                    int faces = 0;
                    // 1:Top, 2:Bottom, 4:North(-Z), 8:South(+Z), 16:East(+X), 32:West(-X)
                    
                    if (y >= surfaceY || shouldRenderFace(level, state, pos, nPos.set(worldX, y, worldZ))) faces |= 1;
                    if (y > minHeight && shouldRenderFace(level, state, pos, nPos.set(worldX, y - 2, worldZ))) faces |= 2;
                    
                    if (shouldRenderFace(level, state, pos, nPos.set(worldX, y - 1, worldZ - 1))) faces |= 4;
                    if (shouldRenderFace(level, state, pos, nPos.set(worldX, y - 1, worldZ + 1))) faces |= 8;
                    if (shouldRenderFace(level, state, pos, nPos.set(worldX + 1, y - 1, worldZ))) faces |= 16;
                    if (shouldRenderFace(level, state, pos, nPos.set(worldX - 1, y - 1, worldZ))) faces |= 32;

                    if (faces > 0 || !is3d) {
                        int shape = 0; // 0 = full, 1 = bottom slab, 2 = top slab
                        boolean isSlab = state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock;
                        if (isSlab) {
                            net.minecraft.world.level.block.state.properties.SlabType slabType = state.getValue(net.minecraft.world.level.block.SlabBlock.TYPE);
                            if (slabType == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM) {
                                shape = 1;
                            } else if (slabType == net.minecraft.world.level.block.state.properties.SlabType.TOP) {
                                shape = 2;
                            }
                        }

                        int encodedFaces = faces | (shape << 6);
                        String blockName = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        String color = getHexColor(level, pos, state);
                        int id = getOrAddBlockId(blockName, color);
                        
                        blocks.add(x);
                        blocks.add(y - 1);
                        blocks.add(z);
                        blocks.add(id);
                        blocks.add(encodedFaces);
                        totalFound++;

                        // Handle waterlogging
                        boolean isWaterlogged = state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED) 
                            && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED);
                        if (isWaterlogged && isSlab && shape != 0) {
                            int waterId = getOrAddBlockId("minecraft:water", "#3F76E4");
                            int waterShape = (shape == 1) ? 2 : 1;
                            int waterFaces = faces;
                            if (shape == 1) {
                                waterFaces &= ~2; // water on top - hide bottom face
                            } else {
                                waterFaces &= ~1; // water on bottom - hide top face
                            }
                            int encodedWaterFaces = waterFaces | (waterShape << 6);

                            blocks.add(x);
                            blocks.add(y - 1);
                            blocks.add(z);
                            blocks.add(waterId);
                            blocks.add(encodedWaterFaces);
                            totalFound++;
                        }
                        
                        if (!is3d) break; 
                    }
                }
            }
        }

        if (totalFound > 0) {
            RealTimeMap.LOGGER.info("RealTimeMap: Scanned chunk {}, {}. Found {} visible blocks.", chunkX, chunkZ, totalFound);
        }
        
        data.put("x", chunkX);
        data.put("z", chunkZ);
        data.put("blocks", blocks);
        
        return data;
    }

    private static boolean shouldRenderFace(ServerLevel level, BlockState state, BlockPos pos, BlockPos neighborPos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        int ncx = neighborPos.getX() >> 4;
        int ncz = neighborPos.getZ() >> 4;
        
        BlockState neighborState;
        if (cx != ncx || cz != ncz) {
            net.minecraft.world.level.chunk.ChunkAccess neighborChunk = level.getChunkSource().getChunk(ncx, ncz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
            if (neighborChunk == null) {
                return true; // neighboring chunk not loaded
            }
            neighborState = neighborChunk.getBlockState(neighborPos);
        } else {
            neighborState = level.getBlockState(neighborPos);
        }
        
        if (neighborState.isSolidRender(level, neighborPos)) {
            return false;
        }

        // Culling for adjacent transparent blocks of the same type
        net.minecraft.world.level.block.Block currentBlock = state.getBlock();
        net.minecraft.world.level.block.Block neighborBlock = neighborState.getBlock();
        if (currentBlock == neighborBlock) {
            if (currentBlock instanceof net.minecraft.world.level.block.LiquidBlock
                || currentBlock instanceof net.minecraft.world.level.block.HalfTransparentBlock
                || currentBlock instanceof net.minecraft.world.level.block.StainedGlassBlock
                || currentBlock instanceof net.minecraft.world.level.block.LeavesBlock) {
                return false;
            }
        }
        return true;
    }

    public static byte[] getChunk2DTile(ServerLevel level, int chunkX, int chunkZ) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        
        net.minecraft.world.level.chunk.ChunkAccess chunkAccess = level.getChunkSource().getChunk(chunkX, chunkZ, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        if (!(chunkAccess instanceof net.minecraft.world.level.chunk.LevelChunk chunk)) {
            throw new RuntimeException("Chunk not loaded");
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int worldX = (chunkX << 4) + x;
                int worldZ = (chunkZ << 4) + z;
                int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                
                int colorArgb = 0x00000000;
                for (int y = surfaceY; y >= level.getMinBuildHeight(); y--) {
                    pos.set(worldX, y - 1, worldZ);
                    BlockState state = chunk.getBlockState(pos);
                    if (!state.isAir()) {
                        String blockName = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        String colorHex = getHexColor(level, pos, state);
                        int id = getOrAddBlockId(blockName, colorHex);
                        colorArgb = TextureAtlasGenerator.getColorForBlock(id);
                        break;
                    }
                }
                image.setRGB(x, z, colorArgb);
            }
        }

        return toPngBytes(image);
    }

    private static byte[] toPngBytes(java.awt.image.BufferedImage image) {
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (java.io.IOException e) {
            return new byte[0];
        }
    }
}
