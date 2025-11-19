package manager;

import IO.CARTRIDGE;
import IO.JOYPAD;
import cpu.CPU;
import ppu.PPU;

public class MMU {
    private int[] wram = new int[8192];
    private int[] hram = new int[127];
    private PPU ppu;
    private CPU cpu;
    public int interruptFlag = 0x00;
    public int interuptEnable = 0x00;
    private CARTRIDGE cartridge;
    private JOYPAD joypad;


    public int DIV = 0x00;   // 0xFF04 - Divider Register
    public int TIMA = 0x00;  // 0xFF05 - Timer Counter
    public int TMA = 0x00;   // 0xFF06 - Timer Modulo
    public int TAC = 0x00;   // 0xFF07 - Timer Control

    private int div_counter = 0; // Internal counter for DIV (resets at 256)
    private int tima_counter = 0; // Internal counter for TIMA (based on TAC frequency)

    public void fetchJoypad(JOYPAD joypad) {
        this.joypad = joypad;
    }
    public void fetchCPU(CPU cpu) {
        this.cpu = cpu;
    }

    // MUST be true initially
    private boolean bootRomEnabled = true;

    // The Official DMG Boot ROM (256 bytes)
    private final int[] bootRom = new int[] {
            0x31, 0xFE, 0xFF, 0xAF, 0x21, 0xFF, 0x9F, 0x32, 0xCB, 0x7C, 0x20, 0xFB, 0x21, 0x26, 0xFF, 0x0E,
            0x11, 0x3E, 0x80, 0x32, 0xE2, 0x0C, 0x3E, 0xF3, 0xE2, 0x32, 0x3E, 0x77, 0x77, 0x3E, 0xFC, 0xE0,
            0x47, 0x11, 0x04, 0x01, 0x21, 0x10, 0x80, 0x1A, 0xCD, 0x95, 0x00, 0xCD, 0x96, 0x00, 0x13, 0x7B,
            0xFE, 0x34, 0x20, 0xF3, 0x11, 0xD8, 0x00, 0x06, 0x08, 0x1A, 0x13, 0x22, 0x23, 0x05, 0x20, 0xF9,
            0x3E, 0x19, 0xEA, 0x10, 0x99, 0x21, 0x2F, 0x99, 0x0E, 0x0C, 0x3D, 0x28, 0x08, 0x32, 0x0D, 0x20,
            0xF9, 0x2E, 0x0F, 0x18, 0xF3, 0x67, 0x3E, 0x64, 0x57, 0xE0, 0x42, 0x3E, 0x91, 0xE0, 0x40, 0x04,
            0x1E, 0x02, 0x0E, 0x0C, 0xF0, 0x44, 0xFE, 0x90, 0x20, 0xFA, 0x0D, 0x20, 0xF7, 0x1D, 0x20, 0xF2,
            0x0E, 0x13, 0x24, 0x7C, 0x1E, 0x83, 0xFE, 0x62, 0x28, 0x06, 0x1E, 0xC1, 0xFE, 0x64, 0x20, 0x06,
            0x7B, 0xE2, 0x0C, 0x3E, 0x87, 0xE2, 0xF0, 0x42, 0x90, 0xE0, 0x42, 0x15, 0x20, 0xD2, 0x05, 0x20,
            0x4F, 0x16, 0x20, 0x18, 0xCB, 0x4F, 0x06, 0x04, 0xC5, 0xCB, 0x11, 0x17, 0xC1, 0xCB, 0x11, 0x17,
            0x05, 0x20, 0xF5, 0x22, 0x23, 0x22, 0x23, 0xC9, 0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B,
            0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D, 0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E,
            0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99, 0xBB, 0xBB, 0x67, 0x63, 0x6E, 0x0E, 0xEC, 0xCC,
            0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E, 0x3C, 0x42, 0xB9, 0xA5, 0xB9, 0xA5, 0x42, 0x3C,
            0x21, 0x04, 0x01, 0x11, 0xA8, 0x00, 0x1A, 0x13, 0xBE, 0x20, 0xFE, 0x23, 0x7D, 0xFE, 0x34, 0x20,
            0xF5, 0x06, 0x19, 0x78, 0x86, 0x23, 0x05, 0x20, 0xFB, 0x86, 0x20, 0xFE, 0x3E, 0x01, 0xE0, 0x50
    };

    public void fetchCartridge(CARTRIDGE cartridge){ this.cartridge = cartridge; }
    public void fetchPPU(PPU ppu){ this.ppu = ppu; }
    public void setBootRomEnabled(boolean enabled) { this.bootRomEnabled = enabled; }

