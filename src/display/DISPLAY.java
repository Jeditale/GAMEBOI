package display;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.Consumer;

public class DISPLAY extends JFrame {

    private final int SCREEN_WIDTH = 160;
    private final int SCREEN_HEIGHT = 144;
    private final int SCALE = 3;

    private final Canvas canvas;

    private final Color[] PALETTE = {
            new Color(0xE0, 0xF8, 0xD0), // 0 - Off-white
            new Color(0x88, 0xC0, 0x70), // 1 - Light green
            new Color(0x34, 0x68, 0x56), // 2 - Dark green
            new Color(0x08, 0x18, 0x20)  // 3 - Off-black
    };

    // This buffer will hold the pixel data to be drawn
    private final BufferedImage image;

    public DISPLAY(String romName,Consumer<Integer> onSave, Consumer<Integer> onLoad) {
        setTitle("GAMEBOI");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);

        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");

        JMenuItem[] loadItems = new JMenuItem[6]; // Slots 1-5 (Index 0 unused)

        // --- SETUP LOAD MENU FIRST (So Save menu can see it) ---
        JMenu loadMenu = new JMenu("Quick Load");
        for (int i = 1; i <= 5; i++) {
            int slot = i;
            JMenuItem item = new JMenuItem("Slot " + slot);

            // Load initial icon
            addIconToItem(item, romName, slot);

            item.addActionListener(e -> onLoad.accept(slot));

            // Store this button in our array
            loadItems[slot] = item;

            loadMenu.add(item);
        }

        // --- SETUP SAVE MENU ---
        JMenu saveMenu = new JMenu("Quick Save");
        for (int i = 1; i <= 5; i++) {
            int slot = i;
            JMenuItem item = new JMenuItem("Slot " + slot);

            // Load initial icon
            addIconToItem(item, romName, slot);

            item.addActionListener(e -> {
                onSave.accept(slot); // Perform the save

                // UPDATE BOTH ICONS
                addIconToItem(item, romName, slot);       // Update 'Save' menu item
                addIconToItem(loadItems[slot], romName, slot); // Update 'Load' menu item
            });

            saveMenu.add(item);
        }

        fileMenu.add(saveMenu);
        fileMenu.add(loadMenu);
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
    private void addIconToItem(JMenuItem item, String romName, int slot) {
        String imagePath = "Saves" + File.separator + "Screenshots" + File.separator + romName + "_slot" + slot + ".png";
        File f = new File(imagePath);

        if (f.exists()) {
            try {
                // FIX: Use ImageIO.read() instead of new ImageIcon(path).
                // ImageIO forces a fresh read from the hard drive, ignoring the cache.
                Image img = ImageIO.read(f);

                // Scale it (40x36)
                Image newImg = img.getScaledInstance(40, 36, Image.SCALE_SMOOTH);

                // Update the menu item
                item.setIcon(new ImageIcon(newImg));

            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            // Optional: If no save exists, remove the icon (so it doesn't show an old one)
            item.setIcon(null);
        }
    }
}