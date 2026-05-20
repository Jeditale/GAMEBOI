import java.io.File;
import java.nio.file.Files;

public class Disassembler {
    public static void main(String[] args) throws Exception {
        byte[] rom = Files.readAllBytes(new File("ROMS/hard to run/Legend of Zelda, The - Link's Awakening (USA, Europe) (Rev 2).gb").toPath());
        int startPC = 0x38B0;
        int endPC = 0x38D0;
        
        int offset = startPC;
        int currentPC = startPC;
        int idx = offset;
        
        System.out.println("Disassembly of Bank 0 from 0x" + Integer.toHexString(startPC) + " to 0x" + Integer.toHexString(endPC));
        while (currentPC < endPC && idx < rom.length) {
            int op = rom[idx] & 0xFF;
            String inst = "";
            int len = 1;
            switch (op) {
                case 0x00: inst = "NOP"; break;
                case 0x06: inst = "LD B, 0x" + String.format("%02X", rom[idx+1] & 0xFF); len = 2; break;
                case 0x0E: inst = "LD C, 0x" + String.format("%02X", rom[idx+1] & 0xFF); len = 2; break;
                case 0x16: inst = "LD D, 0x" + String.format("%02X", rom[idx+1] & 0xFF); len = 2; break;
                case 0x1E: inst = "LD E, 0x" + String.format("%02X", rom[idx+1] & 0xFF); len = 2; break;
                case 0x26: inst = "LD H, 0x" + String.format("%02X", rom[idx+1] & 0xFF); len = 2; break;
                case 0x2E: inst = "LD L, 0x" + String.format("%02X", rom[idx+1] & 0xFF); len = 2; break;
                case 0x3E: inst = "LD A, 0x" + String.format("%02X", rom[idx+1] & 0xFF); len = 2; break;
                case 0xFA: inst = "LD A, (0x" + String.format("%02X%02X", rom[idx+2] & 0xFF, rom[idx+1] & 0xFF) + ")"; len = 3; break;
                case 0xEA: inst = "LD (0x" + String.format("%02X%02X", rom[idx+2] & 0xFF, rom[idx+1] & 0xFF) + "), A"; len = 3; break;
                case 0xE0: inst = "LDH (0xFF" + String.format("%02X", rom[idx+1] & 0xFF) + "), A"; len = 2; break;
                case 0xF0: inst = "LDH A, (0xFF" + String.format("%02X", rom[idx+1] & 0xFF) + ")"; len = 2; break;
                case 0x78: inst = "LD A, B"; break;
                case 0x79: inst = "LD A, C"; break;
                case 0x7A: inst = "LD A, D"; break;
                case 0x7B: inst = "LD A, E"; break;
                case 0x7C: inst = "LD A, H"; break;
                case 0x7D: inst = "LD A, L"; break;
                case 0x47: inst = "LD B, A"; break;
                case 0x4F: inst = "LD C, A"; break;
                case 0x57: inst = "LD D, A"; break;
                case 0x5F: inst = "LD E, A"; break;
                case 0x67: inst = "LD H, A"; break;
                case 0x6F: inst = "LD L, A"; break;
                case 0xAF: inst = "XOR A"; break;
                case 0xFE: inst = "CP 0x" + String.format("%02X", rom[idx+1] & 0xFF); len = 2; break;
                case 0x20: inst = "JR NZ, " + String.format("%d (0x%04X)", (byte)rom[idx+1], currentPC + 2 + (byte)rom[idx+1]); len = 2; break;
                case 0x28: inst = "JR Z, " + String.format("%d (0x%04X)", (byte)rom[idx+1], currentPC + 2 + (byte)rom[idx+1]); len = 2; break;
                case 0x30: inst = "JR NC, " + String.format("%d (0x%04X)", (byte)rom[idx+1], currentPC + 2 + (byte)rom[idx+1]); len = 2; break;
                case 0x38: inst = "JR C, " + String.format("%d (0x%04X)", (byte)rom[idx+1], currentPC + 2 + (byte)rom[idx+1]); len = 2; break;
                case 0x18: inst = "JR " + String.format("%d (0x%04X)", (byte)rom[idx+1], currentPC + 2 + (byte)rom[idx+1]); len = 2; break;
                case 0xC3: inst = "JP 0x" + String.format("%02X%02X", rom[idx+2] & 0xFF, rom[idx+1] & 0xFF); len = 3; break;
                case 0xCD: inst = "CALL 0x" + String.format("%02X%02X", rom[idx+2] & 0xFF, rom[idx+1] & 0xFF); len = 3; break;
                case 0xC9: inst = "RET"; break;
                case 0xD9: inst = "RETI"; break;
                case 0xE6: inst = "AND 0x" + String.format("%02X", rom[idx+1] & 0xFF); len = 2; break;
                case 0xF6: inst = "OR 0x" + String.format("%02X", rom[idx+1] & 0xFF); len = 2; break;
                case 0xCB: {
                    int op2 = rom[idx+1] & 0xFF;
                    inst = "CB " + String.format("%02X", op2);
                    len = 2;
                    break;
                }
                default: inst = "DB 0x" + String.format("%02X", op); break;
            }
            System.out.format("0x%04X: ", currentPC);
            for (int i = 0; i < len; i++) {
                System.out.format("%02X ", rom[idx+i] & 0xFF);
            }
            for (int i = len; i < 3; i++) System.out.print("   ");
            System.out.println(inst);
            idx += len;
            currentPC += len;
        }
    }
}
