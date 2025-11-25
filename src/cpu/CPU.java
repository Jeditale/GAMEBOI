package cpu;

import manager.MMU;

public class CPU {
    // Registers
    private int a, f, b, c, d, e, h, l;
    private int sp, pc;

    // System
    private MMU mmu;
    private boolean IME = false;
    private int cycles = 0;
    private boolean isHalted = false;
    private int dmaCycles = 0;

    // Helper to combine registers
    private int getAF() { return (a << 8) | f; }
    private int getBC() { return (b << 8) | c; }
    private int getDE() { return (d << 8) | e; }
    private int getHL() { return (h << 8) | l; }

    private void setBC(int val) { b = (val >> 8) & 0xFF; c = val & 0xFF; }
    private void setDE(int val) { d = (val >> 8) & 0xFF; e = val & 0xFF; }
    private void setHL(int val) { h = (val >> 8) & 0xFF; l = val & 0xFF; }

    private boolean imeScheduled = false;

    public void fetchMMU(MMU mmu) { this.mmu = mmu; }
    public void setDmaCycles(int cycles) { // NEW: Setter method
        this.dmaCycles = cycles;
    }
    public boolean isDmaActive() {
        return this.dmaCycles > 0;
    }

    public boolean isHalted() {
        return this.isHalted;
    }

    public void reset() {
        a = 0x01; f = 0xB0;
        b = 0x00; c = 0x13;
        d = 0x00; e = 0xD8;
        h = 0x01; l = 0x4D;
        sp = 0xFFFE;
        pc = 0x0000; // Start at boot ROM
        cycles = 0;
        IME = false;
    }
    public void bypassBootROM() {
        // Registers (State after DMG boot rom)
        a = 0x01;
        f = 0xB0;
        b = 0x00;
        c = 0x13;
        d = 0x00;
        e = 0xD8;
        h = 0x01;
        l = 0x4D;
        sp = 0xFFFE;
        pc = 0x0100; // Jump directly to the Game Cartridge Entry Point

        cycles = 0;
        IME = false;

        System.out.println("CPU Reset Complete. PC=" + Integer.toHexString(pc) + " IME=" + IME);

        // We also need to set some hardware registers that the Boot ROM normally sets
        mmu.write(0xFF10, 0x80); // Audio
        mmu.write(0xFF11, 0xBF);
        mmu.write(0xFF12, 0xF3);
        mmu.write(0xFF14, 0xBF);
        mmu.write(0xFF16, 0x3F);
        mmu.write(0xFF17, 0x00);
        mmu.write(0xFF19, 0xBF);
        mmu.write(0xFF1A, 0x7F);
        mmu.write(0xFF1B, 0xFF);
        mmu.write(0xFF1C, 0x9F);
        mmu.write(0xFF1E, 0xBF);
        mmu.write(0xFF20, 0xFF);
        mmu.write(0xFF21, 0x00);
        mmu.write(0xFF22, 0x00);
        mmu.write(0xFF23, 0xBF);
        mmu.write(0xFF24, 0x77);
        mmu.write(0xFF25, 0xF3);
        mmu.write(0xFF26, 0xF1); // Audio

        mmu.write(0xFF40, 0x91); // LCDC: LCD On, BG On
        mmu.write(0xFF42, 0x00); // SCY
        mmu.write(0xFF43, 0x00); // SCX
        mmu.write(0xFF45, 0x00); // LYC
        mmu.write(0xFF47, 0x00); // BGP - The official boot ROM leaves this as 0x00.
        mmu.write(0xFF48, 0xFF); // OBP0 - Official post-boot ROM value
        mmu.write(0xFF49, 0xFF); // OBP1
        mmu.write(0xFF4A, 0x00); // WY
        mmu.write(0xFF4B, 0x00); // WX
        mmu.write(0xFFFF, 0x00); // Interrupt Enable Register (IE) is 0x00 after boot
    }

    // --- MAIN STEP FUNCTION ---
    public int step() {
        cycles = 0;

        // 1. Handle DMA Stall (unchanged)
        if (dmaCycles > 0) {
            dmaCycles--;
            return 4;
        }
        if (pc == 0x015A || pc == 0x0158) {
            System.out.println("Stuck in Loop? PC=" + Integer.toHexString(pc) + " LY=" + mmu.read(0xFF44));
        }

        // 2. Handle Interrupts (Check this BEFORE executing)
        handleInterrupts();

        // 3. Handle HALT state
        if (isHalted) {
            // If halted, we don't fetch opcodes, just burn cycles.
            // handleInterrupts() above handles the "Wake Up" logic.
            return 4;
        }

        // 4. Update IME (The 1-instruction delay for EI)
        if (imeScheduled) {
            IME = true;
            imeScheduled = false;
        }

        // 5. Normal Execution
        int opcode = mmu.read(pc);
        execute(opcode);

        return cycles * 4;
    }

