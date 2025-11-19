package ppu;

import manager.MMU;

import java.util.Arrays;

public class PPU {

    // Main memory bus
    private MMU mmu;
    private boolean frameReady = false;

    // Helper class for sorting sprites
    private static class Sprite {
        int y, x, tile, attributes, oamIndex;

        Sprite(int y, int x, int tile, int attributes, int oamIndex) {
            this.y = y;
            this.x = x;
            this.tile = tile;
            this.attributes = attributes;
            this.oamIndex = oamIndex;
        }
    }

    // Video RAM (8KB)
    private final int[] vram = new int[8192];
    // Object Attribute Memory (Sprite Info)
    private final int[] oam = new int[160];

    // The 160x144 pixel screen buffer. Stores color numbers (0-3).
    private final int[][] screen = new int[144][160];
    private final int[] bgLineBuffer = new int[160];

    // PPU Registers
    private int lcdc = 0x91; // 0xFF40 - LCD Control
    private int stat = 0x00; // 0xFF41 - LCDC Status
    private int scy = 0x00;  // 0xFF42 - Scroll Y
    private int scx = 0x00;  // 0xFF43 - Scroll X
    public int ly = 0;      // 0xFF44 - Current Scanline (Read-only)
    private int lyc = 0x00;  // 0xFF45 - LY Compare
    private int bgp = 0xFC;  // 0xFF47 - BG Palette Data
    private int obp0 = 0xFF; // 0xFF48 - Object Palette 0
    private int obp1 = 0xFF; // 0xFF49 - Object Palette 1
    private int wy = 0x00;   // 0xFF4A - Window Y
    private int wx = 0x00;   // 0xFF4B - Window X

    // Internal PPU State
    private int cycle = 0;   // Cycle counter for the current scanline (0-455)
    private int mode = 2;    // Current PPU mode (0: H-Blank, 1: V-Blank, 2: OAM Scan, 3: Drawing)

    // STAT interrupt line state. This is used to model the 1-cycle delay for STAT interrupts.
    private boolean statInterruptLine = false;
    private boolean lastStatInterruptLine = false;

    // PPU Mode timings
    private static final int MODE_OAM_SCAN_CYCLES = 80;
    private static final int MODE_DRAWING_CYCLES = 172;
    private static final int MODE_HBLANK_CYCLES = 204; // 456 - 172 - 80
    private static final int MODE_VBLANK_CYCLES = 4560; // 456 * 10 lines

    // Helper method to check if the LCD is enabled
    private boolean isLcdEnabled() {
        return (lcdc & 0x80) != 0; // Bit 7: LCD Display Enable
    }

    public void fetchMMU(MMU mmu) {
        this.mmu = mmu;
    }

    public int[][] getScreen() {
        return this.screen;
    }

    /**
     * This is the main PPU tick function. It gets called for every CPU T-cycle.
     */
    // Corrected PPU.cycle() Structure (Only the essential logic remains)
    public void cycle() {
        if (!isLcdEnabled()) {
            // If LCD is off, reset state and do nothing.
            cycle = 0;
            // On LCD disable, LY is reset, but mode and STAT flags are also cleared.
            if (ly != 0) ly = 0;
            mode = 0;
            stat &= 0xF8; // Clear mode and LYC flags
            return;
        }

        cycle++;

        if (cycle >= 456) {
            // End of a scanline
            cycle -= 456;
            ly++;

            if (ly > 153) {
                // End of a full frame, wrap back to line 0
                ly = 0;
            }
        }

        // --- 1. Handle Mode and STAT logic ---
        int oldMode = mode;
        lastStatInterruptLine = statInterruptLine;

        // Determine the current mode based on the cycle count for the current line
        if (ly < 144) {
            if (cycle < 80) mode = 2;      // Mode 2: OAM Scan
            else if (cycle < 252) mode = 3; // Mode 3: Drawing
            else mode = 0;                 // Mode 0: H-Blank
        } else {
            mode = 1; // Mode 1: V-Blank
        }

        // --- 2. Perform Actions and check for interrupts on Mode Change ---
        if (oldMode != mode) {
            if (mode == 0) {
                renderScanline();
            } else if (mode == 1 && ly == 144) {
                int if_flag = mmu.read(0xFF0F);
                mmu.write(0xFF0F, if_flag | 1); // Request V-Blank Interrupt
                this.frameReady = true;
            }
        }

        // The STAT check must happen every single T-cycle
        checkStatInterrupts(oldMode);

        // An interrupt is only triggered on the RISING EDGE of the STAT line.
        if (statInterruptLine && !lastStatInterruptLine) {
            requestSTATInterrupt();
        }
    }

