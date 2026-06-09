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
        boolean is3d = "3d".equalsIgnoreCase(mode);
        List<Integer> blocks = new ArrayList<>();

        int minHeight = level.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos nPos = new BlockPos.MutableBlockPos();

        int totalFound = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int worldX = (chunkX << 4) + x;
                int worldZ = (chunkZ << 4) + z;
                
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                int endY = is3d ? minHeight : surfaceY;

                for (int y = surfaceY; y >= endY; y--) {
                    pos.set(worldX, y - 1, worldZ);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;

                    int faces = 0;
                    // 1:Top, 2:Bottom, 4:North(-Z), 8:South(+Z), 16:East(+X), 32:West(-X)
                    
                    if (y >= surfaceY || !level.getBlockState(nPos.set(worldX, y, worldZ)).isSolidRender(level, nPos)) faces |= 1;
                    if (y > minHeight && !level.getBlockState(nPos.set(worldX, y - 2, worldZ)).isSolidRender(level, nPos)) faces |= 2;
                    
                    if (z == 0 || !level.getBlockState(nPos.set(worldX, y - 1, worldZ - 1)).isSolidRender(level, nPos)) faces |= 4;
                    if (z == 15 || !level.getBlockState(nPos.set(worldX, y - 1, worldZ + 1)).isSolidRender(level, nPos)) faces |= 8;
                    if (x == 15 || !level.getBlockState(nPos.set(worldX + 1, y - 1, worldZ)).isSolidRender(level, nPos)) faces |= 16;
                    if (x == 0 || !level.getBlockState(nPos.set(worldX - 1, y - 1, worldZ)).isSolidRender(level, nPos)) faces |= 32;

                    if (faces > 0 || !is3d) {
                        String blockName = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        String color = getHexColor(level, pos, state);
                        int id = getOrAddBlockId(blockName, color);
                        
                        blocks.add(x);
                        blocks.add(y - 1);
                        blocks.add(z);
                        blocks.add(id);
                        blocks.add(faces);
                        totalFound++;
                        
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

    private static boolean isOpaque(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isSolidRender(level, pos);
    }
}