    private void execute(int opcode) {
        switch (opcode) {
            case 0x00: { pc++; cycles = 1; break; } // NOP

            // --- 8-Bit Loads ---
            case 0x06: { b = mmu.read(pc + 1); pc += 2; cycles = 2; break; } // LD B, d8
            case 0x0E: { c = mmu.read(pc + 1); pc += 2; cycles = 2; break; } // LD C, d8
            case 0x16: { d = mmu.read(pc + 1); pc += 2; cycles = 2; break; } // LD D, d8
            case 0x1E: { e = mmu.read(pc + 1); pc += 2; cycles = 2; break; } // LD E, d8
            case 0x26: { h = mmu.read(pc + 1); pc += 2; cycles = 2; break; } // LD H, d8
            case 0x2E: { l = mmu.read(pc + 1); pc += 2; cycles = 2; break; } // LD L, d8
            case 0x3E: { a = mmu.read(pc + 1); pc += 2; cycles = 2; break; } // LD A, d8

            case 0x78: { a = b; pc++; cycles = 1; break; } // LD A, B
            case 0x79: { a = c; pc++; cycles = 1; break; } // LD A, C
            case 0x7A: { a = d; pc++; cycles = 1; break; } // LD A, D
            case 0x7B: { a = e; pc++; cycles = 1; break; } // LD A, E
            case 0x7C: { a = h; pc++; cycles = 1; break; } // LD A, H
            case 0x7D: { a = l; pc++; cycles = 1; break; } // LD A, L
            case 0x7F: { a = a; pc++; cycles = 1; break; } // LD A, A
            case 0x4F: { c = a; pc++; cycles = 1; break; } // LD C, A (Added this)
            case 0x77: { mmu.write(getHL(), a); pc++; cycles = 2; break; } // LD (HL), A
            case 0xE2: { mmu.write(0xFF00 + c, a); pc++; cycles = 2; break; } // LD (C), A
            case 0xF0: { a = mmu.read(0xFF00 + mmu.read(pc + 1)); pc += 2; cycles = 3; break; } // LDH A, (a8)
            case 0xE0: { mmu.write(0xFF00 + mmu.read(pc + 1), a); pc += 2; cycles = 3; break; } // LDH (a8), A
            case 0x1A: { a = mmu.read(getDE()); pc++; cycles = 2; break; } // LD A, (DE)
            case 0x0A: { a = mmu.read(getBC()); pc++; cycles = 2; break; } // LD A, (BC)
            case 0xEA: { // LD (a16), A
                int addr = (mmu.read(pc+2) << 8) | mmu.read(pc+1);
                mmu.write(addr, a);
                pc+=3; cycles = 4;
                break;
            }

            // --- 16-Bit Loads ---
            case 0x01: { c = mmu.read(pc + 1); b = mmu.read(pc + 2); pc += 3; cycles = 3; break; } // LD BC, d16
            case 0x11: { e = mmu.read(pc + 1); d = mmu.read(pc + 2); pc += 3; cycles = 3; break; } // LD DE, d16
            case 0x21: { l = mmu.read(pc + 1); h = mmu.read(pc + 2); pc += 3; cycles = 3; break; } // LD HL, d16
            case 0x31: { sp = (mmu.read(pc + 2) << 8) | mmu.read(pc + 1); pc += 3; cycles = 3; break; } // LD SP, d16
            case 0x22: { mmu.write(getHL(), a); setHL((getHL() + 1) & 0xFFFF); pc++; cycles = 2; break; } // LD (HL+), A
            case 0x32: { mmu.write(getHL(), a); setHL((getHL() - 1) & 0xFFFF); pc++; cycles = 2; break; } // LD (HL-), A
            case 0x02: { mmu.write(getBC(), a); pc++; cycles = 2; break; } // LD (BC), A
            case 0x12: { mmu.write(getDE(), a); pc++; cycles = 2; break; } // LD (DE), A
            case 0xC1: { // POP BC
                c = mmu.read(sp);
                b = mmu.read(sp + 1);
                sp += 2;
                pc++; cycles = 3; break;
            }
            case 0xC5: { mmu.write(--sp, b); mmu.write(--sp, c); pc++; cycles = 4; break; } // PUSH BC - Correct order is high (B), then low (C)
            case 0xD1: { // POP DE
                e = mmu.read(sp);
                d = mmu.read(sp + 1);
                sp += 2;
                pc++; cycles = 3; break;
            }
            case 0xD5: { mmu.write(--sp, d); mmu.write(--sp, e); pc++; cycles = 4; break; } // PUSH DE - Correct order is high (D), then low (E)
            case 0xE1: { // POP HL
                l = mmu.read(sp);
                h = mmu.read(sp + 1);
                sp += 2;
                pc++; cycles = 3; break;
            }
            case 0xE5: { mmu.write(--sp, h); mmu.write(--sp, l); pc++; cycles = 4; break; } // PUSH HL - Correct order is high (H), then low (L)

            // --- Arithmetic ---
            case 0x80: { add(b); pc++; cycles = 1; break; }
            case 0x81: { add(c); pc++; cycles = 1; break; }
            case 0x83: { add(e); pc++; cycles = 1; break; } // ADD A, E
            case 0x84: { add(h); pc++; cycles = 1; break; } // ADD A, H
            case 0x85: { // ADD A, L
                add(l);
                pc++; cycles = 1; break;
            }
            case 0x87: { add(a); pc++; cycles = 1; break; }
            case 0x90: { sub(b); pc++; cycles = 1; break; }
            case 0x88: { adc(b); pc++; cycles = 1; break; } // ADC A, B
            case 0x8A: { adc(d); pc++; cycles = 1; break; } // ADC A, D
            case 0x8B: { adc(e); pc++; cycles = 1; break; } // ADC A, E
            case 0x8C: { adc(h); pc++; cycles = 1; break; } // ADC A, H
            case 0x8F: { adc(a); pc++; cycles = 1; break; } // ADC A, A

            case 0x9A: { sbc(d); pc++; cycles = 1; break; } // SBC A, D
            case 0x9B: { sbc(e); pc++; cycles = 1; break; } // SBC A, E
            case 0x9C: { sbc(h); pc++; cycles = 1; break; } // SBC A, H
            case 0x9D: { sbc(l); pc++; cycles = 1; break; } // SBC A, L
            case 0x9E: { sbc(mmu.read(getHL())); pc++; cycles = 2; break; } // SBC A, (HL)

            case 0x89: { // ADC A, C

                int val = c;
                int carry = (f & 0x10) != 0 ? 1 : 0;
                int result = a + val + carry;
                f = ((result & 0xFF) == 0 ? 0x80 : 0) | (result > 0xFF ? 0x10 : 0) | (((a & 0x0F) + (val & 0x0F) + carry) > 0x0F ? 0x20 : 0);
                a = result & 0xFF;
                pc++; cycles = 1; break;
            }
            case 0x91: { sub(c); pc++; cycles = 1; break; } // SUB C
            case 0x93: { sub(e); pc++; cycles = 1; break; } // SUB E
            case 0x94: { sub(h); pc++; cycles = 1; break; } // SUB H
            case 0x95: { sub(l); pc++; cycles = 1; break; } // SUB L
            case 0x97: { sub(a); pc++; cycles = 1; break; } // SUB A

            case 0xA0: { a &= b; f = ((a == 0) ? 0x80 : 0) | 0x20; pc++; cycles = 1; break; } // AND B
            case 0xA2: { a &= d; f = ((a == 0) ? 0x80 : 0) | 0x20; pc++; cycles = 1; break; } // AND D
            case 0xA3: { a &= e; f = ((a == 0) ? 0x80 : 0) | 0x20; pc++; cycles = 1; break; } // AND E
            case 0xA4: { a &= h; f = ((a == 0) ? 0x80 : 0) | 0x20; pc++; cycles = 1; break; } // AND H
            case 0xA5: { a &= l; f = ((a == 0) ? 0x80 : 0) | 0x20; pc++; cycles = 1; break; } // AND L

            case 0xA6: { // AND (HL)
                a &= mmu.read(getHL());
                f = (a == 0 ? 0x80 : 0) | 0x20; // Z set if zero. N=0, H=1, C=0.
                pc++; cycles = 2; break;
            }

            case 0xAA: { a ^= d; f = (a == 0) ? 0x80 : 0; pc++; cycles = 1; break; } // XOR D
            case 0xAB: { a ^= e; f = (a == 0) ? 0x80 : 0; pc++; cycles = 1; break; } // XOR E
            case 0xAC: { a ^= h; f = (a == 0) ? 0x80 : 0; pc++; cycles = 1; break; } // XOR H

            case 0xA8: { a ^= b; f = (a == 0) ? 0x80 : 0x00; pc++; cycles = 1; break; } // XOR B
            case 0xAF: { a = 0; f = 0x80; pc++; cycles = 1; break; } // XOR A
            case 0xB0: { a |= b; f = (a == 0) ? 0x80 : 0x00; pc++; cycles = 1; break; } // OR B
            case 0xB8: { cp(b); pc++; cycles = 1; break; } // CP B
            case 0xFE: { cp(mmu.read(pc + 1)); pc += 2; cycles = 2; break; } // CP d8
            case 0xB9: { // CP C
                cp(c);
                pc++; cycles = 1; break;
            }
            case 0xBA: { // CP D
                cp(d);
                pc++; cycles = 1; break;
            }

            case 0xBB: { // CP E
                cp(e);
                pc++; cycles = 1; break;
            }
            case 0xBD: { // CP L
                cp(l);
                pc++; cycles = 1; break;
            }

            case 0xBF: { // CP A
                cp(a);
                pc++; cycles = 1; break;
            }

            case 0xAD: { // XOR L
                a ^= l;
                f = (a == 0 ? 0x80 : 0); // Z set if zero. N=0, H=0, C=0.
                pc++; cycles = 1; break;
            }
            case 0x3B: { // DEC SP
                sp = (sp - 1) & 0xFFFF;
                pc++; cycles = 2; break;
            }
            case 0x03: { setBC((getBC() + 1) & 0xFFFF); pc++; cycles = 2; break; } // INC BC
            case 0x1B: { // DEC DE
                setDE((getDE() - 1) & 0xFFFF);
                pc++; cycles = 2; break;
            }
            case 0x13: { setDE((getDE() + 1) & 0xFFFF); pc++; cycles = 2; break; } // INC DE
            case 0x23: { setHL((getHL() + 1) & 0xFFFF); pc++; cycles = 2; break; } // INC HL
            case 0x24: { // INC H
                h = (h + 1) & 0xFF;
                f = (f & 0x10) | ((h == 0) ? 0x80 : 0) | (((h & 0x0F) == 0) ? 0x20 : 0);
                pc++; cycles = 1; break;
            }
            case 0x0C: { // INC C
                c = (c + 1) & 0xFF;
                f = (f & 0x10) | ((c == 0) ? 0x80 : 0) | (((c & 0x0F) == 0) ? 0x20 : 0);
                pc++; cycles = 1; break;
            }
            case 0x05: { // DEC B
                b = (b - 1) & 0xFF;
                f = (f & 0x10) | 0x40 | ((b == 0) ? 0x80 : 0) | (((b & 0x0F) == 0x0F) ? 0x20 : 0);
                pc++; cycles = 1; break;
            }
            case 0x0D: { // DEC C
                c = (c - 1) & 0xFF;
                f = (f & 0x10) | 0x40 | ((c == 0) ? 0x80 : 0) | (((c & 0x0F) == 0x0F) ? 0x20 : 0);
                pc++; cycles = 1; break;
            }
            case 0x3D: { // DEC A
                a = (a - 1) & 0xFF;
                f = (f & 0x10) | 0x40 | ((a == 0) ? 0x80 : 0) | (((a & 0x0F) == 0x0F) ? 0x20 : 0);
                pc++; cycles = 1; break;
            }
            case 0x17: { // RLA
                int oldC = (f & 0x10) >> 4;
                int newC = (a & 0x80) >> 7;
                a = ((a << 1) | oldC) & 0xFF;
                f = (newC << 4);
                pc++; cycles = 1; break;
            }

            // --- Control Flow ---
            case 0xC3: { int addr = (mmu.read(pc+2)<<8)|mmu.read(pc+1); pc = addr; cycles = 4; break; } // JP a16
            case 0xCD: { // CALL a16
                int addr = (mmu.read(pc+2)<<8)|mmu.read(pc+1);
                int ret = pc + 3;
                mmu.write(--sp, (ret >> 8) & 0xFF);
                mmu.write(--sp, ret & 0xFF);
                pc = addr; cycles = 6; break;
            }
            case 0xC6: { // ADD A, d8
                int n = mmu.read(pc + 1);
                add(n); // Reuse your existing 8-bit add helper
                pc += 2;
                cycles = 2;
                break;
            }
            case 0xC9: { // RET
                int lo = mmu.read(sp++); int hi = mmu.read(sp++);
                pc = (hi << 8) | lo; cycles = 4; break;
            }
            case 0x18: { // JR r8
                int n = mmu.read(pc + 1); if (n > 127) n -= 256;
                pc += 2 + n; cycles = 3; break;
            }
            case 0x20: { // JR NZ, r8
                int n = mmu.read(pc + 1); if (n > 127) n -= 256;
                if ((f & 0x80) == 0) { pc += 2 + n; cycles = 3; }
                else { pc += 2; cycles = 2; }
                break;
            }

            case 0x28: { // JR Z, r8
                int n = mmu.read(pc + 1); if (n > 127) n -= 256;
                if ((f & 0x80) != 0) { pc += 2 + n; cycles = 3; }
                else { pc += 2; cycles = 2; }
                break;
            }
            case 0xC2: { // JP NZ, a16
                int addr = (mmu.read(pc + 2) << 8) | mmu.read(pc + 1);
                if ((f & 0x80) == 0) { // If Z is NOT set
                    pc = addr;
                    cycles = 4; // Taken
                } else {
                    pc += 3;
                    cycles = 3; // Not taken
                }
                break;
            }
            case 0xCA: { // JP Z, a16
                int addr = (mmu.read(pc + 2) << 8) | mmu.read(pc + 1);
                if ((f & 0x80) != 0) { // If Z is set
                    pc = addr;
                    cycles = 4; // Taken
                } else {
                    pc += 3;
                    cycles = 3; // Not taken
                }
                break;
            }

            // --- Misc ---
            case 0xCB: {
                pc++; // Prefix
                int cb = mmu.read(pc);
                pc++; // Opcode
                // Cycles are now handled inside executeCB
                executeCB(cb);
                break;
            }
            case 0xF3: { IME = false; imeScheduled = false; pc++; cycles = 1; break; } // DI - Immediate Disable
            case 0xFB: {
                System.out.println("!!! EI EXECUTED - MASTER INTERRUPTS ON !!!");
                imeScheduled = true;
                pc++;
                cycles = 1;
                break; } // EI - Delayed Enable
            case 0x76: { // HALT
                isHalted = true;
                pc++;
                cycles = 1; // 4 T-cycles
                break;
            }
            case 0x3C: { // INC A
                a = (a + 1) & 0xFF;
                f = (f & 0x10) | ((a == 0) ? 0x80 : 0) | (((a & 0x0F) == 0) ? 0x20 : 0);
                pc++; cycles = 1; break;
            }
            case 0xB3: { // OR E
                a |= e;
                f = (a == 0 ? 0x80 : 0); // Z set if 0, N=0, H=0, C=0
                pc++; cycles = 1; break;
            }
            case 0xB5: { // OR L
                a |= l;
                f = (a == 0 ? 0x80 : 0); // Z set if 0, N=0, H=0, C=0
                pc++; cycles = 1; break;
            }
            case 0xB1: { // OR C
                a |= c;
                f = (a == 0 ? 0x80 : 0); // Z set if 0, N=0, H=0, C=0
                pc++; cycles = 1; break;
            }

            // --- 16-bit Loads/Arithmetic ---
            case 0x0B: { // DEC BC
                setBC((getBC() - 1) & 0xFFFF);
                pc++; cycles = 2; break;
            }
            case 0x2A: { // LD A, (HL+)
                a = mmu.read(getHL());
                setHL((getHL() + 1) & 0xFFFF);
                pc++; cycles = 2; break;
            }
            case 0xBC: { // CP H
                cp(h);
                pc++; cycles = 1; break;
            }
            case 0x57: { // LD D, A
                d = a;
                pc++; cycles = 1; break;
            }
            case 0xF2: { // LDH A, (C) (Read from I/O Port FF00+C)
                a = mmu.read(0xFF00 + c);
                pc++; cycles = 2; break;
            }
            case 0x3F: { // CCF
                f &= 0x90; // Clear N and H flags
                f ^= 0x10; // Flip C flag
                pc++; cycles = 1; break;
            }

            case 0xCE: { // ADC A, d8 (Add with Carry, immediate)
                int val = mmu.read(pc + 1);
                int carry = (f & 0x10) != 0 ? 1 : 0;
                int result = a + val + carry;

                // Flags: Z, N=0, H, C
                f = 0;
                if ((result & 0xFF) == 0) f |= 0x80;
                if (result > 0xFF) f |= 0x10;
                if (((a & 0x0F) + (val & 0x0F) + carry) > 0x0F) f |= 0x20;

                a = result & 0xFF;
                pc += 2; cycles = 2; break;
            }
            case 0x82: { // ADD A, D
                add(d);
                pc++; cycles = 1; break;
            }
            case 0x25: { // DEC H
                h = (h - 1) & 0xFF;
                // Flags: Z, N=1, H. C preserved.
                f = (f & 0x10) | 0x40 | ((h == 0) ? 0x80 : 0) | (((h & 0x0F) == 0x0F) ? 0x20 : 0);
                pc++; cycles = 1; break;
            }

            case 0x8D: { // ADC A, L
                int val = l;
                int carry = (f & 0x10) != 0 ? 1 : 0;
                int result = a + val + carry;

                // Flags: Z, N=0, H, C
                f = 0;
                if ((result & 0xFF) == 0) f |= 0x80;
                if (result > 0xFF) f |= 0x10;
                if (((a & 0x0F) + (val & 0x0F) + carry) > 0x0F) f |= 0x20;

                a = result & 0xFF;
                pc++; cycles = 1; break;
            }

            case 0xDE: { // SBC A, d8 (Subtract with Carry, immediate)
                int val = mmu.read(pc + 1);
                int carry = (f & 0x10) != 0 ? 1 : 0;
                int result = a - val - carry;

                // Flags: Z, N=1, H, C
                f = 0x40; // Start with N=1 (Subtraction)
                if ((result & 0xFF) == 0) f |= 0x80;
                if (result < 0) f |= 0x10; // Set C (borrow)

                // H Flag: Set if borrow from bit 4
                if (((a & 0x0F) - (val & 0x0F) - carry) < 0) f |= 0x20;

                a = result & 0xFF;
                pc += 2; cycles = 2; break;
            }
            // --- Control Flow ---
            case 0xC8: { // RET Z
                if ((f & 0x80) != 0) { // If Z is set
                    int lo = mmu.read(sp++);
                    int hi = mmu.read(sp++);
                    pc = (hi << 8) | lo;
                    cycles = 5; // 20 T-cycles
                } else {
                    pc++;
                    cycles = 2; // 8 T-cycles
                }
                break;
            }
            case 0xD8: { // RET C (Return if Carry Flag is SET)
                if ((f & 0x10) != 0) { // If C is SET
                    int lo = mmu.read(sp++);
                    int hi = mmu.read(sp++);
                    pc = (hi << 8) | lo;
                    cycles = 5; // Taken: 20 T-cycles
                } else {
                    pc++;
                    cycles = 2; // Not Taken: 8 T-cycles
                }
                break;
            }
            case 0x92: { // SUB D
                sub(d);
                pc++; cycles = 1; break;
            }
            case 0x09: { // ADD HL, BC
                addHL(getBC());
                pc++; cycles = 2; break;
            }
            case 0x19: { // ADD HL, DE
                addHL(getDE());
                pc++; cycles = 2; break;
            }

            case 0x29: { // ADD HL, HL
                addHL(getHL());
                pc++; cycles = 2; break;
            }
            case 0x39: { // ADD HL, SP
                addHL(sp);
                pc++; cycles = 2; break;
            }
            case 0xC7: resetPc(0x0000); break; // RST 00H
            case 0xCF: resetPc(0x0008); break; // RST 08H
            case 0xD7: resetPc(0x0010); break; // RST 10H
            case 0xDF: resetPc(0x0018); break; // RST 18H
            case 0xE7: resetPc(0x0020); break; // RST 20H
            case 0xEF: resetPc(0x0028); break; // RST 28H
            case 0xF7: resetPc(0x0030); break; // RST 30H
            case 0xFF: resetPc(0x0038); break; // RST 38H

            // --- New Standard Instructions ---
            case 0x0F: { // RRCA
                int bit0 = a & 0x01;
                a = (a >> 1) | (bit0 << 7);
                f = (bit0 != 0 ? 0x10 : 0); // Z=0, N=0, H=0. C = old bit 0.
                pc++; cycles = 1; break;
            }
            case 0x2F: { // CPL (Complement A)
                a = ~a & 0xFF;
                f = (f & 0x90) | 0x60; // Preserve C. Set N=1, H=1. Z=0.
                pc++; cycles = 1; break;
            }
            case 0x10: { // STOP
                pc += 1; cycles = 1; break; // Length is 2 bytes but 2nd is 0x00
            }
            case 0x1F: { // RRA
                int oldC = (f & 0x10) >> 4;
                int newC = a & 0x01;
                a = (a >> 1) | (oldC << 7);
                f = (newC != 0 ? 0x10 : 0); // Z=0, N=0, H=0. C = old bit 0.
                pc++; cycles = 1; break;
            }
            case 0x36: { // LD (HL), d8 (Store byte to memory)
                mmu.write(getHL(), mmu.read(pc + 1));
                pc += 2; cycles = 3; break;
            }
            case 0x47: { // LD B, A
                b = a; pc++; cycles = 1; break;
            }
            case 0x5F: { // LD E, A
                e = a; pc++; cycles = 1; break;
            }
            case 0x34: { // INC (HL)
                int address = getHL();
                int val = mmu.read(address);
                val = (val + 1) & 0xFF;

                // Flags: Z (set if result 0), N=0, H (set if carry from bit 3), C (unaffected)
                f &= 0x10; // Preserve Carry, clear Z, N, H
                if (val == 0) f |= 0x80;
                if ((val & 0x0F) == 0) f |= 0x20; // Half-Carry is set if overflow from bit 3

                mmu.write(address, val);
                pc++;
                cycles = 3; // 12 T-cycles
                break;
            }
            case 0x35: { // DEC (HL)
                int address = getHL();
                int val = mmu.read(address);
                val = (val - 1) & 0xFF;

                // Flags: Z (set if result 0), N=1, H (set if borrow from bit 4), C (unaffected)
                f = (f & 0x10) | 0x40; // Preserve C, Set N=1
                if (val == 0) f |= 0x80;
                if ((val & 0x0F) == 0x0F) f |= 0x20; // Half-Carry is set if borrow from bit 4

                mmu.write(address, val);
                pc++;
                cycles = 3; // 12 T-cycles
                break;
            }

            case 0x5E: { // LD E, (HL)
                e = mmu.read(getHL());
                pc++; cycles = 2; break;
            }
            case 0x56: { // LD D, (HL)
                d = mmu.read(getHL());
                pc++; cycles = 2; break;
            }
            case 0xA1: { // AND C
                a &= c; 
                f = ((a == 0) ? 0x80 : 0) | 0x20; // Z set if zero. N=0, H=1, C=0.
                pc++; cycles = 1; break;
            }
            case 0xA9: { // XOR C
                a ^= c;
                f = (a == 0 ? 0x80 : 0); // Z set if zero. N=0, H=0, C=0.
                pc++; cycles = 1; break;
            }
            case 0xAE: { // XOR (HL)
                int val = mmu.read(getHL());
                a ^= val;
                f = (a == 0 ? 0x80 : 0); // Z set if zero. N=0, H=0, C=0.
                pc++; cycles = 2; break;
            }
            case 0xE9: { // JP (HL) (Jump to HL)
                pc = getHL();
                cycles = 1; // 4 T-cycles
                break;
            }
            case 0x67: { // LD H, A
                h = a;
                pc++; cycles = 1; break;
            }
            case 0x6F: { // LD L, A
                l = a;
                pc++; cycles = 1; break;
            }
            case 0x7E: { // LD A, (HL)
                a = mmu.read(getHL());
                pc++; cycles = 2; break;
            }
            case 0xA7: { // AND A (Simply sets flags based on A)
                // Flags: Z (set if A=0), N=0, H=1, C=0.
                f = (a == 0 ? 0x80 : 0) | 0x20;
                pc++; cycles = 1; break;
            }
            case 0x2D: { // DEC L
                l = (l - 1) & 0xFF;
                f = (f & 0x10) | 0x40 | ((l == 0) ? 0x80 : 0) | (((l & 0x0F) == 0x0F) ? 0x20 : 0);
                pc++; cycles = 1; break;
            }
            case 0x33: { // INC SP
                sp = (sp + 1) & 0xFFFF;
                pc++; cycles = 2; break;
            }
            case 0x3A: { // LD A, (HL-) (LDI, Decrement)
                a = mmu.read(getHL());
                setHL((getHL() - 1) & 0xFFFF);
                pc++; cycles = 2; break;
            }
            case 0x46: { // LD B, (HL)
                b = mmu.read(getHL());
                pc++; cycles = 2; break;
            }
            case 0x4E: { // LD C, (HL)
                c = mmu.read(getHL());
                pc++; cycles = 2; break;
            }
            case 0x6E: { // LD L, (HL)
                l = mmu.read(getHL());
                pc++; cycles = 2; break;
            }
            case 0x70: { // LD (HL), B
                mmu.write(getHL(), b);
                pc++; cycles = 2; break;
            }
            case 0x71: { // LD (HL), C
                mmu.write(getHL(), c);
                pc++; cycles = 2; break;
            }
            case 0x72: { // LD (HL), D
                mmu.write(getHL(), d);
                pc++; cycles = 2; break;
            }
            case 0x74: { // LD (HL), H
                mmu.write(getHL(), h);
                pc++; cycles = 2; break;
            }
            case 0xB7: { // OR A
                // a |= a; // This is a NOP for the value
                f = (a == 0 ? 0x80 : 0); // Z set if zero. N=0, H=0, C=0.
                pc++; cycles = 1; break;
            }
            case 0x96: { // SUB (HL)
                sub(mmu.read(getHL()));
                pc++; cycles = 2; break;
            }
            case 0xD6: { // SUB d8
                sub(mmu.read(pc + 1));
                pc += 2; cycles = 2; break;
            }
            case 0xDA: { // JP C, a16
                int addr = (mmu.read(pc + 2) << 8) | mmu.read(pc + 1);
                if ((f & 0x10) != 0) {
                    pc = addr; cycles = 4; // Taken
                } else {
                    pc += 3; cycles = 3; // Not taken
                }
                break;
            }
            case 0x14: { // INC D
                d = (d + 1) & 0xFF;
                f = (f & 0x10) | ((d == 0) ? 0x80 : 0) | (((d & 0x0F) == 0) ? 0x20 : 0);
                pc++; cycles = 1; break;
            }
            case 0x15: { // DEC D
                d = (d - 1) & 0xFF;
                f = (f & 0x10) | 0x40 | ((d == 0) ? 0x80 : 0) | (((d & 0x0F) == 0x0F) ? 0x20 : 0);
                pc++; cycles = 1; break;
            }
            case 0x2B: { // DEC HL (16-bit decrement)
                setHL((getHL() - 1) & 0xFFFF);
                pc++; cycles = 2; break;
            }
            case 0x30: { // JR NC, r8 (Jump Relative if No Carry)
                int n = mmu.read(pc + 1);
                if (n > 127) n -= 256; // Signed byte conversion

                if ((f & 0x10) == 0) { // If Carry Flag is NOT set (NC)
                    pc += 2 + n;
                    cycles = 3; // Jump taken
                } else {
                    pc += 2;
                    cycles = 2; // Jump not taken
                }
                break;
            }
            case 0xB2: { // OR D
                a |= d;
                f = (a == 0 ? 0x80 : 0); // Z set if zero. N=0, H=0, C=0.
                pc++; cycles = 1; break;
            }
            case 0xEE: { // XOR d8
                int n = mmu.read(pc + 1);
                a ^= n;
                f = (a == 0 ? 0x80 : 0);
                pc += 2; cycles = 2; break;
            }
            case 0xF8: { // LD HL, SP+r8
                int n = mmu.read(pc + 1);
                if (n > 127) n -= 256;

                int result = sp + n;

                // Flags: Z=0, N=0. H, C set via signed math rules.
                f = 0;
                if (((sp & 0x0F) + (n & 0x0F)) > 0x0F) f |= 0x20; // H Flag
                if (((sp & 0xFF) + (n & 0xFF)) > 0xFF) f |= 0x10; // C Flag

                setHL(result & 0xFFFF);
                pc += 2; cycles = 3; break;
            }
            case 0xF9: { // LD SP, HL
                sp = getHL();
                pc++; cycles = 2; break;
            }
            case 0xD9: { // RETI (Return from Interrupt)
                // 1. Pop PC from the stack (same as RET)
                int lo = mmu.read(sp++);
                int hi = mmu.read(sp++);
                pc = (hi << 8) | lo;

                // 2. Re-enable interrupts (IME)
                IME = true;

                cycles = 4; // 16 T-cycles
                break;
            }

            case 0x86: { // ADD A, (HL)
                int val = mmu.read(getHL());
                add(val); // Reuse your existing 8-bit ADD helper
                pc++; cycles = 2; break;
            }
            case 0x8E: { // ADC A, (HL) (Add with Carry)
                int val = mmu.read(getHL());
                int carry = (f & 0x10) != 0 ? 1 : 0;
                int result = a + val + carry;

                // Flags: Z, N=0, H, C
                f = 0;
                if ((result & 0xFF) == 0) f |= 0x80;
                if (result > 0xFF) f |= 0x10;
                if (((a & 0x0F) + (val & 0x0F) + carry) > 0x0F) f |= 0x20;

                a = result & 0xFF;
                pc++; cycles = 2; break;
            }
            case 0xD0: { // RET NC (Return if Carry Flag is NOT set)
                if ((f & 0x10) == 0) { // If C is 0 (NOT set)
                    int lo = mmu.read(sp++);
                    int hi = mmu.read(sp++);
                    pc = (hi << 8) | lo;
                    cycles = 5; // Return taken: 20 T-cycles
                } else {
                    pc++;
                    cycles = 2; // Return not taken: 8 T-cycles
                }
                break;
            }
            case 0x04: { // INC B
                b = (b + 1) & 0xFF;
                // Flags: Z, N=0, H. C preserved.
                f = (f & 0x10) | ((b == 0) ? 0x80 : 0) | (((b & 0x0F) == 0) ? 0x20 : 0);
                pc++; cycles = 1; break;
            }
            case 0x1C: { // INC E
                e = (e + 1) & 0xFF;
                f = (f & 0x10) | ((e == 0) ? 0x80 : 0) | (((e & 0x0F) == 0) ? 0x20 : 0);
                pc++; cycles = 1; break;
            }
            // --- New Load/Store Instructions ---
            case 0x61: { // LD H, C
                h = c;
                pc++; cycles = 1; break;
            }
            case 0x08: { // LD (a16), SP 
                int addr = (mmu.read(pc + 2) << 8) | mmu.read(pc + 1);
                mmu.write(addr, sp & 0xFF);         // Write Low Byte of SP
                mmu.write(addr + 1, (sp >> 8) & 0xFF); // Write High Byte of SP
                pc += 3; cycles = 5; break; // 20 T-cycles
            }
            case 0x44: { b = h; pc++; cycles = 1; break; } // LD B, H (Assuming you meant LD B, H as the 40-47 range is all LD)
            case 0x4B: { c = e; pc++; cycles = 1; break; } // LD C, E
            case 0x4C: { c = h; pc++; cycles = 1; break; } // LD C, H
            case 0x4D: { c = l; pc++; cycles = 1; break; } // LD C, L
            case 0x50: { d = b; pc++; cycles = 1; break; } // LD D, B
            case 0x51: { d = c; pc++; cycles = 1; break; } // LD D, C
            case 0x52: { pc++; cycles = 1; break; } // LD D, D (NOP)
            case 0x53: { d = e; pc++; cycles = 1; break; } // LD D, E
            case 0x55: { d = l; pc++; cycles = 1; break; } // LD D, L
            case 0x41: { b = c; pc++; cycles = 1; break; } // LD B, C
            case 0x42: { b = d; pc++; cycles = 1; break; } // LD B, D
            case 0x43: { b = e; pc++; cycles = 1; break; } // LD B, E
            case 0x45: { b = l; pc++; cycles = 1; break; } // LD B, L
            case 0x48: { c = b; pc++; cycles = 1; break; } // LD C, B
            case 0x54: { d = h; pc++; cycles = 1; break; } // LD D, H
            case 0x5A: { e = d; pc++; cycles = 1; break; } // LD E, D
            case 0x5B: { pc++; cycles = 1; break; } // LD E, E (NOP)
            case 0x5C: { e = h; pc++; cycles = 1; break; } // LD E, H
            case 0x59: { e = c; pc++; cycles = 1; break; } // LD E, C
            case 0x5D: { e = l; pc++; cycles = 1; break; } // LD E, L
            case 0x73: { mmu.write(getHL(), e); pc++; cycles = 2; break; } // LD (HL), E

            case 0x75: { // LD (HL), L
                mmu.write(getHL(), l);
                pc++;
                cycles = 2; // 8 T-cycles
                break;
            }
            case 0x49: { pc++; cycles = 1; break; } // LD C, C (NOP)
            case 0x64: { pc++; cycles = 1; break; } // LD H, H (NOP)
            case 0x6D: { pc++; cycles = 1; break; } // LD L, L (NOP)
            case 0xFD: { // Illegal Opcode (Treat as NOP)
                pc++; cycles = 1; break; // Skip the byte
            }
            case 0x2C: { // INC L  <-- The main looping instruction
                l = (l + 1) & 0xFF;
                f = (f & 0x10) | ((l == 0) ? 0x80 : 0) | (((l & 0x0F) == 0) ? 0x20 : 0);
                pc++; cycles = 1; break;
            }
            case 0x1D: { // DEC E
                e = (e - 1) & 0xFF;
                // Flags: Z, N=1, H. C preserved.
                f = (f & 0x10) | 0x40 | ((e == 0) ? 0x80 : 0) | (((e & 0x0F) == 0x0F) ? 0x20 : 0);
                pc++; cycles = 1; break;
            }
            case 0x62: { // LD H, D
                h = d;
                pc++; cycles = 1; break;
            }
            case 0x63: { // LD H, E
                h = e;
                pc++; cycles = 1; break;
            }
            case 0x65: { // LD H, L
                h = l;
                pc++; cycles = 1; break;
            }
            case 0x60: { // LD H, B
                h = b;
                pc++; cycles = 1; break;
            }
            case 0x27: { // DAA (Decimal Adjust Accumulator)
                int correction = 0;
                boolean nFlag = (f & 0x40) != 0;
                boolean hFlag = (f & 0x20) != 0;
                boolean cFlag = (f & 0x10) != 0;

                if (hFlag || (!nFlag && (a & 0x0F) > 9)) {
                    correction = 0x06;
                }

                if (cFlag || (!nFlag && a > 0x99)) {
                    correction |= 0x60;
                    f |= 0x10; // Set carry flag
                }

                a += nFlag ? -correction : correction;
                a &= 0xFF;

                f = (f & 0x40) | (a == 0 ? 0x80 : 0) | (f & 0x10); // Preserve N, set Z, clear H, preserve/set C
                pc++; cycles = 1; break;
            }
            case 0x6B: { // LD L, E
                l = e;
                pc++; cycles = 1; break;
            }
            case 0x68: { // LD L, B
                l = b;
                pc++; cycles = 1; break;
            }
            case 0x69: { // LD L, C
                l = c;
                pc++; cycles = 1; break;
            }
            case 0x4A: { // LD C, D
                c = d;
                pc++; cycles = 1; break;
            }
            case 0x6A: { // LD L, D
                l = d;
                pc++; cycles = 1; break;
            }
            case 0x6C: { // LD L, H
                l = h;
                pc++; cycles = 1; break;
            }
            case 0xDC: { // CALL C, a16 (Call if Carry is SET)
                int addr = (mmu.read(pc + 2) << 8) | mmu.read(pc + 1);

                if ((f & 0x10) != 0) { // If Carry is SET
                    int ret = pc + 3;
                    mmu.write(--sp, (ret >> 8) & 0xFF);
                    mmu.write(--sp, ret & 0xFF);
                    pc = addr;
                    cycles = 6; // Taken: 24 T-cycles
                } else {
                    pc += 3;
                    cycles = 3; // Not Taken: 12 T-cycles
                }
                break;
            }
            case 0xD4: { // CALL NC, a16 (Call if No Carry)
                int addr = (mmu.read(pc + 2) << 8) | mmu.read(pc + 1);

                if ((f & 0x10) == 0) { // If Carry is NOT SET
                    int ret = pc + 3;
                    mmu.write(--sp, (ret >> 8) & 0xFF);
                    mmu.write(--sp, ret & 0xFF);
                    pc = addr;
                    cycles = 6; // Taken: 24 T-cycles
                } else {
                    pc += 3;
                    cycles = 3; // Not Taken: 12 T-cycles
                }
                break;
            }
            case 0x98: { // SBC A, B (Subtract B from A + Carry)
                int val = b;
                int carry = (f & 0x10) != 0 ? 1 : 0;
                int result = a - val - carry;

                // Flags: Z, N=1, H, C
                f = 0x40; // Start with N=1 (Subtraction)
                if ((result & 0xFF) == 0) f |= 0x80;
                if (result < 0) f |= 0x10; // Set C (borrow)

                // H Flag: Set if borrow from bit 4
                if (((a & 0x0F) - (val & 0x0F) - carry) < 0) f |= 0x20;

                a = result & 0xFF;
                pc++; cycles = 1; break;
            }

            case 0x07: { // RLCA
                int bit7 = (a & 0x80) >> 7;
                a = ((a << 1) | bit7) & 0xFF;
                f = (bit7 != 0 ? 0x10 : 0); // Z=0, N=0, H=0. C = old bit 7.
                pc++; cycles = 1; break;
            }

            case 0x9F: { // SBC A, A (Subtract A from A + Carry)
                int carry = (f & 0x10) != 0 ? 1 : 0;
                a = (0 - carry) & 0xFF; // Result is either 0 or 0xFF
                // Flags: Z=1 if carry was 0, N=1, H=1 if carry was 1, C=1 if carry was 1
                f = 0x40 | (carry == 0 ? 0x80 : 0) | (carry != 0 ? 0x20 : 0) | (carry != 0 ? 0x10 : 0);
                pc++;
                cycles = 1;
                break;
            }
            case 0x99: { // SBC A, C
                int val = c;
                int carry = (f & 0x10) != 0 ? 1 : 0;
                int result = a - val - carry;

                // Flags: Z, N=1, H, C
                f = 0x40; // Start with N=1 (Subtraction)
                if ((result & 0xFF) == 0) f |= 0x80;
                if (result < 0) f |= 0x10; // Set C (borrow)

                // H Flag: Set if borrow from bit 4
                if (((a & 0x0F) - (val & 0x0F) - carry) < 0) f |= 0x20;

                a = result & 0xFF;
                pc++;
                cycles = 1;
                break;
            }
            case 0x38: { // JR C, r8
                int n = mmu.read(pc + 1);
                if (n > 127) n -= 256; // Signed byte conversion

                if ((f & 0x10) != 0) { // If Carry Flag IS set (Condition TRUE)
                    pc += 2 + n;
                    cycles = 3; // Taken: 12 T-cycles
                } else {
                    // --- THIS IS THE CRITICAL BLOCK ---
                    pc += 2; // Advance PC past the 2-byte instruction
                    cycles = 2; // Not taken: 8 T-cycles
                }
                break;
            }
            case 0x40: { // LD B, B (Effectively NOP)
                pc++; cycles = 1; break;
            }
            case 0x66: { // LD H, (HL)
                h = mmu.read(getHL());
                pc++; cycles = 2; break;
            }
            case 0xB6: { // OR (HL)
                a |= mmu.read(getHL());
                f = (a == 0 ? 0x80 : 0); // Z set if zero. N=0, H=0, C=0.
                pc++;
                cycles = 2; // 8 T-cycles
                break;
            }
            case 0xB4: { // OR H
                a |= h;
                f = (a == 0 ? 0x80 : 0); // Z set if zero. N=0, H=0, C=0.
                pc++; cycles = 1; break;
            }
            case 0xC4: { // CALL NZ, a16
                int addr = (mmu.read(pc + 2) << 8) | mmu.read(pc + 1);

                if ((f & 0x80) == 0) { // If Z is NOT set (NZ)
                    int ret = pc + 3;
                    mmu.write(--sp, (ret >> 8) & 0xFF);
                    mmu.write(--sp, ret & 0xFF);
                    pc = addr;
                    cycles = 6; // Taken: 24 T-cycles
                } else {
                    pc += 3;
                    cycles = 3; // Not taken: 12 T-cycles
                }
                break;
            }
            case 0xCC: { // CALL Z, a16
                int addr = (mmu.read(pc + 2) << 8) | mmu.read(pc + 1);

                if ((f & 0x80) != 0) { // If Z is SET
                    int ret = pc + 3;
                    mmu.write(--sp, (ret >> 8) & 0xFF);
                    mmu.write(--sp, ret & 0xFF);
                    pc = addr;
                    cycles = 6; // Taken: 24 T-cycles
                } else {
                    pc += 3;
                    cycles = 3; // Not Taken: 12 T-cycles
                }
                break;
            }

            case 0x37: { // SCF (Set Carry Flag)
                f &= 0x90; // Clear N and H flags
                f |= 0x10; // Set C flag
                pc++; cycles = 1; break;
            }
            case 0xF5: { // PUSH AF
                mmu.write(--sp, a);
                mmu.write(--sp, f & 0xF0); // Low byte (F - flags), lower 4 bits are not pushed
                pc++; cycles = 4; break;
            }
            case 0xF1: { // POP AF
                f = mmu.read(sp++) & 0xF0; // Low byte (F), lower 4 bits are read as 0
                a = mmu.read(sp++);
                pc++; cycles = 3; break;
            }
            case 0xFA: { // LD A, (a16) (Load A from full 16-bit address)
                int addr = (mmu.read(pc + 2) << 8) | mmu.read(pc + 1);
                a = mmu.read(addr);
                pc += 3; cycles = 4; break;
            }
            case 0xC0: { // RET NZ (Return if Z flag is NOT set)
                if ((f & 0x80) == 0) { // If Z is 0
                    int lo = mmu.read(sp++);
                    int hi = mmu.read(sp++);
                    pc = (hi << 8) | lo;
                    cycles = 5; // Taken: 20 T-cycles
                } else {
                    pc++;
                    cycles = 2; // Not Taken: 8 T-cycles
                }
                break;
            }
            case 0x58: { // LD E, B
                e = b;
                pc++;
                cycles = 1; // 4 T-cycles
                break;
            }
            case 0xE6: { // AND d8
                int n = mmu.read(pc + 1);
                a &= n;
                // Flags: Z set if zero. N=0, H=1, C=0.
                f = (a == 0 ? 0x80 : 0) | 0x20;
                pc += 2;
                cycles = 2; // 8 T-cycles
                break;
            }
            case 0xE8: { // ADD SP, r8
                int n = mmu.read(pc + 1);
                if (n > 127) n -= 256; // Signed byte conversion

                // Flags: Z=0 (ALWAYS), N=0 (ALWAYS). H and C set based on signed math.
                f = 0;

                // H Flag: Carry from bit 3 to bit 4
                if (((sp & 0x0F) + (n & 0x0F)) > 0x0F) f |= 0x20;

                // C Flag: Carry from bit 7 to bit 8 (Full Byte Overflow)
                if (((sp & 0xFF) + (n & 0xFF)) > 0xFF) f |= 0x10;

                sp = (sp + n) & 0xFFFF; // Apply the addition
                pc += 2;
                cycles = 4; // 16 T-cycles (4 M-cycles)
                break;
            }
            case 0xBE: { // CP (HL) (Compare A with value at address HL)
                int val = mmu.read(getHL());
                cp(val); // Reuse your existing CP helper
                pc++;
                cycles = 2; // 8 T-cycles
                break;
            }
            case 0xF6: { // OR d8
                int n = mmu.read(pc + 1);
                a |= n;
                f = (a == 0 ? 0x80 : 0);
                pc += 2; cycles = 2;
                break;
            }
            case 0xD2: { // JP NC, a16
                int addr = (mmu.read(pc + 2) << 8) | mmu.read(pc + 1);

                if ((f & 0x10) == 0) { // If Carry is NOT SET
                    pc = addr;
                    cycles = 4; // Taken: 16 T-cycles
                } else {
                    pc += 3;
                    cycles = 3; // Not Taken: 12 T-cycles
                }
                break;
            }
            case 0xD3: case 0xDB: case 0xDD: case 0xE3: case 0xE4:
            case 0xEB: case 0xEC: case 0xED: case 0xF4: case 0xFC:
                pc++; cycles = 1; break; // Treat as NOP


            default:
                System.err.printf("Unknown opcode: 0x%02X at 0x%04X%n", opcode, pc);
                pc++; cycles = 1; break;
        }
    }