    private void renderScanline() {
        // 1. Check if Background is enabled
        if ((lcdc & 0x01) != 0) {
            renderBackground();
        } else {
            Arrays.fill(bgLineBuffer, 0); // Fill with color ID 0
            // If BG is disabled, the line is just "white" (color 0)
            for(int i = 0; i < 160; i++) {
                screen[ly][i] = 0;
            }
        }

        // 2. Check if Window is enabled (and visible)
        if ((lcdc & 0x20) != 0 && wy <= ly) {
            renderWindow();
        }

        // 3. Check if Sprites are enabled
        if ((lcdc & 0x02) != 0) {
            renderSprites();
        }
    }

    private void renderBackground() {
        int mapBase = (lcdc & 0x08) == 0 ? 0x9800 : 0x9C00;
        int dataBase = (lcdc & 0x10) == 0 ? 0x9000 : 0x8000; // <--- CHANGE THIS
        boolean signedTileIndex = (dataBase == 0x9000); // <--- CHANGE THIS

        // Get the Y coordinate within the 256x256 pixel background map
        int yPos = (ly + scy) & 0xFF;

        // Which row of tiles are we in? (0-31)
        int tileRow = (yPos / 8) & 0x1F;

        // Which line of pixels within that tile are we on? (0-7)
        int tileLine = yPos % 8;

        for (int x = 0; x < 160; x++) {
            // Get the X coordinate within the 256x256 pixel background map
            int xPos = (x + scx) & 0xFF;

            // Which column of tiles are we in? (0-31)
            int tileCol = (xPos / 8) & 0x1F;

            // Get the tile index from the map
            int tileMapAddress = mapBase + (tileRow * 32) + tileCol;
            int tileNum = vram[tileMapAddress - 0x8000];

            // Get the address of the tile's pattern data
            // Each tile is 16 bytes, 2 bytes per line
            int tileAddr;

            if (signedTileIndex) {
                // Handle signed tile indexing (8800-97FF method)
                // Convert unsigned tileNum (0-255) to a signed byte (-128 to 127)
                int signedTileNum = (tileNum > 127) ? (tileNum - 256) : tileNum;
                tileAddr = dataBase + (signedTileNum * 16);
            } else {
                // Handle unsigned tile indexing (8000-8FFF method)
                tileAddr = dataBase + (tileNum * 16);
            }

            int lineAddr = tileAddr + (tileLine * 2);

            // --- Check for VRAM bounds ---
            // This is a common place for errors. Let's check it.
            int data1Address = lineAddr - 0x8000;
            int data2Address = lineAddr + 1 - 0x8000;

            if (data1Address < 0 || data1Address >= 8192 || data2Address < 0 || data2Address >= 8192) {
                // This tile is out of bounds, draw blank
                screen[ly][x] = 0;
                continue; // Skip to next pixel
            }
            // --- End of bounds check ---

            // Get the two bytes for this pixel line
            int data1 = vram[data1Address];
            int data2 = vram[data2Address];

            // Which bit in the bytes represents our current pixel?
            // (7 is left-most, 0 is right-most)
            int bitIndex = 7 - (xPos % 8);
            int bitMask = (1 << bitIndex);

            // Combine the two bits to get the color ID (0-3)
            int colorBit1 = (data2 & bitMask) > 0 ? 1 : 0;
            int colorBit0 = (data1 & bitMask) > 0 ? 1 : 0;
            int colorId = (colorBit1 << 1) | colorBit0;

            // Map this color ID to a shade of gray using the BGP register
            int shade = (bgp >> (colorId * 2)) & 0x03;

            bgLineBuffer[x] = colorId; // Store the original color ID for sprite priority
            screen[ly][x] = shade;
        }
    }

