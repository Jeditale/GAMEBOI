package IO;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CARTRIDGE implements java.io.Serializable {
    private int[] rom;
    private int romBank = 1;
    private int ramBank = 0;
    private boolean ramEnable = false;
    private int bankingMode = 0; // 0 = ROM Banking (default), 1 = RAM Banking
    private int[] externalRam = new int[0x8000]; // 32KB RAM

    // --- NEW SAVE LOGIC VARIABLES ---
    private boolean isRamDirty = false; // Tracks if RAM has changed
    private String currentSaveFile = null; // Where to save the .sav file

    public void setSavePath(String path) {
        this.currentSaveFile = path;
    }

    public int getRomBank() {
        return (bankingMode == 0) ? ((ramBank << 5) | romBank) : romBank;
    }

    public int read(int address) {
        if (address < 0 || address >= rom.length) return 0xFF;

        if (address >= 0x0000 && address <= 0x3FFF) {
            return rom[address];
        }
        else if (address >= 0x4000 && address <= 0x7FFF) {
            int totalBanks = rom.length / 0x4000;
            int mask = totalBanks - 1;

            int actualBank = ((bankingMode == 0) ? ((ramBank << 5) | romBank) : romBank) & mask;

            int offset = (actualBank * 0x4000) + (address - 0x4000);

            if (offset < rom.length) {
                return rom[offset];
            } else {
                return 0xFF;
            }
        }

        // 3. External RAM (0xA000 - 0xBFFF)
        else if (address >= 0xA000 && address <= 0xBFFF) {
            if (ramEnable && externalRam != null) {
                int actualRamBank = (bankingMode == 1) ? (ramBank & 0x03) : 0;
                int offset = (actualRamBank * 0x2000) + (address - 0xA000);
                if (offset < externalRam.length) return externalRam[offset];
            }
            return 0xFF;
        }
        return 0xFF;
    }

    public void write(int address, int data) {
        // Area 1: RAM Enable/Disable (0x0000 - 0x1FFF)
        if (address >= 0x0000 && address <= 0x1FFF) {
            boolean enableRequest = (data & 0x0F) == 0x0A;

            // --- HARDWARE SAVE TRIGGER ---
            if (ramEnable && !enableRequest && isRamDirty) {
                flushSave(); // Write to disk immediately
            }

            ramEnable = enableRequest;
        }

        // Area 2: ROM Bank Number
        else if (address >= 0x2000 && address <= 0x3FFF) {
            romBank = data & 0x1F;
            if (romBank == 0) romBank = 1;
        }

        // Area 3: RAM Bank / Upper ROM Bank
        else if (address >= 0x4000 && address <= 0x5FFF) {
            ramBank = data & 0x03;
        }

        // Area 4: Banking Mode Select
        else if (address >= 0x6000 && address <= 0x7FFF) {
            bankingMode = data & 0x01;
        }

        // WRITING TO RAM (0xA000 - 0xBFFF)
        else if (address >= 0xA000 && address <= 0xBFFF) {
            if (ramEnable && externalRam != null) {
                int actualRamBank = (bankingMode == 1) ? (ramBank & 0x03) : 0;
                int offset = (actualRamBank * 0x2000) + (address - 0xA000);
                if (offset < externalRam.length) {
                    externalRam[offset] = data;
                    isRamDirty = true; // Mark as dirty so we know to save later
                }
            }
        }
    }

    // --- SAVE / LOAD HELPERS ---

    private void flushSave() {
        if (currentSaveFile == null) return;

        try (FileOutputStream fos = new FileOutputStream(currentSaveFile)) {
            // Convert int[] to byte[]
            byte[] rawBytes = new byte[externalRam.length];
            for (int i = 0; i < externalRam.length; i++) {
                rawBytes[i] = (byte) externalRam[i];
            }
            fos.write(rawBytes);

            System.out.println("BATTERY SAVE FLUSHED TO: " + currentSaveFile);
            isRamDirty = false; // Reset flag
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void loadBatterySave(String savePath) {
        File f = new File(savePath);
        if (!f.exists()) return;

        // Set the path so we know where to save later
        this.currentSaveFile = savePath;

        try (FileInputStream fis = new FileInputStream(f)) {
            System.out.println("Loading Battery Save from: " + savePath);
            for (int i = 0; i < externalRam.length; i++) {
                int data = fis.read();
                if (data == -1) break;
                externalRam[i] = data;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void loadROM(String filePath) {
        if (filePath.isEmpty()) throw new NullPointerException("File path is empty.");
        try {
            byte[] fileBytes = Files.readAllBytes(Path.of(filePath));
            rom = new int[fileBytes.length];
            for (int i = 0; i < fileBytes.length; i++) {
                rom[i] = fileBytes[i] & 0xFF;
            }
            System.out.println("Loaded " + rom.length + " bytes.");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load ROM: " + e.getMessage());
        }
    }
}