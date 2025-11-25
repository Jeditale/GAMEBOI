package display;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

public class DISPLAY extends JFrame {

    private final int SCREEN_WIDTH = 160;
    private final int SCREEN_HEIGHT = 144;
    private final int SCALE = 3; // Adjusted to 3x for better visibility, change if needed

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

    public DISPLAY(ActionListener onSaveState, ActionListener onLoadState) {
        setTitle("GAMEBOI");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);

        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");

        JMenuItem saveItem = new JMenuItem("Save State (F5)");
        saveItem.addActionListener(onSaveState);

        JMenuItem loadItem = new JMenuItem("Load State (F9)");
        loadItem.addActionListener(onLoadState);

        fileMenu.add(saveItem);
        fileMenu.add(loadItem);
        menuBar.add(fileMenu);
        setJMenuBar(menuBar);

        // Create a canvas to draw on
        canvas = new Canvas();
        canvas.setPreferredSize(new Dimension(SCREEN_WIDTH * SCALE, SCREEN_HEIGHT * SCALE));

        // --- FOCUS FIX START ---
        canvas.setFocusable(true); // 1. Allow the canvas to accept keyboard input
        canvas.requestFocus();     // 2. Request focus immediately on startup

        // 3. Add Mouse Listener to reclaim focus when clicked
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                canvas.requestFocusInWindow();
                System.out.println("Focus reclaimed by Emulator Canvas");
            }
        });
        // --- FOCUS FIX END ---

        add(canvas);
        pack(); // Sizes the window to fit the canvas

        setLocationRelativeTo(null); // Center the window
        setVisible(true);

        // Create an image buffer to draw to.
        image = new BufferedImage(SCREEN_WIDTH, SCREEN_HEIGHT, BufferedImage.TYPE_INT_RGB);
    }

    /**
     * IMPORTANT: Use this method to add your JOYPAD listener.
     * We attach it to the Canvas, not the JFrame, to ensure it works with the focus fix.
     */
    public void addInputListener(KeyListener listener) {
        canvas.addKeyListener(listener);
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
                // Bounds check to prevent crashes if PPU array size differs
                if (y < screenData.length && x < screenData[0].length) {
                    g.setColor(PALETTE[screenData[y][x]]);
                    g.fillRect(x, y, 1, 1);
                }
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