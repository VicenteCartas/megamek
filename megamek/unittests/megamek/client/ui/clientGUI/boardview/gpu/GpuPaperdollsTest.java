/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import megamek.client.ui.clientGUI.boardview.gpu.GpuPaperdoll.Cell;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPaperdoll.DamageTier;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPaperdoll.View;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.MiscType;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import megamek.common.units.FighterSquadron;
import megamek.common.units.Mek;
import megamek.common.units.TripodMek;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The converted paperdoll geometry (unit panel design 6.2, 14 U2): it parses without desktop classes, every anchor
 * picks its own location, a shield variant changes only its shield and arm, and real units of every family resolve to
 * a doll that covers their armored locations and their shields.
 */
class GpuPaperdollsTest {
    private static final String UNITS = "testresources/megamek/common/units/";
    private static final String MEK_FILES = "../../mm-data/data/mekfiles/";
    private static Path folder;
    private static GpuPaperdolls paperdolls;

    @BeforeAll
    static void locate() {
        // mm-data itself first: unit tests run without staging, so the staged copy under data/ may be stale.
        for (String candidate : List.of("../../mm-data/data/images/paperdolls", "data/images/paperdolls")) {
            if (Files.isRegularFile(Path.of(candidate, "biped-armor.json"))) {
                folder = Path.of(candidate);
                break;
            }
        }
        assertNotNull(folder, "No converted paperdolls in data/images/paperdolls");
        paperdolls = new GpuPaperdolls(folder);
    }

    @Test
    void everyFileParsesWithoutDesktopClasses() throws Exception {
        Class<?> type = Class.forName(GpuPaperdolls.class.getName(), true, new NoDesktop(getClass().getClassLoader()));
        Constructor<?> constructor = type.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        Object isolated = constructor.newInstance(folder);
        Method doll = type.getDeclaredMethod("doll", String.class, String.class, Set.class);
        doll.setAccessible(true);
        List<String[]> views = views();
        assertFalse(views.isEmpty());
        for (String[] view : views) {
            for (Set<String> arms : List.of(Set.<String>of(), Set.of("LA", "RA"))) {
                assertNotNull(doll.invoke(isolated, view[0], view[1], arms), view[0] + " " + view[1]);
            }
        }
    }

    @Test
    void everyAnchorPicksItsOwnLocationAndBothShieldPartsPickTheShield() {
        for (String[] view : views()) {
            for (Set<String> arms : List.of(Set.<String>of(), Set.of("LA", "RA"))) {
                GpuPaperdolls.Doll doll = paperdolls.doll(view[0], view[1], arms);
                assertFalse(doll.regions().isEmpty(), view[0] + " " + view[1]);
                for (GpuPaperdolls.Region region : doll.regions()) {
                    float[] anchor = region.anchor();
                    String expected = region.layer().equals(GpuPaperdolls.SHIELD_LAYER)
                          ? "DC" + region.code().substring(2) : region.code();
                    assertEquals(expected, doll.pick(anchor[0], anchor[1]),
                          view[0] + " " + view[1] + " " + arms + " " + region.layer() + " " + region.code());
                }
            }
        }
        assertNull(paperdolls.doll("biped", GpuPaperdolls.ARMOR, Set.of()).pick(-1000, -1000));
    }

