package net.bagaja.chunktransformer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
    private static final int BUTTONS_PER_PAGE = 10;
    private List<Block> displayedBlocks;
    private EditBox searchBox;
    private List<Button> blockButtons = new ArrayList<>();
    private String currentSearchText = "";
    private Button selectAllButton;
    private Button scrollUpButton;
    private Button scrollDownButton;
    private Button doneButton;
    private Button saveConfigButton;

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

        // Preserve the search box text
        String previousSearch = searchBox != null ? searchBox.getValue() : "";

        // Create and add the search box with visible text
        searchBox = new EditBox(this.font, width / 2 - buttonWidth / 2, 24, buttonWidth, 20, Component.literal("Search blocks"));
        searchBox.setMaxLength(50);
        searchBox.setValue(previousSearch);
        searchBox.setResponder(this::onSearchTextChanged);
        searchBox.setTextColor(0xFFFFFF);
        searchBox.setBordered(true);
        searchBox.setFocused(true);
        this.addRenderableWidget(searchBox);

        // Calculate positions for the top buttons
        int enableAllY = 48;
        int saveConfigY = enableAllY + buttonHeight + 8; // Add 8 pixels of spacing
        int firstBlockButtonY = saveConfigY + buttonHeight + 16; // Add 16 pixels of spacing before block buttons

        // Enable All button
        selectAllButton = Button.builder(Component.literal("Enable All Blocks"), button -> {
                    for (Block block : displayedBlocks) {
                        String blockId = ForgeRegistries.BLOCKS.getKey(block).toString();
                        config.getBlockStates().put(blockId, true);
                    }
                    config.save();
                    refreshButtons();
                })
                .pos(width / 2 - buttonWidth / 2, enableAllY)
                .size(buttonWidth, buttonHeight)
                .build();
        this.addRenderableWidget(selectAllButton);

        // Save Config button
        saveConfigButton = Button.builder(
                        Component.literal((ChunkTransformerMod.shouldSaveChunkTransformations() ? "✔ " : "✘ ") + "Save Chunk Transformations"),
                        button -> {
                            ChunkTransformerMod.toggleSaveChunkTransformations();
                            button.setMessage(Component.literal(
                                    (ChunkTransformerMod.shouldSaveChunkTransformations() ? "✔ " : "✘ ") + "Save Chunk Transformations"
                            ));
                        })
                .pos(width / 2 - buttonWidth / 2, saveConfigY)
                .size(buttonWidth, buttonHeight)
                .build();
        this.addRenderableWidget(saveConfigButton);

        // Done button
        doneButton = Button.builder(Component.literal("Done"), button -> minecraft.setScreen(lastScreen))
                .pos(width / 2 - 100, height - 28)
                .size(200, 20)
                .build();
        this.addRenderableWidget(doneButton);

        // Scroll buttons
        scrollUpButton = Button.builder(Component.literal("⬆"), button -> scrollUp())
                .pos(width / 2 + buttonWidth / 2 + 4, firstBlockButtonY)
                .size(20, 20)
                .build();
        this.addRenderableWidget(scrollUpButton);

        scrollDownButton = Button.builder(Component.literal("⬇"), button -> scrollDown())
                .pos(width / 2 + buttonWidth / 2 + 4, height - 48)
                .size(20, 20)
                .build();
        this.addRenderableWidget(scrollDownButton);

        // Block toggle buttons
        addBlockButtons(firstBlockButtonY);
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

        // Calculate the same starting Y position as in init()
        int enableAllY = 48;
        int saveConfigY = enableAllY + 20 + 8;
        int firstBlockButtonY = saveConfigY + 20 + 16;

        addBlockButtons(firstBlockButtonY);
    }

    private void addBlockButtons(int startY) {
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
                    .pos(width / 2 - buttonWidth / 2, startY + i * spacing)
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

        // Draw the search text mirror label
        String labelText = "Search: " + currentSearchText;
        int labelWidth = this.font.width(labelText);
        graphics.drawString(this.font,
                labelText,
                5, 5, // Position in top-left corner
                0xFFFFFF); // White color

        super.render(graphics, mouseX, mouseY, partialTicks);

        // Render page number
        int totalPages = (int) Math.ceil((double) displayedBlocks.size() / BUTTONS_PER_PAGE);
        graphics.drawCenteredString(this.font,
                Component.literal("Page " + (scrollOffset + 1) + " of " + totalPages),
                this.width / 2, height - 48, 0xFFFFFF);
    }

    private void renderBackground(GuiGraphics graphics) {
        renderDirtBackground(graphics);
    }

    public void renderDirtBackground(GuiGraphics graphics) {
        graphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
    }
}