package ppu;

import cpu.CPU;
import manager.MMU;

import java.io.Serializable;
import java.util.Arrays;

public class PPU implements java.io.Serializable{

    // Main memory bus
    private CPU cpu;
    private MMU mmu;
    private boolean frameReady = false;
    private boolean debugSpriteLogShown = false;
    private int debugFrameCounter = 0;



    // Helper class for sorting sprites
    private static class Sprite implements Serializable {
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

    // A temporary list to hold sprites for the current scanline, populated during OAM scan.
    private final java.util.List<Sprite> lineSprites = new java.util.ArrayList<>(10);

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

    public void fetchCPU(CPU cpu) {
        this.cpu = cpu;
    }

    public int[][] getScreen() {
        return this.screen;
    }

    /**
     * This is the main PPU tick function. It gets called for every CPU T-cycle.
     */
    public void cycle(int lastInstructionCycles) {
        if (!isLcdEnabled()) {
            // If LCD is off, reset state and do nothing.
            cycle = 0;
            ly = 0;
            mode = 0;
            stat &= 0xF8; // Clear mode and LYC flags
            return;
        }

        cycle += lastInstructionCycles;

        // Determine the current mode based on the cycle count for the current scanline
        int oldMode = mode;
        if (ly < 144) {
            if (cycle >= 0 && cycle < MODE_OAM_SCAN_CYCLES) {
                mode = 2; // OAM Scan
            } else if (cycle >= MODE_OAM_SCAN_CYCLES && cycle < MODE_OAM_SCAN_CYCLES + MODE_DRAWING_CYCLES) {
                mode = 3; // Drawing
            } else if (cycle >= MODE_OAM_SCAN_CYCLES + MODE_DRAWING_CYCLES && cycle < 456) {
                mode = 0; // H-Blank
            }
        } else {
            mode = 1; // V-Blank
        }

        // --- 2. Perform Actions and check for interrupts on Mode Change ---
        if (oldMode != mode) {
            // Actions to perform when a mode is *entered*
            if (mode == 0) {
                renderScanline(); // Render the line upon entering H-Blank
            } else if (mode == 1) { // V-Blank starts at line 144, cycle 0
                mmu.write(0xFF0F, mmu.read(0xFF0F) | 1); // Request V-Blank Interrupt
                this.frameReady = true;
                debugFrameCounter++;
//                mmu.hack_forceDMATransfer(); //only work in tetris /dr mario
            } else if (mode == 2) {
                scanOAMForLine(); // Scan OAM upon entering OAM Scan mode

                // OAM Bug Simulation: If the CPU is active during the first M-cycle of OAM scan, corruption occurs.
//                if (cpu != null && !cpu.isHalted()) oamBugCorruption();
            }
        }

        // The STAT interrupt check must happen continuously
        lastStatInterruptLine = statInterruptLine;
        checkStatInterrupts();
        if (statInterruptLine && !lastStatInterruptLine) {
            requestSTATInterrupt();
        }

        // --- 3. Advance Timers ---
        if (cycle >= 456) {
            cycle -= 456; // Use subtraction to carry over extra cycles
            ly++;
            if (ly > 153) {
                ly = 0;
            }
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
        int mapBase = (lcdc & 0x08) == 0 ? 0x9800 : 0x9C00; // BG Tile Map Select
        int dataBase = (lcdc & 0x10) != 0 ? 0x8000 : 0x9000; // BG & Window Tile Data Select
        boolean signedTileIndex = (dataBase == 0x9000);

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
                tileAddr = dataBase + ((byte)tileNum * 16);
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
        int dataBase = (lcdc & 0x10) != 0 ? 0x8000 : 0x9000;
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
                int tileNum = vram[tileMapAddress - 0x8000];

                // Adjust tile number for signed addressing mode
                // Get the address of the tile's pattern data
                int tileAddr;
                if (signedTileIndex) {
                    tileAddr = dataBase + ((byte) tileNum * 16);
                } else {
                    tileAddr = dataBase + (tileNum * 16);
                }

                int lineAddr = tileAddr + (tileLine * 2);
                
                // Read tile data directly from VRAM array
                int data1 = vram[lineAddr - 0x8000];
                int data2 = vram[lineAddr + 1 - 0x8000];

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
                screen[ly][x] = shade;
            }
        }
    }