    private void executeCB(int cb) {
        int regCode = cb & 0x07;
        int bit = (cb >> 3) & 0x07;
        int operation = cb >> 6;

        int val = getRegisterValueCB(regCode);
        int result = val;

        cycles = (regCode == 6) ? 4 : 2; // (HL) operations take 16 T-cycles, others take 8.

        switch (operation) {
            case 0: // Rotates and Shifts
                switch (bit) {
                    case 0: result = rlc(val); break; // RLC
                    case 1: result = rrc(val); break; // RRC
                    case 2: result = rl(val); break;  // RL
                    case 3: result = rr(val); break;  // RR
                    case 4: result = sla(val); break; // SLA
                    case 5: result = sra(val); break; // SRA
                    case 6: result = swap(val); break;// SWAP
                    case 7: result = srl(val); break; // SRL
                }
                break;
            case 1: // BIT
                // BIT is special, it doesn't write a result back, just sets flags.
                f = (f & 0x10) | 0x20; // Preserve C, set H=1, clear N=0
                if ((val & (1 << bit)) == 0) {
                    f |= 0x80; // Set Z if bit is 0
                }
                // No need to write back, so we return early.
                if (regCode == 6) cycles = 3; // BIT (HL) is 12 cycles
                return;
            case 2: // RES
                result = val & ~(1 << bit);
                break;
            case 3: // SET
                result = val | (1 << bit);
                break;
        }

        setRegisterValueCB(regCode, result);
    }

