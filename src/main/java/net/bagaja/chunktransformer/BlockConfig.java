package net.bagaja.chunktransformer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.level.block.FallingBlock;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class BlockConfig {
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("chunktransformer_blocks.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private Map<String, Boolean> blockStates = new HashMap<>();

    public BlockConfig() {
        // Initialize with default values
        ForgeRegistries.BLOCKS.forEach(block -> {
            String blockId = ForgeRegistries.BLOCKS.getKey(block).toString();
            // By default, enable all blocks except falling blocks
            boolean defaultEnabled = !(block instanceof FallingBlock);
            blockStates.put(blockId, defaultEnabled);
        });
        load();
    }

    public void toggleBlock(String blockId) {
        blockStates.put(blockId, !blockStates.getOrDefault(blockId, true));
        save();
    }

    public boolean isBlockEnabled(Block block) {
        String blockId = ForgeRegistries.BLOCKS.getKey(block).toString();
        return blockStates.getOrDefault(blockId, true);
    }

    public Map<String, Boolean> getBlockStates() {
        return new HashMap<>(blockStates);
    }

    public void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                Type type = new TypeToken<HashMap<String, Boolean>>(){}.getType();
                Map<String, Boolean> loadedStates = GSON.fromJson(reader, type);
                if (loadedStates != null) {
                    blockStates = loadedStates;
                }
            } catch (IOException e) {
                ChunkTransformerMod.LOGGER.error("Failed to load block configuration", e);
            }
        } else {
            save(); // Create default config file
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(blockStates, writer);
            }
        } catch (IOException e) {
            ChunkTransformerMod.LOGGER.error("Failed to save block configuration", e);
        }
    }
}