    /**
     * Scans OAM during Mode 2 to find up to 10 sprites visible on the current scanline (ly).
     * The results are sorted by X-coordinate and stored in the lineSprites list.
     */
    private void scanOAMForLine() {
        lineSprites.clear();
        boolean use8x16 = (lcdc & 0x04) != 0;
        int height = use8x16 ? 16 : 8;

        // DEBUG: Run this check once every 60 frames (approx 1 second)
        // We check Line 72 (Middle of screen where falling blocks are)
        boolean doDebug = (ly == 72 && (debugFrameCounter % 60 == 0));

        if (doDebug) {
//            System.out.println("\n=== OAM HEARTBEAT (Frame " + debugFrameCounter + ") ===");
        }

        for (int i = 0; i < 40 && lineSprites.size() < 10; i++) {
            int index = i * 4;

            // Force positive integer (Fixes the "Negative Byte" trap)
            int rawY = oam[index] & 0xFF;
            int rawX = oam[index+1] & 0xFF;
            int tile = oam[index+2] & 0xFF;
            int attr = oam[index+3] & 0xFF;

            int yPos = rawY - 16;
            int xPos = rawX - 8;

            // Is this sprite visible on the current line?
            if (ly >= yPos && ly < (yPos + height)) {
                lineSprites.add(new Sprite(yPos, xPos, tile, attr, i));

                if (doDebug) {
                    System.out.println(String.format(" -> VISIBLE! Sprite %d: Y=%d X=%d Tile=0x%02X",
                            i, yPos, xPos, tile));
                }
            } else if (doDebug && rawY != 0 && rawY < 160) {
                // Log sprites that exist but are just on a different line
                // This helps prove memory isn't empty
//                System.out.println(String.format("    (Exists) Sprite %d: Y=%d X=%d (Not on Line %d)",
//                        i, yPos, xPos, ly));
            }
        }

        if (doDebug) {
//            if (lineSprites.isEmpty()) System.out.println(" -> NO SPRITES VISIBLE ON LINE " + ly);
//            System.out.println("======================================");
        }


        lineSprites.sort((s1, s2) -> {
            if (s1.x != s2.x) return Integer.compare(s1.x, s2.x);
            return Integer.compare(s1.oamIndex, s2.oamIndex);
        });
    }