    // --- CB Helper Functions ---

    private int rlc(int val) {
        int carry = (val >> 7) & 1;
        int result = ((val << 1) | carry) & 0xFF;
        f = (carry << 4) | (result == 0 ? 0x80 : 0);
        return result;
    }

    private int rrc(int val) {
        int carry = val & 1;
        int result = ((val >> 1) | (carry << 7)) & 0xFF;
        f = (carry << 4) | (result == 0 ? 0x80 : 0);
        return result;
    }

    private int rl(int val) {
        int carry = (f & 0x10) >> 4;
        int newCarry = (val >> 7) & 1;
        int result = ((val << 1) | carry) & 0xFF;
        f = (newCarry << 4) | (result == 0 ? 0x80 : 0);
        return result;
    }

    private int rr(int val) {
        int carry = (f & 0x10) >> 4;
        int newCarry = val & 1;
        int result = ((val >> 1) | (carry << 7)) & 0xFF;
        f = (newCarry << 4) | (result == 0 ? 0x80 : 0);
        return result;
    }

    private int sla(int val) {
        int carry = (val >> 7) & 1;
        int result = (val << 1) & 0xFF;
        f = (carry << 4) | (result == 0 ? 0x80 : 0);
        return result;
    }

    private int sra(int val) {
        int carry = val & 1;
        int msb = val & 0x80;
        int result = ((val >> 1) | msb) & 0xFF;
        f = (carry << 4) | (result == 0 ? 0x80 : 0);
        return result;
    }

