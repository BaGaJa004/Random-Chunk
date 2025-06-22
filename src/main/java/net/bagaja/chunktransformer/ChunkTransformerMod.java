package net.bagaja.chunktransformer;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.TickEvent;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

@Mod(ChunkTransformerMod.MODID)
public class ChunkTransformerMod {

    public static final String MODID = "chunktransformer";
    public static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    private static final BlockConfig blockConfig = new BlockConfig();
    private static KeyMapping configKey;
    private static final Random RANDOM = new Random();
    private static final Set<Long> transformedChunks = new HashSet<>();
    private ChunkPos lastChunkPos = null;
    private static boolean saveChunkTransformations = false;
    private static final Path CHUNK_SAVE_PATH = FMLPaths.CONFIGDIR.get().resolve("chunktransformer_chunks.json");
    private static final Path TRANSFORMED_CHUNKS_PATH = FMLPaths.CONFIGDIR.get().resolve("chunktransformer_transformed_chunks.json");
    private static final Path PERFORMANCE_CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("chunktransformer_performance.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Performance optimization fields - now configurable
    private static final ScheduledExecutorService ASYNC_EXECUTOR = Executors.newScheduledThreadPool(2);
    private static final Queue<ChunkTransformTask> TRANSFORM_QUEUE = new ConcurrentLinkedQueue<>();
    private static final Set<Long> PROCESSING_CHUNKS = ConcurrentHashMap.newKeySet();

    // Configurable performance settings
    private static boolean optimizationsEnabled = true;
    private static int maxBlocksPerTick = 500;
    private static int chunksPerSecond = 2;
    private static int transformRadius = 0; // 0 = current chunk only, 1 = 3x3, 2 = 5x5, etc.

    private static class ChunkTransformTask {
        final LevelChunk chunk;
        final BlockState targetBlockState;
        final long chunkPos;

        ChunkTransformTask(LevelChunk chunk, BlockState targetBlockState, long chunkPos) {
            this.chunk = chunk;
            this.targetBlockState = targetBlockState;
            this.chunkPos = chunkPos;
        }
    }

    // Performance configuration getters and setters
    public static boolean isOptimizationsEnabled() {
        return optimizationsEnabled;
    }

    public static void setOptimizationsEnabled(boolean enabled) {
        optimizationsEnabled = enabled;
        savePerformanceConfig();
    }

    public static int getMaxBlocksPerTick() {
        return maxBlocksPerTick;
    }

    public static void setMaxBlocksPerTick(int maxBlocks) {
        maxBlocksPerTick = Math.max(1, Math.min(10000, maxBlocks));
        savePerformanceConfig();
    }

    public static int getChunksPerSecond() {
        return chunksPerSecond;
    }

    public static void setChunksPerSecond(int chunks) {
        chunksPerSecond = Math.max(1, Math.min(20, chunks));
        savePerformanceConfig();
        // Restart the async processor with new rate
        restartAsyncProcessor();
    }

    public static int getTransformRadius() {
        return transformRadius;
    }

    public static void setTransformRadius(int radius) {
        transformRadius = Math.max(0, Math.min(10, radius));
        savePerformanceConfig();
    }

    // Method to toggle save configuration
    public static void toggleSaveChunkTransformations() {
        saveChunkTransformations = !saveChunkTransformations;
        saveChunkSaveConfig();

        // Load transformed chunks when enabling, clear when disabling
        if (saveChunkTransformations) {
            loadTransformedChunks();
        } else {
            transformedChunks.clear();
            // Delete the transformed chunks file when disabling
            try {
                Files.deleteIfExists(TRANSFORMED_CHUNKS_PATH);
            } catch (IOException e) {
                LOGGER.error("Failed to delete transformed chunks file", e);
            }
        }
    }

    // Method to check if chunk transformations should be saved
    public static boolean shouldSaveChunkTransformations() {
        return saveChunkTransformations;
    }

    // Save performance configuration
    private static void savePerformanceConfig() {
        try {
            Files.createDirectories(PERFORMANCE_CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PERFORMANCE_CONFIG_PATH)) {
                Map<String, Object> config = new HashMap<>();
                config.put("optimizationsEnabled", optimizationsEnabled);
                config.put("maxBlocksPerTick", maxBlocksPerTick);
                config.put("chunksPerSecond", chunksPerSecond);
                config.put("transformRadius", transformRadius);
                GSON.toJson(config, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save performance configuration", e);
        }
    }

    // Load performance configuration
    private static void loadPerformanceConfig() {
        if (Files.exists(PERFORMANCE_CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(PERFORMANCE_CONFIG_PATH)) {
                Map<String, Object> config = GSON.fromJson(reader, new TypeToken<Map<String, Object>>(){}.getType());
                if (config != null) {
                    optimizationsEnabled = (Boolean) config.getOrDefault("optimizationsEnabled", true);
                    maxBlocksPerTick = ((Number) config.getOrDefault("maxBlocksPerTick", 500)).intValue();
                    chunksPerSecond = ((Number) config.getOrDefault("chunksPerSecond", 2)).intValue();
                    transformRadius = ((Number) config.getOrDefault("transformRadius", 0)).intValue();
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load performance configuration", e);
            }
        }
    }

    // Save chunk save configuration
    private static void saveChunkSaveConfig() {
        try {
            Files.createDirectories(CHUNK_SAVE_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CHUNK_SAVE_PATH)) {
                GSON.toJson(Map.of("saveChunkTransformations", saveChunkTransformations), writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save chunk transformation save configuration", e);
        }
    }

    // Save transformed chunks to file
    private static void saveTransformedChunks() {
        if (!saveChunkTransformations) return;

        try {
            Files.createDirectories(TRANSFORMED_CHUNKS_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(TRANSFORMED_CHUNKS_PATH)) {
                GSON.toJson(transformedChunks, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save transformed chunks", e);
        }
    }

    // Load transformed chunks from file
    private static void loadTransformedChunks() {
        if (!saveChunkTransformations) return;

        if (Files.exists(TRANSFORMED_CHUNKS_PATH)) {
            try (Reader reader = Files.newBufferedReader(TRANSFORMED_CHUNKS_PATH)) {
                Set<Long> loadedChunks = GSON.fromJson(reader, new TypeToken<Set<Long>>(){}.getType());
                if (loadedChunks != null) {
                    transformedChunks.addAll(loadedChunks);
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load transformed chunks", e);
            }
        }
    }

    // Load chunk save configuration
    private static void loadChunkSaveConfig() {
        if (Files.exists(CHUNK_SAVE_PATH)) {
            try (Reader reader = Files.newBufferedReader(CHUNK_SAVE_PATH)) {
                Map<String, Boolean> config = GSON.fromJson(reader, new TypeToken<Map<String, Boolean>>(){}.getType());
                saveChunkTransformations = config.getOrDefault("saveChunkTransformations", false);
            } catch (IOException e) {
                LOGGER.error("Failed to load chunk transformation save configuration", e);
            }
        }
    }

    // Restart async processor with new settings
    private static void restartAsyncProcessor() {
        // The executor will automatically use the new chunksPerSecond value
        // No need to restart the entire executor
    }

    public ChunkTransformerMod() {
        // Initialize the key mapping
        configKey = new KeyMapping(
                "key.chunktransformer.config",
                GLFW.GLFW_KEY_K,
                "key.categories.misc"
        );

        // Load configurations
        loadPerformanceConfig();
        loadChunkSaveConfig();
        // Load previously transformed chunks if saving is enabled
        loadTransformedChunks();

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.addListener(this::onKeyInput);
        startAsyncChunkProcessor();
    }

    // Register the key mapping on the MOD event bus
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onKeyRegister(RegisterKeyMappingsEvent event) {
            event.register(configKey);
        }
    }

    public static BlockConfig getBlockConfig() {
        return blockConfig;
    }

    private void onKeyInput(InputEvent.Key event) {
        if (configKey.consumeClick() && Minecraft.getInstance().screen == null) {
            Minecraft.getInstance().setScreen(new ConfigScreen(null, blockConfig));
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        // Reset lastChunkPos when player respawns
        lastChunkPos = null;
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player.level().isClientSide()) return; // Only process on server side

        Player player = event.player;
        ChunkPos currentChunkPos = new ChunkPos(player.blockPosition());

        // Check if player moved to a new chunk
        if (!currentChunkPos.equals(lastChunkPos)) {
            handleChunkEnter(player, currentChunkPos);
            lastChunkPos = currentChunkPos;
        }
    }

    private void startAsyncChunkProcessor() {
        // Process queued chunks at a controlled rate
        ASYNC_EXECUTOR.scheduleAtFixedRate(() -> {
            ChunkTransformTask task = TRANSFORM_QUEUE.poll();
            if (task != null && !PROCESSING_CHUNKS.contains(task.chunkPos)) {
                PROCESSING_CHUNKS.add(task.chunkPos);
                processChunkAsync(task);
            }
        }, 0, 1000 / chunksPerSecond, TimeUnit.MILLISECONDS);
    }

    private void handleChunkEnter(Player player, ChunkPos chunkPos) {
        // Transform chunks in radius around the player
        if (transformRadius == 0) {
            // Only transform current chunk
            transformSingleChunk(player, chunkPos);
        } else {
            // Transform chunks in radius
            transformChunksInRadius(player, transformRadius);
        }
    }

    private void transformSingleChunk(Player player, ChunkPos chunkPos) {
        long chunkPosLong = chunkPos.toLong();

        // If saving is disabled, always reset transformation
        if (!saveChunkTransformations) {
            transformedChunks.clear();
        }

        // Check if chunk was already transformed or is being processed
        if (transformedChunks.contains(chunkPosLong) || PROCESSING_CHUNKS.contains(chunkPosLong)) {
            return;
        }

        // Add chunk to transformed list immediately to prevent double-processing
        transformedChunks.add(chunkPosLong);

        // Get the level and chunk
        Level level = player.level();
        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);

        // Pre-calculate valid blocks (cache this if possible)
        List<Block> validBlocks = getValidBlocks(level);
        if (validBlocks.isEmpty()) return;

        // Randomly select a block
        Block randomBlock = validBlocks.get(RANDOM.nextInt(validBlocks.size()));
        BlockState newBlockState = randomBlock.defaultBlockState();

        // Process chunk based on optimization settings
        if (optimizationsEnabled) {
            // Queue the chunk for async processing
            TRANSFORM_QUEUE.offer(new ChunkTransformTask(chunk, newBlockState, chunkPosLong));
        } else {
            // Process immediately without optimizations
            transformChunkImmediate(chunk, newBlockState);
        }

        // Save transformed chunks if saving is enabled
        if (saveChunkTransformations) {
            saveTransformedChunks();
        }
    }

    private static List<Block> cachedValidBlocks = null;
    private static long lastValidBlocksUpdate = 0;
    private static final long VALID_BLOCKS_CACHE_TIME = 30000; // 30 seconds

    private List<Block> getValidBlocks(Level level) {
        long currentTime = System.currentTimeMillis();
        if (cachedValidBlocks == null || (currentTime - lastValidBlocksUpdate) > VALID_BLOCKS_CACHE_TIME) {
            List<Block> validBlocks = new ArrayList<>();
            ForgeRegistries.BLOCKS.forEach(block -> {
                if (block != Blocks.AIR &&
                        block != Blocks.SPAWNER &&
                        block != Blocks.END_PORTAL_FRAME &&
                        block.defaultBlockState().isCollisionShapeFullBlock(level, BlockPos.ZERO) &&
                        blockConfig.isBlockEnabled(block)) {
                    validBlocks.add(block);
                }
            });
            cachedValidBlocks = validBlocks;
            lastValidBlocksUpdate = currentTime;
        }
        return cachedValidBlocks;
    }

    private void processChunkAsync(ChunkTransformTask task) {
        CompletableFuture.runAsync(() -> {
            try {
                // Process chunk in smaller batches to avoid lag
                transformChunkOptimized(task.chunk, task.targetBlockState);
            } finally {
                // Remove from processing set when done
                PROCESSING_CHUNKS.remove(task.chunkPos);
            }
        }, ASYNC_EXECUTOR);
    }

    // Immediate transformation without optimizations (for high-end PCs)
    private void transformChunkImmediate(LevelChunk chunk, BlockState targetBlockState) {
        Level level = chunk.getLevel();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                    BlockPos pos = new BlockPos(
                            chunk.getPos().x * 16 + x,
                            y,
                            chunk.getPos().z * 16 + z
                    );

                    BlockState currentState = level.getBlockState(pos);
                    Block currentBlock = currentState.getBlock();

                    // Skip if the block should not be transformed
                    if (shouldSkipBlock(currentBlock, currentState)) {
                        continue;
                    }

                    // Set block immediately
                    level.setBlock(pos, targetBlockState, Block.UPDATE_ALL);
                }
            }
        }
    }

    private void transformChunkOptimized(LevelChunk chunk, BlockState targetBlockState) {
        Level level = chunk.getLevel();

        // Pre-collect all positions that need to be changed
        List<BlockPos> positionsToChange = new ArrayList<>();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                    BlockPos pos = new BlockPos(
                            chunk.getPos().x * 16 + x,
                            y,
                            chunk.getPos().z * 16 + z
                    );

                    BlockState currentState = level.getBlockState(pos);
                    Block currentBlock = currentState.getBlock();

                    // Skip if the block should not be transformed
                    if (shouldSkipBlock(currentBlock, currentState)) {
                        continue;
                    }

                    positionsToChange.add(pos);
                }
            }
        }