    // Stub functions for Window and Sprites
    // These are complex and should be implemented next
    private void renderWindow() {
        // Check if the Window is enabled and positioned on the current line (ly)
        // LCDC Bit 5: Window Display Enable
        if ((lcdc & 0x20) == 0 || ly < wy) {
            return;
        }

        // Window X position is WX - 7
        int windowXStart = wx - 7;

        // LCDC Bit 6: Window Tile Map Select (0=9800-9BFF, 1=9C00-9FFF)
        int mapBase = (lcdc & 0x40) == 0 ? 0x9800 : 0x9C00;

        // LCDC Bit 4: BG/Window Tile Data Select (0=8800-97FF, 1=8000-8FFF)
        int dataBase = (lcdc & 0x10) == 0 ? 0x9000 : 0x8000;
        boolean signedTileIndex = (dataBase == 0x9000);

        // Window Y coordinate relative to the top of the window (0-143)
        int windowY = ly - wy;

        // Which tile row are we in? (0-31)
        int tileRow = (windowY / 8) & 0x1F;

        // Which line of pixels within that tile are we on? (0-7)
        int tileLine = windowY % 8;

        for (int x = 0; x < 160; x++) {
            // Only draw if the pixel is within the horizontal bounds of the window
            if (x >= windowXStart) {

                // Window X coordinate relative to the start of the window (0-159)
                int windowX = x - windowXStart;

                // Which tile column are we in? (0-31)
                int tileCol = (windowX / 8) & 0x1F;

                // --- Tile Fetching ---
                int tileMapAddress = mapBase + (tileRow * 32) + tileCol;
                int tileNum = mmu.read(tileMapAddress); // Use mmu.read() for the map

                // Adjust tile number for signed addressing mode
                if (signedTileIndex && tileNum > 127) {
                    tileNum -= 256;
                }

                // Get the address of the tile's pattern data
                int tileAddr;
                if (signedTileIndex) {
                    tileAddr = dataBase + (tileNum + 128) * 16;
                } else {
                    tileAddr = dataBase + (tileNum * 16);
                }

                int lineAddr = tileAddr + (tileLine * 2);

                // Safety: Check VRAM bounds before accessing (already done in MMU if you are using MMU read/write for VRAM, but safer here)
                // Assuming MMU routes reads correctly to VRAM (0x8000-0x9FFF)
                int data1 = mmu.read(lineAddr);
                int data2 = mmu.read(lineAddr + 1);

                // Which bit in the bytes represents our current pixel?
                int bitIndex = 7 - (windowX % 8);
                int bitMask = (1 << bitIndex);

                // --- Color Calculation ---
                int colorBit1 = (data2 & bitMask) > 0 ? 1 : 0;
                int colorBit0 = (data1 & bitMask) > 0 ? 1 : 0;
                int colorId = (colorBit1 << 1) | colorBit0;

                // Use the Background Palette (BGP) for the Window Layer
                int shade = (bgp >> (colorId * 2)) & 0x03;

                // Set the pixel in the screen buffer
                bgLineBuffer[x] = colorId; // Also update the priority buffer
                screen[ly][x] = shade;
            }
        }
    }

    private void renderSprites() {
        boolean use8x16 = (lcdc & 0x04) != 0;
        int height = use8x16 ? 16 : 8;

        // 1. Find all sprites visible on the current scanline (ly)
        java.util.List<Sprite> visibleSprites = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int index = i * 4;
            int yPos = oam[index] - 16;
            if (ly >= yPos && ly < (yPos + height)) {
                visibleSprites.add(new Sprite(
                        yPos,
                        oam[index + 1] - 8,
                        oam[index + 2],
                        oam[index + 3],
                        i // Original OAM index for tie-breaking
                ));
            }
        }

        // 2. Sort sprites by X-coordinate (and OAM index for tie-breaking)
        visibleSprites.sort((s1, s2) -> {
            if (s1.x != s2.x) {
                return Integer.compare(s1.x, s2.x);
            }
            return Integer.compare(s1.oamIndex, s2.oamIndex);
        });

        // 3. Take only the first 10 sprites
        int spritesToRender = Math.min(10, visibleSprites.size());

