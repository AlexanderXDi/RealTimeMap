package com.alexanderxdi.realtimemap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.HashMap;
import java.util.Map;

public class MapScanner {

    public static Map<String, Object> getChunkData(ServerLevel level, int chunkX, int chunkZ) {
        Map<String, Object> data = new HashMap<>();
        int[] heights = new int[256];
        String[] blocks = new String[256];

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int worldX = (chunkX << 4) + x;
                int worldZ = (chunkZ << 4) + z;
                
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                pos.set(worldX, y - 1, worldZ);
                
                BlockState state = level.getBlockState(pos);
                heights[z * 16 + x] = y;
                blocks[z * 16 + x] = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            }
        }

        data.put("x", chunkX);
        data.put("z", chunkZ);
        data.put("heights", heights);
        data.put("blocks", blocks);
        
        return data;
    }
}