    @Test
    void aShieldVariantChangesOnlyItsShieldAndArmAndHasAnOutlineAndSplit() {
        for (String family : List.of("biped", "tripod")) {
            GpuPaperdolls.Doll plain = paperdolls.doll(family, GpuPaperdolls.ARMOR, Set.of());
            assertTrue(plain.shields().isEmpty());
            assertTrue(codes(plain, GpuPaperdolls.SHIELD_LAYER).isEmpty());
            for (String arm : List.of("LA", "RA")) {
                GpuPaperdolls.Doll shielded = paperdolls.doll(family, GpuPaperdolls.ARMOR, Set.of(arm));
                for (GpuPaperdolls.Region region : plain.regions()) {
                    GpuPaperdolls.Region same = region(shielded, region.layer(), region.code());
                    if (region.code().equals(arm) && family.equals("biped")) {
                        // The biped art redraws the arm holding the shield; the tripod keeps its arm.
                        assertFalse(Arrays.equals(region.vertices(), same.vertices()), family + " " + arm);
                        continue;
                    }
                    assertArrayEquals(region.vertices(), same.vertices(), family + " " + arm + " " + region.code());
                    assertArrayEquals(region.triangles(), same.triangles(), family + " " + arm + " " + region.code());
                    assertArrayEquals(region.anchor(), same.anchor(), family + " " + arm + " " + region.code());
                }
                assertEquals(Set.of("DC" + arm, "DA" + arm), codes(shielded, GpuPaperdolls.SHIELD_LAYER));
                assertEquals(1, shielded.shields().size());
                GpuPaperdolls.Shield shield = shielded.shields().getFirst();
                assertEquals(arm, shield.arm());
                assertFalse(shield.outline().isEmpty());
                assertTrue(shield.outline().getFirst().length >= 6, "the outline is a ring");
                float[] split = shield.split();
                assertTrue(Math.hypot(split[2] - split[0], split[3] - split[1]) > 5, family + " " + arm + " split");
                // Where the strip is too thin for its number, both numbers share the capacity panel.
                for (float[] anchor : List.of(shield.capacityAnchor(), shield.absorptionAnchor())) {
                    assertEquals("DC" + arm, shielded.pick(anchor[0], anchor[1]), family + " " + arm);
                    assertTrue(anchor[2] >= 3, family + " " + arm + " panel anchor radius");
                }
                // The frame grows to take the shield in.
                assertTrue(shielded.bounds()[1] < plain.bounds()[1], family + " " + arm + " frame");
            }
            GpuPaperdolls.Doll both = paperdolls.doll(family, GpuPaperdolls.ARMOR, Set.of("LA", "RA"));
            assertEquals(Set.of("DCLA", "DALA", "DCRA", "DARA"), codes(both, GpuPaperdolls.SHIELD_LAYER));
            assertEquals(2, both.shields().size());
        }
    }

