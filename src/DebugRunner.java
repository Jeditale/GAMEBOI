import IO.CARTRIDGE;
import IO.JOYPAD;
import cpu.CPU;
import manager.MMU;
import ppu.PPU;

import java.lang.reflect.Field;
import java.awt.event.KeyEvent;

public class DebugRunner {
    public static void main(String[] args) throws Exception {
        CPU cpu = new CPU();
        MMU mmu = new MMU();
        PPU ppu = new PPU();
        CARTRIDGE cartridge = new CARTRIDGE();
        JOYPAD joypad = new JOYPAD();

        cpu.fetchMMU(mmu);
        mmu.fetchCPU(cpu);
        mmu.fetchPPU(ppu);
        mmu.fetchCartridge(cartridge);
        mmu.fetchJoypad(joypad);
        ppu.fetchMMU(mmu);
        ppu.fetchCPU(cpu);
        joypad.fetchMMU(mmu);

        String romPath = "ROMS/hard to run/Legend of Zelda, The - Link's Awakening (USA, Europe) (Rev 2).gb";
        cartridge.loadROM(romPath);

        mmu.setBootRomEnabled(false);
        cpu.bypassBootROM();

        // Title screen
        runFrames(cpu, ppu, mmu, 5000);

        // Press Enter
        pressButton(joypad, KeyEvent.VK_ENTER);
        runFrames(cpu, ppu, mmu, 10);
        releaseButton(joypad, KeyEvent.VK_ENTER);
        runFrames(cpu, ppu, mmu, 120);

        // Press A (select slot)
        pressButton(joypad, KeyEvent.VK_Z);
        runFrames(cpu, ppu, mmu, 10);
        releaseButton(joypad, KeyEvent.VK_Z);
        runFrames(cpu, ppu, mmu, 120);

        // Press A (char 1)
        pressButton(joypad, KeyEvent.VK_Z);
        runFrames(cpu, ppu, mmu, 10);
        releaseButton(joypad, KeyEvent.VK_Z);
        runFrames(cpu, ppu, mmu, 60);

        // Press A (char 2)
        pressButton(joypad, KeyEvent.VK_Z);
        runFrames(cpu, ppu, mmu, 10);
        releaseButton(joypad, KeyEvent.VK_Z);
        runFrames(cpu, ppu, mmu, 60);

        // Press Enter (confirm)
        pressButton(joypad, KeyEvent.VK_ENTER);
        runFrames(cpu, ppu, mmu, 10);
        releaseButton(joypad, KeyEvent.VK_ENTER);
        runFrames(cpu, ppu, mmu, 120);

        // Press A (start game)
        pressButton(joypad, KeyEvent.VK_Z);
        runFrames(cpu, ppu, mmu, 10);
        releaseButton(joypad, KeyEvent.VK_Z);
        runFrames(cpu, ppu, mmu, 120);

        // Run gameplay
        for (int i = 0; i < 50000000; i++) {
            int cycles = cpu.step();
            ppu.cycle(cycles);
            mmu.timer_tick(cycles);
        }

        System.out.println("CPU and MMU status at end of run:");
        Field imeField = CPU.class.getDeclaredField("IME");
        imeField.setAccessible(true);
        Field lycField = PPU.class.getDeclaredField("lyc");
        lycField.setAccessible(true);

        System.out.format("IME:  %b\n", (boolean) imeField.get(cpu));
        System.out.format("IE:   0x%02X\n", mmu.interuptEnable);
        System.out.format("IF:   0x%02X\n", mmu.interruptFlag);
        System.out.format("LYC:  0x%02X\n", (int) lycField.get(ppu));
        System.out.format("LY:   %d\n", ppu.ly);
    }

    private static void runFrames(CPU cpu, PPU ppu, MMU mmu, int count) {
        for (int frame = 0; frame < count; frame++) {
            int cyclesThisFrame = 0;
            while (cyclesThisFrame < 70224) {
                int cycles = cpu.step();
                ppu.cycle(cycles);
                mmu.timer_tick(cycles);
                cyclesThisFrame += cycles;
            }
        }
    }

    private static void pressButton(JOYPAD joypad, int keyCode) {
        joypad.keyPressed(new KeyEvent(new java.awt.Canvas(), 0, 0, 0, keyCode, ' '));
    }

    private static void releaseButton(JOYPAD joypad, int keyCode) {
        joypad.keyReleased(new KeyEvent(new java.awt.Canvas(), 0, 0, 0, keyCode, ' '));
    }
}
