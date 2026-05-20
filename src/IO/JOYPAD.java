package IO;

import manager.MMU;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;

public class JOYPAD implements KeyListener , Serializable {

    private MMU mmu;

    // 0 = Pressed, 1 = Released (Game Boy logic is inverted!)
    private transient int actionButtons = 0x0F; // Start, Select, B, A (Lower 4 bits)
    private transient int directionButtons = 0x0F; // Down, Up, Left, Right (Lower 4 bits)

    // This holds the value the CPU wrote to 0xFF00 to select which row to read
    private int joypadReg = 0xFF;
    public void fetchMMU(MMU mmu) {
        this.mmu = mmu;
    }

    /**
     * CPU writes to 0xFF00 to select which buttons it wants to read.
     * Bit 4 = 0 -> Select Directions
     * Bit 5 = 0 -> Select Actions
     */
    public void write(int data) {
        // We only care about bits 4 and 5. The rest are read-only or unused.
        joypadReg = (joypadReg & 0xCF) | (data & 0x30);
    }

    /**
     * CPU reads 0xFF00 to get the button states.
     */
    public int read() {
        int result = 0xC0 | (joypadReg & 0x30) | 0x0F;

        // If Bit 4 is 0, CPU wants Direction Keys
        if ((joypadReg & 0x10) == 0) {
            result &= (directionButtons | 0xF0);
        }

        // If Bit 5 is 0, CPU wants Action Keys
        if ((joypadReg & 0x20) == 0) {
            result &= (actionButtons | 0xF0);
        }

        return result;
    }

    @Override
    public void keyTyped(KeyEvent e) { }

    @Override
    public void keyPressed(KeyEvent e) {
        updateState(e.getKeyCode(), false); // false = pressed (0)
    }

    @Override
    public void keyReleased(KeyEvent e) {
        updateState(e.getKeyCode(), true); // true = released (1)
    }
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject(); // Read the normal stuff (joypadReg, mmu reference)

        // 3. Manually reset buttons to "Released" (0x0F)
        // If we don't do this, they default to 0 (which means PRESSED on Game Boy!)
        this.actionButtons = 0x0F;
        this.directionButtons = 0x0F;
    }
    private void updateState(int keyCode, boolean isReleased) {
        int bit = isReleased ? 1 : 0;
        int mask;

        // Mapping:
        // Z=A, X=B, Enter=Start, Space=Select
        // Arrows = D-Pad

        // If a button is pressed (isReleased == false), request an interrupt.
        if (!isReleased && mmu != null) {
            int if_flag = mmu.read(0xFF0F);
            mmu.write(0xFF0F, if_flag | 0x10); // Bit 4 is the Joypad interrupt
        }

        switch (keyCode) {
            // Direction Buttons
            case KeyEvent.VK_RIGHT:
                mask = 1 << 0;
                directionButtons = isReleased ? (directionButtons | mask) : (directionButtons & ~mask);
                break;
            case KeyEvent.VK_LEFT:
                mask = 1 << 1;
                directionButtons = isReleased ? (directionButtons | mask) : (directionButtons & ~mask);
                break;
            case KeyEvent.VK_UP:
                mask = 1 << 2;
                directionButtons = isReleased ? (directionButtons | mask) : (directionButtons & ~mask);
                break;
            case KeyEvent.VK_DOWN:
                mask = 1 << 3;
                directionButtons = isReleased ? (directionButtons | mask) : (directionButtons & ~mask);
                break;

            // Action Buttons
            case KeyEvent.VK_Z: // A Button
                mask = 1 << 0;
                actionButtons = isReleased ? (actionButtons | mask) : (actionButtons & ~mask);
                break;
            case KeyEvent.VK_X: // B Button
                mask = 1 << 1;
                actionButtons = isReleased ? (actionButtons | mask) : (actionButtons & ~mask);
                break;
            case KeyEvent.VK_SPACE: // Select
                mask = 1 << 2;
                actionButtons = isReleased ? (actionButtons | mask) : (actionButtons & ~mask);
                break;
            case KeyEvent.VK_ENTER: // Start
                mask = 1 << 3;
                actionButtons = isReleased ? (actionButtons | mask) : (actionButtons & ~mask);
                break;
        }
    }
}