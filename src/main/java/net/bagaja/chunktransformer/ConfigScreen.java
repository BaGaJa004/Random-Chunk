package net.bagaja.chunktransformer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class ConfigScreen extends Screen {
    private final Screen lastScreen;
    private final BlockConfig config;
    private int scrollOffset = 0;
    private static final int BUTTONS_PER_PAGE = 8; // Reduced to make room for performance options
    private List<Block> displayedBlocks;
    private EditBox searchBox;
    private List<Button> blockButtons = new ArrayList<>();
    private String currentSearchText = "";

    // UI Components
    private Button selectAllButton;
    private Button scrollUpButton;
    private Button scrollDownButton;
    private Button doneButton;
    private Button saveConfigButton;

    // Performance option components
    private Checkbox enableOptimizationsCheckbox;
    private EditBox maxBlocksPerTickBox;
    private EditBox chunksPerSecondBox;
    private EditBox radiusBox;
    private Button performancePresetButton;
    private int currentPreset = 1; // 0=Fast, 1=Balanced, 2=Slow

    public ConfigScreen(Screen lastScreen, BlockConfig config) {
        super(Component.literal("Chunk Transformer Configuration"));
        this.lastScreen = lastScreen;
        this.config = config;
        this.displayedBlocks = new ArrayList<>();
    }

    @Override
    protected void init() {
        super.init();

        // Initial block list population if it's empty
        if (displayedBlocks.isEmpty()) {
            displayedBlocks = StreamSupport.stream(ForgeRegistries.BLOCKS.spliterator(), false)
                    .collect(Collectors.toList());
        }

        int buttonWidth = 200;
        int buttonHeight = 20;
        int spacing = 24;
        int leftColumn = width / 4 - buttonWidth / 2;
        int rightColumn = 3 * width / 4 - buttonWidth / 2;

        // Preserve the search box text
        String previousSearch = searchBox != null ? searchBox.getValue() : "";

        // Left Column - Block Configuration
        // Search box
        searchBox = new EditBox(this.font, leftColumn, 24, buttonWidth, 20, Component.literal("Search blocks"));
        searchBox.setMaxLength(50);
        searchBox.setValue(previousSearch);
        searchBox.setResponder(this::onSearchTextChanged);
        searchBox.setTextColor(0xFFFFFF);
        searchBox.setBordered(true);
        searchBox.setFocused(true);
        this.addRenderableWidget(searchBox);

        // Enable All button
        selectAllButton = Button.builder(Component.literal("Enable All Blocks"), button -> {
                    for (Block block : displayedBlocks) {
                        String blockId = ForgeRegistries.BLOCKS.getKey(block).toString();
                        config.getBlockStates().put(blockId, true);
                    }
                    config.save();
                    refreshButtons();
                })
                .pos(leftColumn, 48)
                .size(buttonWidth, buttonHeight)
                .build();
        this.addRenderableWidget(selectAllButton);

        // Block toggle buttons start
        int firstBlockButtonY = 76;
        addBlockButtons(firstBlockButtonY, leftColumn);

        // Scroll buttons for block list
        scrollUpButton = Button.builder(Component.literal("⬆"), button -> scrollUp())
                .pos(leftColumn + buttonWidth + 4, firstBlockButtonY)
                .size(20, 20)
                .build();
        this.addRenderableWidget(scrollUpButton);

        scrollDownButton = Button.builder(Component.literal("⬇"), button -> scrollDown())
                .pos(leftColumn + buttonWidth + 4, firstBlockButtonY + (BUTTONS_PER_PAGE - 1) * spacing)
                .size(20, 20)
                .build();
        this.addRenderableWidget(scrollDownButton);

        // Right Column - Performance Configuration
        int rightColumnY = 24;

        // Performance preset button
        String[] presetNames = {"Fast PC", "Balanced", "Slow PC"};
        performancePresetButton = Button.builder(
                        Component.literal("Preset: " + presetNames[currentPreset]),
                        this::cyclePreset)
                .pos(rightColumn, rightColumnY)
                .size(buttonWidth, buttonHeight)
                .build();
        this.addRenderableWidget(performancePresetButton);
        rightColumnY += spacing;

        // Enable optimizations checkbox
        enableOptimizationsCheckbox = Checkbox.builder(
                        Component.literal("Enable Performance Optimizations"),
                        this.font)
                .pos(rightColumn, rightColumnY)
                .selected(ChunkTransformerMod.isOptimizationsEnabled())
                .build();
        this.addRenderableWidget(enableOptimizationsCheckbox);
        rightColumnY += spacing;

        // Max blocks per tick
        this.addRenderableWidget(new Button.Builder(Component.literal("Max Blocks/Tick:"), (button) -> {})
                .pos(rightColumn, rightColumnY)
                .size(buttonWidth / 2 - 2, buttonHeight)
                .build());

        maxBlocksPerTickBox = new EditBox(this.font, rightColumn + buttonWidth / 2 + 2, rightColumnY,
                buttonWidth / 2 - 2, buttonHeight, Component.literal("Max blocks per tick"));
        maxBlocksPerTickBox.setValue(String.valueOf(ChunkTransformerMod.getMaxBlocksPerTick()));
        maxBlocksPerTickBox.setFilter(this::isValidNumber);
        this.addRenderableWidget(maxBlocksPerTickBox);
        rightColumnY += spacing;

        // Chunks per second
        this.addRenderableWidget(new Button.Builder(Component.literal("Chunks/Second:"), (button) -> {})
                .pos(rightColumn, rightColumnY)
                .size(buttonWidth / 2 - 2, buttonHeight)
                .build());

        chunksPerSecondBox = new EditBox(this.font, rightColumn + buttonWidth / 2 + 2, rightColumnY,
                buttonWidth / 2 - 2, buttonHeight, Component.literal("Chunks per second"));
        chunksPerSecondBox.setValue(String.valueOf(ChunkTransformerMod.getChunksPerSecond()));
        chunksPerSecondBox.setFilter(this::isValidNumber);
        this.addRenderableWidget(chunksPerSecondBox);
        rightColumnY += spacing;

        // Transform radius
        this.addRenderableWidget(new Button.Builder(Component.literal("Transform Radius:"), (button) -> {})
                .pos(rightColumn, rightColumnY)
                .size(buttonWidth / 2 - 2, buttonHeight)
                .build());

        radiusBox = new EditBox(this.font, rightColumn + buttonWidth / 2 + 2, rightColumnY,
                buttonWidth / 2 - 2, buttonHeight, Component.literal("Transform radius"));
        radiusBox.setValue(String.valueOf(ChunkTransformerMod.getTransformRadius()));
        radiusBox.setFilter(this::isValidNumber);
        this.addRenderableWidget(radiusBox);
        rightColumnY += spacing;

        // Save Config button
        saveConfigButton = Button.builder(
                        Component.literal((ChunkTransformerMod.shouldSaveChunkTransformations() ? "✔ " : "✘ ") + "Save Chunk Transformations"),
                        button -> {
                            ChunkTransformerMod.toggleSaveChunkTransformations();
                            button.setMessage(Component.literal(
                                    (ChunkTransformerMod.shouldSaveChunkTransformations() ? "✔ " : "✘ ") + "Save Chunk Transformations"
                            ));
                        })
                .pos(rightColumn, rightColumnY)
                .size(buttonWidth, buttonHeight)
                .build();
        this.addRenderableWidget(saveConfigButton);

        // Bottom buttons
        Button applyButton = Button.builder(Component.literal("Apply Settings"), this::applySettings)
                .pos(width / 2 - 104, height - 52)
                .size(100, 20)
                .build();
        this.addRenderableWidget(applyButton);

        doneButton = Button.builder(Component.literal("Done"), button -> minecraft.setScreen(lastScreen))
                .pos(width / 2 + 4, height - 52)
                .size(100, 20)
                .build();
        this.addRenderableWidget(doneButton);
    }

    private boolean isValidNumber(String text) {
        if (text.isEmpty()) return true;
        try {
            int value = Integer.parseInt(text);
            return value >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void cyclePreset(Button button) {
        currentPreset = (currentPreset + 1) % 3;
        String[] presetNames = {"Fast PC", "Balanced", "Slow PC"};
        button.setMessage(Component.literal("Preset: " + presetNames[currentPreset]));

        // Apply preset values
        switch (currentPreset) {
            case 0: // Fast PC
                maxBlocksPerTickBox.setValue("2000");
                chunksPerSecondBox.setValue("5");
                radiusBox.setValue("1");
                enableOptimizationsCheckbox.selected();
                break;
            case 1: // Balanced
                maxBlocksPerTickBox.setValue("500");
                chunksPerSecondBox.setValue("2");
                radiusBox.setValue("1");
                enableOptimizationsCheckbox.selected();
                break;
            case 2: // Slow PC
                maxBlocksPerTickBox.setValue("100");
                chunksPerSecondBox.setValue("1");
                radiusBox.setValue("1");
                enableOptimizationsCheckbox.selected();
                break;
        }
    }

    private void applySettings(Button button) {
        try {
            boolean optimizations = enableOptimizationsCheckbox.selected();
            int maxBlocks = Integer.parseInt(maxBlocksPerTickBox.getValue());
            int chunksPerSec = Integer.parseInt(chunksPerSecondBox.getValue());
            int radius = Integer.parseInt(radiusBox.getValue());

            // Validate ranges
            maxBlocks = Math.max(1, Math.min(10000, maxBlocks));
            chunksPerSec = Math.max(1, Math.min(20, chunksPerSec));
            radius = Math.max(0, Math.min(10, radius));

            // Apply settings
            ChunkTransformerMod.setOptimizationsEnabled(optimizations);
            ChunkTransformerMod.setMaxBlocksPerTick(maxBlocks);
            ChunkTransformerMod.setChunksPerSecond(chunksPerSec);
            ChunkTransformerMod.setTransformRadius(radius);

            // Update text boxes with validated values
            maxBlocksPerTickBox.setValue(String.valueOf(maxBlocks));
            chunksPerSecondBox.setValue(String.valueOf(chunksPerSec));
            radiusBox.setValue(String.valueOf(radius));

            button.setMessage(Component.literal("Settings Applied!"));

            // Reset button text after 2 seconds
            minecraft.execute(() -> {
                try {
                    Thread.sleep(2000);
                    button.setMessage(Component.literal("Apply Settings"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

        } catch (NumberFormatException e) {
            button.setMessage(Component.literal("Invalid Numbers!"));
        }
    }

    private void onSearchTextChanged(String searchTerm) {
        currentSearchText = searchTerm;
        displayedBlocks = StreamSupport.stream(ForgeRegistries.BLOCKS.spliterator(), false)
                .filter(block -> searchTerm.isEmpty() ||
                        ForgeRegistries.BLOCKS.getKey(block).toString().toLowerCase().contains(searchTerm.toLowerCase()))
                .collect(Collectors.toList());
        scrollOffset = 0;
        refreshButtons();
    }

    private void refreshButtons() {
        blockButtons.forEach(button -> {
            removeWidget(button);
        });
        blockButtons.clear();

        int leftColumn = width / 4 - 200 / 2;
        addBlockButtons(76, leftColumn);
    }

    private void addBlockButtons(int startY, int xPos) {
        int buttonWidth = 200;
        int buttonHeight = 20;
        int spacing = 24;

        // Add block toggle buttons
        int startIndex = scrollOffset * BUTTONS_PER_PAGE;
        for (int i = 0; i < BUTTONS_PER_PAGE && startIndex + i < displayedBlocks.size(); i++) {
            Block block = displayedBlocks.get(startIndex + i);
            String blockId = ForgeRegistries.BLOCKS.getKey(block).toString();
            boolean enabled = config.isBlockEnabled(block);

            Button blockButton = Button.builder(
                            Component.literal((enabled ? "✔ " : "✘ ") + blockId),
                            button -> {
                                config.toggleBlock(blockId);
                                button.setMessage(Component.literal(
                                        (config.isBlockEnabled(block) ? "✔ " : "✘ ") + blockId
                                ));
                            })
                    .pos(xPos, startY + i * spacing)
                    .size(buttonWidth, buttonHeight)
                    .build();

            blockButtons.add(blockButton);
            this.addRenderableWidget(blockButton);
        }
    }

    private void scrollUp() {
        if (scrollOffset > 0) {
            scrollOffset--;
            refreshButtons();
        }
    }

    private void scrollDown() {
        if ((scrollOffset + 1) * BUTTONS_PER_PAGE < displayedBlocks.size()) {
            scrollOffset++;
            refreshButtons();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            minecraft.setScreen(lastScreen);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(graphics);

        // Draw section headers
        graphics.drawString(this.font, "Block Configuration", width / 4 - 100, 8, 0xFFFFFF);
        graphics.drawString(this.font, "Performance Settings", 3 * width / 4 - 100, 8, 0xFFFFFF);

        // Draw the search text mirror label
        String labelText = "Search: " + currentSearchText;
        graphics.drawString(this.font, labelText, 5, 5, 0xFFFFFF);

        super.render(graphics, mouseX, mouseY, partialTicks);

        // Render page number for blocks
        int totalPages = (int) Math.ceil((double) displayedBlocks.size() / BUTTONS_PER_PAGE);
        graphics.drawString(this.font, "Page " + (scrollOffset + 1) + " of " + totalPages,
                width / 4 - 50, height - 80, 0xFFFFFF);

        // Draw performance info tooltips
        if (mouseX >= 3 * width / 4 - 100 && mouseX <= 3 * width / 4 + 100) {
            int tooltipY = 0;
            String tooltip = null;

            if (mouseY >= 48 && mouseY <= 68) {
                tooltip = "Higher = faster transformations, may cause lag";
                tooltipY = 68;
            } else if (mouseY >= 72 && mouseY <= 92) {
                tooltip = "How many chunks transform per second";
                tooltipY = 92;
            } else if (mouseY >= 96 && mouseY <= 116) {
                tooltip = "0 = current chunk only, 1 = 3x3 area, etc.";
                tooltipY = 116;
            }

            if (tooltip != null) {
                graphics.drawString(this.font, tooltip, mouseX - 100, tooltipY + 5, 0xFFFF55);
            }
        }
    }

    private void renderBackground(GuiGraphics graphics) {
        renderDirtBackground(graphics);
    }

    public void renderDirtBackground(GuiGraphics graphics) {
        graphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
    }
}