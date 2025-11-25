import IO.CARTRIDGE;
import IO.JOYPAD;
import cpu.CPU;
import display.DISPLAY;
import manager.MMU;
import ppu.GPUDebugger;
import ppu.PPU;

import java.io.*;

public class Main {
    // 1. GLOBAL COMPONENTS (Must be static so LoadState can swap them)
    public static CPU cpu;
    public static MMU mmu;
    public static PPU ppu;
    public static CARTRIDGE cartridge;
    public static JOYPAD joypad;

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

        // GUI Setup
        // Note: We pass the ROM path to the save/load functions
        String romPath = "ROMS/Super Mario Land (World).gb";

        DISPLAY display = new DISPLAY(
                e -> saveState(romPath), // Action for Save
                e -> loadState(romPath)  // Action for Load
        );

        // Load ROM
        cartridge.loadROM(romPath);

        // Input Setup
        display.addInputListener(joypad);

        // Debugger (Optional)
        // GPUDebugger debugger = new GPUDebugger(ppu);

        // Boot Bypass
        mmu.setBootRomEnabled(false);
        cpu.bypassBootROM();

        System.out.println("Emulator started (Boot ROM Bypassed)...");

        final int CYCLES_PER_FRAME = 70224;

        while (true) {
            // If we are loading a state, we skip execution
            if (isRunning) {
                int cyclesThisFrame = 0;

                while (cyclesThisFrame < CYCLES_PER_FRAME) {
                    int cycles = cpu.step();
                    ppu.cycle(cycles);
                    mmu.timer_tick(cycles);
                    cyclesThisFrame += cycles;
                }
                display.render(ppu.getScreen());
            }

            try { Thread.sleep(16); } catch (InterruptedException e) {}
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

    public static void saveState(String romPath) {
        // Freeze engine to ensure data safety
        boolean wasRunning = isRunning;
        isRunning = false;

        String saveFile = getSavePath(romPath, ".state");

        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(saveFile))) {
            out.writeObject(cpu);
            out.writeObject(mmu);
            out.writeObject(ppu);
            out.writeObject(cartridge); // Save Cartridge (RAM Banks) too!
            System.out.println("State Saved to: " + saveFile);
        } catch (IOException e) {
            System.err.println("Save Failed!");
            e.printStackTrace();
        }

        // Resume
        isRunning = wasRunning;
    }

    // --- LOAD STATE ---
    public static void loadState(String romPath) {
        String filename = getSavePath(romPath, ".state");
        File f = new File(filename);

        if (!f.exists()) {
            System.out.println("No save state found: " + filename);
            return;
        }

        isRunning = false;

        try { Thread.sleep(50); } catch (Exception e) {}

        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
            System.out.println("Loading State...");

            cpu = (CPU) in.readObject();
            mmu = (MMU) in.readObject();
            ppu = (PPU) in.readObject();
            cartridge = (CARTRIDGE) in.readObject();

            linkComponents(cpu, mmu, ppu, cartridge, joypad);

            System.out.println("State Loaded Successfully!");

        } catch (Exception e) {
            e.printStackTrace();
        }

        // 4. RESUME THE ENGINE
        isRunning = true;
    }

    public static String getSavePath(String romPath, String extension) {
        File saveDir = new File("Saves");
        if (!saveDir.exists()) {
            saveDir.mkdir();
        }

        File romFile = new File(romPath);
        String filename = romFile.getName();

        if (filename.endsWith(".gb")) {
            filename = filename.substring(0, filename.length() - 3);
        }

        return "Saves" + File.separator + filename + extension;
    }
}