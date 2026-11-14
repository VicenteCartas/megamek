/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Value;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiCursorCapture;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiModal;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.board.Coords;
import megamek.logging.MMLogger;

/** An embedded, read-only board inspection using the game's terrain, atmosphere and camera implementations. */
final class LobbyBoardPreview extends Table implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(LobbyBoardPreview.class);
    private final UiKit kit;
    private final Label title;
    private final GpuLoadingPanel loading;
    private final Image image = new Image();
    private final Stack stack = new Stack();
    private final GpuMapHexCard hexCard;
    private final Table hexCorner = new Table();
    private final BoardCamera camera = new BoardCamera();
    private final Map<Integer, KeyCommandBind> cameraKeys = new HashMap<>();
    private final UiCursorCapture cursor = new UiCursorCapture();
    private final PreviewInput input = new PreviewInput();
    private final InputListener outsideFocus = new InputListener() {
        @Override
        public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
            if (!event.getTarget().isDescendantOf(LobbyBoardPreview.this)) { unfocus(); }
            return false;
        }
    };
    private BoardScene scene;
    private BoardSource source;
    private BoardSource.Frame frame;
    private GpuLosPanel los;
    private UiPopover menu;
    private GpuTerrain terrain;
    private GpuAtmosphere atmosphere;
    private GpuAmbientOcclusion ambientOcclusion;
    private GpuSkyVisibility skyVisibility;
    private GpuHexGrid hexGrid;
    private GpuHexText hexText;
    private GpuTactical tactical;
    private SpriteBatch annotations;
    private ShapeRenderer hoverLines;
    private Coords hovered;
    private float hoverZ = Float.NaN;
    private FrameBuffer target;
    private boolean installed;
    private boolean loadingDrawn;
    private boolean focusOnReady;

    LobbyBoardPreview(UiKit kit, Runnable close) {
        this(kit, Messages.getString("Lobby.map.backToLayout"), close);
    }

    /** An inspection whose back button reads {@code back} and runs {@code close}. */
    LobbyBoardPreview(UiKit kit, String back, Runnable close) {
        this.kit = kit;
        hexCard = new GpuMapHexCard(kit);
        setName("map-inspection-panel");
        setBackground(kit.skin.getDrawable("token-panel-plain"));
        pad(0);
        float gap = DesignTokens.shipped().space().get("md");
        image.setName("map-inspection-image");
        image.setScaling(Scaling.stretch);
        image.setTouchable(Touchable.enabled);
        camera.terrainHit = ray -> scene == null || terrain == null ? null : terrain.selectionHit(scene, ray);
        loading = new GpuLoadingPanel(kit.skin, Messages.getString("GpuBoard.previewLoading"));
        loading.setBackground(kit.skin.getDrawable("token-panel-plain"));

        Table overlay = new Table();
        overlay.setName("map-inspection-overlay");
        overlay.setTouchable(Touchable.childrenOnly);
        overlay.pad(gap);
        title = kit.typeLabel("", "body.strong", "text.primary");
        title.setName("map-inspection-title");
        title.setEllipsis(true);
        title.setTouchable(Touchable.disabled);
        title.getStyle().fontColor = Color.WHITE;
        Table header = new Table();
        header.add(title).minWidth(0).maxWidth(Value.percentWidth(.4f, overlay)).pad(5, 8, 5, 8).left();
        header.add().growX();
        Table controls = new Table();
        GpuUtilityBar.cameraButtons(kit, controls, camera, () -> {
            if (scene != null) { camera.fit(scene); }
        });
        // The shared utility buttons clear the previous field's focus; this view then takes camera keys.
        UiKit.onChange(controls, this::focus);
        header.add(controls).padLeft(gap);
        UiButton backButton = GpuUtilityBar.compact(kit, kit.button("hud-utility", "chevron-left", back, null));
        backButton.setName("map-inspection-close");
        UiKit.onChange(backButton, close);
        header.add(backButton).height(GpuUtilityBar.HEIGHT).padLeft(gap);
        overlay.add(header).growX().row();
        overlay.add().grow().row();
        Label hint = kit.typeLabel(Messages.getString("Lobby.map.inspectControls"), "small", "text.primary");
        hint.setName("map-inspection-controls");
        hint.setWrap(true);
        hint.setTouchable(Touchable.disabled);
        hint.getStyle().fontColor = Color.WHITE;
        Table footer = new Table();
        footer.add(hint).growX().minWidth(0).pad(5, 8, 5, 8).bottom().left();
        hexCorner.setTouchable(Touchable.disabled);
        hexCorner.bottom().right();
        footer.add(hexCorner).bottom().right().padLeft(gap);
        overlay.add(footer).growX();
        stack.add(image);
        stack.add(loading);
        stack.add(overlay);
        add(stack).grow().minWidth(0).minHeight(0);
        image.addListener(input);
        image.addListener(new FocusListener() {
            @Override
            public void keyboardFocusChanged(FocusEvent event, Actor actor, boolean focused) {
                if (!focused) { input.clear(); }
            }
        });
    }

    @Override
    protected void setStage(Stage stage) {
        if (getStage() != null) {
            unfocus();
            getStage().removeCaptureListener(outsideFocus);
        }
        super.setStage(stage);
        if (stage != null) { stage.addCaptureListener(outsideFocus); }
    }

    private void focus() {
        if (getStage() != null && ascendantsVisible() && UiModal.active(getStage()) == null) {
            getStage().setKeyboardFocus(image);
            getStage().setScrollFocus(image);
        }
    }

    private void unfocus() {
        focusOnReady = false;
        input.clear();
        if (getStage() != null) {
            if (getStage().getKeyboardFocus() == image) { getStage().setKeyboardFocus(null); }
            if (getStage().getScrollFocus() == image) { getStage().setScrollFocus(null); }
        }
    }

    private boolean interactive() {
        return scene != null && installed && !loading.isVisible() && ascendantsVisible()
              && getStage() != null && UiModal.active(getStage()) == null;
    }

    private final class PreviewInput extends InputListener {
        private float lastX, lastY, startX, startY;
        private int button = -1;
        private int modifiers;
        private boolean dragged, orbiting;
        private boolean hovering;
        private float hoverX, hoverY;

        void clear() {
            cameraKeys.clear();
            cursor.release();
            button = -1;
            dragged = false;
        }

        @Override
        public boolean touchDown(InputEvent event, float x, float y, int pointer, int pressed) {
            if (!interactive() || pointer != 0 || button != -1) { return false; }
            focus();
            lastX = startX = x;
            lastY = startY = y;
            button = pressed;
            modifiers = GpuBattleView.modifiers();
            dragged = false;
            orbiting = BoardCameraControls.cameraButton(pressed) && BoardCameraControls.orbits(pressed, modifiers);
            hover(x, y);
            return true;
        }

        @Override
        public void touchDragged(InputEvent event, float x, float y, int pointer) {
            if (!interactive() || button == -1) { return; }
            hover(x, y);
            if (!dragged && Math.abs(x - startX) + Math.abs(y - startY) < BoardCameraControls.DRAG_THRESHOLD) { return; }
            if (!dragged && orbiting) {
                cursor.capture(x, y, event.getStage());
                cursor.beganAt(startX, startY);
            }
            dragged = true;
            if (orbiting) {
                Vector2 at = cursor.trusted(x, y);
                x = at.x;
                y = at.y;
            }
            if (BoardCameraControls.cameraButton(button)) {
                float scale = camera.camera.viewportWidth / image.getWidth();
                BoardCameraControls.drag(camera, orbiting, (x - lastX) * scale, (lastY - y) * scale, scale);
            }
            lastX = x;
            lastY = y;
        }

        @Override
        public void touchUp(InputEvent event, float x, float y, int pointer, int released) {
            if (event.isTouchFocusCancel() || released == button) {
                if (!event.isTouchFocusCancel() && interactive() && !dragged) {
                    boardClick(x, y, released, modifiers);
                }
                cursor.release();
                button = -1;
                dragged = false;
            }
        }

        @Override
        public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
            if (pointer == -1 && interactive()) { getStage().setScrollFocus(image); hover(x, y); }
        }

        @Override
        public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
            if (pointer == -1 && getStage().getScrollFocus() == image) { getStage().setScrollFocus(null); }
            if (pointer == -1) {
                hovering = false;
                hovered = null;
                if (source != null) { source.setHover(null); }
            }
        }

        @Override
        public boolean mouseMoved(InputEvent event, float x, float y) {
            if (interactive()) { hover(x, y); }
            return false;
        }

        private void hover(float x, float y) {
            // The hidden cursor points at nothing; its return move after the release refreshes the hover.
            if (UiCursorCapture.pointerHidden()) { return; }
            hovering = true;
            hoverX = x; hoverY = y;
            Pick hit = pick(x, y);
            hovered = hit == null ? null : hit.coords();
            hoverZ = hit == null ? Float.NaN : hit.height();
            source.setHover(hovered, hoverZ);
        }

        @Override
        public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY) {
            if (!interactive()) { return false; }
            focus();
            camera.zoomAt(GpuBattleView.wheelZoom(amountY), x * camera.camera.viewportWidth / image.getWidth(),
                  y * camera.camera.viewportHeight / image.getHeight());
            return true;
        }

        @Override
        public boolean keyDown(InputEvent event, int keycode) {
            if (!interactive() || getStage().getKeyboardFocus() != image) { return false; }
            int awt = GpuBattleView.awtKey(keycode);
            int modifiers = GpuBattleView.modifiers();
            if (keycode == Input.Keys.ESCAPE && (menu.cancel() || los.cancel())) { return true; }
            var binds = KeyCommandBind.getAllBindsByKey(awt, modifiers);
            if (binds.contains(KeyCommandBind.LOS_SETTING) || binds.contains(KeyCommandBind.HEX_COORDS)) {
                source.key(awt, true, modifiers);
                return true;
            }
            // As in game, Shift accelerates a held camera bind instead of selecting a different command.
            if (modifiers == java.awt.event.InputEvent.SHIFT_DOWN_MASK
                  && KeyCommandBind.getAllBindsByKey(awt, 0).stream().anyMatch(BoardCameraControls::held)) {
                modifiers = 0;
            }
            for (KeyCommandBind command : KeyCommandBind.getAllBindsByKey(awt, modifiers)) {
                if (BoardCameraControls.held(command)) {
                    cameraKeys.put(keycode, command);
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean keyUp(InputEvent event, int keycode) { return cameraKeys.remove(keycode) != null; }
    }

    void loading(String name) {
        unfocus();
        closeSource();
        title.setText(name);
        scene = null;
        installed = false;
        // Every inspection replaces the board, even when its ID and dimensions match the previous one.
        // The renderer otherwise treats it as an edit and keeps showing the old terrain while rebuilding.
        if (terrain != null) { terrain.boardChanged(); }
        if (atmosphere != null) { atmosphere.resetContrast(); }
        loadingDrawn = false;
        image.setDrawable(null);
        loading.reset(Messages.getString("GpuBoard.previewLoading"));
        loading.setVisible(true);
        focus();
        focusOnReady = true;
    }

    void show(@Nullable BoardSource board) {
        source = board;
        frame = board == null ? null : board.takeFrame();
        scene = frame == null ? null : frame.scene();
        installed = false;
        if (board == null) { loading.reset(Messages.getString("Lobby.map.inspectUnavailable")); }
        else {
            los = new GpuLosPanel(kit, getStage(), source, camera);
            stack.add(los.actor());
            menu = new UiPopover(kit);
            getStage().addActor(menu);
        }
    }

    /** The same picked surface used by the full map preview's hover card and LOS tool. */
    private record Pick(Coords coords, float height) { }

    private Pick pick(float x, float y) {
        float px = x * camera.camera.viewportWidth / image.getWidth();
        // Camera.unproject flips against the window height, even for a smaller, offscreen viewport.
        float py = Gdx.graphics.getHeight() - y * camera.camera.viewportHeight / image.getHeight();
        var ray = camera.camera.getPickRay(px, py, 0, 0, camera.camera.viewportWidth, camera.camera.viewportHeight);
        var hit = terrain.selectionHit(scene, ray);
        if (hit == null) { return null; }
        var floor = terrain.walkableHit(scene, ray);
        float z = floor != null && floor.coords().equals(hit.coords())
              ? ray.getEndPoint(new Vector3(), (float) Math.sqrt(floor.distance())).z : Float.NaN;
        return new Pick(hit.coords(), z);
    }

    private void boardClick(float x, float y, int button, int modifiers) {
        Pick hit = pick(x, y);
        if (hit == null) { source.setHover(null); return; }
        float z = hit.height();
        if (button == Input.Buttons.LEFT) {
            source.setHover(hit.coords(), z);
            source.measure(hit.coords(), modifiers, z);
        }
        else if (button == Input.Buttons.RIGHT) {
            source.inspect(hit.coords());
            UiMenuList list = new UiMenuList(kit);
            long generation = frame.boardGeneration();
            Integer height = Float.isNaN(z) ? null : GpuLosResult.pointedHeight(scene.tile(hit.coords()).elevation(), z);
            list.item(Messages.getString("GpuBoard.map.lineOfSight"), Messages.getString("GpuBoard.hud.mouse.altClick"),
                  null, false, true, () -> {
                      menu.cancel();
                      source.changeRuler(generation, model -> { model.clear(); model.addPoint(hit.coords(), height); });
                  }).setName("map-line-of-sight");
            menu.header(Messages.getString("GpuBoard.hud.context.hex", hit.coords().getBoardNum()), null).content(list);
            Vector2 at = image.localToStageCoordinates(new Vector2(x, y));
            menu.showAt(at.x, at.y);
        }
    }

    private void closeSource() {
        if (los != null) { los.dispose(); los.actor().remove(); los = null; }
        if (menu != null) { menu.cancel(); menu.remove(); menu = null; }
        if (source != null) { source.close(); source = null; }
        frame = null;
        hovered = null;
        input.hovering = false;
        hexCard.update("");
        hexCorner.clearChildren();
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        super.draw(batch, parentAlpha);
        // Let the loading UI reach the screen before the renderer starts its first preparation.
        loadingDrawn = true;
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (!ascendantsVisible()) { unfocus(); return; }
        if (source == null || source.isClosed() || getStage() == null) { return; }
        // A board shows here: its programs come first, the shader warm-up waits (GpuShaderWarmup).
        GpuShaderWarmup.boardDrawn();
        if (!loadingDrawn) { return; }
        frame = source.takeFrame();
        scene = frame.scene();
        if (!menu.isVisible() && frame.context() != null) { source.inspect(null); }
        if (UiModal.active(getStage()) != null) { unfocus(); }
        validate();
        if (image.getWidth() < 1 || image.getHeight() < 1) { return; }
        try {
            GpuGlsl.offscreen(() -> render(delta));
        } catch (RuntimeException failure) {
            LOGGER.warn(failure, "The embedded map preview could not render");
            dispose();
            loading.reset(Messages.getString("Lobby.map.inspectUnavailable"));
            loading.setVisible(true);
        } finally {
            FrameBuffer.unbind();
            getStage().getViewport().apply();
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDisable(GL20.GL_CULL_FACE);
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        }
    }

    private void render(float delta) {
        float scale = Gdx.graphics.getBackBufferWidth() / getStage().getViewport().getWorldWidth();
        int width = Math.max(1, Math.round(image.getWidth() * scale));
        int height = Math.max(1, Math.round(image.getHeight() * scale));
        var conditions = frame.scenarioAtmosphere();
        var preferences = source.uiPreferences();
        var display = preferences.display();
        BoardCamera.renderScale = display.resolution();
        if (terrain == null) {
            terrain = new GpuTerrain();
            atmosphere = new GpuAtmosphere(conditions, GpuAtmosphere.Options.DEFAULTS);
            atmosphere.prepare();
            terrain.prepareEffects();
            ambientOcclusion = new GpuAmbientOcclusion();
            skyVisibility = new GpuSkyVisibility();
            hexGrid = new GpuHexGrid();
            hexText = new GpuHexText();
            tactical = new GpuTactical();
            annotations = new SpriteBatch();
            hoverLines = new ShapeRenderer();
        }
        atmosphere.configure(conditions);
        display.apply(terrain, atmosphere, ambientOcclusion, width, height, false);
        skyVisibility.update(terrain.environment(), scene, 1);
        terrain.setColumns(preferences.graphics() == BoardGraphics.CLASSIC);
        terrain.setGravity(conditions.gravity());
        terrain.setVacuum(conditions.pressure().isVacuum());
        if (target == null || target.getWidth() != width || target.getHeight() != height) {
            image.setDrawable(null);
            if (target != null) { target.dispose(); }
            target = GpuAtmosphere.buffer(width, height, true);
            target.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            camera.resize(width, height, scene, scale);
        }
        if (!installed) {
            camera.reset(scene);
            installed = true;
        }
        camera.advance(delta);
        if (interactive() && getStage().getKeyboardFocus() == image) {
            BoardCameraControls.move(camera, cameraKeys.values(), delta * BoardCameraControls.speed(GpuBattleView.modifiers()));
        }
        if (interactive() && input.hovering) { input.hover(input.hoverX, input.hoverY); }
        atmosphere.updateLight(camera.camera);
        terrain.setAtmosphere(atmosphere.lighting());
        atmosphere.configureClouds(terrain, scene);
        terrain.update(scene, camera.camera);
        terrain.refine(camera.camera);
        if (!terrain.ready(scene)) {
            loading.update(terrain);
            loading.setVisible(true);
            los.actor().setVisible(false);
            hexCorner.setVisible(false);
            return;
        }
        try (TerrainSettings.Scope ignored = TerrainSettings.use(terrain.settings())) {
            atmosphere.prepareClouds(terrain, scene, delta);
            terrain.animate(delta, List.of());
            terrain.renderShadows(camera.camera, List.of());
            atmosphere.beginDisplayPixels(width, height, display.resolution(), delta);
            terrain.render(camera.camera, () -> { },
                  () -> ambientOcclusion.render(camera.camera, terrain, atmosphere.depthTexture()));
            ambientOcclusion.end();
            terrain.renderTransparent(camera.camera);
            atmosphere.renderWeather(camera.camera, scene);
            atmosphere.endDisplay(camera.camera, terrain, scene, target);
            atmosphere.prepareContrast(camera.camera, scene, 0);
            // The display-sized target keeps labels and LOS sharp even when the scene renders at a lower resolution.
            target.begin();
            hexGrid.render(camera, scene, terrain.columns(), false, GpuBattleStatus.Arc.NONE, false, atmosphere);
            var overlays = preferences.overlays();
            hexText.update(scene, kit.skin.getFont("default-font"), kit.skin.getFont(GpuBoardSkin.OUTLINE_FONT),
                  terrain::roofBounds, false, overlays.hexTypes(), overlays.hexLabelOutline());
            hexText.render(annotations, camera, atmosphere.depthTexture(), null, 0, atmosphere);
            tactical.update(scene);
            tactical.renderLabels(camera.camera);
            GpuHexHover.render(hoverLines, camera.camera, scene, hovered, GpuHexHover.top(scene, hovered, hoverZ));
            target.end();
        }
        if (image.getDrawable() == null) {
            TextureRegion region = new TextureRegion(target.getColorBufferTexture());
            region.flip(false, true);
            image.setDrawable(new TextureRegionDrawable(region));
        }
        loading.setVisible(false);
        if (focusOnReady) {
            focusOnReady = false;
            // Loading may outlast the opening gesture; an outside click cancels this focus request.
            if (getStage().getKeyboardFocus() == null || getStage().getKeyboardFocus() == image) { focus(); }
        }
        if (hexCard.update(frame.tooltip())) {
            hexCorner.clearChildren();
            if (hexCard.actor().hasChildren()) {
                hexCorner.add(hexCard.actor()).width(new Value() {
                    @Override public float get(Actor context) {
                        return Math.min(GpuMapHexCard.WIDTH, image.getWidth() / 2);
                    }
                });
            }
        }
        hexCorner.setVisible(UiModal.active(getStage()) == null);
        hexCorner.validate();
        Vector2 corner = hexCard.actor().hasChildren() ? hexCard.actor().localToAscendantCoordinates(stack, new Vector2()) : new Vector2();
        List<Rectangle> occupied = hexCard.actor().hasChildren() ? List.of(new Rectangle(corner.x, corner.y,
              hexCard.actor().getWidth(), hexCard.actor().getHeight())) : List.of();
        los.update(new GpuHud.Inputs(frame, GpuHud.HudView.EMPTY, null, preferences,
              GpuHud.Metrics.of(image.getWidth(), image.getHeight()), occupied));
        if (UiModal.active(getStage()) != null) { los.actor().setVisible(false); los.dismiss(); menu.cancel(); }
    }

    @Override
    public void dispose() {
        unfocus();
        closeSource();
        scene = null;
        installed = false;
        image.setDrawable(null);
        if (terrain != null) {
            // As the battle view does: nothing drawn without a view inherits this one's choice.
            BoardQuality.current = BoardQuality.HIGH;
            terrain.dispose();
            terrain = null;
        }
        if (atmosphere != null) {
            atmosphere.dispose();
            atmosphere = null;
        }
        if (target != null) {
            target.dispose();
            target = null;
        }
        if (ambientOcclusion != null) { ambientOcclusion.dispose(); ambientOcclusion = null; }
        if (skyVisibility != null) { skyVisibility.dispose(); skyVisibility = null; }
        if (hexGrid != null) { hexGrid.dispose(); hexGrid = null; }
        if (hexText != null) { hexText.dispose(); hexText = null; }
        if (tactical != null) { tactical.dispose(); tactical = null; }
        if (annotations != null) { annotations.dispose(); annotations = null; }
        if (hoverLines != null) { hoverLines.dispose(); hoverLines = null; }
    }
}
