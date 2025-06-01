package com.yogourt;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class BedrockScanCommand {
    private static final int MAX_CHUNK_RADIUS = 5;

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> 
            dispatcher.register(literal("scanbedrock")
                .then(argument("chunkRadius", IntegerArgumentType.integer(0, MAX_CHUNK_RADIUS))
                    .executes(context -> run(context, IntegerArgumentType.getInteger(context, "chunkRadius"))))
                .executes(context -> run(context, 0)))
        );
    }

    private static int run(CommandContext<FabricClientCommandSource> context, int chunkRadius) throws CommandSyntaxException {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            context.getSource().sendError(Text.literal("You must be in a world to use this command."));
            return 0;
        }

        World world = client.world;
        ChunkPos centerChunkPos = client.player.getChunkPos();
        
        client.player.sendMessage(Text.literal("Scanning chunks with radius " + chunkRadius + " (this might take a moment)..."), false);

        List<BlockPos> bedrockCoordinates = new ArrayList<>();
        
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                ChunkPos currentChunkPos = new ChunkPos(centerChunkPos.x + dx, centerChunkPos.z + dz);
                WorldChunk chunk = world.getChunk(currentChunkPos.x, currentChunkPos.z);
                
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = world.getBottomY(); y < world.getTopY(); y++) {
                            BlockPos blockPos = currentChunkPos.getBlockPos(x, y, z);
                            if (chunk.getBlockState(new BlockPos(x, y, z)).getBlock() == Blocks.BEDROCK) {
                                bedrockCoordinates.add(blockPos);
                            }
                        }
                    }
                }
            }
        }

        if (bedrockCoordinates.isEmpty()) {
            client.player.sendMessage(Text.literal("No bedrock found in the scanned area."), false);
            return 1;
        }

        GridResult gridResult = createGridFromCoordinates(bedrockCoordinates, world.getBottomY());
        List<AreaResult> largestAreas = findLargestAreas(gridResult.grid);

        int totalChunks = (2 * chunkRadius + 1) * (2 * chunkRadius + 1);
        int worldHeight = world.getTopY() - world.getBottomY();
        int totalPossibleBlocks = totalChunks * 16 * 16 * worldHeight;
        
        client.player.sendMessage(Text.literal("=== SCAN RESULTS ==="), false);
        client.player.sendMessage(Text.literal("World height: Y=" + world.getBottomY() + " to Y=" + world.getTopY() + " (" + worldHeight + " blocks)"), false);
        client.player.sendMessage(Text.literal("Scanned " + totalChunks + " chunks (" + (totalChunks * 16 * 16) + " block columns)"), false);
        client.player.sendMessage(Text.literal("Total possible blocks: " + totalPossibleBlocks + ", Bedrock found: " + bedrockCoordinates.size()), false);
        client.player.sendMessage(Text.literal("Bedrock percentage: " + String.format("%.2f", (bedrockCoordinates.size() * 100.0 / totalPossibleBlocks)) + "%"), false);
        
        for (int i = 0; i < largestAreas.size(); i++) {
            AreaResult area = largestAreas.get(i);
            String ordinal = (i == 0) ? "1st" : "2nd";
            
            if (area.gridPosition != null) {
                BlockPos actualPosition = new BlockPos(
                    area.gridPosition.getX() + gridResult.offset.getX(),
                    area.gridPosition.getY() + gridResult.offset.getY(),
                    area.gridPosition.getZ() + gridResult.offset.getZ()
                );
                client.player.sendMessage(Text.literal("The " + ordinal + " largest connected area of zeros is: " + area.size), false);
                client.player.sendMessage(Text.literal("One position in this area: " + actualPosition.getX() + ", " + actualPosition.getY() + ", " + actualPosition.getZ()), false);
            } else {
                client.player.sendMessage(Text.literal("No " + ordinal + " largest connected area found."), false);
            }
        }

        return 1;
    }

    private static GridResult createGridFromCoordinates(List<BlockPos> coordinates, int bedrockLevel) {
        int minX = coordinates.stream().mapToInt(BlockPos::getX).min().orElse(0);
        int maxX = coordinates.stream().mapToInt(BlockPos::getX).max().orElse(0);
        int minY = coordinates.stream().mapToInt(BlockPos::getY).min().orElse(bedrockLevel);
        int maxY = coordinates.stream().mapToInt(BlockPos::getY).max().orElse(bedrockLevel);
        int minZ = coordinates.stream().mapToInt(BlockPos::getZ).min().orElse(0);
        int maxZ = coordinates.stream().mapToInt(BlockPos::getZ).max().orElse(0);

        minY = Math.max(minY, bedrockLevel);

        int width = maxX - minX + 3;
        int height = maxY - minY + 3;
        int depth = maxZ - minZ + 3;

        int[][][] grid = new int[width][height][depth];
        Set<BlockPos> coordSet = new HashSet<>(coordinates);

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    int actualX = x + minX - 1;
                    int actualY = y + minY - 1;
                    int actualZ = z + minZ - 1;

                    if (actualY <= bedrockLevel || coordSet.contains(new BlockPos(actualX, actualY, actualZ))) {
                        grid[x][y][z] = 1;
                    }
                }
            }
        }

        return new GridResult(grid, new BlockPos(minX - 1, Math.max(minY - 1, bedrockLevel), minZ - 1));
    }

    private static List<AreaResult> findLargestAreas(int[][][] grid) {
        if (grid.length == 0 || grid[0].length == 0 || grid[0][0].length == 0) {
            return Arrays.asList(new AreaResult(0, null), new AreaResult(0, null));
        }

        int depth = grid.length;
        int rows = grid[0].length;
        int cols = grid[0][0].length;
        Set<String> visited = new HashSet<>();

        List<AreaResult> areas = new ArrayList<>();

        for (int x = 0; x < depth; x++) {
            for (int y = 0; y < rows; y++) {
                for (int z = 0; z < cols; z++) {
                    String key = x + "," + y + "," + z;
                    if (grid[x][y][z] == 0 && !visited.contains(key)) {
                        int area = dfsIterative(grid, x, y, z, visited);
                        if (area > 0) {
                            areas.add(new AreaResult(area, new BlockPos(x, y, z)));
                        }
                    }
                }
            }
        }

        areas.sort((a, b) -> Integer.compare(b.size, a.size));
        
        while (areas.size() < 2) {
            areas.add(new AreaResult(0, null));
        }

        return areas.subList(0, 2);
    }

    private static int dfsIterative(int[][][] grid, int startX, int startY, int startZ, Set<String> visited) {
        Stack<int[]> stack = new Stack<>();
        stack.push(new int[]{startX, startY, startZ});
        
        int area = 0;
        int[][] directions = {
            {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
            {1, 0, 0}, {-1, 0, 0}
        };

        while (!stack.isEmpty()) {
            int[] current = stack.pop();
            int x = current[0];
            int y = current[1];
            int z = current[2];

            if (x < 0 || x >= grid.length ||
                y < 0 || y >= grid[0].length ||
                z < 0 || z >= grid[0][0].length ||
                grid[x][y][z] == 1) {
                continue;
            }

            String key = x + "," + y + "," + z;
            if (visited.contains(key)) {
                continue;
            }

            visited.add(key);
            area++;

            for (int[] dir : directions) {
                int newX = x + dir[0];
                int newY = y + dir[1];
                int newZ = z + dir[2];
                String newKey = newX + "," + newY + "," + newZ;
                
                if (!visited.contains(newKey)) {
                    stack.push(new int[]{newX, newY, newZ});
                }
            }
        }

        return area;
    }

    private static class GridResult {
        public final int[][][] grid;
        public final BlockPos offset;

        public GridResult(int[][][] grid, BlockPos offset) {
            this.grid = grid;
            this.offset = offset;
        }
    }

    private static class AreaResult {
        public final int size;
        public final BlockPos gridPosition;

        public AreaResult(int size, BlockPos gridPosition) {
            this.size = size;
            this.gridPosition = gridPosition;
        }
    }
} 