/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.MouseWheelEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;

import megamek.logging.MMLogger;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.folding.CurlyFoldParser;
import org.fife.ui.rsyntaxtextarea.folding.FoldParserManager;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;

/** EDT-owned editing surfaces. No OpenGL calls, disk writes while typing, or copies of the board scene. */
final class GpuShaderEditor {
    private static final MMLogger LOGGER = MMLogger.create(GpuShaderEditor.class);
    private static final String LANGUAGE = "text/megamek-glsl";
    private final JFrame window = new JFrame("MegaMek — Shader editor");
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTree tree = new JTree();
    private final JTextField filter = new JTextField();
    private final JTextField find = new JTextField(18);
    private final JTextField replace = new JTextField(18);
    private final JCheckBox matchCase = new JCheckBox("Match case");
    private final JCheckBox live = new JCheckBox("Live preview", true);
    private final JLabel status = new JLabel("Choose a shader. The board is your live preview.");
    private final JLabel location = new JLabel(" ");
    private final JTextArea diagnostics = new JTextArea(4, 60);
    private final JButton failedSource = new JButton("Show failed source");
    private final GpuShaderPreviewPanel objectPreview = new GpuShaderPreviewPanel();
    private final GpuShaderInputPanel inputEditor = new GpuShaderInputPanel();
    private final Map<String, GpuShaderSource.FileSource> sources;
    private final Map<String, Document> documents = new LinkedHashMap<>();
    private final BiConsumer<Map<String, String>, Consumer<GpuShaderManager.Result>> preview;
    private final Timer debounce;
    private long version;
    private boolean closed;
    private boolean savePending;
    private GpuShaderManager.Sources failure;

    private static final class Document {
        final String name;
        final RSyntaxTextArea text;
        final RTextScrollPane scroll;
        final JLabel tabTitle = new JLabel();
        GpuShaderSource.FileSource saved;

        Document(String name, GpuShaderSource.FileSource source) {
            this.name = name;
            saved = source;
            text = code(source.text(), true);
            scroll = scroll(text);
        }

        boolean dirty() { return !text.getText().equals(saved.text()); }
    }

