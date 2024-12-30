package net.bagaja.chunktransformer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.TickEvent;

import java.util.*;

@Mod(ChunkTransformerMod.MODID)
public class ChunkTransformerMod {

    public static final String MODID = "chunktransformer";
    private static final Random RANDOM = new Random();
    private static final Set<Long> transformedChunks = new HashSet<>();
    private ChunkPos lastChunkPos = null;

    public ChunkTransformerMod() {
        MinecraftForge.EVENT_BUS.register(this);
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
                    block.defaultBlockState().isCollisionShapeFullBlock(level, BlockPos.ZERO)) {
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

                    // Skip if the block is in the exclusion list
                    if (currentBlock == Blocks.AIR ||
                            currentBlock == Blocks.SPAWNER ||
                            currentBlock == Blocks.END_PORTAL_FRAME) {
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