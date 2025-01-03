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
import net.minecraftforge.client.event.InputEvent;
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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Method to toggle save configuration
    public static void toggleSaveChunkTransformations() {
        saveChunkTransformations = !saveChunkTransformations;
        saveChunkSaveConfig();
    }

    // Method to check if chunk transformations should be saved
    public static boolean shouldSaveChunkTransformations() {
        return saveChunkTransformations;
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

    public ChunkTransformerMod() {
        configKey = new KeyMapping(
                "key.chunktransformer.config",
                GLFW.GLFW_KEY_K,
                "key.categories.misc"
        );

        // Load save configuration
        loadChunkSaveConfig();

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.addListener(this::onKeyInput);
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
        if (lastChunkPos == null || !currentChunkPos.equals(lastChunkPos)) {
            handleChunkEnter(player, currentChunkPos);
            lastChunkPos = currentChunkPos;
        }
    }

    private void handleChunkEnter(Player player, ChunkPos chunkPos) {
        long chunkPosLong = chunkPos.toLong();

        // If saving is disabled, always reset transformation
        if (!saveChunkTransformations) {
            transformedChunks.clear();
        }

        // Check if chunk was already transformed
        if (transformedChunks.contains(chunkPosLong)) {
            return;
        }

        // Add chunk to transformed list
        transformedChunks.add(chunkPosLong);

        // Get the level and chunk
        Level level = player.level();
        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);

        // Create list of valid blocks
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

        // Randomly select a block
        Block randomBlock = validBlocks.get(RANDOM.nextInt(validBlocks.size()));
        BlockState newBlockState = randomBlock.defaultBlockState();

        // Transform the chunk
        transformChunk(chunk, newBlockState);
    }

    private void transformChunk(LevelChunk chunk, BlockState targetBlockState) {
        Level level = chunk.getLevel();

        // Iterate through all block positions in the chunk
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

                    // Skip if the block is in the exclusion list or is water/lava
                    if (currentBlock == Blocks.AIR ||
                            currentBlock == Blocks.SPAWNER ||
                            currentBlock == Blocks.END_PORTAL_FRAME ||
                            currentBlock == Blocks.WATER ||
                            currentBlock == Blocks.LAVA ||
                            currentState.getFluidState().is(Fluids.WATER) ||
                            currentState.getFluidState().is(Fluids.LAVA) ||
                            currentState.getFluidState().is(Fluids.FLOWING_WATER) ||
                            currentState.getFluidState().is(Fluids.FLOWING_LAVA)) {
                        continue;
                    }

                    // Remove old block completely first
                    level.removeBlock(pos, false);

                    // Place new block with full update
                    level.setBlock(pos, targetBlockState, Block.UPDATE_ALL);

                    // Force a block update to neighbors
                    level.blockUpdated(pos, targetBlockState.getBlock());
                }
            }
        }
    }
}

// no replacement after reopening world (optional)