package display;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

public class DISPLAY extends JFrame {

    private final int SCREEN_WIDTH = 160;
    private final int SCREEN_HEIGHT = 144;
    private final int SCALE = 4; // You can change this to make the window bigger or smaller

    private final Canvas canvas;

    // A simple monochrome palette (like the original Game Boy)
    // 0 = White, 1 = Light Gray, 2 = Dark Gray, 3 = Black
    private final Color[] PALETTE = {
            new Color(0xE0, 0xF8, 0xD0), // 0 - Off-white
            new Color(0x88, 0xC0, 0x70), // 1 - Light green
            new Color(0x34, 0x68, 0x56), // 2 - Dark green
            new Color(0x08, 0x18, 0x20)  // 3 - Off-black
    };

    // This buffer will hold the pixel data to be drawn
    private final BufferedImage image;

    public DISPLAY() {
        setTitle("GameBoy Emulator");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);

        // Create a canvas to draw on
        canvas = new Canvas();
        canvas.setPreferredSize(new Dimension(SCREEN_WIDTH * SCALE, SCREEN_HEIGHT * SCALE));
        add(canvas);
        pack(); // Sizes the window to fit the canvas

        setLocationRelativeTo(null); // Center the window
        setVisible(true);

        // Create an image buffer to draw to.
        // We'll draw to this image, then scale the image onto the canvas.
        image = new BufferedImage(SCREEN_WIDTH, SCREEN_HEIGHT, BufferedImage.TYPE_INT_RGB);
    }

    /**
     * Takes the 2D array from the PPU and draws it to the screen.
     * @param screenData A 144x160 array of color values (0-3)
     */
    public void render(int[][] screenData) {
        // 1. Get the graphics context of our buffered image
        Graphics g = image.getGraphics();

        // 2. Loop through every pixel and set its color based on the palette
        for (int y = 0; y < SCREEN_HEIGHT; y++) {
            for (int x = 0; x < SCREEN_WIDTH; x++) {
                // The PPU's screen[y][x] will give a value from 0-3
                // We use this to pick a color from our palette
                g.setColor(PALETTE[screenData[y][x]]);
                g.fillRect(x, y, 1, 1); // Draw a 1x1 pixel
            }
        }
        g.dispose();

        // 3. Get the graphics context of the *canvas*
        Graphics canvasGraphics = canvas.getGraphics();

        // 4. Draw our buffered image onto the canvas, scaling it up
        if (canvasGraphics != null) {
            canvasGraphics.drawImage(image, 0, 0, SCREEN_WIDTH * SCALE, SCREEN_HEIGHT * SCALE, null);
            canvasGraphics.dispose();
        }
    }
}