        // 4. Render the selected sprites (in reverse order to handle overlap correctly)
        for (int i = spritesToRender - 1; i >= 0; i--) {
            Sprite s = visibleSprites.get(i);

            int line = ly - s.y;

            // Handle Y-Flip
            if ((s.attributes & 0x40) != 0) {
                line = height - 1 - line;
            }

            int tileIndex = s.tile;
            if (use8x16) {
                tileIndex &= 0xFE; // Ignore LSB for 8x16 sprites
            }

            int tileAddr = 0x8000 + (tileIndex * 16) + (line * 2);
            int data1 = vram[tileAddr - 0x8000];
            int data2 = vram[tileAddr + 1 - 0x8000];

            for (int tilePixel = 7; tilePixel >= 0; tilePixel--) {
                int colorBit = tilePixel;
                // Handle X-Flip
                if ((s.attributes & 0x20) != 0) {
                    colorBit = 7 - colorBit;
                }

                int mask = 1 << colorBit;
                int colorId = ((data2 & mask) != 0 ? 2 : 0) | ((data1 & mask) != 0 ? 1 : 0);

                // Color 0 is transparent for sprites
                if (colorId == 0) continue;

                int x = s.x + (7 - tilePixel);
                if (x >= 0 && x < 160) {
                    // Priority check (Sprite behind BG if BG color is not 0)
                    if ((s.attributes & 0x80) != 0 && bgLineBuffer[x] != 0) {
                        continue;
                    }

                    int palette = ((s.attributes & 0x10) != 0) ? obp1 : obp0;
                    screen[ly][x] = (palette >> (colorId * 2)) & 0x03;
                }
            }
        }
    }

    /**
     * Updates the STAT register's Mode flag (bits 0-1)
     */
    private void checkStatInterrupts(int oldMode) {
        // Update the LY=LYC coincidence flag in the STAT register
        if (ly == lyc) {
            stat |= 0x04;
        } else {
            stat &= ~0x04;
        }

        // Check for interrupt conditions
        boolean modeInterrupt = (mode == 0 && (stat & 0x08) != 0) ||
                                (mode == 1 && (stat & 0x10) != 0) ||
                                (mode == 2 && (stat & 0x20) != 0);
        boolean lycInterrupt = (ly == lyc && (stat & 0x40) != 0);

        statInterruptLine = modeInterrupt || lycInterrupt;
    }

    /**
     * Helper method to request a STAT interrupt (Bit 1 of IF register).
     */
    private void requestSTATInterrupt() {
        int if_flag = mmu.read(0xFF0F);
        mmu.write(0xFF0F, if_flag | 2);
    }

    // ... your existing read/write methods for VRAM and OAM ...
    public int readVRAM(int address) {
        return vram[address - 0x8000];
    }
    public void writeVRAM(int address, int data) {
        vram[address - 0x8000] = data;
    }
    public int readOAM(int address){
        return oam[address - 0xFE00];
    }
    public void writeOAM(int address,int data){
        oam[address - 0xFE00] = data;
    }
    public boolean isFrameReady() {
        return this.frameReady;
    }

    public void clearFrameReadyFlag() {
        this.frameReady = false;
    }

    public boolean isOamAccessible() {
        // OAM is only accessible during H-Blank (Mode 0) and V-Blank (Mode 1)
        return mode == 0 || mode == 1;
    }

    // --- Updated Register Read/Write ---

    public int readRegister(int address) {
        switch (address) {
            case 0xFF40: return this.lcdc;
            case 0xFF41:
                // Combine read-only mode bits with read/write interrupt bits
                return this.stat | 0x80; // Bit 7 is always 1
            case 0xFF42: return this.scy;
            case 0xFF43: return this.scx;
            case 0xFF44: return this.ly;
            case 0xFF45: return this.lyc;
            case 0xFF47: return this.bgp;
            case 0xFF48: return this.obp0;
            case 0xFF49: return this.obp1;
            case 0xFF4A: return this.wy;
            case 0xFF4B: return this.wx;
            default:     return 0xFF; // Unused registers often return 0xFF
        }
    }

    public void writeRegister(int address, int data) {
        switch (address) {
            case 0xFF40:
                boolean wasEnabled = isLcdEnabled();
                boolean isNowEnabled = (data & 0x80) != 0;
                this.lcdc = data;

                if (!isNowEnabled && wasEnabled) {
                    // LCD is being turned OFF. LY immediately resets to 0.
                    this.ly = 0;
                    this.cycle = 0;
                    this.mode = 0; // PPU enters H-Blank
                } else if (isNowEnabled && !wasEnabled) {
                    // LCD is being turned ON.
                    this.ly = 0;
                    this.cycle = 0;
                }
                break;
            case 0xFF41:
                // Only bits 3-6 (interrupt enables) are writable
                // Bits 0-2 (Mode, LYC flag) are read-only
                this.stat = (data & 0xF8) | (this.stat & 0x07);
                // Writing to STAT can immediately trigger an interrupt check
                checkStatInterrupts(this.mode);
                if(statInterruptLine && !lastStatInterruptLine)
                    requestSTATInterrupt();
                break;
            case 0xFF42: this.scy = data; break;
            case 0xFF43: this.scx = data; break;
            case 0xFF44: /* LY is Read-Only, writes reset it to 0 */ this.ly = 0; break;
            case 0xFF45: this.lyc = data; break;
            case 0xFF47: this.bgp = data; break;
            case 0xFF48: this.obp0 = data; break;
            case 0xFF49: this.obp1 = data; break;
            case 0xFF4A: this.wy = data; break;
            case 0xFF4B: this.wx = data; break;
            default:     break;
        }
    }
}