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
        getOrAddBlockId("air");
        getOrAddBlockId("minecraft:air");
    }

    private static int getOrAddBlockId(String blockId) {
        synchronized (blockToId) {
            if (!blockToId.containsKey(blockId)) {
                int id = idToBlock.size();
                blockToId.put(blockId, id);
                idToBlock.add(blockId);
                return id;
            }
            return blockToId.get(blockId);
        }
    }

    public static List<String> getDictionary() {
        synchronized (blockToId) {
            return new ArrayList<>(idToBlock);
        }
    }

    public static Map<String, Object> getChunkData(ServerLevel level, int chunkX, int chunkZ, String mode) {
        Map<String, Object> data = new HashMap<>();
        boolean is3d = "3d".equalsIgnoreCase(mode);
        
        int[] topY = new int[256];
        List<List<Integer>> columns = new ArrayList<>(256);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int worldX = (chunkX << 4) + x;
                int worldZ = (chunkZ << 4) + z;
                
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                topY[z * 16 + x] = surfaceY;
                
                List<Integer> columnBlocks = new ArrayList<>();
                if (is3d) {
                    // Скан от поверхности до самого низа мира
                    for (int y = surfaceY; y >= level.getMinBuildHeight(); y--) {
                        pos.set(worldX, y - 1, worldZ);
                        BlockState state = level.getBlockState(pos);
                        String blockName = state.isAir() ? "air" : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        columnBlocks.add(getOrAddBlockId(blockName));
                    }
                } else {
                    // Режим 2D - только верхний блок
                    pos.set(worldX, surfaceY - 1, worldZ);
                    BlockState state = level.getBlockState(pos);
                    String blockName = state.isAir() ? "air" : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    columnBlocks.add(getOrAddBlockId(blockName));
                }
                columns.add(columnBlocks);
            }
        }

        data.put("x", chunkX);
        data.put("z", chunkZ);
        data.put("topY", topY);
        data.put("columns", columns);
        
        return data;
    }
}