    @Test
    void oneRealUnitOfEachFamilyResolvesToADollCoveringItsArmoredLocations() throws Exception {
        Map<String, String> units = new LinkedHashMap<>();
        units.put(MEK_FILES + "meks/3050U/Atlas AS7-K.mtf", "biped");
        units.put(UNITS + "Shadow Hawk LAM SHD-X2.mtf", "biped");
        units.put(UNITS + "Barghest BGS-1T.mtf", "quad");
        units.put(UNITS + "Boreas C.mtf", "quadvee");
        units.put(UNITS + "Triskelion TRK-4V.mtf", "tripod");
        units.put(UNITS + "LRM Carrier.blk", "vehicle-noturret");
        units.put(UNITS + "Bulldog Medium Tank.blk", "vehicle-turret");
        units.put(UNITS + "Devastator II Superheavy Tank .blk", "vehicle-superheavy-noturret");
        units.put(UNITS + "Dromedary Water Transport.blk", "vehicle-superheavy-turret");
        units.put(UNITS + "Cobra Transport VTOL.blk", "vtol-noturret");
        units.put(MEK_FILES + "vehicles/3039u/Monitor Naval Vessel.blk", "naval-turret");
        units.put(MEK_FILES + "vehicles/3075/Hiryo Armored Infantry Transport.blk", "wige-noturret");
        units.put(UNITS + "Centaur.blk", "protomek-biped");
        units.put(UNITS + "Chippewa CHP-W7.blk", "fighter-aerospace");
        units.put(UNITS + "Boeing Jump Bomber.blk", "fighter-conventional");
        units.put(UNITS + "Mowang Courier (Clandestine).blk", "smallcraft-aerodyne");
        units.put(UNITS + "Union (3055).blk", "dropship-spheroid");
        units.put(UNITS + "Explorer JumpShip.blk", "jumpship");
        units.put(UNITS + "Aegis Heavy Cruiser (2372).blk", "warship");
        units.put(UNITS + "Crucible Station.blk", "spacestation");
        // Drawn as tiles, dots or a stat block (design 4.2).
        units.put(UNITS + "Elemental BA [Laser] (Sqd5).blk", "");
        units.put(UNITS + "Foot Platoon (AFFS) (Laser 3067+).blk", "");
        units.put(UNITS + "Medium Blaze Turret 3025.blk", "");
        units.put(UNITS + "Simple Building Entity.blk", "");
        units.put(UNITS + "TestHandheldWeapon.blk", "");
        for (Map.Entry<String, String> unit : units.entrySet()) {
            Entity entity = load(unit.getKey());
            String family = GpuPaperdolls.family(entity);
            assertEquals(unit.getValue(), family, unit.getKey());
            if (family.isEmpty()) {
                continue;
            }
            GpuPaperdolls.Doll armor = paperdolls.doll(family, GpuPaperdolls.ARMOR, Set.of());
            assertNotNull(armor, family);
            Set<String> drawn = codes(armor, null);
            for (int loc = 0; loc < entity.locations(); loc++) {
                if (entity.getOArmor(loc) > 0) {
                    assertTrue(drawn.contains(entity.getLocationAbbr(loc)), unit.getKey() + " lacks "
                          + entity.getLocationAbbr(loc) + " in " + drawn);
                }
            }
            if (entity instanceof Mek) {
                Set<String> rear = codes(paperdolls.doll(family, GpuPaperdolls.REAR, Set.of()), null);
                Set<String> structure = codes(paperdolls.doll(family, GpuPaperdolls.STRUCTURE, Set.of()), null);
                for (int loc = 0; loc < entity.locations(); loc++) {
                    if (entity.hasRearArmor(loc) && entity.getOArmor(loc, true) > 0) {
                        assertTrue(rear.contains(entity.getLocationAbbr(loc)), unit.getKey() + " rear");
                    }
                    if (entity.getOInternal(loc) > 0) {
                        assertTrue(structure.contains(entity.getLocationAbbr(loc)), unit.getKey() + " structure");
                    }
                }
            }
        }
        // A squadron is an aerospace fighter class but is drawn as tiles (design 4.2).
        assertEquals("", GpuPaperdolls.family(new FighterSquadron()), "squadron");
    }

    @Test
    void aMountedShieldSelectsItsArmsVariant() throws Exception {
        Entity centurion = load(MEK_FILES + "meks/3145/Davion/Centurion CN11-OD.mtf");
        assertEquals(Set.of("LA"), shieldArms(centurion));
        GpuPaperdolls.Doll doll = paperdolls.doll(GpuPaperdolls.family(centurion), GpuPaperdolls.ARMOR,
              shieldArms(centurion));
        assertEquals(Set.of("DCLA", "DALA"), codes(doll, GpuPaperdolls.SHIELD_LAYER));
        assertEquals("LA", doll.shields().getFirst().arm());

        Entity atlas = load(MEK_FILES + "meks/3050U/Atlas AS7-K.mtf");
        assertTrue(shieldArms(atlas).isEmpty());
        GpuPaperdolls.Doll plain = paperdolls.doll(GpuPaperdolls.family(atlas), GpuPaperdolls.ARMOR,
              shieldArms(atlas));
        assertTrue(plain.shields().isEmpty());
        assertTrue(codes(plain, GpuPaperdolls.SHIELD_LAYER).isEmpty());

        // A tripod fixture with a shield on its right arm: no published tripod mounts one.
        Entity tripod = load(UNITS + "Triskelion TRK-4V.mtf");
        tripod.addEquipment(EquipmentType.get("ISSmallShield"), TripodMek.LOC_RIGHT_ARM);
        assertEquals(Set.of("RA"), shieldArms(tripod));
        GpuPaperdolls.Doll tripodDoll = paperdolls.doll(GpuPaperdolls.family(tripod), GpuPaperdolls.ARMOR,
              shieldArms(tripod));
        assertEquals("tripod", tripodDoll.family());
        assertEquals(Set.of("DCRA", "DARA"), codes(tripodDoll, GpuPaperdolls.SHIELD_LAYER));
    }