        // Process positions in batches on the main thread
        processBatchedBlockUpdates(level, positionsToChange, targetBlockState);
    }

    private boolean shouldSkipBlock(Block currentBlock, BlockState currentState) {
        return currentBlock == Blocks.AIR ||
                currentBlock == Blocks.SPAWNER ||
                currentBlock == Blocks.END_PORTAL_FRAME ||
                currentBlock == Blocks.WATER ||
                currentBlock == Blocks.LAVA ||
                currentState.getFluidState().is(Fluids.WATER) ||
                currentState.getFluidState().is(Fluids.LAVA) ||
                currentState.getFluidState().is(Fluids.FLOWING_WATER) ||
                currentState.getFluidState().is(Fluids.FLOWING_LAVA);
    }

    private void processBatchedBlockUpdates(Level level, List<BlockPos> positions, BlockState targetBlockState) {
        if (positions.isEmpty()) return;

        // Process blocks in batches spread across multiple ticks
        int batchSize = Math.min(maxBlocksPerTick, positions.size());
        int totalBatches = (int) Math.ceil((double) positions.size() / batchSize);

        for (int batch = 0; batch < totalBatches; batch++) {
            final int batchIndex = batch;
            final int startIndex = batch * batchSize;
            final int endIndex = Math.min(startIndex + batchSize, positions.size());
            final List<BlockPos> batchPositions = positions.subList(startIndex, endIndex);

            // Schedule each batch to run on the main thread with a slight delay
            ASYNC_EXECUTOR.schedule(() -> {
                // Ensure we're on the main thread for block updates
                level.getServer().execute(() -> {
                    for (BlockPos pos : batchPositions) {
                        // Use more efficient block setting with minimal updates
                        level.setBlock(pos, targetBlockState, Block.UPDATE_CLIENTS);
                    }

                    // Only do neighbor updates for the last batch to reduce lag
                    if (batchIndex == totalBatches - 1) {
                        for (BlockPos pos : batchPositions) {
                            level.blockUpdated(pos, targetBlockState.getBlock());
                        }
                    }
                });
            }, batch * 50, TimeUnit.MILLISECONDS); // 50ms delay between batches
        }
    }

    // Enhanced chunk processing with radius support
    public void transformChunksInRadius(Player player, int radius) {
        ChunkPos playerChunk = new ChunkPos(player.blockPosition());

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                ChunkPos chunkPos = new ChunkPos(playerChunk.x + x, playerChunk.z + z);

                // Delay each chunk to spread the load
                int delay = optimizationsEnabled ? (Math.abs(x) + Math.abs(z)) * 100 : 0;

                if (delay > 0) {
                    ASYNC_EXECUTOR.schedule(() -> {
                        transformSingleChunk(player, chunkPos);
                    }, delay, TimeUnit.MILLISECONDS);
                } else {
                    // Process immediately if optimizations are disabled
                    transformSingleChunk(player, chunkPos);
                }
            }
        }
    }

    // Cleanup method - call this when the mod is unloading
    public static void shutdown() {
        if (ASYNC_EXECUTOR != null && !ASYNC_EXECUTOR.isShutdown()) {
            ASYNC_EXECUTOR.shutdown();
            try {
                if (!ASYNC_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                    ASYNC_EXECUTOR.shutdownNow();
                }
            } catch (InterruptedException e) {
                ASYNC_EXECUTOR.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}

// fixing saving for multiple worlds