    private void renderSprites() {
        boolean use8x16 = (lcdc & 0x04) != 0;
        int height = use8x16 ? 16 : 8;

        // Render the sprites found during the OAM scan (Mode 2).
        // We iterate in reverse to handle sprite-over-sprite priority correctly (lower X-coordinate wins).
        for (int i = lineSprites.size() - 1; i >= 0; i--) {
            Sprite s = lineSprites.get(i);
//            System.out.println("Drawing Sprite: Y=" + s.y + " X=" + s.x + " Tile=" + s.tile);
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
//                if (colorId != 0) System.out.println("Pixel Found! Color: " + colorId);
                if (colorId == 0) continue;


                int x = s.x + (7 - tilePixel);
                if (x >= 0 && x < 160) {
                    // Priority check:
                    // 1. Is the master BG/Window enable bit (LCDC bit 0) on?
                    // 2. Is the sprite's priority bit (OBJ-to-BG) set?
                    // 3. Is the underlying BG pixel's color ID non-transparent?
                    boolean bgEnabled = (lcdc & 0x01) != 0;
                    if (bgEnabled && (s.attributes & 0x80) != 0 && bgLineBuffer[x] != 0) {
                        continue;
                    }
                    screen[ly][x] = 3;
                    int palette = ((s.attributes & 0x10) != 0) ? obp1 : obp0;
                    screen[ly][x] = (palette >> (colorId * 2)) & 0x03;


                }
            }
        }
    }

    /**
     * Simulates the OAM bug on the DMG.
     * When the CPU accesses a 16-bit register during the first M-cycle of OAM scan (Mode 2),
     * the OAM memory becomes corrupted in a specific, repeatable pattern.
     */
    private void oamBugCorruption() {
        // This pattern is derived from hardware tests. The PPU's internal OAM pointer
        // gets modified by the CPU's high byte access, causing writes to be scattered.
        for (int i = 0; i < 40; i += 2) {
            int val = oam[i + 1];
            oam[i] = val;
            oam[i + 2] = val;
        }
    }

    /**
     * Updates the STAT register's Mode flag (bits 0-1)
     */
    private void checkStatInterrupts() {
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
        oam[address - 0xFE00] = data & 0xFF;
    }
    public boolean isFrameReady() {
        return this.frameReady;
    }

    public void clearFrameReadyFlag() {
        this.frameReady = false;
    }

    public boolean isVramAccessible() {
        // VRAM is only accessible during modes 0, 1, and 2. It is inaccessible during mode 3 (Drawing).
        return mode != 3;
    }

    public boolean isOamAccessible() {
        // OAM is inaccessible to the CPU if a DMA transfer is active.
        if (cpu != null && cpu.isDmaActive()) {
            return false;
        }
        // Otherwise, access is determined by the PPU mode.
        // Accessible in H-Blank (0) and V-Blank (1).
        return mode < 2;
    }

    // --- Updated Register Read/Write ---

    public int readRegister(int address) {
        switch (address) {
            case 0xFF40: return this.lcdc;
            case 0xFF41:
                // Combine read-only mode bits with read/write interrupt bits
                int currentStat = (stat & 0xFC); // Keep writable bits, clear mode/lyc
                currentStat |= (mode & 0x03); // Set mode bits
                if (ly == lyc) currentStat |= 0x04; // Set LYC coincidence flag
                return currentStat | 0x80; // Bit 7 is always 1
            case 0xFF42: return this.scy;
            case 0xFF43: return this.scx;
            case 0xFF44: return this.ly;
            case 0xFF45: return this.lyc;
            case 0xFF47: return this.bgp;   // BGP
            case 0xFF48: return this.obp0;  // OBP0
            case 0xFF49: return this.obp1;  // OBP1
            case 0xFF4A: return this.wy;    // WY
            case 0xFF4B: return this.wx;    // WX
            default:     return 0xFF;
        }
    }

    public void writeRegister(int address, int data) {
        switch (address) {
            case 0xFF40:
                boolean wasEnabled = isLcdEnabled();
                boolean isNowEnabled = (data & 0x80) != 0;
                this.lcdc = data;

                if (!isNowEnabled && wasEnabled) {
                    // LCD is being turned OFF. LY and cycle counter reset. PPU enters H-Blank.
                    this.ly = 0;
                    this.cycle = 0;
                    this.mode = 0; // PPU enters H-Blank
                } else if (isNowEnabled && !wasEnabled) {
                    // LCD is being turned ON. State is reset. PPU immediately enters OAM Scan.
                    // This is a critical step for synchronization.
                    this.ly = 0;
                    this.cycle = 4; // The PPU doesn't start at cycle 0 when turned on. This aligns with hardware behavior.
                    this.mode = 2; // Start in OAM Scan
                }
                break;
            case 0xFF41:
                // Only bits 3-6 (interrupt enables) are writable
                // Bits 0-2 (Mode, LYC flag) are read-only
                this.stat = (data & 0x78) | (this.stat & 0x07);
                checkStatInterrupts();
                if (statInterruptLine && !lastStatInterruptLine)
                    requestSTATInterrupt();
                break;
            case 0xFF42: this.scy = data; break;
            case 0xFF43: this.scx = data; break;
            case 0xFF44: // LY is Read-Only. Writes reset it to 0 and also reset the PPU's internal cycle counter.
                this.ly = 0;
                this.cycle = 0;
                break;
            case 0xFF45: this.lyc = data; break;
            case 0xFF47: this.bgp = data; break;
            case 0xFF48: this.obp0 = data; break;
            case 0xFF49: this.obp1 = data; break;
            case 0xFF4A: this.wy = data; break;
            case 0xFF4B: this.wx = data; break;
            default:     break;
        }
    }
    public int getMode() {
        return this.mode;
    }
}