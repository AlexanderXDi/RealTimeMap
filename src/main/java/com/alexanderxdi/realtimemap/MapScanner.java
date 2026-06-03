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

    public static Map<String, Object> getChunkData(ServerLevel level, int chunkX, int chunkZ) {
        Map<String, Object> data = new HashMap<>();
        
        // Будем хранить данные как список "столбцов"
        // Каждый столбец - это массив ID блоков от самого верхнего до определенной глубины
        List<List<String>> columns = new ArrayList<>(256);
        int[] topY = new int[256];

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int worldX = (chunkX << 4) + x;
                int worldZ = (chunkZ << 4) + z;
                
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                topY[z * 16 + x] = surfaceY;
                
                List<String> columnBlocks = new ArrayList<>();
                // Сканируем 10 блоков вниз от поверхности (для детальности)
                // В будущем это можно расширить для полноценных пещер
                for (int y = surfaceY; y > surfaceY - 15 && y > level.getMinBuildHeight(); y--) {
                    pos.set(worldX, y - 1, worldZ);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        columnBlocks.add("air");
                    } else {
                        columnBlocks.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    }
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