    private int srl(int val) {
        int carry = val & 1;
        int result = (val >> 1) & 0xFF;
        f = (carry << 4) | (result == 0 ? 0x80 : 0);
        return result;
    }

    private int swap(int val) {
        int upper = (val >> 4) & 0x0F;
        int lower = (val & 0x0F) << 4;
        int result = upper | lower;
        f = (result == 0 ? 0x80 : 0);
        return result;
    }
    private int getRegisterValueCB(int regCode) {
        switch (regCode) {
            case 0: return b;
            case 1: return c;
            case 2: return d;
            case 3: return e;
            case 4: return h;
            case 5: return l;
            case 6:
                return mmu.read(getHL());
            case 7: return a;
            default: return 0; // Should not happen
        }
    }
    private void setRegisterValueCB(int regCode, int value) {
        switch (regCode) {
            case 0: b = value; break;
            case 1: c = value; break;
            case 2: d = value; break;
            case 3: e = value; break;
            case 4: h = value; break;
            case 5: l = value; break;
            case 6: mmu.write(getHL(), value); break;
            case 7: a = value; break;
        }
    }

    // Helper Logic
    private void add(int val) {
        int res = a + val;
        f = (res > 0xFF ? 0x10 : 0) | ((res & 0xFF) == 0 ? 0x80 : 0) |
                (((a & 0xF) + (val & 0xF) > 0xF) ? 0x20 : 0);
        a = res & 0xFF;
    }
    private void sub(int val) {
        int res = a - val;
        f = 0x40 | (res < 0 ? 0x10 : 0) | ((res & 0xFF) == 0 ? 0x80 : 0) |
                (((a & 0xF) < (val & 0xF)) ? 0x20 : 0);
        a = res & 0xFF;
    }
    private void cp(int val) {
        int res = a - val;
        f = 0x40 | (res < 0 ? 0x10 : 0) | ((res & 0xFF) == 0 ? 0x80 : 0) |
                (((a & 0xF) < (val & 0xF)) ? 0x20 : 0);
    }

