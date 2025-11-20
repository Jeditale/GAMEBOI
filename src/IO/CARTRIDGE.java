package IO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CARTRIDGE {
    private int[] rom;
    private int romBank = 1;
    private int ramBank = 0;
    private boolean ramEnable = false;
    private int bankingMode = 0; // 0 = ROM Banking (default), 1 = RAM Banking
    private int[] externalRam = new int[0x8000];

    public int read(int address) {
        // Safety check to prevent crashes if the game tries to read past the end of the ROM
        if (address < 0 || address >= rom.length) {
            return 0xFF;
        }
        if (address >= 0x0000 && address <= 0x3FFF) {
            // Fixed ROM Bank 0 (Always reads from the start of the file)
            return rom[address];
        } else if (address >= 0x4000 && address <= 0x7FFF) {
            // Swappable ROM Banks 1-N
            int offset = (romBank * 0x4000) + (address - 0x4000);

            // Safety check (assuming you loaded the full ROM into your 'rom' array)
            if (offset < rom.length) {
                return rom[offset];
            }
        }
        else if (address >= 0xA000 && address <= 0xBFFF) {
            if (ramEnable) {
                // Calculate offset based on ramBank
                int offset = (ramBank * 0x2000) + (address - 0xA000);
                return externalRam[offset];
            }
            return 0xFF; // RAM disabled returns high impedance
        }
//        return rom[address];
        return 0xFF;
    }

    public void write(int address, int data) {
        if (address >= 0x0000 && address <= 0x1FFF) {
            // Area 1: RAM Enable/Disable (0x0000 - 0x1FFF)
            // Any value with 0x0A in the lower 4 bits enables RAM
            ramEnable = (data & 0x0F) == 0x0A;
        }
        else if (address >= 0x2000 && address <= 0x3FFF) {
            // Area 2: Low 5 Bits of ROM Bank Number (0x2000 - 0x3FFF)
            // Value 0 is mapped to 1. Mask ensures only 5 bits are used.
            int bank = data & 0x1F;
            if (bank == 0) bank = 1;

            // This sets the lower 5 bits of the ROM Bank number
            romBank = (romBank & 0xE0) | bank;
        }

        else if (address >= 0xA000 && address <= 0xBFFF) {
            if (ramEnable) {
                int offset = (ramBank * 0x2000) + (address - 0xA000);
                externalRam[offset] = data;
            }
        }

        else if (address >= 0x4000 && address <= 0x5FFF) {
            // Area 3: RAM Bank Number (0x4000 - 0x5FFF) or High 2 Bits of ROM Bank
            if (bankingMode == 1) {
                // RAM Banking Mode: Select RAM Bank (0-3)
                ramBank = data & 0x03;
            } else {
                // ROM Banking Mode: Select High 2 Bits of ROM Bank (bits 5-6)
                romBank = (romBank & 0x1F) | ((data & 0x03) << 5);
            }
        }
        else if (address >= 0x6000 && address <= 0x7FFF) {
            // Area 4: Banking Mode Select (0x6000 - 0x7FFF)
            // 0 = ROM Banking (default), 1 = RAM Banking
            bankingMode = data & 0x01;
        }
    }

    public void loadROM(String filePath) {
        if (filePath.isEmpty()) {
            throw new NullPointerException("File path is empty. CHECK THE PATH");
        }

        try {
            byte[] fileBytes = Files.readAllBytes(Path.of(filePath));
            System.out.println("File read. Size: " + fileBytes.length + " bytes.");

            // Initialize rom array to the EXACT size of the file
            rom = new int[fileBytes.length];

            // Copy bytes to int array (handling signed bytes correctly)
            for (int i = 0; i < fileBytes.length; i++) {
                rom[i] = fileBytes[i] & 0xFF;
            }

            System.out.println("Loaded " + rom.length + " bytes into Cartridge.");

        } catch (IOException e) {
            throw new RuntimeException("Failed to load ROM: " + e.getMessage());
        }
    }
}