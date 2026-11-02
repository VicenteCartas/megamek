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
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector2;
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
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiCursorCapture;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiModal;
import megamek.client.ui.util.KeyCommandBind;
import megamek.logging.MMLogger;

/** An embedded, read-only board inspection using the game's terrain, atmosphere and camera implementations. */
final class LobbyBoardPreview extends Table implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(LobbyBoardPreview.class);
    private final Label title;
    private final GpuLoadingPanel loading;
    private final Image image = new Image();
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
    private GpuTerrain terrain;
    private GpuAtmosphere atmosphere;
    /** Immutable lobby conditions, published on the GPU thread even while a board is loading. */
    private BoardAtmosphere.Settings conditions = BoardAtmosphere.DEFAULTS;
    private FrameBuffer target;
    private boolean installed;
    private boolean loadingDrawn;

    LobbyBoardPreview(UiKit kit, Runnable close) {
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
        UiButton back = GpuUtilityBar.utility(kit, "chevron-left", "Lobby.map.backToLayout", "map-inspection-close");
        UiKit.onChange(back, close);
        header.add(back).height(GpuUtilityBar.HEIGHT).padLeft(gap);
        overlay.add(header).growX().row();
        overlay.add().grow().row();
        Label hint = kit.typeLabel(Messages.getString("Lobby.map.inspectControls"), "small", "text.primary");
        hint.setName("map-inspection-controls");
        hint.setWrap(true);
        hint.setTouchable(Touchable.disabled);
        hint.getStyle().fontColor = Color.WHITE;
        overlay.add(hint).growX().minWidth(0).pad(5, 8, 5, 8).left();
        add(new Stack(image, loading, overlay)).grow().minWidth(0).minHeight(0);
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
        private boolean dragged, orbiting;

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
            dragged = false;
            // Inspection has no unit-selection drag: left also takes the game's right-button gesture.
            orbiting = BoardCameraControls.orbits(pressed == Input.Buttons.LEFT ? Input.Buttons.RIGHT : pressed,
                  GpuBattleView.modifiers());
            return true;
        }

        @Override
        public void touchDragged(InputEvent event, float x, float y, int pointer) {
            if (!interactive() || button == -1) { return; }
            if (!dragged && Math.abs(x - startX) + Math.abs(y - startY) < BoardCameraControls.DRAG_THRESHOLD) { return; }
            if (!dragged && orbiting) { cursor.capture(x, y); }
            dragged = true;
            if (orbiting) {
                Vector2 at = cursor.trusted(x, y);
                x = at.x;
                y = at.y;
            }
            if (button == Input.Buttons.LEFT || BoardCameraControls.cameraButton(button)) {
                float scale = camera.camera.viewportWidth / image.getWidth();
                BoardCameraControls.drag(camera, orbiting, (x - lastX) * scale, (lastY - y) * scale, scale);
            }
            lastX = x;
            lastY = y;
        }

        @Override
        public void touchUp(InputEvent event, float x, float y, int pointer, int released) {
            if (event.isTouchFocusCancel() || released == button) {
                cursor.release();
                button = -1;
                dragged = false;
            }
        }

        @Override
        public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
            if (pointer == -1 && interactive()) { getStage().setScrollFocus(image); }
        }

        @Override
        public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
            if (pointer == -1 && getStage().getScrollFocus() == image) { getStage().setScrollFocus(null); }
        }

        @Override
        public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY) {
            if (!interactive()) { return false; }
            camera.zoomAt(GpuBattleView.wheelZoom(amountY), x * camera.camera.viewportWidth / image.getWidth(),
                  y * camera.camera.viewportHeight / image.getHeight());
            return true;
        }

        @Override
        public boolean keyDown(InputEvent event, int keycode) {
            if (!interactive() || getStage().getKeyboardFocus() != image) { return false; }
            int awt = GpuBattleView.awtKey(keycode);
            int modifiers = GpuBattleView.modifiers();
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
    }

    void show(@Nullable BoardScene board) {
        scene = board;
        installed = false;
        if (board == null) { loading.reset(Messages.getString("Lobby.map.inspectUnavailable")); }
    }

    void atmosphere(BoardAtmosphere.Settings settings) {
        conditions = settings;
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
        if (scene == null || getStage() == null || !loadingDrawn) { return; }
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
        if (terrain == null) {
            terrain = new GpuTerrain();
            atmosphere = new GpuAtmosphere(conditions, GpuAtmosphere.Options.DEFAULTS);
            atmosphere.prepare();
            terrain.prepareEffects();
        }
        atmosphere.configure(conditions);
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
        atmosphere.updateLight(camera.camera);
        terrain.setAtmosphere(atmosphere.lighting());
        atmosphere.configureClouds(terrain, scene);
        terrain.update(scene, camera.camera);
        terrain.refine(camera.camera);
        if (!terrain.ready(scene)) {
            loading.update(terrain);
            loading.setVisible(true);
            return;
        }
        try (TerrainSettings.Scope ignored = TerrainSettings.use(terrain.settings())) {
            atmosphere.updateLight(camera.camera);
            terrain.setAtmosphere(atmosphere.lighting());
            atmosphere.prepareClouds(terrain, scene, delta);
            terrain.animate(delta, List.of());
            terrain.renderShadows(camera.camera, List.of());
            atmosphere.beginPixels(width, height, delta);
            terrain.render(camera.camera);
            terrain.renderTransparent(camera.camera);
            atmosphere.renderWeather(camera.camera, scene);
            atmosphere.end(camera.camera, terrain, scene, 0, null, false, target);
        }
        if (image.getDrawable() == null) {
            TextureRegion region = new TextureRegion(target.getColorBufferTexture());
            region.flip(false, true);
            image.setDrawable(new TextureRegionDrawable(region));
        }
        loading.setVisible(false);
    }

    @Override
    public void dispose() {
        unfocus();
        scene = null;
        installed = false;
        image.setDrawable(null);
        if (terrain != null) {
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
    }
}