    /** The arms that mount a shield: the location of every F_SHIELD mount, as the record's shields name them. */
    private static Set<String> shieldArms(Entity entity) {
        return entity.getMisc().stream().filter(mount -> mount.getType().hasFlag(MiscType.F_SHIELD))
              .map(mount -> entity.getLocationAbbr(mount.getLocation())).collect(Collectors.toSet());
    }

    private static Entity load(String file) throws Exception {
        return new MekFileParser(new File(file)).getEntity();
    }

    /** Every converted file as {family, view}. */
    private static List<String[]> views() {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".json")).sorted()
                  .map(name -> {
                      String base = name.substring(0, name.length() - ".json".length());
                      for (String view : List.of(GpuPaperdolls.REAR, GpuPaperdolls.STRUCTURE, GpuPaperdolls.ARMOR)) {
                          if (base.endsWith("-" + view)) {
                              return new String[] { base.substring(0, base.length() - view.length() - 1), view };
                          }
                      }
                      throw new IllegalStateException("Not a paperdoll view: " + name);
                  }).toList();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    /** The codes of a doll's regions in one layer, or in every layer for null. */
    private static Set<String> codes(GpuPaperdolls.Doll doll, String layer) {
        return doll.regions().stream().filter(region -> layer == null || region.layer().equals(layer))
              .map(GpuPaperdolls.Region::code).collect(Collectors.toCollection(TreeSet::new));
    }

    private static GpuPaperdolls.Region region(GpuPaperdolls.Doll doll, String layer, String code) {
        return doll.regions().stream().filter(region -> region.layer().equals(layer) && region.code().equals(code))
              .findFirst().orElseThrow(() -> new AssertionError(doll.family() + " lacks " + layer + " " + code));
    }

    /**
     * Defines GpuPaperdolls and its nested types itself and refuses them java.awt, javax.imageio and Batik, which
     * Android and iOS lack; every other class comes from the test's own loader.
     */
    private static final class NoDesktop extends ClassLoader {
        NoDesktop(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("java.awt.") || name.startsWith("javax.imageio.")
                  || name.startsWith("org.apache.batik.")) {
                throw new ClassNotFoundException("A desktop class: " + name);
            }
            String own = GpuPaperdolls.class.getName();
            if (!name.equals(own) && !name.startsWith(own + "$")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try (InputStream bytes = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        byte[] code = bytes.readAllBytes();
                        loaded = defineClass(name, code, 0, code.length);
                    } catch (IOException error) {
                        throw new ClassNotFoundException(name, error);
                    }
                }
                return loaded;
            }
        }
    }

    @Test
    void damageFillTiersAtTheCardThresholds() {
        assertEquals(DamageTier.INTACT, GpuPaperdoll.damageFill(34, 34, false));
        assertEquals(DamageTier.LIGHT, GpuPaperdoll.damageFill(33, 34, false));
        assertEquals(DamageTier.LIGHT, GpuPaperdoll.damageFill(71, 100, false));
        assertEquals(DamageTier.DAMAGED, GpuPaperdoll.damageFill(70, 100, false));
        assertEquals(DamageTier.DAMAGED, GpuPaperdoll.damageFill(41, 100, false));
        assertEquals(DamageTier.CRITICAL, GpuPaperdoll.damageFill(40, 100, false));
        assertEquals(DamageTier.CRITICAL, GpuPaperdoll.damageFill(1, 100, false));
        assertEquals(DamageTier.EXPOSED, GpuPaperdoll.damageFill(0, 47, false));
        assertEquals(DamageTier.DESTROYED, GpuPaperdoll.damageFill(0, 34, true));
        assertEquals(DamageTier.DESTROYED, GpuPaperdoll.damageFill(20, 34, true));
        assertEquals(DamageTier.NONE, GpuPaperdoll.damageFill(0, 0, false));
    }

    @Test
    void cellsPerView() {
        // The Atlas of the approved mockup: CT armor gone over 24 structure with a critical hit, LA destroyed.
        assertEquals(new Cell(DamageTier.EXPOSED, "(24)", true, false, 0), GpuPaperdoll.cell(0, 47, false, 24, true,
              View.CARD));
        assertEquals(new Cell(DamageTier.EXPOSED, "", true, false, 0), GpuPaperdoll.cell(0, 47, false, 24, true,
              View.ARMOR));
        assertEquals(new Cell(DamageTier.DESTROYED, GpuPaperdoll.CROSS, false, false, 0), GpuPaperdoll.cell(0, 34,
              true, 0, true, View.CARD));
        assertEquals(new Cell(DamageTier.DESTROYED, "", false, false, 0), GpuPaperdoll.cell(0, 34, true, 0, false,
              View.ARMOR));
        assertEquals(new Cell(DamageTier.DESTROYED, GpuPaperdoll.CROSS, false, false, 0), GpuPaperdoll.cell(0, 17,
              true, 0, false, View.STRUCTURE));
        // Armor and structure both gone is destroyed, even before MegaMek marks the location.
        assertEquals(DamageTier.DESTROYED, GpuPaperdoll.cell(0, 41, false, 0, false, View.CARD).tier());
        assertEquals(new Cell(DamageTier.CRITICAL, "12", true, false, 0), GpuPaperdoll.cell(12, 41, false, 21, true,
              View.ARMOR));
        assertEquals(new Cell(DamageTier.DAMAGED, "18", false, false, 0), GpuPaperdoll.cell(18, 32, false, 21, false,
              View.CARD));
        assertEquals(new Cell(DamageTier.NONE, "\u2014", false, false, 0), GpuPaperdoll.cell(0, 0, false, 5, false,
              View.CARD));
        assertEquals(new Cell(DamageTier.DAMAGED, "18", false, true, 5), GpuPaperdoll.cell(18, 32, false, 21, false,
              View.CARD).with(true, 5));
    }

    @Test
    void shieldCells() {
        // Shield (Medium): capacity 18, absorption 5 (MiscMounted base values).
        assertEquals(Map.of("DCLA", new Cell(DamageTier.DAMAGED, "11", false, false, 0), "DALA",
              new Cell(DamageTier.INTACT, "5", false, false, 0)), GpuPaperdoll.shieldCells("LA", true, 11, 18, 5, 5));
        // Capacity spent: one destroyed object, one cross in the panel and the destroyed fill in the strip.
        Map<String, Cell> destroyed = Map.of("DCRA", new Cell(DamageTier.DESTROYED, GpuPaperdoll.CROSS, false, false,
              0), "DARA", new Cell(DamageTier.DESTROYED, "", false, false, 0));
        assertEquals(destroyed, GpuPaperdoll.shieldCells("RA", true, 0, 25, 7, 7));
        // Not active (its arm blown off): MegaMek's getters still report full values.
        assertEquals(destroyed, GpuPaperdoll.shieldCells("RA", false, 25, 25, 7, 7));
        // No absorption left alone: the strip is destroyed, the panel keeps its number; never exposed.
        assertEquals(Map.of("DCLA", new Cell(DamageTier.CRITICAL, "4", false, false, 0), "DALA",
              new Cell(DamageTier.DESTROYED, "", false, false, 0)), GpuPaperdoll.shieldCells("LA", true, 4, 18, 0, 5));
    }
}
