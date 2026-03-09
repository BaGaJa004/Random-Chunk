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
    private static final KeyMapping configKey = new KeyMapping(
            "key.chunktransformer.config",
            GLFW.GLFW_KEY_K,
            "key.categories.misc"
    );
    private static final Random RANDOM = new Random();
    private static final Set<Long> transformedChunks = new HashSet<>();
    private static final Map<String, Set<Long>> worldTransformedChunks = new ConcurrentHashMap<>();
    private static String currentWorldId = null;
    private ChunkPos lastChunkPos = null;
    private static boolean saveChunkTransformations = false;
    private static final Path CHUNK_SAVE_PATH = FMLPaths.CONFIGDIR.get().resolve("chunktransformer_chunks.json");
    private static final Path TRANSFORMED_CHUNKS_PATH = FMLPaths.CONFIGDIR.get().resolve("chunktransformer_transformed_chunks.json");
    private static final Path PERFORMANCE_CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("chunktransformer_performance.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path getWorldSpecificTransformedChunksPath(String worldId) {
        return FMLPaths.CONFIGDIR.get().resolve("chunktransformer_transformed_chunks_" + worldId + ".json");
    }

    private static String getWorldIdentifier(Level level) {
        if (level == null) {
            return "unknown_world";
        }

        try {
            if (level.isClientSide()) {
                return "client_world_" + System.currentTimeMillis() % 10000;
            }

            if (level.getServer() != null) {
                // 1.20.6: level.getServer().getWorldPath() requires a LevelResource parameter.
                // Use the dimension key for a reliable unique identifier instead.
                String dimensionName = level.dimension().location().toString();
                String serverHash = String.valueOf(level.getServer().hashCode() % 100000);
                return "world_" + serverHash + "_" + dimensionName.replace(":", "_");
            } else {
                return "server_world_" + level.dimension().location().toString().replace(":", "_");
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to get world identifier, using fallback", e);
            return "fallback_world_" + System.currentTimeMillis() % 10000;
        }
    }

    // Performance optimization fields
    private static final ScheduledExecutorService ASYNC_EXECUTOR = Executors.newScheduledThreadPool(2);
    private static final Queue<ChunkTransformTask> TRANSFORM_QUEUE = new ConcurrentLinkedQueue<>();
    private static final Set<Long> PROCESSING_CHUNKS = ConcurrentHashMap.newKeySet();

    // Configurable performance settings
    private static boolean optimizationsEnabled = true;
    private static int maxBlocksPerTick = 500;
    private static int chunksPerSecond = 2;
    private static int transformRadius = 0;

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

    public static boolean isOptimizationsEnabled() { return optimizationsEnabled; }
    public static void setOptimizationsEnabled(boolean enabled) {
        optimizationsEnabled = enabled;
        savePerformanceConfig();
    }

    public static int getMaxBlocksPerTick() { return maxBlocksPerTick; }
    public static void setMaxBlocksPerTick(int maxBlocks) {
        maxBlocksPerTick = Math.max(1, Math.min(10000, maxBlocks));
        savePerformanceConfig();
    }

    public static int getChunksPerSecond() { return chunksPerSecond; }
    public static void setChunksPerSecond(int chunks) {
        chunksPerSecond = Math.max(1, Math.min(20, chunks));
        savePerformanceConfig();
    }

    public static int getTransformRadius() { return transformRadius; }
    public static void setTransformRadius(int radius) {
        transformRadius = Math.max(0, Math.min(10, radius));
        savePerformanceConfig();
    }

    public static void toggleSaveChunkTransformations() {
        saveChunkTransformations = !saveChunkTransformations;
        saveChunkSaveConfig();

        if (saveChunkTransformations) {
            if (currentWorldId != null) {
                try {
                    loadTransformedChunksForWorld(currentWorldId);
                } catch (Exception e) {
                    LOGGER.error("Failed to load transformed chunks for current world", e);
                }
            }
        } else {
            worldTransformedChunks.clear();
            try {
                Path configDir = FMLPaths.CONFIGDIR.get();
                if (Files.exists(configDir)) {
                    Files.list(configDir)
                            .filter(path -> path.getFileName().toString().startsWith("chunktransformer_transformed_chunks_"))
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    LOGGER.error("Failed to delete transformed chunks file: " + path, e);
                                }
                            });
                }
            } catch (IOException e) {
                LOGGER.error("Failed to list config directory for cleanup", e);
            }
        }
    }

    public static boolean shouldSaveChunkTransformations() {
        return saveChunkTransformations;
    }

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

    private static void loadTransformedChunks() {
        if (currentWorldId != null) {
            try {
                loadTransformedChunksForWorld(currentWorldId);
            } catch (Exception e) {
                LOGGER.error("Failed to load transformed chunks", e);
            }
        }
    }

    public static void onWorldChanged(Level newWorld) {
        if (newWorld == null) return;
        try {
            String newWorldId = getWorldIdentifier(newWorld);
            if (!newWorldId.equals(currentWorldId)) {
                currentWorldId = newWorldId;
                if (saveChunkTransformations) {
                    loadTransformedChunksForWorld(newWorldId);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error handling world change", e);
        }
    }

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

    public ChunkTransformerMod() {
        loadPerformanceConfig();
        loadChunkSaveConfig();
        loadTransformedChunks();

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.addListener(this::onKeyInput);
        startAsyncChunkProcessor();
    }

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
        lastChunkPos = null;
        try {
            if (event.getEntity() != null && !event.getEntity().level().isClientSide()) {
                onWorldChanged(event.getEntity().level());
            }
        } catch (Exception e) {
            LOGGER.error("Error handling player respawn world change", e);
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player == null || event.player.level() == null) return;
        if (event.player.level().isClientSide()) return;

        try {
            Player player = event.player;
            ChunkPos currentChunkPos = new ChunkPos(player.blockPosition());

            if (currentWorldId == null) {
                onWorldChanged(player.level());
            }

            if (!currentChunkPos.equals(lastChunkPos)) {
                handleChunkEnter(player, currentChunkPos);
                lastChunkPos = currentChunkPos;
            }
        } catch (Exception e) {
            LOGGER.error("Error in player tick event", e);
        }
    }

    private void startAsyncChunkProcessor() {
        ASYNC_EXECUTOR.scheduleAtFixedRate(() -> {
            ChunkTransformTask task = TRANSFORM_QUEUE.poll();
            if (task != null && !PROCESSING_CHUNKS.contains(task.chunkPos)) {
                PROCESSING_CHUNKS.add(task.chunkPos);
                processChunkAsync(task);
            }
        }, 0, 1000 / chunksPerSecond, TimeUnit.MILLISECONDS);
    }

    private void handleChunkEnter(Player player, ChunkPos chunkPos) {
        if (transformRadius == 0) {
            transformSingleChunk(player, chunkPos);
        } else {
            transformChunksInRadius(player, transformRadius);
        }
    }

    private void transformSingleChunk(Player player, ChunkPos chunkPos) {
        if (player == null || player.level() == null) return;

        Level level = player.level();
        String worldId;

        try {
            worldId = getWorldIdentifier(level);
        } catch (Exception e) {
            LOGGER.error("Failed to get world identifier, skipping chunk transformation", e);
            return;
        }

        if (!worldId.equals(currentWorldId)) {
            currentWorldId = worldId;
            if (saveChunkTransformations) {
                try {
                    loadTransformedChunksForWorld(worldId);
                } catch (Exception e) {
                    LOGGER.error("Failed to load transformed chunks for world: " + worldId, e);
                }
            }
        }

        long chunkPosLong = chunkPos.toLong();
        Set<Long> currentWorldChunks = worldTransformedChunks.computeIfAbsent(worldId, k -> ConcurrentHashMap.newKeySet());

        if (!saveChunkTransformations) {
            currentWorldChunks.clear();
        }

        if (currentWorldChunks.contains(chunkPosLong) || PROCESSING_CHUNKS.contains(chunkPosLong)) {
            return;
        }

        currentWorldChunks.add(chunkPosLong);

        try {
            LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
            List<Block> validBlocks = getValidBlocks(level);
            if (validBlocks.isEmpty()) return;

            Block randomBlock = validBlocks.get(RANDOM.nextInt(validBlocks.size()));
            BlockState newBlockState = randomBlock.defaultBlockState();

            if (optimizationsEnabled) {
                TRANSFORM_QUEUE.offer(new ChunkTransformTask(chunk, newBlockState, chunkPosLong));
            } else {
                transformChunkImmediate(chunk, newBlockState);
            }

            if (saveChunkTransformations) {
                try {
                    saveTransformedChunksForWorld(worldId);
                } catch (Exception e) {
                    LOGGER.error("Failed to save transformed chunks for world: " + worldId, e);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error during chunk transformation", e);
            currentWorldChunks.remove(chunkPosLong);
        }
    }

    private static void saveTransformedChunksForWorld(String worldId) {
        if (!saveChunkTransformations || worldId == null) return;

        Set<Long> chunksToSave = worldTransformedChunks.get(worldId);
        if (chunksToSave == null || chunksToSave.isEmpty()) return;

        try {
            Path worldSpecificPath = getWorldSpecificTransformedChunksPath(worldId);
            Files.createDirectories(worldSpecificPath.getParent());
            try (Writer writer = Files.newBufferedWriter(worldSpecificPath)) {
                GSON.toJson(chunksToSave, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save transformed chunks for world: " + worldId, e);
        }
    }

    private static void loadTransformedChunksForWorld(String worldId) {
        if (!saveChunkTransformations || worldId == null) return;

        Path worldSpecificPath = getWorldSpecificTransformedChunksPath(worldId);
        if (Files.exists(worldSpecificPath)) {
            try (Reader reader = Files.newBufferedReader(worldSpecificPath)) {
                Set<Long> loadedChunks = GSON.fromJson(reader, new TypeToken<Set<Long>>(){}.getType());
                if (loadedChunks != null) {
                    worldTransformedChunks.put(worldId, ConcurrentHashMap.newKeySet());
                    worldTransformedChunks.get(worldId).addAll(loadedChunks);
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load transformed chunks for world: " + worldId, e);
            }
        }
    }

    private static List<Block> cachedValidBlocks = null;
    private static long lastValidBlocksUpdate = 0;
    private static final long VALID_BLOCKS_CACHE_TIME = 30000;

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
                transformChunkOptimized(task.chunk, task.targetBlockState);
            } finally {
                PROCESSING_CHUNKS.remove(task.chunkPos);
            }
        }, ASYNC_EXECUTOR);
    }

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
                    if (shouldSkipBlock(currentState.getBlock(), currentState)) continue;
                    level.setBlock(pos, targetBlockState, Block.UPDATE_ALL);
                }
            }
        }
    }

    private void transformChunkOptimized(LevelChunk chunk, BlockState targetBlockState) {
        Level level = chunk.getLevel();
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
                    if (shouldSkipBlock(currentState.getBlock(), currentState)) continue;
                    positionsToChange.add(pos);
                }
            }
        }

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

        int batchSize = Math.min(maxBlocksPerTick, positions.size());
        int totalBatches = (int) Math.ceil((double) positions.size() / batchSize);

        for (int batch = 0; batch < totalBatches; batch++) {
            final int batchIndex = batch;
            final int startIndex = batch * batchSize;
            final int endIndex = Math.min(startIndex + batchSize, positions.size());
            final List<BlockPos> batchPositions = positions.subList(startIndex, endIndex);

            ASYNC_EXECUTOR.schedule(() -> {
                Objects.requireNonNull(level.getServer()).execute(() -> {
                    for (BlockPos pos : batchPositions) {
                        level.setBlock(pos, targetBlockState, Block.UPDATE_CLIENTS);
                    }
                    if (batchIndex == totalBatches - 1) {
                        for (BlockPos pos : batchPositions) {
                            level.blockUpdated(pos, targetBlockState.getBlock());
                        }
                    }
                });
            }, batch * 50L, TimeUnit.MILLISECONDS);
        }
    }

    public void transformChunksInRadius(Player player, int radius) {
        ChunkPos playerChunk = new ChunkPos(player.blockPosition());

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                ChunkPos chunkPos = new ChunkPos(playerChunk.x + x, playerChunk.z + z);
                int delay = optimizationsEnabled ? (Math.abs(x) + Math.abs(z)) * 100 : 0;

                if (delay > 0) {
                    ASYNC_EXECUTOR.schedule(() -> transformSingleChunk(player, chunkPos),
                            delay, TimeUnit.MILLISECONDS);
                } else {
                    transformSingleChunk(player, chunkPos);
                }
            }
        }
    }

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