    public void write(int address, int data){
        address &= 0xFFFF;
        if (address == 0xFF00) {
            joypad.write(data);
            return; // Return to avoid writing to other registers accidentally
        }
//        else if (address == 0xFF04) {
////break stall
//            DIV = 0;
//            div_counter = 0;
//
//            return; // Stop further processing of the write.
//        }

        if (address < 0x8000) {
            cartridge.write(address, data);
        } else if (address >= 0x8000 && address <= 0x9FFF) {
            ppu.writeVRAM(address, data);
        } else if (address >= 0xC000 && address <= 0xDFFF) {
            wram[address - 0xC000] = data;
        } else if (address >= 0xE000 && address <= 0xFDFF) {
            wram[address - 0xE000] = data;
        } else if (address == 0xFF04) {
            DIV = 0;
            div_counter = 0; // Reset internal counter
        } else if (address == 0xFF05) {
            TIMA = data;
        } else if (address == 0xFF06) {
            TMA = data;
        } else if (address == 0xFF07) {
            TAC = data;
        } else if (address >= 0xFE00 && address <= 0xFE9F) {
            if (ppu.isOamAccessible()) {
                ppu.writeOAM(address, data);
            }
        } else if (address == 0xFF0F) {
            interruptFlag = data;
        } else if (address >= 0xFF40 && address <= 0xFF4B) {
            ppu.writeRegister(address, data);
        } else if (address >= 0xFF80 && address <= 0xFFFE) {
            hram[address - 0xFF80] = data;
        }else if (address == 0xFF50) {
            // Disable Boot ROM
            if (data != 0) bootRomEnabled = false;
        } else if (address == 0xFF46) { // DMA Transfer Trigger
            int source = data << 8;
            // The CPU stall handles the timing, but the data copy is "instant"
            for (int i = 0; i < 160; i++) {
                int val = this.read(source + i);
                ppu.writeOAM(0xFE00 + i, val);
            }
            this.cpu.setDmaCycles(160); // Stall the CPU for 160 M-cycles
        }
        else if (address >= 0xA000 && address <= 0xBFFF) {
            cartridge.write(address, data);
        }
        else if (address == 0xFFFF) {
            interuptEnable = data;
        }
    }

    public int read(int address){
        address &= 0xFFFF;

        // During a DMA transfer, only HRAM is accessible
        if (cpu.isDmaActive() && (address < 0xFF80 || address > 0xFFFE)) {
            return 0xFF; // Return garbage
        }

        if (address == 0xFF00) {
            return joypad.read();
        }
        if (address >= 0xA000 && address <= 0xBFFF) {
            return cartridge.read(address);
        }

        // 1. Boot ROM (Only if enabled and in range 0-255)
        if (bootRomEnabled && address < 0x0100) {
            return bootRom[address];
        }

        // 2. Cartridge ROM
        if (address < 0x8000) {
            return cartridge.read(address);
        }
        // 3. VRAM
        if (address >= 0x8000 && address <= 0x9FFF) {
            return ppu.readVRAM(address);
        }
        // 4. WRAM
        if (address >= 0xC000 && address <= 0xDFFF) {
            return wram[address - 0xC000];
        }
        // 5. Echo RAM
        if (address >= 0xE000 && address <= 0xFDFF) {
            return wram[address - 0xE000];
        }
        // 6. OAM
        if (address >= 0xFE00 && address <= 0xFE9F) {
            if (ppu.isOamAccessible()) {
                return ppu.readOAM(address);
            }
            return 0xFF; // OAM is inaccessible during modes 2 and 3
        }
        // 7. I/O
        if (address >= 0xFF40 && address <= 0xFF4B) {
            return ppu.readRegister(address);
        }

        if (address == 0xFF0F) return interruptFlag;
        if (address == 0xFFFF) return interuptEnable;
        if (address >= 0xFF80 && address <= 0xFFFE) {
            return hram[address - 0xFF80];
        }
        else if (address == 0xFF04) {
            return DIV;
        } else if (address == 0xFF05) {
            return TIMA;
        } else if (address == 0xFF06) {
            return TMA;
        } else if (address == 0xFF07) {
            return TAC;
        }

        return 0xFF;
    }
    // In MMU.java

    public void timer_tick(int cycles) {
        // 1. DIV Register (Divider Register)
        // DIV increments every 256 cycles (T-cycles)
        div_counter += cycles;

        // We only update DIV once every 256 cycles, but we need to check if we crossed that threshold multiple times
        while (div_counter >= 256) {
            DIV = (DIV + 1) & 0xFF; // Increment DIV register
            div_counter -= 256;
        }

        // Check if the Timer is enabled (TAC bit 2)
        if ((TAC & 0x04) == 0) return;

        // 2. TIMA Register
        tima_counter += cycles;

        // Determine the frequency divisor based on TAC bits 0 & 1
        int clockDivisor = 0;
        switch (TAC & 0x03) {
            case 0b00: clockDivisor = 1024; break; // 4096 Hz
            case 0b01: clockDivisor = 16; break;   // 262144 Hz
            case 0b10: clockDivisor = 64; break;   // 65536 Hz
            case 0b11: clockDivisor = 256; break;  // 16384 Hz
        }

        // Now, we check if the TIMA counter crossed its threshold multiple times
        while (tima_counter >= clockDivisor) {
            tima_counter -= clockDivisor;
            TIMA++;

            if (TIMA > 0xFF) {
                // TIMA has overflowed!
                TIMA = TMA; // Reset TIMA to the modulo value

                // Request a Timer Interrupt (Bit 2 of 0xFF0F)
                int if_flag = read(0xFF0F);
                write(0xFF0F, if_flag | 0x04);
            }
        }
    }

}