    GpuShaderEditor(Map<String, GpuShaderSource.FileSource> sources, String graphics,
          BiConsumer<Map<String, String>, Consumer<GpuShaderManager.Result>> preview,
          Consumer<Consumer<List<GpuShaderManager.Sources>>> compiled) {
        this.sources = new LinkedHashMap<>(sources);
        this.preview = preview;
        debounce = new Timer(500, event -> apply(false));
        debounce.setRepeats(false);
        ((AbstractTokenMakerFactory) TokenMakerFactory.getDefaultInstance()).putMapping(LANGUAGE,
              GpuGlslTokenMaker.class.getName());
        FoldParserManager.get().addFoldParserMapping(LANGUAGE, new CurlyFoldParser());
        window.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        window.setTitle("MegaMek — Shader editor — " + graphics);
        window.setMinimumSize(new Dimension(980, 650));
        window.setSize(1300, 860);
        window.setLocationByPlatform(true);
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        toolbar.add(live);
        toolbar.add(button("Apply", "Compile now (Ctrl+Enter)", () -> apply(false)));
        toolbar.add(button("Save all", "Apply and save edited files (Ctrl+S)", () -> apply(true)));
        toolbar.add(button("Revert file", "Restore this file's last saved text", this::revert));
        toolbar.add(button("Reload file", "Reread the selected file after editing it outside MegaMek", this::reload));
        toolbar.add(button("Reload all files", "Reread all shader files from disk, including closed files", this::reloadAll));
        toolbar.add(button("Compiled sources", "Inspect complete active shader variants and their generated line numbers",
              () -> compiled.accept(this::showSources)));
        failedSource.setEnabled(false);
        failedSource.addActionListener(event -> { if (failure != null) { showSources(List.of(failure)); } });
        toolbar.add(failedSource);
        live.addActionListener(event -> {
            if (live.isSelected() || savePending) { debounce.restart(); }
            else { debounce.stop(); status.setText("Live preview paused. Apply with Ctrl+Enter."); }
        });

        JPanel navigation = new JPanel(new BorderLayout(0, 5));
        navigation.setBorder(BorderFactory.createEmptyBorder(0, 5, 5, 0));
        filter.setToolTipText("Filter shader filenames");
        filter.putClientProperty("JTextField.placeholderText", "Filter shaders…");
        navigation.add(filter, BorderLayout.NORTH);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        navigation.add(new JScrollPane(tree), BorderLayout.CENTER);
        refreshTree();
        listen(filter, this::refreshTree);
        tree.addTreeSelectionListener(event -> {
            if (tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node
                  && sources.containsKey(node.toString())) { open(node.toString()); }
        });
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addChangeListener(event -> showLocation());
        JPanel search = new JPanel(new GridLayout(2, 1));
        JPanel findRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        JPanel replaceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        search.add(findRow);
        search.add(replaceRow);
        find.setToolTipText("Find in the current file (Ctrl+F, Enter for next, Shift+Enter for previous)");
        replace.setToolTipText("Replacement text");
        findRow.add(new JLabel("Find")); findRow.add(find);
        findRow.add(button("Next", "Find next", () -> search(true, false, false)));
        findRow.add(button("Previous", "Find previous", () -> search(false, false, false)));
        findRow.add(matchCase);
        replaceRow.add(new JLabel("Replace")); replaceRow.add(replace);
        replaceRow.add(button("Replace", "Replace the current match", () -> search(true, true, false)));
        replaceRow.add(button("All", "Replace all matches in this file", () -> search(true, true, true)));
        find.addActionListener(event -> search(true, false, false));
        shortcut(find, "shift ENTER", "previous", () -> search(false, false, false));
        JPanel editing = new JPanel(new BorderLayout());
        editing.add(search, BorderLayout.NORTH);
        editing.add(tabs, BorderLayout.CENTER);
        location.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        editing.add(location, BorderLayout.SOUTH);
        JSplitPane sidebar = new JSplitPane(JSplitPane.VERTICAL_SPLIT, navigation, objectPreview);
        sidebar.setResizeWeight(.6);
        sidebar.setDividerLocation(260);
        JSplitPane horizontal = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, editing);
        horizontal.setDividerLocation(330);
        horizontal.setResizeWeight(0);
        diagnostics.setEditable(false);
        diagnostics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        diagnostics.setText("Edits preview after a 0.5 second pause. Save all writes to the paths shown below each file.\n"
              + "Compile errors keep the last working preview. Closing this window keeps your drafts until the board closes.");
        JTabbedPane details = new JTabbedPane();
        details.addTab("Inputs", inputEditor);
        details.addTab("Diagnostics", new JScrollPane(diagnostics));
        JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, horizontal, details);
        vertical.setResizeWeight(0.82);
        vertical.setDividerLocation(580);
        window.add(toolbar, BorderLayout.NORTH);
        window.add(vertical, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        window.add(status, BorderLayout.SOUTH);
        shortcut(window.getRootPane(), "ctrl ENTER", "apply", () -> apply(false));
        shortcut(window.getRootPane(), "ctrl S", "save", () -> apply(true));
        shortcut(window.getRootPane(), "ctrl F", "find", () -> { find.requestFocusInWindow(); find.selectAll(); });
        shortcut(window.getRootPane(), "ctrl W", "close-tab", () -> closeTab(selected()));
        if (sources.containsKey("atmosphere-composite.frag")) { open("atmosphere-composite.frag"); }
    }

    void show() { window.setVisible(true); window.toFront(); }
    void close() { closed = true; debounce.stop(); objectPreview.close(); inputEditor.close(); window.dispose(); }
    GpuShaderPreviewPanel previewPanel() { return objectPreview; }
    GpuShaderInputPanel inputsPanel() { return inputEditor; }

    private void refreshTree() {
        String query = filter.getText().toLowerCase(Locale.ROOT);
        var root = new DefaultMutableTreeNode("Shaders");
        Map<String, DefaultMutableTreeNode> groups = new LinkedHashMap<>();
        for (String name : sources.keySet().stream().sorted().toList()) {
            if (!name.toLowerCase(Locale.ROOT).contains(query)) { continue; }
            var group = groups.computeIfAbsent(group(name), key -> {
                var node = new DefaultMutableTreeNode(key); root.add(node); return node;
            });
            group.add(new DefaultMutableTreeNode(name));
        }
        tree.setModel(new DefaultTreeModel(root));
        for (int row = 0; row < tree.getRowCount(); row++) { tree.expandRow(row); }
    }

    private static String group(String name) {
        if (name.startsWith("water") || name.startsWith("ocean") || name.startsWith("liquid")) { return "Water"; }
        if (name.startsWith("terrain") || name.startsWith("road") || name.startsWith("fungus")) { return "Terrain"; }
        if (name.startsWith("unit")) { return "Units"; }
        if (name.startsWith("hex")) { return "Board overlays"; }
        if (name.startsWith("atmosphere") || name.startsWith("cloud") || name.startsWith("weather")
              || name.startsWith("sun") || name.startsWith("scattering") || name.startsWith("ground-layer")) {
            return "Atmosphere";
        }
        if (name.startsWith("beam") || name.startsWith("effects") || name.startsWith("explosion")
              || name.startsWith("missile") || name.startsWith("particles") || name.startsWith("projectile")) {
            return "Combat effects";
        }
        return "Shared functions";
    }

    void open(String name) {
        Document document = documents.get(name);
        if (document == null) {
            document = new Document(name, sources.get(name));
            documents.put(name, document);
            Document created = document;
            document.text.getDocument().addDocumentListener(listener(() -> edited(created)));
            shortcut(document.text, "ctrl F", "shader-find", () -> { find.requestFocusInWindow(); find.selectAll(); });
            shortcut(document.text, "ctrl S", "shader-save", () -> apply(true));
            shortcut(document.text, "ctrl ENTER", "shader-apply", () -> apply(false));
            shortcut(document.text, "ctrl W", "shader-close-tab", () -> closeTab(created));
        }
        if (tabs.indexOfComponent(document.scroll) < 0) {
            tabs.addTab(name, document.scroll);
            var header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            header.setOpaque(false);
            header.add(document.tabTitle);
            Document opening = document;
            var close = button("×", "Close tab (Ctrl+W). Unsaved drafts are retained.", () -> closeTab(opening));
            close.setMargin(new Insets(0, 4, 0, 4));
            close.setFocusable(false);
            header.add(close);
            tabs.setTabComponentAt(tabs.indexOfComponent(document.scroll), header);
            title(document);
        }
        tabs.setSelectedComponent(document.scroll);
        document.text.requestFocusInWindow();
        showLocation();
    }

    private Document selected() {
        return documents.values().stream().filter(document -> document.scroll == tabs.getSelectedComponent())
              .findFirst().orElse(null);
    }

    private void edited(Document document) {
        version++;
        title(document);
        status.setText(live.isSelected() ? "Waiting for typing…" : "Modified. Press Apply to preview.");
        if (live.isSelected() || savePending) { debounce.restart(); }
    }

    private void title(Document document) {
        String title = document.name + (document.dirty() ? " *" : "");
        document.tabTitle.setText(title);
        int index = tabs.indexOfComponent(document.scroll);
        if (index >= 0) { tabs.setTitleAt(index, title); }
    }

    private void closeTab(Document document) {
        if (document == null) { return; }
        tabs.remove(document.scroll);
        // Clearing the tree selection lets another click reopen the same file.
        tree.clearSelection();
        if (document.dirty()) { status.setText("Tab closed; its unsaved draft is retained and included in Save all."); }
    }

    private void showLocation() {
        Document document = selected();
        if (document == null) {
            location.setText(" "); objectPreview.select(null); inputEditor.select(null); return;
        }
        location.setText((document.saved.exists() ? "File: " : "Bundled source — Save creates: ") + document.saved.destination());
        location.setToolTipText(location.getText());
        objectPreview.select(document.name);
        inputEditor.select(document.name);
    }

    void apply(boolean save) {
        debounce.stop();
        if (closed || sources.isEmpty()) { return; }
        // Keep an explicit save requested while keystrokes supersede an in-flight compilation.
        savePending |= save;
        long submittedVersion = version;
        Map<String, String> drafts = new LinkedHashMap<>();
        sources.forEach((name, source) -> drafts.put(name, source.text()));
        documents.forEach((name, document) -> drafts.put(name, document.text.getText()));
        status.setText("Compiling…");
        preview.accept(Map.copyOf(drafts), result -> {
            if (closed || submittedVersion != version) { return; }
            display(result);
            boolean shouldSave = savePending;
            savePending = false;
            if (shouldSave && result.success()) {
                try {
                    for (Document document : documents.values()) {
                        if (!document.dirty()) { continue; }
                        document.saved = GpuShaderSource.save(document.saved, drafts.get(document.name));
                        sources.put(document.name, document.saved);
                        title(document);
                    }
                    status.setText("Saved. " + result.message());
                    showLocation();
                } catch (IOException failure) {
                    status.setText("Preview applied; save failed.");
                    diagnostics.setText(failure.getMessage());
                }
            }
        });
    }

    void fallback(GpuShaderManager.Result result) { if (!closed) { display(result); } }

    private void display(GpuShaderManager.Result result) {
        status.setText(result.message().lines().findFirst().orElse(""));
        diagnostics.setText(result.message());
        diagnostics.setCaretPosition(0);
        failure = result.failure();
        failedSource.setEnabled(failure != null);
    }

    private void revert() {
        Document document = selected();
        if (document != null) { document.text.setText(document.saved.text()); document.text.setCaretPosition(0); }
    }

    private void reload() {
        Document document = selected();
        if (document == null) { return; }
        // Programs built from disk afterwards read the files again too (GpuShaderSource#forget).
        GpuShaderSource.forget();
        try {
            if (Files.exists(document.saved.destination())) {
                document.saved = new GpuShaderSource.FileSource(Files.readString(document.saved.destination()),
                      document.saved.destination(), true);
                sources.put(document.name, document.saved);
            } else if (document.saved.exists()) {
                throw new IOException("File no longer exists: " + document.saved.destination());
            }
            revert();
            showLocation();
        } catch (IOException failure) {
            status.setText("Could not reload file.");
            diagnostics.setText(failure.getMessage());
        }
    }

    private void reloadAll() {
        if (documents.values().stream().anyMatch(Document::dirty)
              && JOptionPane.showConfirmDialog(window, "Reload all shader files from disk? Unsaved drafts, including closed tabs, will be replaced.",
                    "Reload all files", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) { return; }
        reloadAllFromDisk();
    }

    void reloadAllFromDisk() {
        GpuShaderSource.forget();
        Map<String, GpuShaderSource.FileSource> loaded = new LinkedHashMap<>();
        try {
            var names = new java.util.TreeSet<>(sources.keySet());
            var discovered = GpuShaderSource.files();
            names.addAll(discovered);
            for (String name : names) {
                if (discovered.contains(name)) { loaded.put(name, GpuShaderSource.file(name)); }
                else {
                    var old = sources.get(name);
                    loaded.put(name, new GpuShaderSource.FileSource(Files.readString(old.destination()), old.destination(), true));
                }
            }
        } catch (IOException | RuntimeException failure) {
            status.setText("Could not reload all files; existing documents were retained.");
            diagnostics.setText(failure.getMessage());
            return;
        }
        savePending = false;
        version++;
        sources.clear(); sources.putAll(loaded);
        documents.forEach((name, document) -> {
            document.saved = loaded.get(name);
            document.text.setText(document.saved.text());
            document.text.setCaretPosition(0);
            title(document);
        });
        refreshTree();
        showLocation();
        apply(false);
    }

    private void search(boolean forward, boolean replaceMatch, boolean all) {
        Document document = selected();
        if (document == null || find.getText().isEmpty()) { return; }
        SearchContext context = new SearchContext();
        context.setSearchFor(find.getText());
        context.setReplaceWith(replace.getText());
        context.setMatchCase(matchCase.isSelected());
        context.setSearchForward(forward);
        context.setSearchWrap(true);
        var result = all ? SearchEngine.replaceAll(document.text, context)
              : replaceMatch ? SearchEngine.replace(document.text, context) : SearchEngine.find(document.text, context);
        if (!result.wasFound()) { status.setText("No match in " + document.name); }
    }

    private void showSources(List<GpuShaderManager.Sources> values) {
        if (values.isEmpty()) { status.setText("No shader programs have been drawn yet."); return; }
        JDialog dialog = new JDialog(window, "Compiled GLSL — driver line numbers", false);
        JComboBox<String> variants = new JComboBox<>(values.stream().map(GpuShaderManager.Sources::name).toArray(String[]::new));
        RSyntaxTextArea vertex = code("", false), fragment = code("", false);
        JTabbedPane stages = new JTabbedPane();
        stages.addTab("Vertex", scroll(vertex));
        stages.addTab("Fragment", scroll(fragment));
        Runnable select = () -> {
            var value = values.get(variants.getSelectedIndex());
            vertex.setText(value.vertex()); fragment.setText(value.fragment());
            vertex.setCaretPosition(0); fragment.setCaretPosition(0);
        };
        variants.addActionListener(event -> select.run());
        select.run();
        dialog.add(variants, BorderLayout.NORTH);
        dialog.add(stages, BorderLayout.CENTER);
        dialog.setSize(950, 720);
        dialog.setLocationRelativeTo(window);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setVisible(true);
    }

    private static RSyntaxTextArea code(String source, boolean editable) {
        RSyntaxTextArea text = new RSyntaxTextArea(source);
        text.setSyntaxEditingStyle(LANGUAGE);
        text.setCodeFoldingEnabled(true);
        text.setAntiAliasingEnabled(true);
        text.setTabsEmulated(true);
        text.setTabSize(4);
        text.setBracketMatchingEnabled(true);
        text.setAutoIndentEnabled(true);
        text.setEditable(editable);
        try (var theme = Theme.class.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/dark.xml")) {
            if (theme != null) { Theme.load(theme).apply(text); }
        } catch (IOException failure) { LOGGER.warn("Cannot load shader editor colors", failure); }
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        text.setCaretPosition(0);
        text.discardAllEdits();
        return text;
    }

    private static RTextScrollPane scroll(RSyntaxTextArea text) {
        return new RTextScrollPane(text) {
            @Override
            protected void processMouseWheelEvent(MouseWheelEvent event) {
                // A listener on the text area prevents AWT from forwarding ordinary wheel events to this pane.
                if (event.isControlDown()) {
                    text.setFont(text.getFont().deriveFont((float) Math.clamp(
                          text.getFont().getSize() - event.getWheelRotation(), 10, 32)));
                    event.consume();
                } else {
                    super.processMouseWheelEvent(event);
                }
            }
        };
    }

    private static JButton button(String name, String tooltip, Runnable action) {
        JButton button = new JButton(name);
        button.setToolTipText(tooltip);
        button.addActionListener(event -> action.run());
        return button;
    }

    private static void shortcut(JComponent component, String key, String name, Runnable action) {
        int condition = component instanceof javax.swing.text.JTextComponent
              ? JComponent.WHEN_FOCUSED : JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT;
        component.getInputMap(condition).put(KeyStroke.getKeyStroke(key), name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    private static void listen(JTextField field, Runnable action) { field.getDocument().addDocumentListener(listener(action)); }

    private static DocumentListener listener(Runnable action) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) { action.run(); }
            @Override
            public void removeUpdate(DocumentEvent event) { action.run(); }
            @Override
            public void changedUpdate(DocumentEvent event) { }
        };
    }
}
