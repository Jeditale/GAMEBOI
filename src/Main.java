import IO.CARTRIDGE;
import IO.JOYPAD;
import cpu.CPU;
import display.DISPLAY;
import manager.MMU;
import ppu.PPU;

import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {

        CPU cpu = new CPU();
        MMU mmu = new MMU();
        PPU ppu = new PPU();
        mmu.fetchCPU(cpu);
        DISPLAY display = new DISPLAY();
        CARTRIDGE cartridge = new CARTRIDGE();
        JOYPAD joypad = new JOYPAD();
        // 1. Load the TEST ROM (Ensure this file is in your folder!)
        cartridge.loadROM("ROMS/dmg-acid2.gb");

        cpu.fetchMMU(mmu);
        mmu.fetchPPU(ppu);
        mmu.fetchCartridge(cartridge);
        ppu.fetchMMU(mmu);

        mmu.fetchJoypad(joypad);

        display.addKeyListener(joypad);

        // 2. Disable the internal Boot ROM logic so we read from the Cartridge immediately
        mmu.setBootRomEnabled(false);

        // 3. Skip the boot sequence and jump straight to the game
        cpu.bypassBootROM();



        System.out.println("Emulator started (Boot ROM Bypassed)...");

        while (true) {
            int cycles = cpu.step();

            for (int i = 0; i < cycles; i++) {
                ppu.cycle();
            }
            mmu.timer_tick(cycles);

            // --- DIAGNOSTIC CHECK: Run this once per frame (very fast) ---
            if (ppu.ly == 143) {
                // This block runs when the CPU is just about to enter V-Blank
                int ifFlag = mmu.read(0xFF0F);
                System.out.printf("DIAGNOSTIC: LY=143. IF Register (0xFF0F): 0x%02X\n", ifFlag);
            }
            if (ppu.ly > 153) {
                // This indicates the PPU should have wrapped around to 0
                System.out.println("DIAGNOSTIC: Error - LY exceeded 153!");
            }
            if (ppu.isFrameReady()) {
                display.render(ppu.getScreen());
                ppu.clearFrameReadyFlag();
                try {
                    Thread.sleep(16);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}