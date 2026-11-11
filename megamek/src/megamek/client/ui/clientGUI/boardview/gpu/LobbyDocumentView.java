/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import jakarta.annotation.Nullable;
import megamek.client.lobby.HelpDocument;
import megamek.client.lobby.HelpDocument.Block;
import megamek.client.lobby.HelpDocument.Chapter;
import megamek.client.lobby.HelpDocument.Kind;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiReveal;
import megamek.client.ui.gdx.UiTheme;

/**
 * A help document or battle report a chapter at a time: the chapters down a rail at the left, the chapter that shows
 * at the right under its title, and Previous and Next in the footer. A guide's topics are sections, each heading over
 * its text at the page's full width, a report's are cards; a list of short items reads as tags, code as a code chip, and a report's lines as events, each the line that
 * starts it over its indented details, edged in the colour of the unit that acts.
 * <p>
 * GPU thread.
 */
final class LobbyDocumentView {
    /** A list of at least this many items, none longer than {@link #TAG_LENGTH}, reads as tags. */
    static final int TAG_COUNT = 4;
    static final int TAG_LENGTH = 24;

    private final UiKit kit;
    private final boolean motion;
    private final boolean grid;
    private final List<Chapter> chapters;
    private final List<UiButton> entries = new ArrayList<>();
    private final List<Image> marks = new ArrayList<>();
    private final Table page = new Table();
    private static final float MARK = 2;
    private final ScrollPane pageScroll;
    private final @Nullable UiButton previous;
    private final @Nullable UiButton next;
    private final @Nullable Label where;
    private int shown;

