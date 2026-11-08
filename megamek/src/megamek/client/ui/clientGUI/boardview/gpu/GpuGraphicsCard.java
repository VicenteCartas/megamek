/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.badlogic.gdx.utils.Os;
import com.badlogic.gdx.utils.SharedLibraryLoader;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.logging.MMLogger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.windows.WinBase;

/**
 * The graphics card the board asks for on a Windows computer with NVIDIA Optimus: exactly two cards, one of them
 * NVIDIA, such as a laptop with Intel and NVIDIA graphics. It asks for the NVIDIA card unless the integrated one is
 * saved. Optimus's process override, the SHIM_MCCOMPAT environment variable, only says "the NVIDIA card" or "the other
 * card", and only Optimus reads it, so other computers get no choice and draw with the card the system picks. Windows
 * ties a process to one card when the process creates its first OpenGL context, and nothing moves it afterwards, not
 * even closing and reopening the board; so a new choice applies from the next start of MegaMek. Everything here other
 * than the saved choice runs only on Windows.
 */
enum GpuGraphicsCard {
    /** The card Optimus pairs with the NVIDIA one, the integrated graphics on a laptop. */
    INTEGRATED("0x800000000"),
    /** The NVIDIA card. */
    DISCRETE("0x800000001");

    /** Overrides the saved choice for one run, as tests and benchmarks do: {@code -Dmegamek.gpu.card=DISCRETE}. */
    static final String PROPERTY = "megamek.gpu.card";
    private static final int NVIDIA = 0x10DE;
    private static final MMLogger LOGGER = MMLogger.create(GpuGraphicsCard.class);
    /** The choice this run applied before its first context; null until the board first opens. */
    private static volatile GpuGraphicsCard applied;
    /** {@link #cards()}, listed once per run; null until first asked. */
    private static volatile List<String> cards;

    private final String override;

    GpuGraphicsCard(String override) {
        this.override = override;
    }

    /** The card's name as Windows lists it. */
    @Override
    public String toString() {
        List<String> names = cards();
        return names.isEmpty() ? name() : names.get(ordinal());
    }

    /** Only Windows can ask for a card; the request is verified with NVIDIA Optimus. */
    static boolean supported() { return SharedLibraryLoader.os == Os.Windows; }

    /**
     * The names of the two cards Optimus switches between, INTEGRATED's then DISCRETE's, when this computer has
     * exactly two and one of them is NVIDIA; empty elsewhere, and on every system other than Windows. Ask only once
     * the board is open: the card is chosen before, and listing the cards must not take part in that choice.
     */
    static List<String> cards() {
        List<String> names = cards;
        if (names == null) { cards = names = supported() ? optimusCards() : List.of(); }
        return names;
    }

    /**
     * The choice for the next start: the system property's for this run, else the saved preference, else the NVIDIA
     * card.
     */
    static GpuGraphicsCard preferred() {
        String name = System.getProperty(PROPERTY, GUIPreferences.getInstance().getBoardGraphicsCard());
        for (GpuGraphicsCard card : values()) {
            if (card.name().equals(name == null ? "" : name.toUpperCase(Locale.ROOT))) { return card; }
        }
        return DISCRETE;
    }

    /** The choice in effect for this run; null before the board first opens. */
    static GpuGraphicsCard applied() { return applied; }

    /** The Optimus override this run asked for, for a process that must draw on the same card; else null. */
    static String override() { return applied != null && supported() ? applied.override : null; }

    /**
     * Asks for the preferred card once per run. Call it before the process's first OpenGL context, with LWJGL's
     * natives loaded; later calls do nothing, because the driver has made its choice by then.
     */
    static void apply() {
        if (applied != null) { return; }
        GpuGraphicsCard card = preferred();
        applied = card;
        if (!supported()) { return; }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Java's own environment is a copy; the driver reads the process's, which only the Windows call changes.
            long function = WinBase.GetProcAddress(WinBase.GetModuleHandle("kernel32.dll"), "SetEnvironmentVariableW");
            ByteBuffer name = stack.UTF16("SHIM_MCCOMPAT"), value = stack.UTF16(card.override);
            if (function == 0 || JNI.callPPI(MemoryUtil.memAddress(name), MemoryUtil.memAddress(value), function) == 0) {
                LOGGER.warn("3D board: could not ask for the {} graphics card", card.name());
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn(failure, "3D board: could not ask for the {} graphics card", card.name());
        }
    }

    /** Lists the hardware cards through DXGI, the list Windows' own graphics settings show. */
    private static List<String> optimusCards() {
        List<String> others = new ArrayList<>(), nvidia = new ArrayList<>();
        long dxgi = WinBase.LoadLibrary("dxgi.dll");
        if (dxgi == 0) { return List.of(); }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long create = WinBase.GetProcAddress(dxgi, "CreateDXGIFactory1");
            // IID_IDXGIFactory1, {770aae78-f26f-4dba-a829-253c83d1b387}; the long holds a8 29 25 3c 83 d1 b3 87.
            ByteBuffer iid = stack.malloc(16).putInt(0, 0x770aae78).putShort(4, (short) 0xf26f)
                  .putShort(6, (short) 0x4dba).putLong(8, 0x87b3d1833c2529a8L);
            PointerBuffer out = stack.mallocPointer(1);
            if (create == 0 || JNI.callPPI(MemoryUtil.memAddress(iid), MemoryUtil.memAddress(out), create) < 0) {
                return List.of();
            }
            long factory = out.get(0);
            // DXGI_ADAPTER_DESC1: a 128-character name, VendorId and three more UINTs, three SIZE_Ts, a LUID, Flags.
            int flags = 256 + 16 + 3 * Pointer.POINTER_SIZE + 8;
            ByteBuffer description = stack.calloc(flags + 8);
            try {
                // IDXGIFactory1::EnumAdapters1 is method 12; it fails with DXGI_ERROR_NOT_FOUND after the last card.
                for (int index = 0; JNI.callPPI(factory, index, MemoryUtil.memAddress(out), method(factory, 12)) >= 0;
                      index++) {
                    long adapter = out.get(0);
                    // IDXGIAdapter1::GetDesc1 is method 10.
                    boolean read = JNI.callPPI(adapter, MemoryUtil.memAddress(description), method(adapter, 10)) >= 0;
                    release(adapter);
                    // DXGI_ADAPTER_FLAG_SOFTWARE marks the Microsoft Basic Render Driver.
                    if (read && (description.getInt(flags) & 2) == 0) {
                        (description.getInt(256) == NVIDIA ? nvidia : others)
                              .add(MemoryUtil.memUTF16(MemoryUtil.memAddress(description)));
                    }
                }
            } finally {
                release(factory);
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn(failure, "3D board: could not list the graphics cards");
            return List.of();
        } finally {
            WinBase.FreeLibrary(dxgi);
        }
        LOGGER.info("3D board: graphics cards: NVIDIA {}, other {}", nvidia, others);
        return others.size() == 1 && nvidia.size() == 1 ? List.of(others.getFirst(), nvidia.getFirst()) : List.of();
    }

    /** The address of a COM object's method, by its place in the object's method table. */
    private static long method(long object, int index) {
        return MemoryUtil.memGetAddress(MemoryUtil.memGetAddress(object) + (long) index * Pointer.POINTER_SIZE);
    }

    /** IUnknown::Release, method 2. */
    private static void release(long object) {
        JNI.callPI(object, method(object, 2));
    }
}
