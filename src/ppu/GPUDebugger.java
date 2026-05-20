package ppu;

import javax.swing.*;
import java.awt.*;
import java.util.Timer;
import java.util.TimerTask;

public class GPUDebugger extends JPanel {

    private final PPU ppu;

    // Palette for the debugger: White, Light Gray, Dark Gray, Black
    private final int[] PALETTE = {0xFFFFFF, 0xC0C0C0, 0x606060, 0x000000};

    // FPS Counters
    private long lastTime = System.currentTimeMillis();
    private int frames = 0;
    private int currentFps = 0;

    public GPUDebugger(PPU ppu) {
        this.ppu = ppu;
        this.setPreferredSize(new Dimension(500, 600));

        JFrame frame = new JFrame("GPU Debugger (VRAM & OAM)");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.add(this);
        frame.pack();
        frame.setLocationRelativeTo(null); // Center
        frame.setVisible(true);

        // Auto-refresh the debugger at 60FPS (~16ms)
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                repaint();
            }
        }, 100, 16);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        // --- FPS CALCULATION START ---
        frames++;
        if (System.currentTimeMillis() - lastTime >= 1000) {
            currentFps = frames;
            frames = 0;
            lastTime = System.currentTimeMillis();
        }
        // --- FPS CALCULATION END ---

        // 1. Draw VRAM Tiles (0x8000 - 0x97FF)
        // We draw 16 tiles per row, scaled 2x
        g2.scale(2.0, 2.0);

        g2.setColor(Color.BLUE);
        g2.drawString("Tile Data (0x8000-0x97FF)", 5, 10);

        // Draw FPS in Top Right (Red so it stands out)
        g2.setColor(Color.RED);
        g2.drawString("FPS: " + currentFps, 200, 10);

        int xDraw = 0;
        int yDraw = 15; // Start below text

        for (int i = 0; i < 384; i++) {
            // Calculate memory address for this tile (16 bytes per tile)
            int tileAddr = 0x8000 + (i * 16);
            drawTile(g2, tileAddr, xDraw, yDraw);

            xDraw += 8;
            if (xDraw >= 16 * 8) { // 16 tiles per row
                xDraw = 0;
                yDraw += 8;
            }
        }

        // 2. Draw OAM / Register Info
        g2.scale(0.5, 0.5); // Reset scale for text
        g2.setColor(Color.BLACK);
        g2.setFont(new Font("Monospaced", Font.BOLD, 12));

        int textX = 10;
        int textY = (yDraw * 2) + 40; // Position below tiles

        // --- Register Diagnostics ---
        int lcdc = ppu.readRegister(0xFF40);
        boolean objEnable = (lcdc & 0x02) != 0;

        g2.drawString("--- PPU STATE ---", textX, textY);
        g2.drawString(String.format("LCDC: 0x%02X | OBJ Enabled: %s", lcdc, objEnable ? "YES" : "NO"), textX, textY + 15);
        g2.drawString(String.format("STAT: 0x%02X | LY: %d | Mode: %d", ppu.readRegister(0xFF41), ppu.ly, ppu.getMode()), textX, textY + 30);

        if (!objEnable) {
            g2.setColor(Color.RED);
            g2.drawString("WARNING: Sprites (OBJ) are disabled in LCDC!", textX, textY + 45);
            g2.setColor(Color.BLACK);
        }

        // --- OAM Dump ---
        textY += 60;
        g2.drawString("--- OAM (Top 10 Sprites) ---", textX, textY);
        textY += 15;
        g2.drawString("IDX | Y   | X   | Tile | Attr", textX, textY);

        for (int i = 0; i < 10; i++) { // Show first 10 sprites
            int addr = 0xFE00 + (i * 4);
            int y = ppu.readOAM(addr);
            int x = ppu.readOAM(addr + 1);
            int tile = ppu.readOAM(addr + 2);
            int attr = ppu.readOAM(addr + 3);

            textY += 15;
            g2.drawString(String.format("%02d  | %03d | %03d | 0x%02X | 0x%02X", i, y, x, tile, attr), textX, textY);
        }
    }

    private void drawTile(Graphics2D g, int address, int xOffset, int yOffset) {
        for (int line = 0; line < 8; line++) {
            // Using PPU's public readVRAM method (bypasses Mode 3 locking for debug)
            int byte1 = ppu.readVRAM(address + (line * 2));
            int byte2 = ppu.readVRAM(address + (line * 2) + 1);

            for (int bit = 7; bit >= 0; bit--) {
                int colorBit1 = (byte1 >> bit) & 1;
                int colorBit2 = (byte2 >> bit) & 1;
                int colorIndex = (colorBit2 << 1) | colorBit1;

                g.setColor(new Color(PALETTE[colorIndex]));
                g.fillRect(xOffset + (7 - bit), yOffset + line, 1, 1);
            }
        }
    }
}