    /**
     * @param grid whether the document is a guide, its topics full-width sections, rather than a report of cards
     */
    LobbyDocumentView(UiKit kit, UiDialog dialog, HelpDocument document, boolean grid, boolean motion,
          Runnable close) {
        this.kit = kit;
        this.motion = motion;
        this.grid = grid;
        this.chapters = document.chapters();
        // The rail and the page scroll on their own, so the rail stays while a long chapter scrolls
        dialog.scroll().setScrollingDisabled(true, true);
        Table body = dialog.body();
        body.top().left();
        float height = size("dialog.md");
        boolean several = chapters.size() > 1;
        if (several) {
            Table rail = new Table();
            rail.top().left();
            for (int i = 0; i < chapters.size(); i++) {
                rail.add(entry(i)).growX().row();
            }
            ScrollPane railScroll = kit.scrollList(rail, true, false);
            railScroll.setName("document-rail");
            body.add(railScroll).width(size("dialog.sm") / 2).prefHeight(height).minHeight(0).growY().top()
                  .padRight(space("lg"));
        }
        page.top().left();
        pageScroll = kit.scrollList(page, true, false);
        pageScroll.setName("document-page");
        body.add(pageScroll).grow().prefHeight(height).minHeight(0).minWidth(0);
        if (several) {
            previous = kit.button("button-icon", "chevron-left", null, null);
            previous.setName("document-previous");
            kit.tip(previous, true).getActor().setText(Messages.getString("Previous"));
            UiKit.onChange(previous, () -> show(shown - 1));
            next = kit.button("button-icon", "chevron-right", null, null);
            next.setName("document-next");
            kit.tip(next, true).getActor().setText(Messages.getString("Next"));
            UiKit.onChange(next, () -> show(shown + 1));
            where = kit.typeLabel("", "small", "text.muted");
            where.setName("document-where");
            dialog.leading().add(previous);
            dialog.leading().add(next).padLeft(space("xs"));
            dialog.leading().add(where).padLeft(space("md"));
        } else {
            previous = null;
            next = null;
            where = null;
        }
        dialog.primary(Messages.getString("Close"), close).setName("document-close");
        if (chapters.isEmpty()) {
            page.add(kit.typeLabel(Messages.getString("Lobby.readout.none"), "body", "text.muted")).left();
        } else {
            show(0);
        }
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    /** A chapter's rail entry: its title, with a mark at its edge while it shows. */
    private Table entry(int index) {
        Table entry = new Table();
        Image mark = kit.solid("accent.primary");
        marks.add(mark);
        UiButton row = kit.menuRow(UiTheme.TOKEN_MENU_ROW, chapters.get(index).title(), null, null, false);
        row.setName("document-chapter-" + index);
        row.getLabel().setWrap(true);
        // A wrapped title knows its height only from a fixed width, so a long title's lines do not overlap the next
        float width = size("dialog.sm") / 2 - MARK - space("xs") - row.getPadLeft() - row.getPadRight();
        row.getCell(row.getLabel()).width(width).minWidth(0).padTop(space("xs")).padBottom(space("xs"));
        row.getLabel().setWidth(width);
        UiKit.onChange(row, () -> show(index));
        entries.add(row);
        entry.add(mark).width(MARK).growY().padRight(space("xs"));
        entry.add(row).growX().minWidth(0).minHeight(size("control.sm"));
        return entry;
    }

    /** Shows chapter {@code index}, from its top. */
    void show(int index) {
        if (index < 0 || index >= chapters.size()) {
            return;
        }
        shown = index;
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).pressed(i == index);
            marks.get(i).setVisible(i == index);
        }
        if (previous != null && next != null && where != null) {
            previous.setDisabled(index == 0);
            next.setDisabled(index == chapters.size() - 1);
            where.setText(Messages.getString("Lobby.document.where", index + 1, chapters.size()));
        }
        Chapter chapter = chapters.get(index);
        page.clearChildren();
        UiReveal content = new UiReveal(motion);
        Label title = kit.roleTypeLabel("headline", chapter.title(), "text.primary");
        title.setWrap(true);
        title.setName("document-title");
        content.band(title).padBottom(space("md"));
        chapter(content, chapter.blocks());
        page.add(content).growX().minWidth(0);
        pageScroll.layout();
        pageScroll.setScrollY(0);
        pageScroll.updateVisualScroll();
    }

    /** A chapter's blocks: those before its first topic as they come, then its callouts and its topics' cards. */
    private void chapter(UiReveal content, List<Block> blocks) {
        List<Block> free = new ArrayList<>();
        int at = 0;
        while (at < blocks.size() && !starts(blocks.get(at))) {
            free.add(blocks.get(at++));
        }
        if (!free.isEmpty()) {
            content.band(blocks(free)).padBottom(space("md"));
        }
        while (at < blocks.size()) {
            Block head = blocks.get(at++);
            List<Block> topic = new ArrayList<>();
            while (at < blocks.size() && !starts(blocks.get(at))) {
                topic.add(blocks.get(at++));
            }
            if (head.kind() == Kind.CALLOUT) {
                content.band(callout(head.text())).padBottom(space("md"));
                if (!topic.isEmpty()) {
                    content.band(blocks(topic)).padBottom(space("md"));
                }
                continue;
            }
            if (topic.isEmpty()) {
                continue;
            }
            if (grid) {
                content.band(section(head, topic)).padBottom(space("lg"));
            } else {
                content.band(card(head, topic)).padBottom(space("md"));
            }
        }
    }

    private static boolean starts(Block block) {
        return block.kind() == Kind.HEADING || block.kind() == Kind.CALLOUT;
    }

    /** A headline result, such as who won, as a banner. */
    private Table callout(String text) {
        Table banner = kit.card();
        banner.setName("document-callout");
        banner.pad(space("lg"));
        Label label = kit.roleTypeLabel("display", text, "accent.primary");
        label.setWrap(true);
        label.setAlignment(Align.center);
        banner.add(label).growX().minWidth(0);
        return banner;
    }

    /** A guide's topic: its title as a section heading over its blocks, the page's full width. */
    private Table section(Block head, List<Block> topic) {
        Table section = new Table();
        section.setName("document-section");
        section.top().left();
        Label title = kit.roleTypeLabel("headline", head.text(), "accent.primary");
        title.setWrap(true);
        if (head.color() != null) {
            title.setColor(Color.valueOf(head.color()));
        }
        section.add(title).growX().minWidth(0).left().row();
        section.add(blocks(topic)).growX().minWidth(0).left().padTop(space("sm")).row();
        return section;
    }

    /** A topic: its title over its blocks, on a card. */
    private Table card(Block head, List<Block> topic) {
        Table card = kit.card();
        card.setName("document-topic");
        card.top().left();
        card.pad(space("md"));
        Label title = kit.roleTypeLabel("title", head.text(), "text.primary");
        title.setWrap(true);
        if (head.color() != null) {
            title.setColor(Color.valueOf(head.color()));
        }
        card.add(title).growX().minWidth(0).left().row();
        card.add(blocks(topic)).growX().minWidth(0).left().padTop(space("sm")).row();
        card.add().grow();
        return card;
    }

    /** Blocks one under another: runs of bullets as a list or tags, runs of report lines as events. */
    private Table blocks(List<Block> blocks) {
        Table column = new Table();
        column.top().left().defaults().left().growX().minWidth(0);
        int at = 0;
        boolean first = true;
        while (at < blocks.size()) {
            Block block = blocks.get(at);
            boolean leading = first;
            float gap = leading ? 0 : space("sm");
            first = false;
            switch (block.kind()) {
                case BULLET -> {
                    List<Block> run = new ArrayList<>();
                    while (at < blocks.size() && blocks.get(at).kind() == Kind.BULLET) {
                        run.add(blocks.get(at++));
                    }
                    column.add(tags(run) ? tags(run, 3) : bullets(run)).padTop(gap).row();
                    continue;
                }
                case LINE -> {
                    List<Block> run = new ArrayList<>();
                    while (at < blocks.size() && blocks.get(at).kind() == Kind.LINE) {
                        run.add(blocks.get(at++));
                    }
                    column.add(events(run)).padTop(gap).row();
                    continue;
                }
                case CODE -> column.add(code(block.text())).padTop(gap).row();
                case SUBHEADING -> {
                    Label label = wrapped(block.text(), "label", "text.secondary");
                    if (block.color() != null) {
                        label.setColor(Color.valueOf(block.color()));
                    }
                    column.add(label).padTop(leading ? 0 : space("md")).row();
                }
                default -> column.add(wrapped(block.text(), "body", "text.primary")).padTop(gap).row();
            }
            at++;
        }
        return column;
    }

    /** Whether a run of list items reads as tags: enough of them, all short and none nested. */
    static boolean tags(List<Block> run) {
        return run.size() >= TAG_COUNT && run.stream()
              .allMatch(item -> item.depth() == 0 && item.text().length() <= TAG_LENGTH);
    }

    private Table tags(List<Block> run, int columns) {
        Table tags = new Table();
        tags.top().left();
        float gap = space("xs");
        for (int i = 0; i < run.size(); i++) {
            Table tag = new Table();
            tag.setName("document-tag");
            tag.setBackground(kit.skin.newDrawable("white", UiTheme.tokenColor("surface.raised")));
            tag.pad(space("xxs"), space("sm"), space("xxs"), space("sm"));
            tag.add(wrapped(run.get(i).text(), "small", "text.primary")).growX().minWidth(0);
            tags.add(tag).growX().uniformX().fill().minWidth(0).padRight(gap).padBottom(gap);
            if ((i + 1) % columns == 0) {
                tags.row();
            }
        }
        for (int i = run.size() % columns; i > 0 && i < columns; i++) {
            tags.add().growX().uniformX().padRight(gap);
        }
        return tags;
    }

    private Table bullets(List<Block> run) {
        Table list = new Table();
        list.top().left();
        boolean first = true;
        for (Block item : run) {
            Label dot = kit.typeLabel("\u2022", "body", "accent.primary");
            list.add(dot).top().padLeft(item.depth() * space("lg")).padRight(space("sm"))
                  .padTop(first ? 0 : space("xs"));
            list.add(wrapped(item.text(), "body", "text.primary")).growX().minWidth(0).top()
                  .padTop(first ? 0 : space("xs")).row();
            first = false;
        }
        return list;
    }

    /** Code, such as a chat command's syntax, as a chip. */
    private Table code(String text) {
        Table chip = new Table();
        chip.setName("document-code");
        chip.setBackground(kit.skin.newDrawable("white", UiTheme.tokenColor("surface.input")));
        chip.pad(space("xs"), space("sm"), space("xs"), space("sm"));
        chip.add(wrapped(text, "body.strong", "accent.primary")).growX().minWidth(0);
        return chip;
    }

    /**
     * A report's lines as events: a line starts one and the lines indented deeper than it are its details, under it
     * in small type; each event is edged in the colour of the unit or player its lines start with.
     */
    private Table events(List<Block> run) {
        Table events = new Table();
        events.top().left();
        int at = 0;
        boolean first = true;
        while (at < run.size()) {
            Block head = run.get(at++);
            List<Block> details = new ArrayList<>();
            while (at < run.size() && run.get(at).depth() > head.depth()) {
                details.add(run.get(at++));
            }
            events.add(event(head, details)).growX().minWidth(0).padTop(first ? 0 : space("xs")).row();
            first = false;
        }
        return events;
    }

    private Table event(Block head, List<Block> details) {
        Table event = new Table();
        event.setName("document-event");
        String color = head.color();
        for (int i = 0; i < details.size() && color == null; i++) {
            color = details.get(i).color();
        }
        Actor edge = (color == null) ? kit.solid("border.subtle") : kit.solid(Color.valueOf(color));
        event.add(edge).width(3).growY().padRight(space("sm"));
        Table lines = new Table();
        lines.top().left();
        lines.add(wrapped(head.text(), "body", "text.primary")).growX().minWidth(0).left().row();
        for (Block detail : details) {
            lines.add(wrapped(detail.text(), "small", "text.secondary")).growX().minWidth(0).left()
                  .padLeft((detail.depth() - head.depth() - 1) * space("lg")).padTop(space("xxs")).row();
        }
        event.add(lines).growX().minWidth(0).top();
        return event;
    }

    private Label wrapped(String text, String type, String role) {
        Label label = kit.typeLabel(text, type, role);
        label.setWrap(true);
        return label;
    }

    // ---- For the probe and the tests ----

    int shown() {
        return shown;
    }

    int chapterCount() {
        return chapters.size();
    }

    ScrollPane pageScroll() {
        return pageScroll;
    }
}