    private void adc(int val) {
        int carry = (f & 0x10) != 0 ? 1 : 0;
        int result = a + val + carry;
        f = ((result & 0xFF) == 0 ? 0x80 : 0) | (result > 0xFF ? 0x10 : 0) | (((a & 0x0F) + (val & 0x0F) + carry) > 0x0F ? 0x20 : 0);
        a = result & 0xFF;
    }

    private void sbc(int val) {
        int carry = (f & 0x10) != 0 ? 1 : 0;
        int result = a - val - carry;
        f = 0x40 | ((result & 0xFF) == 0 ? 0x80 : 0) | (result < 0 ? 0x10 : 0) | (((a & 0x0F) - (val & 0x0F) - carry) < 0 ? 0x20 : 0);
        a = result & 0xFF;
    }


    private void addHL(int value) {
        int hl = getHL();
        int result = hl + value;

        f &= 0x80; // Preserve Z flag. Clear N, H, C.
        // N is effectively 0 now.

        // H Flag: Set if carry from bit 11
        if (((hl & 0x0FFF) + (value & 0x0FFF)) > 0x0FFF) {
            f |= 0x20;
        }

        // C Flag: Set if carry from bit 15 (overflow 16-bit)
        if (result > 0xFFFF) {
            f |= 0x10;
        }

        setHL(result & 0xFFFF);
    }
    // In CPU.java

