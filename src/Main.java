import IO.CARTRIDGE;
import IO.JOYPAD;
import cpu.CPU;
import display.DISPLAY;
import manager.MMU;
import ppu.GPUDebugger;
import ppu.PPU;

import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {

        CPU cpu = new CPU();
        MMU mmu = new MMU();
        PPU ppu = new PPU();
        GPUDebugger debugger = new GPUDebugger(ppu);
        mmu.fetchCPU(cpu);
        DISPLAY display = new DISPLAY();
        CARTRIDGE cartridge = new CARTRIDGE();
        JOYPAD joypad = new JOYPAD();
        // 1. Load the TEST ROM (Ensure this file is in your folder!)
        cartridge.loadROM("");

        cpu.fetchMMU(mmu);
        mmu.fetchPPU(ppu);
        mmu.fetchCartridge(cartridge);
        ppu.fetchMMU(mmu);

        joypad.fetchMMU(mmu);
        mmu.fetchJoypad(joypad);

//        display.addKeyListener(joypad);
        display.addInputListener(joypad);
        // 2. Disable the internal Boot ROM logic so we read from the Cartridge immediately
        mmu.setBootRomEnabled(false);

        // 3. Skip the boot sequence and jump straight to the game
        cpu.bypassBootROM();



        System.out.println("Emulator started (Boot ROM Bypassed)...");

        // The Game Boy CPU runs at 4.194304 MHz.
        // A full frame (154 scanlines * 456 cycles/scanline) takes 70224 cycles.
        final int CYCLES_PER_FRAME = 70224;

        while (true) {
            int cyclesThisFrame = 0;

            while (cyclesThisFrame < CYCLES_PER_FRAME) {
                int cycles = cpu.step();
                ppu.cycle(cycles);
                mmu.timer_tick(cycles);
                cyclesThisFrame += cycles;
            }

            // This loop ensures we've completed a full frame's worth of work.
            // Now we can render it.
            display.render(ppu.getScreen());
            try {
                Thread.sleep(16); // Sleep to target ~60 FPS
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}