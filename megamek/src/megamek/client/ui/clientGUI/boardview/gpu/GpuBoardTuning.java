/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.gdx.UiSliderInput;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;

/**
 * The board view's presentation settings as a model of Scene2D controls: the rows of a Board and an Atmosphere page,
 * and Defaults. Each control holds its value and applies it when set; GpuTuningPanel shows the rows in the hud look
 * and never draws these widgets. Geometry is shared with picking; the other settings belong to this GPU window.
 */
final class GpuBoardTuning {
    private static final float SLIDER_WIDTH = 120;
    private static final float LABEL_WIDTH = 120;

    private record Knob(String name, float min, float max, float step, String format, String help) {
        Knob(String name, float min, float max, float step, String format) {
            this(name, min, max, step, format, "");
        }
    }
    private record Control(Knob knob, Slider slider, Label reading, TextButton toggle) { }

    /** One row of the panel: the board value it drives, its range and how its reading is written. */
    private static final List<Knob> KNOBS = List.of(
          new Knob("Hex scale", 0.5f, 3f, 0.05f, "%.2f"),
          new Knob("Unit scale", 0.25f, 3f, 0.05f, "%.2f"),
          new Knob("Unit height scale", 0.1f, 2f, 0.05f, "%.2f"),
          new Knob("Base level height", 4, 40, 1, "%.0f"),
          // A value of one hides the grid.
          new Knob("Hex frame shade", 0f, 1f, 0.05f, "%.2f"),
          new Knob("Multi-hex unit scale", 0.25f, 1.5f, 0.05f, "%.2f"));
    /** The Terrain page's hex padding, beside Hex transitions, which it replaces while on. */
    private static final Knob PADDING = new Knob("Hex padding (m)", 0, BoardGeometry.MAX_PADDING, 0.5f, "%.1f",
          "Gap the board opens between neighbouring hexes, in metres; each hex keeps its size. Where levels match the ground "
          + "runs on through the gap; where they differ the gap holds a slope up to two levels and a cliff above its talus "
          + "from three. Replaces hex transitions while on. Visual only; the game's levels and hexes are unchanged.");

    private static final List<Knob> LIGHTING_KNOBS = List.of(
          // One-minute steps, the same grid scenario times are chosen on (BoardAtmosphere.hourInWindow).
          new Knob("Time of day", 0, 24, 1f / 60, "clock"),
          new Knob("Exposure (EV)", -2, 2, 0.1f, "%+.1f"));

    private static final List<Knob> ATMOSPHERE_KNOBS = List.of(
          new Knob("Cloud cover", 0, 1, 0.01f, "%.2f"),
          new Knob("Ground fog", 0, 1, 0.01f, "%.2f"),
          new Knob("Ground layer height", BoardAtmosphere.MIN_GROUND_LAYER_HEIGHT, 8, 0.25f, "%.2f"),
          new Knob("Haze", 0, 1, 0.05f, "%.2f"));

    private static final List<Knob> VISIBILITY_KNOBS = List.of(
          new Knob("Building opacity", 0, 100, 5, "%.0f%%"),
          new Knob("See-through", 0, 100, 5, "%.0f%%"));

    private static final List<Knob> FOV_KNOBS = List.of(
          new Knob("FoV darkness", 0, 100, 5, "%.0f%%"));

    private static final List<Knob> SENSOR_KNOBS = List.of(
          new Knob("Sensor darkness", 0, 100, 5, "%.0f%%"));

    private static final List<Knob> EFFECT_KNOBS = List.of(
          new Knob("Rain", 0, 1, 0.05f, "%.2f"),
          new Knob("Snow", 0, 1, 0.05f, "%.2f"),
          new Knob("Hail", 0, 1, 0.05f, "%.2f"),
          new Knob("Blowing sand", 0, 1, 0.05f, "%.2f"),
          new Knob("Lightning", 0, 1, 0.05f, "%.2f"),
          new Knob("Wind strength", 0, 1, 0.05f, "%.2f"),
          new Knob("Wind direction", 0, 360, 15, "%.0f"));

    /** Construction cursor only; each page owns its own rows. */
    private Table rows = new Table();
    private final Table cameraRows;
    private final Table display;
    private final Table general;
    private final Table atmospheric;
    private final Table terrain;
    private final TextButton reset;
    private final BoardCamera camera;
    private final CheckBox firstPerson;
    private final CheckBox wireframe;
    private final CheckBox dramaticDeath;
    private final List<Control> cameraFieldOfView;
    private final CheckBox normalMaps;
    private final CheckBox terrainParallax;
    private final CheckBox terrainCache;
    private final CheckBox terrainCacheDebug;
    private final Label terrainCacheLegend;
    private final Label terrainCacheState;
    private final Label terrainCacheDetails;
    private Supplier<GpuMaterialCache.Status> terrainCacheStatus = () -> null;
    private GpuMaterialCache.Status shownTerrainCache;
    private final SelectBox<UnitDisplayMode> unitDisplayMode;
    private final CheckBox vsync;
    /** Windowed or Fullscreen; the view switches the window and shows its state here every frame. */
    private final SelectBox<String> displayMode;
    private Consumer<Boolean> displayModeChange = fullscreen -> { };
    private GpuAtmosphereControls.Editor conditionsEditor;
    private Consumer<Runnable> materialPalette;
    private final List<Control> renderResolution;
    private final Label renderSize;
    /** The window's physical pixels, which the render resolution's scene size is shown for. */
    private int windowWidth, windowHeight;
    private final List<Control> uiScale;
    /** The UI scale last saved: the slider's value once no button is held (see {@link #saveUiScale()}). */
    private float uiScaleSaved;
    private final SelectBox<BoardQuality> quality;
    private final SelectBox<String> antiAliasing;
    private final SelectBox<String> shadows;
    private final CheckBox specularAntialiasing;
    private final CheckBox tessellation;
    private final CheckBox ambientOcclusion;
    private final CheckBox depthCues;
    private final CheckBox vignette;
    /** The graphics card rows; null on computers without two cards to choose from. */
    private SelectBox<GpuGraphicsCard> graphicsCard;
    private Label cardInUse;
    private Label cardPending;
    private final List<Control> geometry;
    /** The hex padding slider on the Terrain page; null while the panel builds the pages before it. */
    private List<Control> padding;
    private final CheckBox transitions;
    private final CheckBox grass;
    private final CheckBox waterEffects;
    private final Label terrainProgress;
    private int terrainPercent = -1;
    private final CheckBox cliffsIntoWater;
    private final CheckBox containmentWalls;
    private final SelectBox<BoardRelief.StepOutline> stepOutline;
    private final List<Control> familySizes;
    private final CheckBox zoomScaling;
    private final List<Control> zoomScale;
    private final CheckBox routePulse;
    private final List<Control> pulse;
    private final CheckBox ecmPulse;
    private final List<Control> ecmPulseKnobs;
    private final List<Control> visibility;
    private final ButtonGroup<TextButton> fovModes;
    private final List<Control> fieldOfView;
    private final ButtonGroup<TextButton> sensorModes;
    private final List<Control> sensors;
    private final List<Control> daylight;
    private final List<Control> colorGrading;
    private final List<Control> lightBalance;
    private GpuLightingEnvironment environmentImage;
    private int environmentRequest;
    private TextButton chooseEnvironment;
    private Runnable environmentChooser = () -> { };
    /** Show the sky that lights the scene as the backdrop; not saved. */
    private final CheckBox lightingSky;
    private Label environmentStatus;
    private final CheckBox moonlight;
    private final SelectBox<Atmosphere> pressure;
    private final SelectBox<AtmosphericTaint> atmosphericTaint;
    private final List<Control> temperature;
    private final List<Control> gravity;
    private final List<Control> weather;
    private final CheckBox fixedSun;
    private final List<Control> effects;
    private final List<Control> rendering;
    private final CheckBox overrideDamage;
    private final SelectBox<UnitDamageDisplay.Location> damageLocation;
    private final List<Control> damage;
    private BoardAtmosphere.Settings atmosphere = BoardAtmosphere.DEFAULTS;
    private BoardAtmosphere.Settings lastScenario;
    private boolean conditionsPreview;
    private boolean syncing;
    private final TextButton reloadAssets;
    private final Label assetReloadStatus;
    private String assetReloadPhase = "";
    private String assetReloadFile = "";
    private float assetReloadFraction = -1;
    private final TextButton editShaders;
    private boolean assetReloadRequested;

    GpuBoardTuning(Skin skin) {
        this(skin, null);
    }

    GpuBoardTuning(Skin skin, BoardSource source) {
        this(skin, source, new BoardCamera());
    }