    private void handleInterrupts() {
        int requested = mmu.read(0xFF0F);
        int enabled = mmu.read(0xFFFF);
        int pending = requested & enabled;

        if ((requested & 1) != 0) {
            // Use a static counter to prevent console flood (define 'debugLogLimit' in class if needed, or just print once)
            // For now, let's just print it. The output will be fast, but readable at the end.
            if (!IME || pending == 0) {
                System.out.println(String.format("INT IGNORED: IME=%b | IE=0x%02X | IF=0x%02X | Pending=0x%02X",
                        IME, enabled, requested, pending));
            }
        }

        if (pending != 0) {
            isHalted = false;

            if (IME) {
                int vector = 0;
                int bitMask = 0;

                if ((pending & 0x01) != 0)      { vector = 0x0040; bitMask = 0x01; } // V-Blank
                else if ((pending & 0x02) != 0) { vector = 0x0048; bitMask = 0x02; } // LCD STAT
                else if ((pending & 0x04) != 0) { vector = 0x0050; bitMask = 0x04; } // Timer
                else if ((pending & 0x08) != 0) { vector = 0x0058; bitMask = 0x08; } // Serial
                else if ((pending & 0x10) != 0) { vector = 0x0060; bitMask = 0x10; } // Joypad

                if (vector != 0) {
                    System.out.println(String.format("!!! INTERRUPT FIRED !!! Vector=0x%04X | PC=0x%04X | SP=0x%04X", vector, pc, sp));

                    IME = false;
                    // Ack the interrupt
                    mmu.write(0xFF0F, requested & ~bitMask);

                    // Push PC
                    mmu.write(--sp, (pc >> 8) & 0xFF);
                    mmu.write(--sp, pc & 0xFF);

                    // Jump
                    pc = vector;
                    cycles += 5;

                }
            }
        }
    }
    private void resetPc(int newAddress) {
        int returnAddr = pc + 1;
        mmu.write(--sp, (returnAddr >> 8) & 0xFF); // Push High Byte
        mmu.write(--sp, returnAddr & 0xFF);        // Push Low Byte
        pc = newAddress;
        cycles = 4; // 16 T-cycles
    }
    private int getRegisterValue(int opcode) {
        int registerIndex = opcode & 0x07; // Extract the last 3 bits (0-7)

        switch (registerIndex) {
            case 0b000: return b; // 0x98 = SBC A, B
            case 0b001: return c; // 0x99 = SBC A, C
            case 0b010: return d; // 0x9A = SBC A, D
            case 0b011: return e; // 0x9B = SBC A, E
            case 0b100: return h; // 0x9C = SBC A, H
            case 0b101: return l; // 0x9D = SBC A, L
            case 0b110: return mmu.read(getHL()); // Not used in this block (0x9E is SBC A, (HL))
            case 0b111: return a; // 0x9F = SBC A, A
            default: return 0; // Should never happen
        }
    }
    // Debug String
    private String getFlags() {
        return ((f&0x80)!=0?"Z":"-") + ((f&0x40)!=0?"N":"-") +
                ((f&0x20)!=0?"H":"-") + ((f&0x10)!=0?"C":"-");
    }
}