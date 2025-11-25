package manager;

import IO.CARTRIDGE;
import IO.JOYPAD;
import cpu.CPU;
import ppu.PPU;

public class MMU implements java.io.Serializable{
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

    // Flag to handle the 1-cycle delay on TIMA overflow
    private boolean timaOverflowed = false;

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
            // VRAM is only accessible when the PPU is not in Mode 3 (Drawing)
            if (ppu.isVramAccessible()) {
                ppu.writeVRAM(address, data);
            }
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
            if ((data & 1) != 0) {
//                System.out.println("!!! V-BLANK INTERRUPT REQUESTED !!!");
            }
        } else if (address >= 0xFF40 && address <= 0xFF4B) {
            ppu.writeRegister(address, data);
        } else if (address >= 0xFF80 && address <= 0xFFFE) {
            hram[address - 0xFF80] = data;
        }else if (address == 0xFF50) {
            // Disable Boot ROM
            if (data != 0) bootRomEnabled = false;
        }
        if (address < 0x8000) {
            // Writing to ROM area? This is impossible on Game Boy.
            // Usually means SP is 0 or uninitialized.
//            System.out.println(String.format("!!! CRITICAL: ILLEGAL WRITE TO ROM [0x%04X] !!! Data: 0x%02X", address, data));
        }
        else if (address == 0xFF02) { // Serial Control (SC)
            // If the game writes 0x81 (Start Transfer + Internal Clock)
            if ((data & 0x81) == 0x81) {

                // 1. Force "No Connection" (0xFF) in the Data Buffer
                // This is the signal Tetris needs to see to give up on 2-Player mode.
                write(0xFF01, 0xFF);

                // 2. Trigger the Serial Interrupt (Bit 3)
                // This tells the game the "transfer" (of nothing) is finished.
                int if_reg = read(0xFF0F);
                write(0xFF0F, if_reg | 0x08);

                // 3. Optional: Print log to confirm it happened
                // System.out.println("Serial Transfer Handled (Fake Disconnect)");
            }
        }
        else if (address == 0xFF01) {

            //E
        }
        else if (address == 0xFF46) { // DMA Transfer Trigger
            int source = data << 8;

            // --- DMA DEBUG TRAP START ---
//            System.out.println(String.format("!!! DMA TRIGGERED !!! Source: 0x%04X", source));

            // Check what is actually in WRAM at the source
            // Tetris normally uses 0xC000. Let's peek at the first sprite's Y coordinate.
            int firstByte = this.dmaRead(source);
//            System.out.println(String.format(" -> WRAM[Source] Value: 0x%02X (Should be Y-Coordinate)", firstByte));
            // --- DMA DEBUG TRAP END ---

            for (int i = 0; i < 160; i++) {
                int val = this.dmaRead(source + i);
                ppu.writeOAM(0xFE00 + i, val);
            }
            this.cpu.setDmaCycles(160);
        }
        else if (address >= 0xA000 && address <= 0xBFFF) {
            cartridge.write(address, data);
        }
        else if (address == 0xFFFF) {
//            System.out.println(String.format("!!! GAME ENABLED INTERRUPTS (IE) !!! Value: 0x%02X", data));
            interuptEnable = data;

            // --- NEW: CATCH THE CULPRIT ---
            if (data == 0x08) {
                System.out.println("!!! WARNING: V-BLANK DISABLED (IE=0x08) !!!");
                System.out.println("Trace:");
                // This will print exactly where in CPU.java the write came from
                new Exception().printStackTrace(System.out);
            }
        }
    }

    public int read(int address){
        address &= 0xFFFF;

        // 1. Boot ROM (Only if enabled and in range 0-255)
        if (bootRomEnabled && address < 0x0100) {
            return bootRom[address];
        }

        // 2. Cartridge ROM
        if (address <= 0x7FFF) {
            return cartridge.read(address);
        }
        // 3. VRAM
        if (address >= 0x8000 && address <= 0x9FFF) {
            // VRAM Lockout: Block access if PPU is in Mode 3 (Drawing)
            if (!ppu.isVramAccessible()) {
                return 0xFF; // CPU reads garbage during lockout
            }
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
            if (!ppu.isOamAccessible()) {
                return 0xFF; // CPU reads garbage during lockout
            }
            return ppu.readOAM(address);
        }
        // 7. I/O Registers
        if (address >= 0xFF00 && address <= 0xFF7F) {
            if (address == 0xFF00) return joypad.read();
            if (address == 0xFF04) return DIV;
            if (address == 0xFF05) return TIMA;
            if (address == 0xFF06) return TMA;
            if (address == 0xFF07) return TAC;
            if (address == 0xFF0F) return interruptFlag;
            if (address >= 0xFF40 && address <= 0xFF4B) {
                return ppu.readRegister(address);
            }
            // Other I/O registers can be added here
        }
        // 8. HRAM
        if (address >= 0xFF80 && address <= 0xFFFE) {
            return hram[address - 0xFF80];
        }

        // 9. Interrupt Enable Register
        if (address == 0xFFFF) return interuptEnable;
        if (address == 0xFF02) {
            // Return 0x7E:
            // Bit 7 (Transfer Flag) is 0 -> Transfer Complete / Not Busy
            // Bit 0 (Internal Clock) is 0
            return 0x7E;
        }
        else if (address == 0xFF01) {
            return 0xFF; // ALWAYS return 0xFF (No Link Cable Connected)
        }
        return 0xFF;
    }
    // In MMU.java

    /**
     * A special read method for the DMA transfer.
     * This bypasses the PPU memory access restrictions that the CPU is subject to,
     * as the DMA hardware has a direct bus connection.
     * It does NOT handle I/O registers or HRAM, as DMA can only source from
     * Cartridge ROM, WRAM, and Echo RAM.
     */
    private int dmaRead(int address) {
        address &= 0xFFFF;

        // 1. Cartridge ROM
        if (address <= 0x7FFF) {
            return cartridge.read(address);
        }
        // 2. WRAM
        if (address >= 0xC000 && address <= 0xDFFF) {
            return wram[address - 0xC000];
        }
        // 3. Echo RAM
        if (address >= 0xE000 && address <= 0xFDFF) {
            return wram[address - 0xE000];
        }
        return 0xFF; // Should not happen with valid DMA source addresses
    }

    public void timer_tick(int cycles) {
        // Handle the delayed TIMA overflow from the previous cycle
        if (timaOverflowed) {
            timaOverflowed = false;
            TIMA = TMA; // Reset TIMA to the modulo value
            int if_flag = read(0xFF0F);
            write(0xFF0F, if_flag | 0x04); // Request a Timer Interrupt
        }

        // =========================================================
        // 1. DIV Register (Divider Register) - ALWAYS RUNS
        // =========================================================
        // DIV increments every 256 cycles (T-cycles) regardless of TAC.
        div_counter += cycles;
        while (div_counter >= 256) {
            DIV = (DIV + 1) & 0xFF; // Increment DIV register
            div_counter -= 256;
        }

        // =========================================================
        // 2. TIMA Register (Timer Counter) - ONLY IF ENABLED
        // =========================================================

        // Check if the Timer is enabled (TAC bit 2)
        // We return HERE, only after DIV has been updated.
        if ((TAC & 0x04) == 0) return;

        tima_counter += cycles;

        // Determine the frequency divisor based on TAC bits 0 & 1
        int clockDivisor = 1024; // Default to avoiding divide by zero
        switch (TAC & 0x03) {
            case 0b00: clockDivisor = 1024; break; // 4.194304 MHz / 4096 = 1024 cycles
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
                TIMA = 0; // Set to 0 for one cycle
                timaOverflowed = true; // Schedule the real reset for the next cycle
            }
        }
    }
    public void hack_forceDMATransfer() {
        // Tetris stores its sprite data at 0xC000 in WRAM.
        // We will blindly copy 160 bytes from 0xC000 to OAM (0xFE00)
        // skipping the CPU's permission entirely.

        int source = 0xC000; // Standard Shadow OAM location for Tetris

        for (int i = 0; i < 160; i++) {
            int val = this.read(source + i); // Read from WRAM
            ppu.writeOAM(0xFE00 + i, val);   // Write to OAM
        }
    }

}