    GpuBoardTuning(Skin skin, BoardSource source, BoardCamera camera) {
        this.camera = camera;
        cameraRows = rows;
        rows.top().defaults().pad(0, 3, 0, 3);
        dramaticDeath = checkbox(skin, Messages.getString("GpuBoard.dramaticDeath"), "camera-dramatic-death");
        dramaticDeath.setChecked(GpuBattleView.DRAMATIC_DEATH);
        dramaticDeath.addListener(new TextTooltip(Messages.getString("GpuBoard.dramaticDeathHelp"), skin, "menu"));
        firstPerson = checkbox(skin, "Free Flight", "tuning-free-flight");
        firstPerson.addListener(new TextTooltip("Fly freely: WASD moves, Q/E lowers/raises, Shift speeds up, "
              + "right drag pans, middle drag looks around, Shift swaps the drags, and the wheel moves forward/back. "
              + "Turn off to restore the tactical view.", skin, "menu"));
        cameraFieldOfView = controls(skin, List.of(new Knob("Vertical FOV",BoardCamera.MIN_FIELD_OF_VIEW,
              BoardCamera.MAX_FIELD_OF_VIEW, 1, "%.0f\u00b0")), this::applyCamera, 0);
        cameraFieldOfView.getFirst().slider().setName("tuning-camera-fov");
        cameraFieldOfView.getFirst().slider().addListener(new TextTooltip(
              "Vertical field of view in degrees. Larger angles show more of the board. Requires Free Flight.", skin, "menu"));
        firstPerson.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (!syncing) { applyCamera(); }
            }
        });
        // Read by the board view each frame; the HUD's wireframe utility switches the same box.
        wireframe = checkbox(skin, Messages.getString("GpuBoard.wireframe"), "camera-wireframe");
        wireframe.addListener(new TextTooltip(Messages.getString("GpuBoard.wireframeHelp"), skin, "menu"));
        wireframe.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                // The wireframe and the Tactical View exclude each other: it leaves the view.
                if (wireframe.isChecked()) { camera.setTactical(false, null); }
            }
        });
        general = rows = new Table();
        rows.top().defaults().pad(0, 3, 0, 3);
        section(skin, "Geometry");
        geometry = controls(skin, KNOBS, this::applyGeometry, 0);
        unitDisplayMode = choice(skin, "Unit display", "tuning-unit-display", UnitDisplayMode.values(), () -> { });
        unitDisplayMode.addListener(new TextTooltip("All Meeples: troops use meeple figures, every other unit an extruded artwork token. "
              + "Mek Meeples: only Meks use tokens; other units use 3D models. "
              + "3D Models: use the existing models for all units.", skin, "menu"));
        // The Display page: the window's output, from its mode to the graphics card.
        display = rows = new Table();
        rows.top().defaults().pad(0, 3, 0, 3);
        quality = choice(skin, "Quality", "tuning-quality", BoardQuality.values(), this::applyQuality);
        // The saved choice; like anti-aliasing, Defaults leaves it alone.
        syncing = true;
        quality.setSelected(BoardQuality.parse(GUIPreferences.getInstance().getBoardQuality()));
        syncing = false;
        quality.addListener(new TextTooltip("Medium shades the board with cheaper light and materials, coarser distant "
              + "terrain and fewer grass blades, for a higher frame rate on a similar look. Low does the same with no "
              + "grass blades at all and a quarter fewer plants in fields. Both leave out high shadows, specular "
              + "anti-aliasing, terrain parallax, tessellation and ambient occlusion; High brings your choices back. "
              + "Switching to or from High rebuilds the board's shaders. The choice is saved for the next session.", skin, "menu"));
        displayMode = choice(skin, "Display mode", "tuning-display-mode", new String[] { "Windowed", "Fullscreen" },
              this::applyDisplayMode);
        vsync = checkbox(skin, "VSync", "tuning-vsync");
        // The window's own preference, shown once here and then left to the user; Defaults never touches it.
        vsync.setChecked(GpuBoardWindow.DEFAULT_VSYNC);
        vsync.addListener(new TextTooltip("Synchronize with the monitor's refresh rate. Off leaves rendering uncapped.",
              skin, "menu"));
        vsync.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                Gdx.graphics.setVSync(vsync.isChecked());
                Gdx.graphics.setForegroundFPS(0);
            }
        });
        renderResolution = controls(skin, List.of(new Knob("Render resolution", 50, 100, 10, "%.0f%%")),
              this::applyRenderResolution, 0);
        Slider resolution = renderResolution.getFirst().slider();
        resolution.setName("tuning-render-resolution");
        resolution.addListener(new TextTooltip("The 3D scene renders at this share of the window's pixels and is then "
              + "scaled up to the window: lower is faster and softer. Overlays, labels and the HUD keep the window's "
              + "resolution. The choice is saved for the next session.", skin, "menu"));
        renderSize = new Label("", skin, "small");
        renderSize.setName("tuning-render-size");
        rows.add(renderSize).colspan(3).left().height(18).row();
        uiScale = controls(skin, List.of(new Knob("UI scale", 50, 100, 5, "%.0f%%")), this::applyUiScale, 0);
        Slider scale = uiScale.getFirst().slider();
        scale.setName("tuning-ui-scale");
        scale.addListener(new TextTooltip("The size of the HUD and the board's labels, as a share of the size the "
              + "window takes from the display scale and its own size. The board's zoom follows. A drag applies when "
              + "released; the choice is saved for the next session.", skin, "menu"));
        antiAliasing = choice(skin, "Anti-aliasing", "tuning-anti-aliasing", new String[] { "Off", "SMAA" },
              this::applyAntiAliasing);
        // The saved choice (Off until one is saved). Like the graphics card it persists, and Defaults leaves it alone.
        String savedAntiAliasing = GUIPreferences.getInstance().getBoardAntiAliasing();
        syncing = true;
        antiAliasing.setSelectedIndex("SMAA".equalsIgnoreCase(savedAntiAliasing) ? 1 : 0);
        syncing = false;
        antiAliasing.addListener(new TextTooltip("SMAA smooths the jagged edges of the 3D scene after its lighting and "
              + "atmosphere, before overlays, labels and the HUD. Off shows the scene as rendered. The choice is "
              + "saved for the next session.", skin, "menu"));
        shadows = choice(skin, "Shadows", "tuning-shadows", new String[] { "Normal", "High" }, this::applyShadows);
        // The saved choice (Normal until one is saved); like anti-aliasing, Defaults leaves it alone.
        syncing = true;
        shadows.setSelectedIndex("HIGH".equalsIgnoreCase(GUIPreferences.getInstance().getBoardShadows()) ? 1 : 0);
        syncing = false;
        shadows.addListener(new TextTooltip("The sun's shadow map follows the screen: about one shadow texel to a "
              + "rendered pixel. High doubles that for sharper shadow edges under rims, rocks and plants; it uses more "
              + "video memory and takes longer to redraw the shadows when the view zooms. The choice is saved for the "
              + "next session.", skin, "menu"));
        specularAntialiasing = checkbox(skin, "Specular anti-aliasing", "tuning-specular-anti-aliasing");
        // The saved choice (on until one is saved); like anti-aliasing, Defaults leaves it alone.
        specularAntialiasing.setChecked(GUIPreferences.getInstance().getBoardSpecularAntiAliasing());
        GpuEnvironmentLighting.specularAntialiasing = specularAntialiasing.isChecked();
        specularAntialiasing.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                boolean enabled = specularAntialiasing.isChecked();
                GpuEnvironmentLighting.specularAntialiasing = enabled;
                SwingUtilities.invokeLater(() -> GUIPreferences.getInstance().setBoardSpecularAntiAliasing(enabled));
            }
        });
        normalMaps = checkbox(skin, "Normal maps", "tuning-normal-maps");
        // The saved choice (on until one is saved); like anti-aliasing, Defaults leaves it alone.
        normalMaps.setChecked(GUIPreferences.getInstance().getBoardNormalMaps());
        normalMaps.addListener(new TextTooltip("Shades surfaces with their materials' fine relief, and gives buildings,"
              + " props and roads their parallax. Off is flatter and a little faster. The choice is saved for the next"
              + " session.", skin, "menu"));
        normalMaps.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                boolean enabled = normalMaps.isChecked();
                SwingUtilities.invokeLater(() -> GUIPreferences.getInstance().setBoardNormalMaps(enabled));
            }
        });
        terrainParallax = checkbox(skin, "Terrain Parallax", "tuning-terrain-parallax");
        // The saved choice (on until one is saved); like anti-aliasing, Defaults leaves it alone.
        terrainParallax.setChecked(GUIPreferences.getInstance().getBoardTerrainParallax());
        terrainParallax.addListener(new TextTooltip("Moves the sculpted terrain's materials with the view by their"
              + " heightmaps, so stones, cracks and joints stand out of the ground as the camera turns. Independent of"
              + " Normal maps; off is a little faster on rocky ground and cliffs. The choice is saved for the next"
              + " session.", skin, "menu"));
        terrainParallax.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                boolean enabled = terrainParallax.isChecked();
                SwingUtilities.invokeLater(() -> GUIPreferences.getInstance().setBoardTerrainParallax(enabled));
            }
        });
        tessellation = checkbox(skin, "Tessellation", "tuning-tessellation");
        // The saved choice (off until one is saved); like anti-aliasing, Defaults leaves it alone.
        tessellation.setChecked(GUIPreferences.getInstance().getBoardTessellation());
        // OpenGL 4.0 and later: every Mac's 4.1, current Windows and Linux drivers' 4.6.
        tessellation.setDisabled(!GpuTessellation.available());
        tessellation.addListener(new TextTooltip("Rounds the sculpted banks and cliffs between their mesh points, so"
              + " their outlines and the shadows they cast curve as the ground does. Up close it costs an integrated"
              + " graphics chip a few milliseconds a frame. Needs OpenGL 4.0. Switching it rebuilds the board. The"
              + " choice is saved for the next session.", skin, "menu"));
        tessellation.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                boolean enabled = tessellation.isChecked();
                SwingUtilities.invokeLater(() -> GUIPreferences.getInstance().setBoardTessellation(enabled));
            }
        });
        ambientOcclusion = checkbox(skin, "Ambient occlusion", "tuning-ambient-occlusion");
        // The saved choice (off until one is saved); like anti-aliasing, Defaults leaves it alone.
        ambientOcclusion.setChecked(GUIPreferences.getInstance().getBoardAmbientOcclusion());
        ambientOcclusion.addListener(new TextTooltip("Darkens the sky light where the ground and the trees hide the sky:"
              + " cliff feet, hollows, the ground around trunks. Sunlight is unchanged; buildings and units take no part."
              + " Switching it rebuilds the board's shaders. The choice is saved for the next session.", skin, "menu"));
        ambientOcclusion.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                boolean enabled = ambientOcclusion.isChecked();
                SwingUtilities.invokeLater(() -> GUIPreferences.getInstance().setBoardAmbientOcclusion(enabled));
            }
        });
        depthCues = checkbox(skin, "Depth cues", "tuning-depth-cues");
        // The saved choice (off until one is saved); like anti-aliasing, Defaults leaves it alone.
        depthCues.setChecked(GUIPreferences.getInstance().getBoardDepthCues());
        depthCues.addListener(new TextTooltip("Separates nearer terrain from farther terrain without fog: a light"
              + " haze over what lies behind the middle of the view, following the camera. The Tactical View keeps"
              + " clear air. The choice is saved for the next session.", skin, "menu"));
        depthCues.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                boolean enabled = depthCues.isChecked();
                SwingUtilities.invokeLater(() -> GUIPreferences.getInstance().setBoardDepthCues(enabled));
            }
        });
        vignette = checkbox(skin, "Vignette", "tuning-vignette");
        // The saved choice (off until one is saved); like anti-aliasing, Defaults leaves it alone.
        vignette.setChecked(GUIPreferences.getInstance().getBoardVignette());
        vignette.addListener(new TextTooltip("Darkens the corners of the view a little, as a camera lens does. Prints"
              + " never take it. The choice is saved for the next session.", skin, "menu"));
        vignette.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                boolean enabled = vignette.isChecked();
                SwingUtilities.invokeLater(() -> GUIPreferences.getInstance().setBoardVignette(enabled));
            }
        });
        Label graphics = new Label("Graphics: " + GpuGlsl.description(), skin, "small");
        graphics.setName("tuning-graphics");
        graphics.addListener(new TextTooltip("Detected when the board opens: the board compiles its shaders for the newest "
              + "shading language the graphics driver offers, from GLSL 3.30 up to 4.60.", skin, "menu"));
        rows.add(graphics).colspan(3).left().height(18).row();
        if (!GpuGraphicsCard.cards().isEmpty()) {
            graphicsCard = choice(skin, "Graphics card", "tuning-graphics-card", GpuGraphicsCard.values(),
                  this::applyGraphicsCard);
            syncing = true;
            graphicsCard.setSelected(GpuGraphicsCard.preferred());
            syncing = false;
            graphicsCard.addListener(new TextTooltip("The card the board draws with. Windows keeps a program on the "
                  + "card it started with, so a change applies the next time MegaMek starts.", skin, "menu"));
            cardInUse = new Label("", skin, "small");
            cardInUse.setName("tuning-graphics-card-in-use");
            cardInUse.setEllipsis(true);
            rows.add(cardInUse).colspan(3).left().minWidth(0).growX().height(18).row();
            cardPending = new Label("Applies when MegaMek next starts", skin, "small");
            cardPending.setName("tuning-graphics-card-pending");
            rows.add(cardPending).colspan(3).left().height(18).row();
            updateGraphicsCard();
        }
        section(skin, "Terrain cache");
        terrainCache = newCheckbox(skin, "Enable terrain cache", "tuning-terrain-cache");
        rows.add(terrainCache).colspan(2).left().height(20);
        terrainCacheDebug = newCheckbox(skin, "Debug", "tuning-terrain-cache-debug");
        rows.add(terrainCacheDebug).left().height(20).row();
        terrainCacheDebug.addListener(new TextTooltip("Tint sculpted ground by the material actually drawn: red"
              + " means direct rendering; other colours identify the cache level. L0 is finest; each next level doubles"
              + " the ground texel size. Water surfaces and objects keep their normal appearance. This switch starts"
              + " off and is never saved.", skin, "menu"));
        terrainCache.setChecked(GpuMaterialCache.ENABLED && GpuMaterialCache.SPLIT);
        terrainCache.setDisabled(!GpuMaterialCache.ENABLED || !GpuMaterialCache.SPLIT);
        terrainCache.addListener(new TextTooltip("Live A/B comparison: off draws ground materials directly and pauses"
              + " cache fills. Allocated memory and cached pages are retained for switching back. This session only;"
              + " startup materialCache/materialCacheSplit overrides still apply.", skin, "menu"));
        terrainCacheState = new Label("Waiting for terrain", skin, "small");
        terrainCacheState.setName("tuning-terrain-cache-state");
        rows.add(terrainCacheState).colspan(3).growX().row();
        terrainCacheDetails = new Label("", skin, "small");
        terrainCacheDetails.setName("tuning-terrain-cache-details");
        terrainCacheDetails.addListener(new TextTooltip("GPU allocation is estimated from texture formats, mip levels,"
              + " build targets and page tables; driver overhead and other terrain resources are excluded. Occupied"
              + " slots include spare pages used during rebuilds. Progress counts current pages admitted for this view,"
              + " not the whole board or elapsed rebuild time. A terrain chunk uses the cache only when all its pages"
              + " are readable. Pages outside capacity or table coverage draw directly."
              + " Stale pages outside the view wait until needed again.", skin, "menu"));
        rows.add(terrainCacheDetails).colspan(3).growX().row();
        terrainCacheLegend = new Label("", skin, "small");
        terrainCacheLegend.setName("tuning-terrain-cache-legend");
        rows.add(terrainCacheLegend).colspan(3).growX().row();
        rows = general;
        section(skin, "Unit family sizes");
        familySizes = controls(skin, Arrays.stream(UnitFamilyScale.values())
              .map(family -> new Knob(family.label, 0.25f, 3, 0.05f, "%.2f")).toList(), this::applyFamilySizes, 0);
        for (int index = 0; index < familySizes.size(); index++) {
            var slider = familySizes.get(index).slider();
            slider.setName("tuning-size-" + UnitFamilyScale.values()[index].name());
            slider.addListener(new TextTooltip("Uniform size multiplier; 1.00 draws the authored size. "
                  + "Infantry and battle armor start at 1.80: their canonical figures, drawn larger to read "
                  + "on the board. Stacks with Unit scale. Mek weight classes also multiply All Meks; "
                  + "ultralight Meks use Light Meks.", skin, "menu"));
        }
        section(skin, "Zoom-out unit scaling");
        zoomScaling = checkbox(skin, "Scale units up when zoomed out", "tuning-zoom-scaling");
        zoomScaling.addListener(new TextTooltip("Zoomed out past the threshold, 3D units grow on every axis so they "
              + "keep their size on screen, up to the maximum; picking and labels follow. Tactical View icons keep "
              + "their size in the hex.", skin, "menu"));
        zoomScale = controls(skin, List.of(new Knob("Threshold hex px", 16, 256, 2, "%.0f"),
              new Knob("Max zoom-out scale", 1, 4, 0.05f, "%.2f")), this::applyZoomScaling, 0);
        zoomScale.getFirst().slider().addListener(new TextTooltip("Units grow while a hex is narrower on screen than "
              + "this many HUD pixels (window pixels over the display scale): half that width doubles them.", skin,
              "menu"));
        zoomScale.get(1).slider().addListener(new TextTooltip("The largest growth; 1.00 keeps units at their size.",
              skin, "menu"));
        zoomScaling.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                applyZoomScaling();
            }
        });
        section(skin, "Route pulse");
        routePulse = checkbox(skin, "Pulse the planned route", "tuning-route-pulse");
        routePulse.addListener(new TextTooltip("In the movement turn a glow runs along the plotted route from the unit "
              + "to its destination, where the ghost surges and a ring ripples out; then it rests and runs again.",
              skin, "menu"));
        pulse = controls(skin, List.of(new Knob("Pulse speed", .25f, 3, .05f, "%.2f"),
              new Knob("Pulse intensity", 0, 2, .05f, "%.2f")), this::applyPulse, 0);
        pulse.getFirst().slider().addListener(new TextTooltip(String.format(Locale.ROOT, "How fast the glow "
              + "travels: at 1.00 it takes %.2f s a hex, %.1f s at least and %.1f s at most.",
              GpuRoutePulse.SECONDS_PER_HEX, GpuRoutePulse.MIN_TRAVEL, GpuRoutePulse.MAX_TRAVEL), skin, "menu"));
        pulse.get(1).slider().addListener(new TextTooltip("The brightness of the glow, its trail, the ripple and the "
              + "ghost's surge; 0 hides them.", skin, "menu"));
        routePulse.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                applyPulse();
            }
        });
        section(skin, "ECM pulse");
        ecmPulse = checkbox(skin, "Pulse the open ECM field", "tuning-ecm-pulse");
        ecmPulse.addListener(new TextTooltip("Hovering or selecting an ECM or ECCM unit sends a glow from the unit "
              + "across the hexes only its field covers, flashing at the border; a hover repeats it, a selection "
              + "runs it " + GpuElectronicWarfare.SELECTION_CYCLES + " times.", skin, "menu"));
        ecmPulseKnobs = controls(skin, List.of(new Knob("ECM pulse intensity", 0, 2, .05f, "%.2f"),
              new Knob("ECM pulse period s", 1, 5, .1f, "%.1f")), this::applyEcmPulse, 0);
        ecmPulse.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                applyEcmPulse();
            }
        });
        section(skin, "Unit visibility");
        visibility = controls(skin, VISIBILITY_KNOBS, this::applyVisibility, 0);
        visibility.get(1).slider().addListener(new TextTooltip(
              "Highlights occluded units with an outline and fill. Set to 0% to turn off.", skin, "menu"));
        section(skin, "Outside field of view");
        fovModes = effectModes(skin, "fov");
        fieldOfView = controls(skin, FOV_KNOBS, this::applyFieldOfView, 0);
        section(skin, "Outside sensor range");
        sensorModes = effectModes(skin, "sensor");
        sensors = controls(skin, SENSOR_KNOBS, this::applyFieldOfView, 0);
        atmospheric = new Table();
        rows = atmospheric;
        rows.top().defaults().pad(0, 3, 0, 3);
        TextButton conditions = new TextButton("Planetary conditions...", skin, "menu-control");
        conditions.setName("tuning-planetary-conditions");
        conditions.setDisabled(source == null);
        conditions.setProgrammaticChangeEvents(false);
        conditions.addListener(new TextTooltip("Edit the active visual planetary conditions. Apply always restores their values "
              + "and resets the extra atmosphere effects to their constants, even when conditions are unchanged.", skin, "menu"));
        conditions.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                conditions.setChecked(false);
                if (conditionsEditor == null) { return; }
                conditions.setDisabled(true);
                var application = Gdx.app;
                source.editPlanetaryConditions(conditionsEditor, settings -> application.postRunnable(() -> {
                    if (source.isClosed()) { return; }
                    conditions.setDisabled(false);
                    if (settings != null) { applyConditions(settings); }
                }));
            }
        });
        section(skin, "Atmosphere presets").add(conditions).height(22).padLeft(8);
        Table presets = new Table();
        for (AtmospherePreset preset : AtmospherePreset.values()) {
            TextButton button = new TextButton(preset.label, skin, "menu-control");
            button.setName("atmosphere-" + preset.name());
            button.setProgrammaticChangeEvents(false);
            button.addListener(new TextTooltip(preset.description(), skin, "menu"));
            button.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    button.setChecked(false);
                    applyConditions(source == null ? preset.settings(0.5) : source.preview(preset));
                }
            });
            presets.add(button).width(94).height(22).padRight(2);
            if (presets.getChildren().size % 3 == 0) { presets.row(); }
        }
        rows.add(presets).colspan(3).left().padBottom(2).row();
        section(skin, "Lighting");
        daylight = controls(skin, LIGHTING_KNOBS, this::applyAtmosphere, 0);
        lightBalance = controls(skin, List.of(
              new Knob("Shadows", 0, 100, 1, "%.0f%%",
                    "Cast-shadow strength: 0 removes geometry shadows; 100 keeps full occlusion. Independent of moon contrast."),
              new Knob("Ambient light", 0, 100, 1, "%.0f%%",
                    "Ambient/direct balance: 20 is the default daylight balance, 0 is direct only, 100 is ambient only."),
              new Knob("Sky contribution", 0, 200, 1, "%.0f%%",
                    "Scales sky illumination and reflections. 100 is the default; 0 removes them. The visible sky is unchanged.")),
              this::applyRendering, 0);
        chooseEnvironment = new TextButton("Choose lighting environment...", skin, "menu-control");
        chooseEnvironment.setName("tuning-environment-image");
        chooseEnvironment.setProgrammaticChangeEvents(false);
        chooseEnvironment.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                chooseEnvironment.setChecked(false);
                environmentChooser.run();
            }
        });
        rows.add(chooseEnvironment).colspan(3).left().row();
        lightingSky = checkbox(skin, "Show lighting sky", "tuning-lighting-sky");
        TextButton clearEnvironment = new TextButton("Use generated sky lighting", skin, "menu-control");
        clearEnvironment.setName("tuning-environment-clear");
        clearEnvironment.setProgrammaticChangeEvents(false);
        clearEnvironment.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                clearEnvironment.setChecked(false);
                setEnvironmentImage(null);
            }
        });
        rows.add(clearEnvironment).colspan(3).left().row();
        environmentStatus = new Label("Generated sky lighting", skin, "small");
        rows.add(environmentStatus).colspan(3).left().row();
        colorGrading = controls(skin, List.of(new Knob("White balance", -1, 1, 0.01f, "%+.2f"),
              new Knob("Tint", -1, 1, 0.01f, "%+.2f"),
              new Knob("Saturation", 0, 2, 0.01f, "%.2f"),
              new Knob("Contrast", .5f, 1.5f, 0.01f, "%.2f")), this::applyRendering, 0);
        colorGrading.get(0).slider().addListener(new TextTooltip(
              "Cooler blue to the left, warmer amber to the right. 0 is neutral. "
                    + "Adjusts the whole scene, including sky and effects; HUD colors stay unchanged.", skin, "menu"));
        colorGrading.get(1).slider().addListener(new TextTooltip(
              "Green to the left, magenta to the right. 0 is neutral; complements white balance.", skin, "menu"));
        colorGrading.get(2).slider().addListener(new TextTooltip(
              "Color intensity: 0 is grayscale, 1 is unchanged, 2 boosts color. Applies after the time-of-day palette.",
              skin, "menu"));
        colorGrading.get(3).slider().addListener(new TextTooltip(
              "Contrast around display mid-gray: 1 is unchanged. Higher values deepen shadows and brighten highlights.",
              skin, "menu"));
        moonlight = checkbox(skin, "Moonlight at night", "tuning-moonlight");
        moonlight.addListener(new TextTooltip("Enable the directional moon at night. Moonless and Pitch Black disable it; "
              + "Exposure (EV) controls their darkness. This never disables daytime sunlight.", skin, "menu"));
        moonlight.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (!syncing) { applyAtmosphere(); }
            }
        });
        fixedSun = checkbox(skin, "Fixed sun/moon", "tuning-fixed-sun");
        fixedSun.addListener(new TextTooltip("Keep the light at the same position on screen when rotating or tilting the board. "
              + "Time of day still sets its color and strength; Moonless and Pitch Black have no moonlight.", skin, "menu"));
        daylight.getFirst().slider().addListener(new TextTooltip(
              "Starts at a random minute within the scenario's daylight, dawn/dusk or night window. "
                    + "It stays fixed during combat; adjust here to override. Defaults restores the scenario's choice.", skin, "menu"));
        daylight.get(1).slider().addListener(new TextTooltip(
              "Visual brightness offset. The Moonless preset uses -0.3 EV; Pitch Black uses -1 EV, both without moonlight. "
                    + "Glare/Solar Flare use +0.6/+1.2 EV. Time of day also sets the base exposure.", skin, "menu"));
        section(skin, "Planet properties");
        gravity = controls(skin, List.of(new Knob("Gravity (g)", 0, 10, 0.01f, "%.2f")), this::applyAtmosphere, 0);
        gravity.getFirst().slider().addListener(new TextTooltip(
              "Visual gravity controls water waves, waterfall spray, and the height and timing of new jump animations. "
                    + "At zero gravity or in vacuum, vegetation disappears and the surface becomes lunar; "
                    + "in vacuum, water freezes and magma stays molten. At zero gravity, all liquids disappear and "
                    + "liquid depth lowers the ground level, loose stones disappear, rough ground becomes bedrock "
                    + "outcrops, air pressure becomes Vacuum and all weather effects are disabled. "
                    + "Moves and gameplay rules stay unchanged; an airborne jump finishes its existing arc.", skin, "menu"));
        pressure = choice(skin, "Air pressure", "tuning-atmosphere-pressure", Atmosphere.values(), this::applyAtmosphere);
        pressure.addListener(new TextTooltip("Visual atmosphere pressure: controls sky scattering, clouds and permitted weather. "
              + "Vacuum clears atmospheric effects and makes the surface lunar, without vegetation and with frozen water; "
              + "changing pressure never changes the game's rules.", skin, "menu"));
        temperature = controls(skin, List.of(new Knob("Temperature (C)", -200, 200, 1, "%.0f")), this::applyAtmosphere, 0);
        temperature.getFirst().slider().addListener(new TextTooltip(
              "Scenario temperature controls rain wetness: freezing or colder keeps the ground dry. Visual preview only.", skin, "menu"));
        atmosphericTaint = choice(skin, "Atmospheric taint", "tuning-atmospheric-taint", AtmosphericTaint.values(), this::applyAtmosphere);
        atmosphericTaint.addListener(new TextTooltip("Select the atmospheric palette to preview. Taint strength scales its color. "
              + "This does not change gameplay exposure, fire or damage rules.", skin, "menu"));
        section(skin, "Clouds and ground air");
        weather = controls(skin, ATMOSPHERE_KNOBS, this::applyAtmosphere, 0);
        weather.getFirst().slider().addListener(new TextTooltip(
              "Moving cloud shadows and a soft sky gradient. Thin air gives lighter shade; trace atmosphere and vacuum disable clouds.",
              skin, "menu"));
        rows.add(new Label(String.format(Locale.ROOT, "Fog + haze opacity cap: %.0f%%",
              BoardAtmosphere.MAX_FOG_OPACITY * 100), skin, "small")).colspan(3).left().height(18).row();
        section(skin, "Weather effects");
        effects = controls(skin, EFFECT_KNOBS, this::applyAtmosphere, 5);
        effects.get(5).slider().addListener(new TextTooltip(
              "Clouds and fog keep drifting even when wind strength is zero.", skin, "menu"));
        section(skin, "Light and fog effects");
        rendering = controls(skin, List.of(new Knob("God rays", 0, 2, 0.05f, "%.2f"),
              new Knob("Cloud shadow min", 0, 1, 0.05f, "%.2f"),
              new Knob("Cloud shadow max", 0, 1, 0.05f, "%.2f"),
              new Knob("Sun glare", 0, 1, 0.05f, "%.2f"),
              new Knob("Fog height variation", 0, 4, 0.05f, "%.2f"),
              new Knob("Fog density variation", 0, 1, 0.05f, "%.2f"),
              new Knob("Moon shadow contrast", 0, 1, 0.05f, "%.2f"),
              new Knob("Taint strength", 0, 10, 0.05f, "%.2f"),
              new Knob("Fog calm drift", 0, 2, 0.01f, "%.2f")), this::applyRendering, 0);
        rendering.get(0).slider().addListener(new TextTooltip(
              "Sunlight scattered through cloud openings. 0 disables shafts; haze and viewing angle affect visibility.", skin, "menu"));
        rendering.get(1).slider().addListener(new TextTooltip(
              "Cloud-patch opacity at sparse cover. Strength rises with cover toward the maximum; density stays at 0.25.",
              skin, "menu"));
        rendering.get(2).slider().addListener(new TextTooltip(
              "Cloud-patch opacity at full cover. Cannot be lower than the minimum; ambient light remains in shadow.",
              skin, "menu"));
        rendering.get(3).slider().addListener(new TextTooltip(
              "Golden glare and lens reflections when facing the sun, strongest near dawn/dusk. "
                    + "Clouds, fog and source occlusion reduce it. "
                    + "0 disables it; this lens effect also works without an atmosphere.", skin, "menu"));
        effects.get(0).slider().addListener(new TextTooltip(
              "Rain also wets exposed ground above freezing in sufficiently dense air.", skin, "menu"));
        effects.get(3).slider().addListener(new TextTooltip(String.format(Locale.ROOT,
              "Wind-driven dust above the board, with at most %.0f%% opacity even at full strength. "
                    + "Ground layer height controls its falloff; the solid map base stays clear.",
              GpuAtmosphere.MAX_SAND_OPACITY * 100), skin, "menu"));
        weather.get(2).slider().addListener(new TextTooltip(
              "Shared fog and sand falloff height above the board baseline, in terrain levels.", skin, "menu"));
        rendering.get(4).slider().addListener(new TextTooltip(
              "Maximum height deviation of drifting fog banks, in terrain levels. 0 gives a constant height.", skin, "menu"));
        rendering.get(5).slider().addListener(new TextTooltip(
              "Thins the gaps between wind-driven fog banks. 0 gives uniform density; 1 permits clear gaps. "
                    + "The fog opacity cap still applies.", skin, "menu"));
        rendering.get(6).slider().addListener(new TextTooltip(
              "Strengthens Full Moon shadows while preserving the brightness of lit level ground. "
                    + "0 restores the original softer shadows. Moonless and Pitch Black have no moonlight.", skin, "menu"));
        rendering.get(7).slider().addListener(new TextTooltip(
              "Scale the selected taint's palette across the sky, existing fog and the board itself; "
                    + "0 disables the tint. Keeps brightness and fog density unchanged. Fades at dawn and dusk, "
                    + "weaker in thin air, and disabled in vacuum or breathable air.",
              skin, "menu"));
        rendering.get(8).slider().addListener(new TextTooltip(
              "Intrinsic fog speed in hex widths per second when calm. A gently changing vector blends into the wind. "
                    + "0 disables calm drift; wind still moves the fog.", skin, "menu"));
        rows = general;
        section(skin, "Unit damage");
        overrideDamage = checkbox(skin, "Override visible unit damage", "tuning-override-damage");
        damageLocation = choice(skin, "Damage location", "tuning-damage-location", UnitDamageDisplay.Location.values(), this::applyDamage);
        damageLocation.addListener(new TextTooltip("Applies to matching model locations across the board. Models without locations "
              + "always use All locations. Other locations keep their actual damage.", skin, "menu"));
        damage = controls(skin, List.of(new Knob("Display damage", 0, 1, 0.01f, "%.2f")), this::applyDamage, 0);
        damage.getFirst().slider().addListener(new TextTooltip(
              "Non-Meks: linear damage. Meks: 0-0.50 removes armor; 0.50-1 damages structure. "
                    + "Infantry: proportion of troops fallen (rounded to visible figures). "
                    + "Destroyed parts take priority. Visual preview only.", skin, "menu"));
        overrideDamage.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                applyDamage();
            }
        });
        terrain = new Table();
        rows = terrain;
        rows.top().defaults().pad(0, 3, 0, 3);
        terrainProgress = new Label("", skin, "small");
        terrainProgress.setName("terrain-build-progress");
        rows.add(terrainProgress).colspan(3).left().row();
        TextButton palette = new TextButton("Material palette...", skin, "menu-control");
        palette.setName("tuning-material-palette");
        palette.setProgrammaticChangeEvents(false);
        palette.addListener(new TextTooltip("Every board material side by side on a tile, a cube or a sphere, as the "
              + "game draws it from the files on disk.", skin, "menu"));
        palette.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                palette.setChecked(false);
                if (materialPalette != null) {
                    materialPalette.accept(source == null ? null : GpuBoardTuning.this::requestAssetReload);
                }
            }
        });
        rows.add(palette).colspan(3).left().row();
        section(skin, "Hex joins");
        transitions = checkbox(skin, "Hex transitions", "tuning-transitions");
        transitions.addListener(new TextTooltip("Steps between hexes take room on both sides of their edge: slopes up to "
              + "two levels, deep cliffs above a talus from three. Visual only; the game's levels and hexes are unchanged.",
              skin, "menu"));
        transitions.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (!syncing) { applyGeometry(); }
            }
        });
        padding = controls(skin, List.of(PADDING), this::applyGeometry, 0);
        stepOutline = choice(skin, "Hill outlines", "tuning-step-outline", BoardRelief.StepOutline.values(),
              this::applyRelief);
        syncing = true;
        stepOutline.setSelected(BoardRelief.tuning().stepOutline());
        syncing = false;
        stepOutline.addListener(new TextTooltip("How the slopes between levels run round a hill: along each hex's "
              + "edges, with every hex corner rounded, or smooth, the hill's edge running as one curve along its "
              + "row of hexes. Visual only; the game's levels and hexes are unchanged.", skin, "menu"));
        section(skin, "Water");
        cliffsIntoWater = checkbox(skin, "Cliffs directly into water", "tuning-cliffs-into-water");
        cliffsIntoWater.addListener(new TextTooltip("Keep more flat land above waterside cliffs and let their rock face "
              + "continue down to the riverbed, without a dry beach. Applies to drops of three or more levels, counting "
              + "water depth. Turn off to restore the beach and rubble slope.", skin, "menu"));
        cliffsIntoWater.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (!syncing) { applyRelief(); }
            }
        });
        section(skin, "Roads");
        containmentWalls = checkbox(skin, "Containment walls", "tuning-containment-walls");
        containmentWalls.setChecked(BoardRelief.tuning().containmentWalls());
        containmentWalls.addListener(new TextTooltip("Where a road cuts into a natural slope, keep the slope's foot behind "
              + "a low precast wall at the road's edge, so the slope above gives up less ground. Uncheck to let the whole "
              + "slope recede from the road instead.", skin, "menu"));
        containmentWalls.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (!syncing) { applyRelief(); }
            }
        });
        section(skin, "Terrain detail");
        grass = checkbox(skin, "Grass blades", "tuning-grass");
        grass.addListener(new TextTooltip("Show wind-blown grass blades on the ground. Uncheck to remove the blades; "
              + "fields' crops stay.", skin, "menu"));
        waterEffects = checkbox(skin, "Water effects", "tuning-water-effects");
        waterEffects.addListener(new TextTooltip("Animate water: waves, currents, foam, ripples, glitter and the "
              + "light patterns on riverbeds, with cloud reflections. Uncheck for calm water with simpler sunlight "
              + "reflection; sky reflection remains.", skin, "menu"));
        reloadAssets = new TextButton("Reload assets", skin, "menu-control");
        reloadAssets.setName("tuning-reload-assets");
        reloadAssets.setDisabled(source == null);
        reloadAssets.setProgrammaticChangeEvents(false);
        reloadAssets.addListener(new TextTooltip("Copy changed files from a sibling mm-data/data/models directory, when available, then reload "
              + "textures, GLB meshes, model descriptors, tilesets and shaders from disk. "
              + "Save your edited files first. The board rebuilds with your current camera and tuning settings.", skin, "menu"));
        assetReloadStatus = new Label("", skin, "small");
        assetReloadStatus.setName("tuning-reload-status");
        assetReloadStatus.setEllipsis(true);
        reloadAssets.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                reloadAssets.setChecked(false);
                requestAssetReload();
            }
        });
        reset = new TextButton("Defaults", skin, "menu-control");
        reset.setName("tuning-defaults");
        reset.addListener(new TextTooltip("Restore all tabs but Display: camera projection, geometry, terrain, "
              + "water, family sizes, visibility, light/fog effects, the game's current planetary conditions, and "
              + "disable damage preview. The Display settings are saved choices and stay as they are.",
              skin, "menu"));
        reset.setProgrammaticChangeEvents(false);
        reset.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                reset.setChecked(false);
                if (source != null) { source.resetConditionsPreview(); }
                conditionsPreview = false;
                restoreDefaults();
            }
        });
        GpuShaderManager shaderEdits = GpuShaderManager.current();
        editShaders = new TextButton("Edit shaders", skin, "menu-control");
        editShaders.setName("tuning-edit-shaders");
        editShaders.setDisabled(shaderEdits == null);
        editShaders.setProgrammaticChangeEvents(false);
        editShaders.addListener(new TextTooltip("Edit GLSL with live preview on this board. "
              + "Invalid edits keep the last working shaders; save only when you are ready.", skin, "menu"));
        editShaders.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                editShaders.setChecked(false);
                if (shaderEdits != null) { shaderEdits.showEditor(); }
            }
        });
        restoreDefaults();
        updateQualityOptions();
        // The Display page's saved choices; Defaults leaves them alone.
        GUIPreferences preferences = GUIPreferences.getInstance();
        setValues(uiScale, new float[] { preferences.getBoardUiScale() * 100 });
        applyUiScale();
        uiScaleSaved = value(uiScale, 0) / 100;
        setValues(renderResolution, new float[] { preferences.getBoardRenderResolution() * 100 });
        applyRenderResolution();
    }

    /** Reload assets (its button, the material palette's): the board reloads them on its next frame. Render thread. */
    private void requestAssetReload() {
        if (reloadAssets.isDisabled()) { return; }
        reloadAssets.setDisabled(true);
        assetReloadStage("Finding mm-data");
        assetReloadStatus.setText("Looking for authoring assets...");
        assetReloadRequested = true;
    }

    boolean takeAssetReloadRequest() {
        boolean requested = assetReloadRequested;
        assetReloadRequested = false;
        return requested;
    }

    void assetReloadFinished(boolean success) {
        reloadAssets.setDisabled(false);
        assetReloadPhase = "";
        assetReloadFile = "";
        assetReloadStatus.setText(success ? "OK" : "FAIL (logs)");
    }

    /** The render thread applies the latest worker snapshot; widgets are never touched by the file worker. */
    void assetSyncProgress(GpuAssetSync.Progress progress) {
        assetReloadPhase = progress.phase();
        assetReloadFraction = progress.total() < 0 ? -1
              : progress.total() == 0 ? 1 : progress.completed() / (float) progress.total();
        assetReloadFile = progress.file() == null ? "" : progress.file().toString();
        assetReloadStatus.setText(progress.total() < 0
              ? String.format(Locale.ROOT, "Found %,d files", progress.completed())
              : String.format(Locale.ROOT, "%,d / %,d files · %,d copied",
                    progress.completed(), progress.total(), progress.copied()));
    }

    void assetReloadStage(String phase) {
        assetReloadPhase = phase;
        assetReloadFraction = -1;
        assetReloadFile = "";
    }

    String assetReloadPhase() { return assetReloadPhase; }
    String assetReloadFile() { return assetReloadFile; }
    float assetReloadFraction() { return assetReloadFraction; }

    private Table section(Skin skin, String title) {
        float spacing = rows.hasChildren() ? 8 : 0;
        Table heading = new Table();
        Label label = new Label(title.toUpperCase(Locale.ROOT), skin, "kicker");
        label.setEllipsis(true);
        heading.add(label).minWidth(0).growX();
        rows.add(heading).colspan(3).growX().padTop(spacing).padBottom(3).row();
        return heading;
    }

    private CheckBox checkbox(Skin skin, String label, String name) {
        CheckBox checkbox = newCheckbox(skin, label, name);
        rows.add(checkbox).colspan(3).left().height(20).row();
        return checkbox;
    }

    private static CheckBox newCheckbox(Skin skin, String label, String name) {
        CheckBox checkbox = new CheckBox(label, skin, "menu");
        checkbox.setName(name);
        checkbox.getImage().setScaling(Scaling.fit);
        checkbox.getImageCell().size(14).padRight(5);
        return checkbox;
    }

    private <T> SelectBox<T> choice(Skin skin, String label, String name, T[] values, Runnable apply) {
        SelectBox<T> choice = new SelectBox<>(skin, "menu");
        choice.setName(name);
        choice.setItems(values);
        choice.setMaxListCount(7);
        choice.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (!syncing) { apply.run(); }
            }
        });
        rows.add(new Label(label, skin, "menu")).left().width(LABEL_WIDTH);
        rows.add(choice).colspan(2).minWidth(0).growX().height(24).row();
        return choice;
    }

    private ButtonGroup<TextButton> effectModes(Skin skin, String name) {
        ButtonGroup<TextButton> group = new ButtonGroup<>();
        Table modes = new Table();
        for (GpuFieldOfView.Style style : GpuFieldOfView.Style.values()) {
            TextButton button = new TextButton(style.label, skin, "menu-control");
            button.setName(name + "-style-" + style.name());
            button.setUserObject(style);
            group.add(button);
            modes.add(button).width(92).height(22).padRight(2);
            if (modes.getChildren().size % 3 == 0) { modes.row(); }
        }
        rows.add(modes).colspan(3).left().padBottom(2).row();
        return group;
    }

    private List<Control> controls(Skin skin, List<Knob> knobs, Runnable apply, int toggleCount) {
        List<Control> result = new ArrayList<>();
        for (Knob knob : knobs) {
            Slider slider = new Slider(knob.min(), knob.max(), knob.step(), false, skin, "menu");
            slider.setName(knob.name());
            Label reading = new Label("", skin, "small");
            reading.setAlignment(Align.right);
            new UiSliderInput(slider);
            slider.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    if (!syncing) { apply.run(); }
                }
            });
            TextButton toggle = null;
            if (result.size() < toggleCount) {
                toggle = new TextButton(knob.name(), skin, "menu-control");
                toggle.setName("weather-toggle-" + knob.name());
                toggle.getLabel().setAlignment(Align.left);
                toggle.setProgrammaticChangeEvents(false);
                toggle.addListener(new ChangeListener() {
                    @Override
                    public void changed(ChangeEvent event, Actor actor) {
                        slider.setValue(slider.getValue() > 0 ? 0 : 0.5f);
                    }
                });
            }
            result.add(new Control(knob, slider, reading, toggle));
            rows.add(toggle == null ? new Label(knob.name(), skin, "menu") : toggle).left().width(LABEL_WIDTH);
            rows.add(slider).minWidth(60).prefWidth(SLIDER_WIDTH).growX().height(20);
            rows.add(reading).width(38).right().row();
            if (!knob.help().isEmpty()) {
                Label help = new Label(knob.help(), skin, "small");
                help.setName("tuning-help-" + knob.name());
                help.setWrap(true);
                rows.add(help).colspan(3).minWidth(0).growX().padBottom(9).row();
                slider.addListener(new TextTooltip(knob.help(), skin, "menu"));
            }
        }
        return result;
    }

    /** The Display page's rows (display mode, VSync, render resolution, UI scale, anti-aliasing, graphics card). */
    Table displayRows() {
        return display;
    }

    /**
     * The Board page's rows, one control per row; GpuTuningPanel (G17) shows them as its Board tab. Their widgets hold
     * the values: setting one applies it.
     */
    Table boardRows() {
        return general;
    }

    /** The Atmosphere page's rows, which GpuTuningPanel shows as its Atmosphere tab. */
    Table atmosphereRows() {
        return atmospheric;
    }

    /** The Terrain page's rows (terrain build progress, hex joins, water, detail): GpuTuningPanel's Terrain tab. */
    Table terrainRows() {
        return terrain;
    }

    /** The camera rows (Free Flight, the wireframe view, the field of view), shown on GpuTuningPanel's Camera tab. */
    Table cameraRows() {
        return cameraRows;
    }

    boolean dramaticDeath() { return dramaticDeath.isChecked(); }

    /** The Defaults button: a ChangeEvent on it restores the tabs' board values. */
    TextButton defaults() {
        return reset;
    }

    /** Reload assets: a ChangeEvent on it asks the board view to reload (see {@link #takeAssetReloadRequest}). */
    TextButton reloadAssets() {
        return reloadAssets;
    }

    /** The outcome of the last asset reload. */
    Label assetReloadStatus() {
        return assetReloadStatus;
    }

    /** Edit shaders: a ChangeEvent on it opens the shader editor; disabled without one. */
    TextButton editShaders() {
        return editShaders;
    }

    /** True while the board draws as the thermal wireframe view. */
    boolean wireframe() {
        return wireframe.isChecked();
    }

    void setWireframe(boolean on) {
        wireframe.setChecked(on);
    }

    /**
     * Writes the current board values into the sliders, as the initial state and after a reset. VSync, the display
     * mode and the fixed sun/moon frame are the user's window preferences, and the graphics card and both
     * anti-aliasing choices saved preferences, not board values, so a reset leaves them alone.
     */
    private void restoreDefaults() {
        dramaticDeath.setChecked(GpuBattleView.DRAMATIC_DEATH);
        camera.setReturnAfterPlayback(true);
        syncing = true;
        firstPerson.setChecked(false);
        syncing = false;
        setValues(cameraFieldOfView, new float[] { BoardCamera.DEFAULT_FIELD_OF_VIEW });
        applyCamera();
        unitDisplayMode.setSelected(UnitDisplayMode.DEFAULT);
        grass.setChecked(true);
        waterEffects.setChecked(true);
        boolean fixedSunKept = fixedSun.isChecked();
        BoardGeometry.Tuning defaults = BoardGeometry.DEFAULTS;
        float[] values = { defaults.hexScale(), defaults.unitScale(), defaults.unitHeightScale(),
              defaults.levelHeight(), defaults.gridShade(), defaults.multiHexUnitScale() };
        setValues(geometry, values);
        setValues(padding, new float[] { defaults.padding() });
        syncing = true;
        transitions.setChecked(defaults.transitions());
        syncing = false;
        applyGeometry();
        BoardRelief.tune(BoardRelief.DEFAULTS);
        syncRelief();
        float[] familyDefaults = new float[familySizes.size()];
        for (int index = 0; index < familyDefaults.length; index++) {
            familyDefaults[index] = UnitFamilyScale.values()[index].defaultUnitScale;
        }
        setValues(familySizes, familyDefaults);
        applyFamilySizes();
        zoomScaling.setChecked(UnitScreenScale.ENABLED);
        setValues(zoomScale, new float[] { UnitScreenScale.THRESHOLD, UnitScreenScale.MAX });
        applyZoomScaling();
        routePulse.setChecked(GpuRoutePulse.ENABLED);
        setValues(pulse, new float[] { GpuRoutePulse.SPEED, GpuRoutePulse.INTENSITY });
        applyPulse();
        ecmPulse.setChecked(GpuElectronicWarfare.PULSE);
        setValues(ecmPulseKnobs, new float[] { GpuElectronicWarfare.PULSE_INTENSITY, GpuElectronicWarfare.PULSE_PERIOD });
        applyEcmPulse();
        setValues(visibility, new float[] { GpuTerrain.DEFAULT_BUILDING_OPACITY * 100,
              GpuUnitVisibility.DEFAULT_OUTLINE_INTENSITY * 100 });
        updateReadings(visibility);
        fovModes.getButtons().get(GpuFieldOfView.FOV_STYLE.ordinal()).setChecked(true);
        setValues(fieldOfView, new float[] { GpuFieldOfView.FOV_DARKNESS * 100 });
        sensorModes.getButtons().get(GpuFieldOfView.SENSOR_STYLE.ordinal()).setChecked(true);
        setValues(sensors, new float[] { GpuFieldOfView.SENSOR_DARKNESS * 100 });
        applyFieldOfView();
        setEnvironmentImage(null);
        setRenderingOptions(GpuAtmosphere.Options.DEFAULTS);
        // Restored board values must not move the light back out of the frame the user chose for it.
        fixedSun.setChecked(fixedSunKept);
        setAtmosphere(lastScenario == null ? BoardAtmosphere.DEFAULTS : lastScenario);
        overrideDamage.setChecked(false);
        damageLocation.setSelected(UnitDamageDisplay.Location.ALL);
        setValues(damage, new float[] { 0 });
        applyDamage();
    }

    private void setValues(List<Control> controls, float[] values) {
        syncing = true;
        for (int index = 0; index < controls.size(); index++) {
            controls.get(index).slider().setValue(values[index]);
        }
        syncing = false;
    }

    BoardAtmosphere.Settings atmosphere() {
        return atmosphere;
    }

    /** Leave captured game gravity intact until a local conditions selection or gravity edit overrides it. */
    float gravityOverride() {
        return conditionsPreview || lastScenario != null && Math.abs(atmosphere.gravity() - lastScenario.gravity()) > 0.00001f
              ? atmosphere.gravity() : Float.NaN;
    }

    GpuAtmosphere.Options atmosphereOptions() {
        return new GpuAtmosphere.Options(value(rendering, 0), fixedSun.isChecked(), value(rendering, 1), value(rendering, 2),
              value(rendering, 3), value(rendering, 4), value(rendering, 5), value(rendering, 6), value(rendering, 7), value(rendering, 8),
              value(colorGrading, 0), value(colorGrading, 1), value(colorGrading, 2), value(colorGrading, 3),
              value(lightBalance, 0) / 100, value(lightBalance, 1) / 100, value(lightBalance, 2) / 100, environmentImage,
              lightingSky.isChecked());
    }

    private void applyRendering() {
        setRenderingOptions(atmosphereOptions());
    }

    private void setRenderingOptions(GpuAtmosphere.Options options) {
        fixedSun.setChecked(options.fixedSun());
        setValues(rendering, new float[] { options.rays(), options.minCloudShadow(), options.maxCloudShadow(), options.sunGlare(),
              options.fogHeightVariation(), options.fogDensityVariation(), options.moonShadowContrast(), options.taintStrength(),
              options.fogCalmDrift() });
        updateReadings(rendering);
        setValues(colorGrading, new float[] { options.whiteBalance(), options.tint(), options.saturation(), options.contrast() });
        updateReadings(colorGrading);
        setValues(lightBalance, new float[] { options.shadowStrength() * 100, options.ambientShare() * 100,
              options.skyContribution() * 100 });
        updateReadings(lightBalance);
        lightingSky.setChecked(options.lightingSky());
        if (environmentImage != options.environmentImage()) { setEnvironmentImage(options.environmentImage()); }
    }

    void onChooseEnvironment(Runnable choose) { environmentChooser = choose; }

    /** CPU-only file decode/filtering publishes one immutable result to this window's render thread. */
    void loadEnvironment(Path file, BoardSource source) {
        if (chooseEnvironment.isDisabled()) { return; }
        chooseEnvironment.setDisabled(true);
        int request = ++environmentRequest;
        var application = Gdx.app;
        environmentStatus.setText("Preparing " + file.getFileName());
        CompletableFuture.supplyAsync(() -> {
            try { return GpuLightingEnvironment.load(file); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }).whenComplete((image, failure) -> application.postRunnable(() -> {
            if (request != environmentRequest || source != null && source.isClosed()) { return; }
            chooseEnvironment.setDisabled(false);
            if (failure == null) { setEnvironmentImage(image); }
            else {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                environmentStatus.setText("Previous lighting kept: " + cause.getMessage());
            }
        }));
    }

    void setEnvironmentImage(GpuLightingEnvironment image) {
        environmentRequest++;
        environmentImage = image;
        chooseEnvironment.setDisabled(false);
        environmentStatus.setText(image == null ? "Generated sky lighting" : image.name);
    }

    boolean normalMaps() {
        return normalMaps.isChecked();
    }

    boolean terrainParallax() {
        return terrainParallax.isChecked();
    }

    boolean terrainCache() { return terrainCache.isChecked(); }

    /** The Display page's VSync choice, which the window follows while a board shows (GpuBattleView#pace). */
    boolean vsync() { return vsync.isChecked(); }

    boolean terrainCacheDebug() { return terrainCacheDebug.isChecked(); }

    /** The current renderer, resolved on the render thread so an asset reload cannot leave a disposed cache here. */
    void terrainCacheStatus(Supplier<GpuMaterialCache.Status> status) { terrainCacheStatus = status; }

    /** Called only while Display is visible, after the frame's rendering; these labels never drive cache state. */
    void showTerrainCacheStatus() {
        terrainCacheLegend.setText(terrainCacheDebug() ? "Ground: red = direct rendering\n"
              + "Cached: L0 green · L1 cyan · L2 blue\n"
              + "L3 violet · L4 magenta · L5 yellow · L6 orange\n"
              + "Water surfaces and objects are not tinted." : "");
        var status = terrainCacheStatus.get();
        if (status == null) {
            shownTerrainCache = null;
            terrainCacheState.setText("Waiting for terrain");
            terrainCacheDetails.setText("");
            return;
        }
        if (status.equals(shownTerrainCache)) { return; }
        shownTerrainCache = status;
        var pages = status.pages();
        String progress = status.active() && pages.needed() > 0
              ? String.format(Locale.ROOT, "\nAdmitted pages built: %d%% (%d / %d pages current)",
                    100L * pages.current() / pages.needed(), pages.current(), pages.needed()) : "";
        terrainCacheState.setText(status.state() + progress);
        String coverage = status.active()
              ? String.format(Locale.ROOT, "\nView: %d / %d pages admitted · level %d\nPrefetch pending: %d",
                    status.admitted(), status.requested(), status.level(), pages.prefetchPending()) : "";
        if (status.wallsOutside() > 0) { coverage += "\nWall pages outside table: " + status.wallsOutside(); }
        if (status.active()) {
            coverage += String.format(Locale.ROOT, "\nChunks using cache: tops %d / %d · walls %d / %d",
                  status.cachedTopChunks(), status.topChunks(), status.cachedWallChunks(), status.wallChunks());
        }
        terrainCacheDetails.setText(String.format(Locale.ROOT,
              "GPU allocation (est.): %.1f MiB\nPage storage: %.1f MiB · occupied: %.1f MiB"
                    + "\nSlots: %d / %d · stale: %d · building: %d\nCapacity: %s%s",
              status.allocatedBytes() / 1048576.0, pages.capacity() * status.pageBytes() / 1048576.0,
              pages.occupied() * status.pageBytes() / 1048576.0, pages.occupied(), pages.capacity(),
              pages.stale(), pages.building(), status.capacityReason(), coverage));
    }

    /** Sets the window's conditions editor, which "Planetary conditions..." opens. */
    void conditionsEditor(GpuAtmosphereControls.Editor editor) {
        conditionsEditor = editor;
    }

    /**
     * Sets what "Material palette..." opens, on this thread: the window's palette card, given the board's Reload assets
     * or null without a board.
     */
    void materialPalette(Consumer<Runnable> open) {
        materialPalette = open;
    }

    /** The view switches the window when the user picks a display mode: true for Fullscreen. */
    void onDisplayMode(Consumer<Boolean> change) {
        displayModeChange = change;
    }

    /** Shows the window's display mode, however it was switched; this asks for nothing. */
    void showFullscreen(boolean fullscreen) {
        syncing = true;
        displayMode.setSelectedIndex(fullscreen ? 1 : 0);
        syncing = false;
    }

    private void applyDisplayMode() {
        displayModeChange.accept(displayMode.getSelectedIndex() == 1);
    }

    /** The Display page's effective rendering choices. */
    BoardDisplaySettings displaySettings() {
        return new BoardDisplaySettings(quality(), BoardCamera.renderScale, antiAliasing(), highShadows(),
              specularAntialiasing.isChecked(), normalMaps(), terrainParallax(), tessellation(), ambientOcclusion(),
              depthCues(), vignette());
    }

    /** The chosen Quality level. */
    BoardQuality quality() {
        return quality.getSelected();
    }

    /** SMAA on the window's 3D scene. */
    boolean antiAliasing() {
        return antiAliasing.getSelectedIndex() == 1;
    }

    /** High shadows: twice the shadow texels a rendered pixel takes (GpuTerrainShadows). */
    boolean highShadows() {
        return shadows.getSelectedIndex() == 1;
    }

    /** Tessellated sculpted relief (GpuTessellation), where the context offers it. */
    boolean tessellation() {
        return tessellation.isChecked();
    }

    /** Screen-space ambient occlusion on the opaque scene (GpuAmbientOcclusion). */
    boolean ambientOcclusion() {
        return ambientOcclusion.isChecked();
    }

    /** The haze that separates nearer surfaces from farther ones (GpuDepthCues). */
    boolean depthCues() {
        return depthCues.isChecked();
    }

    /** The lens vignette over the composited view (atmosphere-composite.frag). */
    boolean vignette() {
        return vignette.isChecked();
    }

    /** The window's physical size, after a resize: the render resolution shows the scene size it gives. */
    void windowPixels(int width, int height) {
        windowWidth = width;
        windowHeight = height;
        showRenderSize();
    }

    /** The view reads the choice each frame; it is also saved for later sessions, on the Swing thread as the card is. */
    private void applyQuality() {
        updateQualityOptions();
        String choice = quality().name();
        SwingUtilities.invokeLater(() -> GUIPreferences.getInstance().setBoardQuality(choice));
    }

    /** What a level leaves out keeps its choice, disabled, until a level that has it brings the choice back. */
    private void updateQualityOptions() {
        BoardQuality level = quality();
        shadows.setDisabled(!level.costlyOptions);
        specularAntialiasing.setDisabled(!level.costlyOptions);
        terrainParallax.setDisabled(!level.costlyOptions);
        tessellation.setDisabled(!level.costlyOptions || !GpuTessellation.available());
        ambientOcclusion.setDisabled(!level.costlyOptions);
        grass.setDisabled(level.meadowShare == 0);
    }

    private void applyAntiAliasing() {
        String choice = antiAliasing.getSelected().toUpperCase(Locale.ROOT);
        SwingUtilities.invokeLater(() -> GUIPreferences.getInstance().setBoardAntiAliasing(choice));
    }

    private void applyShadows() {
        String choice = shadows.getSelected().toUpperCase(Locale.ROOT);
        SwingUtilities.invokeLater(() -> GUIPreferences.getInstance().setBoardShadows(choice));
    }

    /** The view reads the choice each frame; it is also saved for later sessions, on the Swing thread. */
    private void applyRenderResolution() {
        float resolution = value(renderResolution, 0) / 100;
        BoardCamera.renderScale = resolution;
        updateReadings(renderResolution);
        showRenderSize();
        SwingUtilities.invokeLater(() -> GUIPreferences.getInstance().setBoardRenderResolution(resolution));
    }

    /**
     * Saves the slider's UI scale, each frame, once no mouse button is held: the window lays out at the saved share
     * (BoardSource.UiPreferences.scale), and the new layout moves the slider under the pointer.
     */
    void saveUiScale() {
        float value = value(uiScale, 0) / 100;
        if (value != uiScaleSaved && !Gdx.input.isTouched()) {
            uiScaleSaved = value;
            SwingUtilities.invokeLater(() -> GUIPreferences.getInstance().setBoardUiScale(value));
        }
    }

    private void applyUiScale() {
        updateReadings(uiScale);
    }

    private void showRenderSize() {
        float scale = BoardCamera.renderScale;
        renderSize.setText(windowWidth <= 0 ? "" : String.format(Locale.ROOT, "Scene %d x %d px (window %d x %d)",
              GpuAtmosphere.scaled(windowWidth, scale), GpuAtmosphere.scaled(windowHeight, scale), windowWidth,
              windowHeight));
    }

    UnitDisplayMode unitDisplayMode() {
        return unitDisplayMode.getSelected();
    }

    boolean grass() {
        return grass.isChecked();
    }

    boolean waterEffects() {
        return waterEffects.isChecked();
    }

    boolean fixedSun() {
        return fixedSun.isChecked();
    }

    void setFixedSun(boolean fixed) {
        fixedSun.setChecked(fixed);
    }

    UnitDamageDisplay.Location damageLocation() {
        return damageLocation.getSelected();
    }

    /** Negative means the preview is disabled; otherwise this is the displayed loss from zero to one. */
    float damageOverride() {
        return overrideDamage.isChecked() ? value(damage, 0) : -1;
    }

    float buildingOpacity() {
        return value(visibility, 0) / 100;
    }

    float seeThrough() {
        return value(visibility, 1) / 100;
    }

    GpuFieldOfView.Style fovStyle() {
        return (GpuFieldOfView.Style) fovModes.getChecked().getUserObject();
    }

    float fovDarkness() {
        return value(fieldOfView, 0) / 100;
    }

    GpuFieldOfView.Style sensorStyle() {
        return (GpuFieldOfView.Style) sensorModes.getChecked().getUserObject();
    }

    float sensorDarkness() {
        return value(sensors, 0) / 100;
    }

    /** Follow effective scenario changes while preserving individual visual overrides. */
    void useScenario(BoardAtmosphere.Settings initial, boolean newBoard) {
        if (lastScenario == null || newBoard) {
            conditionsPreview = false;
            setAtmosphere(initial);
        } else if (!conditionsPreview && lastScenario != null && !lastScenario.equals(initial)) {
            setAtmosphere(BoardAtmosphere.followScenario(atmosphere, lastScenario, initial));
        }
        lastScenario = initial;
    }

    /** An explicit Apply is an action, including when the selected conditions have not changed. */
    private void applyConditions(BoardAtmosphere.Settings settings) {
        conditionsPreview = true;
        setEnvironmentImage(null);
        setRenderingOptions(GpuAtmosphere.Options.DEFAULTS);
        setAtmosphere(settings);
    }

    private void setAtmosphere(BoardAtmosphere.Settings settings) {
        atmosphere = settings;
        syncing = true;
        moonlight.setChecked(settings.moonlight());
        pressure.setSelected(settings.pressure());
        pressure.setDisabled(settings.gravity() == 0);
        atmosphericTaint.setSelected(settings.taint());
        // Preserve unusual loaded temperatures when editing an unrelated control.
        temperature.getFirst().slider().setRange(Math.min(-200, settings.temperature()), Math.max(200, settings.temperature()));
        gravity.getFirst().slider().setRange(0, Math.max(10, settings.gravity()));
        syncing = false;
        setValues(daylight, new float[] { settings.hour(), settings.exposure() });
        setValues(temperature, new float[] { settings.temperature() });
        setValues(gravity, new float[] { settings.gravity() });
        setValues(weather, new float[] { settings.clouds(), settings.fog(), settings.groundLayerHeight(), settings.haze() });
        BoardAtmosphere.Effects next = settings.effects();
        setValues(effects, new float[] { next.rain(), next.snow(), next.hail(), next.sand(), next.lightning(),
              next.wind(), next.windDirection() });
        boolean weatherAllowed = atmosphere.pressure().isDenserThan(Atmosphere.THIN);
        weather.getFirst().slider().setDisabled(atmosphere.pressure().isLighterThan(Atmosphere.THIN));
        weather.get(1).slider().setDisabled(!weatherAllowed);
        weather.get(2).slider().setDisabled(atmosphere.pressure().isVacuum());
        weather.get(3).slider().setDisabled(atmosphere.pressure().isVacuum());
        for (int index = 0; index < effects.size(); index++) {
            boolean disabled = atmosphere.pressure().isVacuum() || (index < 3 || index == 4) && !weatherAllowed;
            effects.get(index).slider().setDisabled(disabled);
            if (effects.get(index).toggle() != null) { effects.get(index).toggle().setDisabled(disabled); }
        }
        updateReadings(daylight);
        updateReadings(temperature);
        updateReadings(gravity);
        updateReadings(weather);
        updateReadings(effects);
        for (int index = 0; index < rendering.size(); index++) {
            rendering.get(index).slider().setDisabled(index < 3 && atmosphere.pressure().isLighterThan(Atmosphere.THIN)
                  || (index == 4 || index == 5 || index == 8) && !weatherAllowed || index == 6 && !atmosphere.moonlight()
                  || index == 7 && (atmosphere.pressure().isVacuum() || atmosphere.taint().isBreathable()));
        }
        updateReadings(rendering);
    }

    /** Saves the card for MegaMek's next start; this run keeps the card it opened the board with. */
    private void applyGraphicsCard() {
        String card = graphicsCard.getSelected().name();
        SwingUtilities.invokeLater(() -> GUIPreferences.getInstance().setBoardGraphicsCard(card));
        updateGraphicsCard();
    }

    private void updateGraphicsCard() {
        // Drivers append their bus and instruction set: "NVIDIA GeForce RTX 4070 Laptop GPU/PCIe/SSE2".
        String renderer = GpuGlsl.renderer().split("/")[0];
        cardInUse.setText(renderer.isEmpty() ? "" : "In use: " + renderer);
        cardPending.setVisible(graphicsCard.getSelected() != GpuGraphicsCard.applied());
    }

    private void applyCamera() {
        camera.setFieldOfView(value(cameraFieldOfView, 0));
        camera.setFirstPerson(firstPerson.isChecked());
        syncCamera();
        updateReadings(cameraFieldOfView);
    }

    /** Camera presets and the Camera menu can also change modes; the camera owns the current choice. */
    void syncCamera() {
        syncing = true;
        firstPerson.setChecked(camera.firstPerson());
        syncing = false;
        // Entering the Tactical View, by its utility, key or menu, turns the wireframe off.
        if (camera.tactical() && wireframe.isChecked()) { wireframe.setChecked(false); }
        cameraFieldOfView.getFirst().slider().setDisabled(!camera.firstPerson());
    }

    private void applyGeometry() {
        // The Geometry sliders apply while the panel is still being built, before the Terrain page's controls exist.
        boolean steps = transitions != null ? transitions.isChecked() : BoardGeometry.DEFAULT_TRANSITIONS;
        float gap = padding != null ? value(padding, 0) : BoardGeometry.DEFAULTS.padding();
        BoardGeometry.tune(new BoardGeometry.Tuning(value(geometry, 0), value(geometry, 1), value(geometry, 2),
              Math.round(value(geometry, 3)), value(geometry, 4), value(geometry, 5), steps, gap));
        // Padding replaces transitions while it is on.
        if (transitions != null) { transitions.setDisabled(gap > 0); }
        updateReadings(geometry);
        if (padding != null) { updateReadings(padding); }
    }

    private void applyRelief() {
        BoardRelief.tune(new BoardRelief.Tuning(cliffsIntoWater.isChecked(), containmentWalls.isChecked(),
              stepOutline.getSelected()));
    }

    private void syncRelief() {
        syncing = true;
        cliffsIntoWater.setChecked(BoardRelief.tuning().cliffsIntoWater());
        containmentWalls.setChecked(BoardRelief.tuning().containmentWalls());
        stepOutline.setSelected(BoardRelief.tuning().stepOutline());
        syncing = false;
    }

    void terrainProgress(int percent) {
        if (terrainPercent == percent) { return; }
        terrainPercent = percent;
        terrainProgress.setText(percent < 0 ? "" : Messages.getString("GpuBoard.preparingTerrain", percent));
    }

    private void applyFamilySizes() {
        for (int index = 0; index < familySizes.size(); index++) {
            UnitFamilyScale.values()[index].UNIT_SCALE = value(familySizes, index);
        }
        updateReadings(familySizes);
    }

    private void applyVisibility() {
        updateReadings(visibility);
    }

    private void applyZoomScaling() {
        UnitScreenScale.enabled = zoomScaling.isChecked();
        UnitScreenScale.threshold = value(zoomScale, 0);
        UnitScreenScale.max = value(zoomScale, 1);
        zoomScale.forEach(control -> control.slider().setDisabled(!zoomScaling.isChecked()));
        updateReadings(zoomScale);
    }

    private void applyPulse() {
        GpuRoutePulse.enabled = routePulse.isChecked();
        GpuRoutePulse.speed = value(pulse, 0);
        GpuRoutePulse.intensity = value(pulse, 1);
        pulse.forEach(control -> control.slider().setDisabled(!routePulse.isChecked()));
        updateReadings(pulse);
    }

    private void applyEcmPulse() {
        GpuElectronicWarfare.pulse = ecmPulse.isChecked();
        GpuElectronicWarfare.pulseIntensity = value(ecmPulseKnobs, 0);
        GpuElectronicWarfare.pulsePeriod = value(ecmPulseKnobs, 1);
        ecmPulseKnobs.forEach(control -> control.slider().setDisabled(!ecmPulse.isChecked()));
        updateReadings(ecmPulseKnobs);
    }

    private void applyFieldOfView() {
        updateReadings(fieldOfView);
        updateReadings(sensors);
    }

    private void applyDamage() {
        damage.getFirst().slider().setDisabled(!overrideDamage.isChecked());
        damageLocation.setDisabled(!overrideDamage.isChecked());
        updateReadings(damage);
    }

    private void applyAtmosphere() {
        setAtmosphere(new BoardAtmosphere.Settings(value(daylight, 0), value(weather, 0), value(weather, 1),
              value(weather, 2), value(weather, 3), value(daylight, 1),
              new BoardAtmosphere.Effects(value(effects, 0), value(effects, 1), value(effects, 2), value(effects, 3),
                    value(effects, 4), value(effects, 5), value(effects, 6)), pressure.getSelected(),
              Math.round(value(temperature, 0)), moonlight.isChecked(), atmosphericTaint.getSelected(), value(gravity, 0)));
    }

    private static void updateReadings(List<Control> controls) {
        for (Control control : controls) {
            float value = control.slider().getValue();
            if (control.toggle() != null) {
                control.toggle().setChecked(value > 0);
                control.toggle().setText(control.knob().name() + (value > 0 ? " On" : " Off"));
            }
            int minutes = Math.round(value * 60);
            control.reading().setText(control.knob().format().equals("clock")
                  ? String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
                  : String.format(Locale.ROOT, control.knob().format(), value));
        }
    }

    private static float value(List<Control> controls, int index) {
        return controls.get(index).slider().getValue();
    }
}
