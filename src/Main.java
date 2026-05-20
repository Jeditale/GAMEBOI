import IO.CARTRIDGE;
import IO.JOYPAD;
import cpu.CPU;
import display.DISPLAY;
import manager.MMU;
import ppu.GPUDebugger;
import ppu.PPU;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;

public class Main {
    // 1. GLOBAL COMPONENTS (Must be static so LoadState can swap them)
    public static CPU cpu;
    public static MMU mmu;
    public static PPU ppu;
    public static CARTRIDGE cartridge;
    public static JOYPAD joypad;
    public static boolean isTurbo = false;

    // 2. The "Freeze" Switch
    public static boolean isRunning = true;

    public static void main(String[] args) throws IOException {

        cpu = new CPU();
        mmu = new MMU();
        ppu = new PPU();
        cartridge = new CARTRIDGE();
        joypad = new JOYPAD();




        // Wire them up for the first time
        linkComponents(cpu, mmu, ppu, cartridge, joypad);

        // Note: We pass the ROM path to the save/load functions
        String romPath = "ROMS/hard to run/Legend of Zelda, The - Link's Awakening (USA, Europe) (Rev 2).gb";
        String romName = new File(romPath).getName().replace(".gb", "");

        DISPLAY display = new DISPLAY(romName,
                slot -> saveState(romPath, slot),

                slot -> loadState(romPath, slot)
        );
        display.addInputListener(joypad);
        display.addInputListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE) {
                    isTurbo = true; // Hold Space to go fast
                }
            }

            @Override
            public void keyReleased(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE) {
                    isTurbo = false; // Release to go normal speed
                }
            }
        });

        // Load ROM
        cartridge.loadROM(romPath);

        // 2. SETUP BATTERY SAVE (Native .sav files)
        File batteryDir = new File("Saves/Battery");
        if (!batteryDir.exists()) {
            batteryDir.mkdirs();
        }

        String savName = new File(romPath).getName().replace(".gb", ".sav");
        String savPath = "Saves" + File.separator + "Battery" + File.separator + savName;

        cartridge.setSavePath(savPath);
        cartridge.loadBatterySave(savPath);

        // Input Setup
        display.addInputListener(joypad);

        // Debugger (Optional)
         GPUDebugger debugger = new GPUDebugger(ppu);

        // Boot Bypass
        mmu.setBootRomEnabled(false);
        cpu.bypassBootROM();

        System.out.println("Emulator started (Boot ROM Bypassed)...");


        final double TARGET_FPS = 59.7275;
        final long OPTIMAL_TIME = (long) (1000000000 / TARGET_FPS); // In Nanoseconds

        long lastLoopTime = System.nanoTime();

        while (true) {
            long now = System.nanoTime();
            long updateLength = now - lastLoopTime;
            lastLoopTime = now;

            // 1. Check if we need to pause (Save States)
            if (isRunning) {

                // 2. Run ONE Frame (70224 Cycles)
                int cyclesThisFrame = 0;
                while (cyclesThisFrame < 70224) {
                    int cycles = cpu.step();
                    ppu.cycle(cycles);
                    mmu.timer_tick(cycles);
                    cyclesThisFrame += cycles;
                }
                display.render(ppu.getScreen());
            }

            // 3. THE SYNC LOGIC
            // If Turbo is OFF, we wait until the frame time (16.7ms) has passed.
            // If Turbo is ON, we don't wait at all (run as fast as CPU allows).
            if (!isTurbo) {
                long timeout = (System.nanoTime() - lastLoopTime);
                long wait = OPTIMAL_TIME - timeout;

                if (wait > 0) {
                    try {
                        // Convert nanoseconds to milliseconds for sleep
                        Thread.sleep(wait / 1000000);
                    } catch (InterruptedException e) { }
                }
            }
        }
    }

    public static void linkComponents(CPU c, MMU m, PPU p, CARTRIDGE cart, JOYPAD joy) {
        c.fetchMMU(m);
        m.fetchCPU(c);
        m.fetchPPU(p);
        m.fetchCartridge(cart);
        m.fetchJoypad(joy);
        p.fetchMMU(m);
        p.fetchCPU(c);
        joy.fetchMMU(m);
    }

    public static void saveState(String romPath, int slot) {
        // 1. Setup Folders
        new File("Saves/States").mkdirs();
        new File("Saves/Screenshots").mkdirs();

        // 2. Build Filenames
        // Example: "Saves/States/Pokemon Red_slot1.state"
        String romName = new File(romPath).getName().replace(".gb", "");
        String statePath = "Saves" + File.separator + "States" + File.separator + romName + "_slot" + slot + ".state";
        String imagePath = "Saves" + File.separator + "Screenshots" + File.separator + romName + "_slot" + slot + ".png";

        // 3. Freeze Engine
        boolean wasRunning = isRunning;
        isRunning = false;

        // 4. Save the State Data
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(statePath))) {
            out.writeObject(cpu);
            out.writeObject(mmu);
            out.writeObject(ppu);
            out.writeObject(cartridge);
            System.out.println("Quick Save (Slot " + slot + ") Complete!");
        } catch (IOException e) {
            e.printStackTrace();
        }

        // 5. Save the Screenshot (Raw Game Pixels only)
        takeScreenshot(ppu, imagePath);

        // 6. Resume
        isRunning = wasRunning;
    }

    public static void loadState(String romPath, int slot) {
        String romName = new File(romPath).getName().replace(".gb", "");
        String statePath = "Saves" + File.separator + "States" + File.separator + romName + "_slot" + slot + ".state";
        File f = new File(statePath);

        if (!f.exists()) {
            System.out.println("Slot " + slot + " is empty.");
            return;
        }

        // Freeze
        isRunning = false;
        try { Thread.sleep(50); } catch (Exception e) {}

        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
            System.out.println("Loading Slot " + slot + "...");

            cpu = (CPU) in.readObject();
            mmu = (MMU) in.readObject();
            ppu = (PPU) in.readObject();
            cartridge = (CARTRIDGE) in.readObject();

            linkComponents(cpu, mmu, ppu, cartridge, joypad);
            System.out.println("Loaded!");
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Resume
        isRunning = true;
    }

    public static String getSavePath(String romPath, String ext) {
        File f = new File("Saves"); if(!f.exists()) f.mkdir();
        return "Saves" + File.separator + new File(romPath).getName().replace(".gb", "") + ext;
    }
    public static void takeScreenshot(PPU ppu, String filepath) {
        int[][] screenData = ppu.getScreen();
        int width = 160;
        int height = 144;

        // 1. Create a blank image
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        // 2. Define the Palette (Classic Game Boy Green colors)
        // 0=White, 1=Light, 2=Dark, 3=Black
        int[] PALETTE = {0xE0F8D0, 0x88C070, 0x346856, 0x081820};

        // 3. Draw pixels directly from PPU data
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Safety check in case PPU isn't ready
                if (y < screenData.length && x < screenData[0].length) {
                    int colorIndex = screenData[y][x];
                    // Safety check for color index
                    if (colorIndex >= 0 && colorIndex <= 3) {
                        img.setRGB(x, y, PALETTE[colorIndex]);
                    }
                }
            }
        }

        // 4. Write to disk
        try {
            ImageIO.write(img, "png", new File(filepath));
            System.out.println("Screenshot saved: " + filepath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


}