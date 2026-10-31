# MegaMek Suite design system: gap analysis

Status: draft v0 for discussion. Step 1 of the unified look-and-feel effort.

## Goal and decisions so far

One look and feel for the controls of MegaMek, MegaMekLab, MekHQ and the launcher, implemented twice from one source
of truth:

- **libGDX**: the native board and HUD (`megamek.client.ui.gdx`).
- **Swing**: a FlatLaf theme plus a small custom component library in `megamek`, used by every Swing app.

Agreed so far:

| Topic | Decision |
| --- | --- |
| Swing toolkit | Stay on Swing + FlatLaf; no JavaFX. |
| Source of truth | One token file; platform themes are generated from it. MekBay may follow it but is not bound by it. |
| Custom Swing components | Only where FlatLaf theming cannot reach; they live in `megamek` so MML and MHQ share them. |
| Visual reference | The libGDX hud-v3 style. The Immersive/interstellar blue palette is retired in favor of it. |
| Modes | Dark **and** light. |
| Scaling | Required on both platforms. |
| Accessibility | Contrast and colorblind safety are criteria when choosing tokens. |
| Fonts and icons | Shared 100% across platforms. |
| Pilot | Buttons first. |
| Base | Start from hud-v3 structure, but fix its contrast and colorblind problems rather than copying its palette. |
| Swing themes | The four stock FlatLaf themes are replaced by MegaMek Dark and MegaMek Light. |
| Button family | Collapse the 19 hud-v3 styles into a small variant × size set. |
| Focus | Designed for Swing first, then ported to libGDX. |
| On, selected and focus | **Final.** Three looks. **On** (checked toggle, selected segment, on chip, check box, radio, switch) is the solid cream `surface.inverse` face with `text.onInverse` marks, as hud-v3's `button-checked`; a checked toggle looks like the primary button, as in hud-v3. **Selected** (list, table and tree rows, tabs, selectable panels, unit rows, menu and select checks) is a neutral wash plus a `selected.primary` (inverse color) marker, underline, edge or check. **Mint** is only focus, progress, slider fill, links, info and badges. Focus on a cream face is the mint ring `focus.gap` outside it, falling back to two inside lines where it would be clipped. On while disabled is the faint `surface.inverseDisabled` face with a `text.onInverse` label. This replaces the earlier "mint selection everywhere". Violet stays only for the Game Master (`status.gamemaster`). See [tokens.md](tokens.md#color-roles). |
| Palettes | **Final.** Four themes: dark and light, each in a standard and a color-blind safe palette, picked by a user setting ("Color-blind safe colors"). Standard statuses use the expected red, amber and green and are checked for typical vision; the color-blind palette changes only the statuses and is checked for all simulated vision types. Contrast is checked in all four. Player and team colors are out of scope; whether MekHQ and MegaMekLab share the setting is decided when they adopt the tokens. Both galleries have a switch. See [tokens.md](tokens.md#palettes). |
| Frames | Corner ticks for regular frames; Immersive dialogs may keep their own style. |
| Startup screens | `MegaMekButton` bitmap skins will probably be replaced by the shared button (not final). |
| libGDX light mode | Deferred; the HUD is dark only for now. The Java2D board has no light/dark theming either. |
| Accent | hud-v3 mint, retuned as needed; it stops doubling as the success color. |
| Migration | The stock themes stay behind a hidden "classic" option until migration is done. |
| Presentation mode | **Final.** Two modes, chosen per screen: **game** and **app**. The mode sets the text case, the [type width](tokens.md#type-width) and the slider thumb (game: hud-v3's thin bar; app: a disc), and later differences join it as whole-screen choices. Game mode puts the short framing labels (one to three words) in upper case; app mode is sentence case. Content is sentence case in both. Game screens to start with: the in-game libGDX HUD, startup and main menus, MekHQ Immersive dialogs and the interstellar map. Everything else starts as app and can move to game when it is reviewed. Weight and size rules are shared; upper-case text uses each style's wider **caps tracking** token (values signed off: display .04, headline .05, title .06, label .08, overline .1 em). Both galleries have a switch between the modes. See [tokens.md](tokens.md#presentation-mode). |
| Type width | **Final.** Roboto Condensed (`wdth` 75) for game mode's framing roles (display, headline, title, button, tab, overline) and a new `name` role for unit and pilot names (`body.strong`, sentence case), at the same weight (500) and tracking rules; body, small, caption, field labels, tables, values and running text stay regular width, and app mode is all regular width. Condensed packs some 15-20% more into narrow HUD columns and reads as technical and military; it stays off running text, tables and app mode because condensed letters crowd at 10-12 px and tire in long text. hud-v3's wide tracking on mixed case is not adopted: it uses up the space condensed saves. All condensed roles are medium, so Swing needs one more static face, Roboto Condensed Medium. See [tokens.md](tokens.md#type-width). |
| Panels and rails | **Final.** Ticked frames (panels, dialog cards, status cards) draw their edge only at the top and bottom, a paper roll, since the ticks close the sides. The edge stays the quiet `border.subtle` so busy screens don't fill with lines (revisited and kept). Only the outermost frame has ticks: cards in a card grid stay plain, so a grid doesn't repeat a tick set per card. hud-v3's `panel-rails` (the Tactical View chip) keeps its rails as `token-rails`; `token-frame` is the plain frame. See [tokens.md](tokens.md#panels). |
| Dialog buttons | **Final.** Centred, equal width, main action first, in both presentation modes. hud-v3 puts them in the bottom-right corner, but that is a desktop OS habit, and one rule for both modes means shared dialogs don't move their buttons. See [tokens.md](tokens.md#dialogs). |
| Segmented control | **Final.** Separate segments, `xs` apart, each with its own edge, instead of a joined frame with hairlines, in both sizes: hud-v3's joined `hud-pill` becomes the compact size, not a second look. See [tokens.md](tokens.md#segmented-control). |
| Primary button | **Final.** The `surface.inverse` face, hud-v3's cream `hud-main` (dark in light mode), not mint, so mint stays for focus; checked toggles share its look. See [tokens.md](tokens.md#buttons). |
| Menus and popups | **Final.** A plain popup frame without corner ticks in both modes; ticks frame lasting containers (panels, dialogs, status cards), and submenus would stack them. See [tokens.md](tokens.md#menus). |
| Dial | **Final.** The angle dial keeps hud-v3's mint hub ring, and the needle stops at it. See [tokens.md](tokens.md#inputs). |
| Framed button | Corner ticks that become a full frame on hover (hud-v3 bracket, Immersive `FramedCommandButton`) are one shared special-purpose variant. |
| MekHQ `hud` library | Folded into the new library. |
| Launcher | Retune its colors to the tokens for now; shared controls wait for the planned monorepo. |
| Accessibility modes | Light and dark only; no dedicated high-contrast mode. |
| Character | A game UI, not an OS one. Where game conventions and desktop conventions differ, prefer what fits a game; desktop conventions are a fallback, not the goal. |
| Token location | `megamek` repository, next to both theme implementations. Fonts and icons stay in `mm-data`. |
| Ownership | Vicente owns UI decisions and presents them to the team. |
| Review | Runnable galleries opened from MegaMek: one Swing, one libGDX. The Swing library is the complete set; libGDX is a subset that grows on demand. |
| Naming, translucency | Role-based token names with a `UiTheme` rename pass; surfaces get opaque and overlay values. |
| In-game UI | Once a game starts, all UI is libGDX and dark only; the remaining in-game Swing dialogs migrate to libGDX. The lobby is a libGDX scene of the window (step 6, L1); its dialogs stay Swing for now. |
| Lobby | **Final.** The lobby is rewritten in libGDX as a scene of the one window, for MegaMek and for MekHQ games alike: one lobby, not a Swing one beside a libGDX one. It replaces the Swing lobby outright, with no fallback while it is built, and lands as one pull request. The Swing briefing room explored on `design-system-start-followups` (steps 6 and 7 there) is the starting point for its layout, not a spec to copy: where libGDX allows more, such as a live 3D map or motion between views, the design is rethought. The lobby's Swing dialogs stay Swing at first and open over the window, as the board's do. See step 6 under Next steps. |
| Native window | One libGDX window for the whole run (`GpuHost`), switching between scenes: start screen, lobby, map editor and preview, game and UI library. No Swing frame shows beside it; Swing dialogs are natively owned by it so they stay in front. A game always uses the GPU board; Legacy 2D remains only for map previews and the editor's 2D Preview. |
| Animation | Broad UI animation (for example panel reveals) comes after the unification pass. |

## Sources inventoried

| Source | Where | Tech | Notes |
| --- | --- | --- | --- |
| hud-v3 toolkit | [`UiTheme`](../../megamek/src/megamek/client/ui/gdx/UiTheme.java), [`UiKit`](../../megamek/src/megamek/client/ui/gdx/UiKit.java), [`UiButton`](../../megamek/src/megamek/client/ui/gdx/UiButton.java) and 15 more widgets; documented in [gpu-hud.md §8](../gpu-hud.md) | libGDX scene2d | The reference. Ported from the MekBay "hud-v3" CSS mock; comments cite its CSS classes (`.b`, `.b.util`, `.row`, `.fsel`...). Dark only. |
| Immersive controls | MekHQ `mekhq/gui/baseComponents/ImmersiveControlStyle` + `ImmersiveComboBox`, `ImmersiveCheckBox`, `ImmersiveRadioButton`, `ImmersiveSpinner`, `ImmersiveScrollBarStyle` | Swing, hand-painted | Navy/cyan palette, dark only. |
| Immersive dialogs | MekHQ `mekhq/gui/baseComponents/immersiveDialogs/ImmersiveDialogStyle` and `ImmersiveDialog*` | Swing on top of the active FlatLaf theme | Teal signal + amber information, **dark and light** (picks by `Panel.background` luminance). Cut corners, monospace "technical" labels, grid/stripe backdrop. |
| Interstellar map | MekHQ `mekhq/gui/InterstellarMapPanel` (10k lines, ~200 color constants), `InterstellarMapLegend` | Swing/Java2D | Same navy/cyan palette as Immersive controls, plus map-domain colors (HPG classes, routes, territories). |
| MekHQ hud library | `mekhq/gui/baseComponents/hud` (17 classes: `HudStyle`, `HudButton`, `HudCard`, `HudChip`, `HudSegmentedControl`, `HudTabStrip`...) | Swing, hand-painted | Same navy/cyan palette, fixed dark. Merged Oct 2026 with the new contract GUI; 49 files use it. Its name collides with libGDX "hud-v3". |
| Framed command button | `mekhq/gui/baseComponents/FramedCommandButton(Style)` | Swing | Corner ticks that grow into a full frame on hover (320 ms). Theme-derived teal, dark and light. Base of the Immersive dialogs' response buttons. |
| Standalone launcher | `mm-launcher/.../gui/LauncherTheme` | Swing, **no FlatLaf and no megamek dependency** | A copy of the Immersive palette (identical RGB values). Honors Windows high-contrast mode. |
| App startup screens | MegaMek `client/ui/widget/MegaMekButton` + `SkinSpecification`/`SkinXMLHandler` (`defaultSkin.xml`); used by MekHQ `StartupScreenPanel` and MegaMek's main menu | Swing, image-skinned | Bitmap backgrounds, text colors from skin XML (white / yellow active / grey disabled). |
| Everything else in Swing | MM, MML, MHQ | FlatLaf | User picks one of four stock themes: Flat Light, Flat IntelliJ, Flat Dark, Flat Darcula (`MegaMek.java`); MML and MHQ apply the stored choice. No custom FlatLaf properties anywhere. (Since the Swing trial, MegaMek defaults to MegaMek Dark and lists the stock themes as classic ones.) |

About 128 MekHQ source files reference an Immersive component or the interstellar map, so the blue palette has real
reach.

## Tokens

### Color

The libGDX names are mostly *appearance* names (`MINT`, `CORAL`, `FILL`). Light mode needs *role* names, so the
proposed column below is a first draft of semantic names for Token v0.

| Proposed role | hud-v3 (libGDX) | Immersive / map / launcher | Immersive dialogs (dark / light) | FlatLaf stock | Gap |
| --- | --- | --- | --- | --- | --- |
| `surface.panel` | `PANEL` rgba(18,24,26,.92) | `PANEL` #050D17, `CONTROL_BACKGROUND` #0C1D2A | derived from `Panel.background` | theme | hud-v3 panels are translucent (sit over the board); Swing needs an opaque equivalent. |
| `surface.raised` | `PANEL2` rgba(28,36,38,.97) | `ACTIVE_BACKGROUND` #122D38 | mix(panel, label, 6%) | theme | |
| `surface.popover` | `POP` #172022 | `POPUP_BACKGROUND` #07101B | — | theme | |
| `surface.input` | white @ .05 over panel | `INPUT_BACKGROUND` #11404E | — | theme | |
| `text.primary` | `TEXT` #F1F4F0 | `TEXT` #DAE7EB | `Label.foreground` | theme | |
| `text.secondary` | `ACCENT` #CBD3D0 (button labels) | — | — | — | hud-v3 `ACCENT` is a light grey, not an accent; rename. |
| `text.muted` | `MUTED` #9AA6A3 | `MUTED` #8499A1 | — | theme | |
| `text.disabled` | `DISABLED` #5F6B69 | `MUTED` (reused) | — | theme | |
| `border.default` | `QUIET` rgba(174,187,180,.24) | `BORDER` #234252 | — | theme | Both fail 3:1 (see Accessibility). |
| `border.hover` | `HOVER` #E8F1EB | — | — | theme | |
| `border.divider` | `LINE` white @ .08 | `DIVIDER` cyan @ 45/255 | — | theme | |
| `accent.primary` (focus, selection, active) | `MINT` #82E2CE | `ACCENT` #41D2E0 | signal #56D0C5 / #177070 | theme | Three different teals today. Converge on one per mode. |
| `accent.selected` (current/selected item) | — (selection uses `MINT` or `FILL`) | `SELECTED` #EBA642 (orange) | — | — | hud-v3 has no orange "selected"; the map's selected-system/active-route orange must map to a role. |
| `fill.strong` (checked / primary button face) | `FILL` #F0F2EE, `MAIN` #F1F3F2 | — | — | — | Inverted (light-on-dark) faces are a hud-v3 signature; need a light-mode rule. |
| `fill.strong.text` | `FILL_INK` #16201E, `FILL_MUTED` #39433F | — | — | — | |
| `status.danger` | `CORAL` #EC9189 (hover #FF554C) | `NAVIGATION_BLOCKED` #EA5656 | — | — | |
| `status.warning` | `AMBER` #E6C991 | `NAVIGATION_CAUTION` #F2B848 | information #EBB14C / #965C10 | — | |
| `status.success` / friendly | `MINT` (shared with accent) | `PLAYER_BASE` #57D6BE | — | — | Accent and success share a color in hud-v3. |
| `status.info` / special | `VIOLET` #B9A8FF | — | — | — | |
| `status.attention` | `BLIP` #FFAE66 | `CURRENT_LOCATION` #FFBE52 | — | — | |
| `badge` | `AMBER` with #1A1A1A text | — | — | — | |
| `scrollbar.thumb` | #4A5553 (v), #738581 (h) | #366571 | — | theme | |

Out of scope for the shared palette, but should *use* status tokens where the meaning matches: map-domain colors
(HPG classes, faction territory, reachability, routes), hex terrain, unit tints.

### Typography

| | hud-v3 | Swing today |
| --- | --- | --- |
| Family | Roboto variable (`mm-data/data/fonts/Roboto`), Regular/Medium/Condensed instances | OS default via FlatLaf (Segoe UI on Windows); Immersive labels use `Font.MONOSPACED` bold |
| Scale | 13 named styles, 10–23 units: `hud-body` 13, `hud-small` 11.5, `hud-medium` 12.5, `hud-utility` 10.5, `hud-sub` 10, `hud-caption` 10.5, `hud-mini` 11, `hud-button` 12.5, `hud-name` 13.5, `hud-title` 16, `hud-heading` 20, `hud-phase` 23, `hud-main` 17 | FlatLaf default size (scaled by the user's GUI scale) |
| Tracking | Per style, 0–.14em; uppercase at display time for titles, captions, buttons | None |
| Fallbacks | Noto Sans Symbols 2, then Bitter Bold for arrows | JDK fallback |

Gaps:

- Swing needs to load Roboto (variable font) at runtime. Java2D does not select variable-font named instances;
  static Roboto/Roboto Condensed weights may need to ship alongside, or FlatLaf's bundled Roboto font extension used.
  To investigate in step 4.
- Swing has no letter-spacing beyond `TextAttribute.TRACKING`. **Resolved:** the tokens give each style a tracking
  and, where it can be upper case, a wider caps tracking; Swing applies them through `TextAttribute.TRACKING`
  (`TokenFonts`), libGDX bakes them into the font.
- 13 sizes is a HUD scale; desktop forms need a body size that reads well at 100% scaling. Decide whether Swing uses
  the same scale or a subset.
- Uppercase-at-display is a style rule both platforms must apply the same way (and it must be locale-aware for i18n).
  **Resolved:** the [presentation](tokens.md#presentation-mode) modes; both platforms read the same role rules from the tokens
  and upper-case with the player's locale when the text is drawn.

### Shape, spacing and size

| | hud-v3 | Immersive / launcher | Immersive dialogs |
| --- | --- | --- | --- |
| Corner radius | 3 on buttons, fields, rows, chips; 2 on pill sections | 0 (square) | 0 + **cut corners** of 11 |
| Panel frame | `HudFrame`: rail border plus corner ticks (13) | 1px border | Angular frame, 2px signal underline on headers, 3px left bar on information panels |
| Button heights | 36 default, 46 main, 26 mini, 30 icon, 34 plain, 56 utility | 30 (`CONTROL_HEIGHT`) | FlatLaf default |
| Button padding (v × h) | 8 × 12 default, 3 × 9 mini | — | — |
| Field padding | 7 × 10 | — | — |
| Panel padding | 12 × 14 | — | 10 frame, 6 section gap |
| Border width | 1 (1.5 on dial hub, 2 on tab underline) | 1 | 1, 2, 3 |

Gap: hud-v3 has an implicit spacing scale (2, 3, 4, 6, 7, 8, 9, 10, 12, 13, 14) rather than a stepped one. Token v0
should propose a step scale (for example 2/4/6/8/12/16/24) and map existing values onto it.

### Icons

- hud-v3: Material Symbols Rounded variable font (`mm-data/data/fonts/Icons`), ~60 named glyphs baked into a texture
  sheet; four are drawn filled.
- Swing: the interstellar map already draws one Material Symbol glyph (`MATERIAL_EDIT_SYMBOL`); elsewhere PNG icons and
  FlatLaf's built-in icons.
- Good news: Roboto, Material Symbols and Noto Symbols 2 are already in mm-data `main`, not only on the GDX branch.
- Gap: the icon *name* map (`UiTheme.ICONS`) belongs in the token source so both platforms use the same names and
  codepoints. Swing can render the same font glyphs, or use the SVG versions through FlatLaf's `FlatSVGIcon`.

### Motion

- hud-v3 state changes (hover, pressed, checked) are **instant**; only `UiList` reordering animates (critically damped
  springs, ~150 ms settle).
- Immersive dialogs have text reveal and distortion effects (bespoke; stay custom).
- Interstellar map animates at 16 ms steps; zoom settles after 180 ms.
- FlatLaf has no state transitions of its own, so the Swing themes add them: one shared animator eases every control
  between states on the `motion` tokens, with a reduce-motion setting; see [tokens.md](tokens.md#motion).

### Scaling

| | Rule |
| --- | --- |
| hud-v3 | One logical-pixel scale per window ([`DisplayScale`](../../megamek/src/megamek/client/ui/gdx/DisplayScale.java)): max(monitor DPI, window resolution / 1600×1000), capped so 960×640 units fit, times the user preference. Tokens are in those units (one unit = one CSS px of the mock). |
| Swing | `UIUtil.scaleForGUI(n)` on every fixed measurement, plus FlatLaf's own `flatlaf.uiScale`. |

Both platforms already think in logical pixels, so tokens can be logical pixels with no conversion. The open point is
that hud-v3 also grows with window size, which Swing does not, so the same token can render at different physical sizes
in the HUD and in a Swing dialog side by side.

## Components

### Buttons (pilot)

hud-v3 already has **19 button styles**, so consolidation is needed inside libGDX too, not only across platforms:

| hud-v3 style | Look | Proposed variant |
| --- | --- | --- |
| `hud` | quiet: faint fill, `QUIET` border, hover border `HOVER`, checked = filled `FILL` | **secondary** (default) |
| `hud-main` | filled `MAIN` with `FILL_INK` text, 46 high | **primary** |
| `hud-danger`, `hud-delete-icon` | coral text, coral-tinted hover | **danger** (+ icon-only size) |
| `hud-plain` | quiet, normal-case Roboto Medium, 34 high | secondary, *normal-case* option |
| `hud-mini`, `hud-utility-small` | 26 / 28 high | secondary, **small** size |
| `hud-icon` | no frame at rest, framed on hover, 30×30 | **ghost**, icon-only |
| `hud-utility`, `-narrow`, `-row` | bracket corner ticks at rest, outline on hover, filled when checked; icon above label, 56 high | **toolbar / utility** (HUD-specific? decide) |
| `hud-seg`, `hud-pill` | segments of one pill; pressed section filled | **segmented control** (separate component) |
| `hud-tab`, `hud-tab-caps` | text only; checked = 2px mint underline | **tabs** (separate component) |
| `hud-row`, `hud-menu-row` | list row / popover item | **list row**, **menu item** (separate components) |
| `hud-select`, `hud-select-inline` | drop-down face; inline has a dotted underline | **select** (separate component) |

States used: up, over, down (= over), checked, checkedOver (= checked), disabled. There is **no focus state**, so
keyboard users cannot see where focus is. A shared spec must add `focused` (a focus ring) on both platforms.

Swing today: FlatLaf `JButton` (theme-dependent, with focus ring), `MegaMekButton` (bitmap skin), the Immersive
`TransmissionResponseButton` (frame style), the launcher's `FirstLaunchButton` and split buttons, and the map's
layer-control buttons. Roughly six visual families.

### Other controls

| Component | hud-v3 | Immersive (Swing) | FlatLaf | Notes |
| --- | --- | --- | --- | --- |
| Text field | `field` box, mint border + mint tint on focus | `INPUT_BACKGROUND` | yes | Search field variant in both. |
| Select / combo | `hud-select` face + `UiPopover` list | `ImmersiveComboBox` | yes | |
| Checkbox | Material Symbols `checkbox-on`/`-off` glyphs | `ImmersiveCheckBox` | yes | Radio: not in hud-v3. |
| Number / spinner | `UiNumber` (caption scrub), `UiSliderInput` | `ImmersiveSpinner` | yes | Scrub-to-change is HUD-only behavior. |
| Tabs | underline tabs | — | `JTabbedPane` | FlatLaf supports underline tabs natively. |
| List row / tree | `UiList`, `UiTree` | — | `JList`, `JTree` | |
| Popover / menu | `UiPopover`, `UiMenuList` | map popup colors | `JPopupMenu` | |
| Tooltip | `panel` frame, `hud-small`, wraps at 340 | — | yes | |
| Dialog / modal | `UiModal`, `UiKit.dialog` (620 wide), `UiConfirmation` | `ImmersiveDialog*` | `JDialog` | Decided: corner ticks; a modal card over a scrim, or a window. See [Dialogs](tokens.md#dialogs). |
| Status card | `UiKit.Progress` (print card) | — | — | Decided: the dialog's frame without a scrim, Cancel in the header. See [Status cards](tokens.md#status-cards). |
| Panel / frame | `HudFrame` with corner ticks | angular surface | `JPanel` | Decided: the dialog's frame without rails, status ticks, selectable and plain variants. See [Panels](tokens.md#panels). |
| Scrollbar | 4-wide thin knob | `ImmersiveScrollBarStyle` | yes | FlatLaf thin-scrollbar properties cover it. |
| Chip / badge | `chip`, `chip-warn`, `chip-bad`, amber badge | — | — | Decided: status, filter and tag chips in sentence case; count and dot badges in the accent. See [Chips and badges](tokens.md#chips-and-badges). |
| Toast | `GpuToastStack` (3px bar, above the dock) | `BoardToastOverlay` (tinted fill) | — | Decided: top centre, bar and icon, the newest on top, hover pause and ✕. See [Toasts](tokens.md#toasts). |
| Section header / rule | `rule`, `UiKit.header` | `createSectionHeader` (mono label + rule) | — | |

## Accessibility findings

Measured against hud-v3's panel composited over a dark board (#111719). WCAG 2.2 asks for 4.5:1 for body text, 3:1 for
large text, and 3:1 for the borders that identify a control (non-text contrast).

| Pair | Ratio | Verdict |
| --- | ---: | --- |
| `TEXT` on panel | 16.3 | pass |
| `ACCENT` (button label) on panel | 11.9 | pass |
| `MUTED` on panel | 7.2 | pass |
| `DISABLED` on panel | 3.3 | ok (disabled is exempt) |
| `MINT` / `CORAL` / `AMBER` / `VIOLET` on panel | 11.8 / 7.7 / 11.3 / 8.7 | pass |
| `FILL_INK` on `FILL` | 14.8 | pass |
| **Button border** `QUIET` on panel | **1.7** | **fail** (3.0) |
| **Field border** white@.18 on panel | **1.8** | **fail** |
| **Chip border** #4B5654 on panel | **2.4** | **fail** |
| Divider `LINE` on panel | 1.2 | ok if purely decorative |
| Immersive `BORDER` on control background | 1.6 | fail |

The quiet-button and field outlines are the hud-v3 look's defining feature, and they fail non-text contrast. Options:
raise them to ~3:1, or keep them subtle and make sure every control is also identifiable by its label or fill. This is
a design call for the group.

Colorblind simulation (Machado 2009, full severity), CIE ΔE76 between status colors, where below ~20 is hard to tell
apart at a glance:

| Pair | Normal | Deutan | Protan | Tritan |
| --- | ---: | ---: | ---: | ---: |
| `CORAL`–`AMBER` (danger vs warning) | 36 | **15** | 25 | 32 |
| `AMBER`–`BLIP` | 27 | **18** | **16** | 21 |
| `CORAL`–`BLIP` | 33 | 29 | 35 | **12** |
| `MINT`–`CORAL` (friend vs foe) | 70 | 27 | 22 | 90 |

Danger vs warning collapses for deuteranopes (~5% of men). Status colors need a second cue (icon or shape, as the chips
partly do with `chip-warn`/`chip-bad`), and danger should probably move to a redder, darker hue to separate it from
amber. Friend vs foe stays distinguishable but with a thin margin under protanopia.

Light mode: there are no hud-v3 light values to measure yet. Immersive dialogs' light colors (signal #177070 on light
panels) are the only light-mode precedent.

## Gaps and decisions needed

Items 1–10 and 12–14 are settled (see the table at the top); item 1 is deferred for libGDX but still needs design
work for Swing, and item 11 is under investigation.

1. **Light mode does not exist in hud-v3.** Every token needs a light value, and the inverted `FILL` faces need a
   light-mode rule. Needs design work, not just mapping.
2. **User-selectable Swing themes.** Today users pick among four stock FlatLaf themes. Proposal: replace them with
   "MegaMek Dark" and "MegaMek Light" (generated), optionally keeping stock themes as "classic" during migration.
3. **Accent.** Three teals today (#82E2CE, #41D2E0, #56D0C5). Converge on hud-v3 `MINT` for dark; derive a light
   counterpart.
4. **Orange "selected".** The map and Immersive controls use orange for the selected/current item; hud-v3 uses mint
   or a white fill. Decide whether `accent.selected` exists or maps to `accent.primary`.
5. **Frame language.** hud-v3 corner ticks vs Immersive cut corners. Pick one for panels and dialogs; the other can
   stay as a decorative variant of Immersive dialogs. *Decided: corner ticks for panels and dialogs; cut corners stay
   in the Immersive dialogs.*
6. **Focus state.** hud-v3 has none; add a focus ring to the spec.
7. **Border contrast.** See Accessibility.
8. **Danger hue / colorblind cues.** See Accessibility.
9. **Button family size.** 19 hud-v3 styles → about 5 variants × 3 sizes, plus separate tabs, segmented, row, menu
   item and select components.
10. **Utility (bracket) buttons.** HUD-only, or part of the shared set (for example a Swing toolbar)?
11. **Fonts in Swing.** Java2D only draws the default instance of the Roboto variable font, so medium (500) rendered
    as regular. Fixed with a static Roboto Medium generated into `mm-data`; see [tokens.md](tokens.md#fonts).
12. **Startup screens.** `MegaMekButton` bitmap skins: migrate to the shared button, or keep as themed art?
13. **Translucency.** hud-v3 panels are translucent over the board; Swing surfaces are opaque. Tokens need both an
    opaque and an overlay value for surfaces.
14. **Token naming.** hud-v3 names (`ACCENT` is grey, `MINT` doubles as success) do not survive light mode; the
    generator will emit role names, and `UiTheme` will need a rename pass.

## Next steps

- ~~Token v0~~: done, see [tokens.md](tokens.md).
- ~~Shared assets~~: static Roboto Medium and Roboto Condensed Medium for Swing are in `mm-data`.
- ~~FlatLaf theme~~: MegaMek Dark and MegaMek Light are built from the tokens and selectable; see
  [tokens.md](tokens.md#swing-themes). Making them the default and hiding the stock themes behind a classic option
  comes after the controls are settled.
- ~~Button spec~~: Primary, Secondary, Quiet, Danger, Toggle, Icon and Special in regular and compact sizes, with
  every state including focus, in Swing and libGDX; see [tokens.md](tokens.md#buttons).
- ~~Focus~~: one keyboard-only ring inside the edge for every Swing control, and on libGDX buttons; see
  [tokens.md](tokens.md#focus).
- ~~Inputs~~: text, password, search, number and text area at button heights, with hover, focus, status, read-only
  and disabled looks, plus the form field (label above, helper or status below), in Swing; libGDX has the in-game
  subset (no number spinner yet); see [tokens.md](tokens.md#inputs).
- ~~Choices~~: check box (with a mixed state), radio button and switch, with hover, focus and disabled looks, in
  Swing and libGDX; see [tokens.md](tokens.md#choices).
- ~~Tabs and segmented control~~: underlined tabs and a segmented control of separate segments with a cream
  selected one, in two sizes, in Swing and libGDX; see [tokens.md](tokens.md#tabs).
- ~~Select~~: an input-faced select in two sizes and an inline one with a dotted underline, sharing one popup list
  (neutral wash, a check on the current value), in Swing and libGDX; see [tokens.md](tokens.md#select).
- ~~Lists and trees~~: inset rounded rows at least 28 high, a neutral hover wash, and a stronger neutral wash plus a
  leading marker for the selection, with two-line rows and an empty state, in Swing; libGDX keeps hud-v3's `UiList` and
  `UiTree` until the developers accept the system; see [tokens.md](tokens.md#lists-and-trees).
- ~~Menus~~: menu bars, menus and context menus on the select's popup, with neutral hover rows, checks and
  radios, and muted shortcuts, in Swing; see [tokens.md](tokens.md#menus).
- ~~Tooltips~~: small cards on the popup surface that wrap at 280, with a key chip for shortcuts and a title over
  secondary detail for info cards, in Swing; see [tokens.md](tokens.md#tooltips).
- ~~Scrollbars~~: a thin pill thumb with no track or arrows of its own, sitting on the content it scrolls, with a
  rail and a brighter thumb under the pointer, in Swing; see [tokens.md](tokens.md#scrollbars).
- ~~Sliders~~: an accent fill up to a cream `surface.inverse` thumb (a round disc in app mode, hud-v3's flat thin bar in game mode) on a pill track, the same with ticks and labels, with an optional
  value readout, in Swing; see [tokens.md](tokens.md#sliders).
- ~~Tables~~: the list's rows across every column with faint zebra stripes, a hover wash over the cells, the
  selection wash and marker, and upper-case overline headers aligned like their column, in Swing; see
  [tokens.md](tokens.md#tables).
- ~~The rest of the gallery~~: progress bars (a thin accent pill with its text above it), section headers (titled
  panels, collapsible sections and separators share one upper-case header with a line) and split panes (a subtle line
  that turns accent under the pointer), in Swing; see [tokens.md](tokens.md#progress-bars),
  [Sections](tokens.md#sections) and [Split panes](tokens.md#split-panes). No view uses a toolbar, so there is none.
- ~~Motion~~: hover that comes in fast and lingers out, instant presses with fading releases, drawn checks, sliding
  switches, the Special button's closing ticks, fading popups and tooltips, opening sections and per-row hover washes,
  from one shared animator with a reduce-motion client setting, in Swing; see [tokens.md](tokens.md#motion).
- ~~Dialogs~~: a corner-ticked modal card over a scrim for messages, confirmations and short forms, and a themed window
  for tools, with centred equal-width buttons (main action first), Enter and Escape, Cancel-first destructive
  confirmations and restyled `JOptionPane` dialogs, in Swing; see [tokens.md](tokens.md#dialogs).
- ~~Status cards~~: the progress of a long task in the dialog's frame, beside the view or over a scrim, with Cancel in
  the header, a percentage on the bar and a failed state with Close and Retry, in Swing; see
  [tokens.md](tokens.md#status-cards).
- ~~Panels and frames~~: the dialog card's corner-ticked frame for anything that isn't a dialog, with an optional
  header, status ticks with their icon, selectable unit cards (hover, selection, focus ring, one at a time),
  plain frames for groups inside a panel and a border for existing panels, with paper-roll edges, in Swing; see
  [tokens.md](tokens.md#panels).
- ~~Chips and badges~~: status chips with their icon and an edge in the status color, filter chips (a check when on,
  one at a time in a group), tag chips removed by their ✕ or Delete, and count and dot badges in the accent or a status
  color on a button's corner, in Swing; see [tokens.md](tokens.md#chips-and-badges).
- ~~Toasts~~: short notices at the top centre of a view, a bar and an icon in the level's color (the Game Master in
  violet with a shield), the newest on top and at most five, paused by the pointer, closed by their ✕, with clicks
  passing through, in Swing; see [tokens.md](tokens.md#toasts).
- ~~All-caps~~ (discussion closed): a game and an app presentation mode, chosen per screen. Game mode upper-cases only
  the short framing labels, with signed-off caps tracking, and sets them and unit names in condensed type (see Type
  width); content stays sentence case in both modes. Both galleries switch between the modes and compare each hud-v3
  font with its token type as a game screen draws them, on a realistic sample for that kind of text. In Swing and
  libGDX; see [tokens.md](tokens.md#presentation-mode).
- ~~Drake's feedback~~: presentation mode (text case plus the slider thumb), violet kept only for the Game Master, standard and color-blind safe palettes as a user setting, paper-roll panel edges, rails
  kept for `panel-rails`, separate segments and the dial's mint hub, in Swing and libGDX.
- ~~Type width~~: game mode sets the framing labels and unit names in Roboto Condensed Medium (a second static face for
  Swing in `mm-data`), the rest regular width, in Swing and libGDX; see [tokens.md](tokens.md#type-width).
- ~~On state~~: cream for every "on" control (toggles, segments, chips, check boxes, radios, switches), neutral
  selection for items in collections, mint only for focus and accents, an outside focus ring on cream faces and a
  faint cream disabled-on look, in Swing and libGDX; see [tokens.md](tokens.md#color-roles).
- A Swing trial: move MegaMek's startup path (everything before the libGDX board opens) onto the themes and the new
  controls, so the developers can judge the system in real use. The startup path is also reorganized where the old
  layout gets in the way. Steps, one screen at a time:
  1. ~~Theme~~: MegaMek Dark is MegaMek's default; settings list the MegaMek themes, the stock ones behind **Show
     classic themes**. Players who picked a theme keep it.
  2. ~~Shared dialog footers~~: `AbstractButtonDialog`, `AbstractValidationButtonDialog` and the Host/Connect
     dialogs put their buttons in `TokenDialogs.footer`, Ok as the primary button first. Dialogs with their own
     footers (settings, game options, scenario chooser, unit and bot dialogs) move with their own steps.
  3. Main menu, in libGDX (a Swing version dropped frames on ordinary machines, and the 2D board that kept the
     startup path in Swing is going away): a full-window game screen over the battle art
     (`GpuStartWindow`, `GpuStartScreen`), opened by a skippable "Welcome back" greeting and reveal on app launch (an
     instant cut with reduce motion), with Settings, Help and Tools on screen and Up, Down, Tab and Enter moving
     through the menu. The developers chose the hex reveal: hexes pop open across the art from left to right. The menu
     sits in a column on the right, because the art's main mech stands on the left, with the tip card at the bottom
     left. The window opens maximized the first time and remembers its bounds; it is the one window of the whole run,
     and hosting or joining a game switches it to the lobby scene. If OpenGL cannot start, MegaMek shows an error and exits. The licensing
     notice waits for the reveal to end. The old splash medal is dropped. Its pages replace the menu in the menu's own
     column, with Back (Esc) to return, so the art stays in view. ~~G1, the window and menu~~, ~~G2, the Host
     page~~, ~~G3, the Connect page~~, ~~G4, Load game~~, ~~G5, the scenario browser~~ and ~~G6, the briefing~~ are
     done. **New game** opens the Host page: the player name, then a closed
     **Online options** section
     (password, port, the server browser and the local and public addresses), **Host game** and Back. The section
     opens by itself when the password or port was changed before or the port is wrong; errors show under the
     inputs, and Enter in a field hosts while Tab moves to the next control. The password has an eye button that shows
     it as typed, in both presentations, because a game password is a word shared with friends and there is no
     confirm field to catch a typo. The local address shows at once; the public one is only asked of
     checkip.amazonaws.com when the player clicks **Show**, so it stays off streams and screenshots and MegaMek makes no
     outside request unasked. **Connect to a game** opens the Connect page: a **Player | Bot** segmented control at
     the top, the name, the server address beside its port, a **Strategic BattleForce game** check box for the player
     only (there is no SBF bot), **Connect** (**Connect bot**, which opens the bot setup) and Back; errors show under
     the inputs as on Host. It has no password: joining a game never asks for one, since MegaMek's server password
     only guards game options and server commands, which ask for it when they need it. When `savegames` has a
     save, a **Continue** item tops the menu with the newest save's name and how long ago it was saved, and takes
     the accent from New game. **Load game** opens a list of the saves, newest first, with when each was saved, and
     **Browse files…** for others. Clicking a save (or Continue) loads it at once when the player's last name is the
     save's only human player; otherwise, and from each row's **Options…** and from Browse files, it opens the Host
     form titled Load game, with the save's name and date on top and a **Play as** select of the saved players and
     **Watch as observer**, which asks for an observer name. Sides the save marks as bots come back as default
     Princess bots under their saved names. A save that cannot be read, has no version or comes from another
     version says so in the page instead of a popup. **Scenario** opens the scenario browser: a search field (every
     word must match the name, group or planet) beside a group select (All scenarios or one folder) over a list of
     names, each with its group and planet, and **Open a file…**. On Windows, Browse files and Open a file use the
     system's open-file dialog (LWJGL's Native File Dialog, on its own thread, natively owned by the start window
     so it stays in front); elsewhere, or if it fails, Swing's chooser. Picking a scenario opens the **briefing**:
     the title, single player or multiplayer, the description and the players, each with a camo swatch that opens
     the camo chooser (scenarios skip the lobby, so this is the only place to change it; multiplayer sides start in
     their own colour). In single player you are the first player and the others are bots; in multiplayer each
     player has a You | Human | Bot select, the first is You and the rest Bot, an observer name shows when nobody is
     You, and the server password and port show only while a side is Human. **Rules** and **Conditions** lines
     summarise the game options and planetary conditions, with **Change…** opening their Swing dialogs (or "Set by
     the scenario" when the scenario fixes them); Start no longer pops them up. **Start scenario** reads
     "Starting…" while it works and comes back if the start fails, rebuilding the game with the player's choices
     when the server already took it. The load and scenario pages are wider than the menu, so long names fit, and
     their list fills the room left in the column. Ctrl+click on Load game and Scenario still reaches quick load
     and the last scenario. Follow-ups: a **Load anyway** for saves from another version (to propose to the MegaMek
     team), the map thumbnail and the native dialog on macOS and Linux (step 5.5), and libGDX camo, options and
     conditions pickers with the lobby's dialogs (step 8). To review a page without clicking through it, set
     `MM_START_PAGE` (such as `host+online+reveal`, `connect+bot`, `load`, `resume`, `scenarios` or `briefing`, with
     `MM_START_SCENARIO` naming the scenario file) before `gradlew :megamek:run`;
     `MM_START_SHOT` saves a PNG from the screen's own frame buffer, `MM_START_SHOT_AT` sets when, and
     `MM_START_QUIT=true` quits afterwards (`StartScreenProbe`). `MM_START_PAGE=lobby` hosts a local game at once
     with the Host page's saved name ("Probe Player" when none is saved) on the first free port from the saved one,
     so the lobby scene shows; `MM_LOBBY_BOTS=n` adds n Princess bots on team 2, `MM_LOBBY_UNITS` loads `.mul`
     files for the player, `lobby+ready` readies the player alone before the shot and `lobby+start` presses Ready once
     the bots have joined. `MM_LOBBY_SIZE=WIDTHxHEIGHT` sizes the window (without saving the size), `lobby+narrow`
     makes it 1100 by 800 so the battlefield folds into its rail, and `lobby+rail` opens the folded battlefield. The
     same `MM_START_SHOT*`
     switches take the lobby's shot, which also logs the open windows (libGDX windows, GPU threads and showing
     Swing windows) to `logs\unified_log.log` (`GpuLobbyProbe`).
  4. ~~Host and Connect~~ as modal cards over the main menu in app mode: form fields with their labels above, the
     main action named for what it does (**Host game**, **Connect**) and errors under the inputs instead of popups.
     Host keeps the password, port and server browser in a closed **Online options** section, which opens when the
     password or port was changed before or one of them is wrong. Bot setup moves with the lobby's dialogs (step 8). The new main menu shows these forms as
     its own pages; the card stays for MekHQ.
  5. ~~The scenario flow~~: the scenario browser and the briefing in the start screen's column (G5 and G6 in step 3).
  5.5. Start screen follow-ups, before the lobby:
     - A map thumbnail on the briefing, ideally drawn by the 3D board, so a scenario shows its battlefield before it
       starts.
     - The system's file dialog on macOS and Linux. LWJGL's Native File Dialog supports both, but it is only enabled
       on Windows so far: on macOS it must run on the main thread, which the start window's loop holds (so it would
       open from that loop and pause drawing while it is open), and on Linux it uses GTK, which needs checking
       beside AWT. Both need testing on real machines; Swing's chooser stays the fallback.
  6. ~~The lobby in libGDX~~: a briefing room in game mode, as a scene of the one window, for MegaMek and MekHQ alike (see
     the Lobby decision). The Swing version on `design-system-start-followups` settled the layout: a top bar with the
     title, ready count, a BV chip per side and Ready; players by team on the left, with the chat docked under them;
     forces as unit cards or formations in the centre; the battlefield (map, rules, victory, auto-resolve) on the
     right, folding to a rail in a narrow window; and the unit selector as a Requisition view in the centre column.
     The libGDX lobby starts from that and rethinks it where libGDX allows more. It lands in pieces, in one pull
     request:
     - L0 The lobby model. The lobby's state and actions leave `ChatLounge` and its helpers for a model that knows
       neither Swing nor libGDX: players and teams, forces and formations, units, the map and options, readiness and
       its warnings, chat, and every change the lobby sends to the server (adding, removing, moving and configuring
       units, bots, teams, camo, deployment, C3 and transport). It has unit tests, so the view only reads it and
       calls it.
     - L1 ~~The lobby scene~~. When the game reaches the lounge phase, the window switches to the lobby scene
       (`GpuLobbyWindow` on the Swing thread, `GpuLobbyScreen` on the GPU thread, fed immutable `LobbySnapshot`s)
       instead of showing the lobby frame; when the game starts, it switches to the board, and a game that returns
       to the lounge shows the lobby again. The ClientGUI frame stays hidden. `GpuLobbyOverlay` and the dimmed start
       screen are gone. Leave (or the window's close box) exits as the Swing lobby did and the start screen comes
       back. A MekHQ game opens the window at the lobby with its forces already loaded, then the board, and closes
       after the game, as it does now. If the lobby scene cannot start, the native window closes and the Swing
       frame with `ChatLounge` shows instead, so there is still only one window (L9 replaced this with an error). For L1 the screen is minimal: the
       top bar (title, server line, ready count, Ready and Leave), the players by team and the chat. A probe opens
       it without clicking through (see step 3).
     - L2 ~~The shell~~. The lobby screen now has the briefing room's frame on the plain `surface.base`: the top bar
       (title, server and map line, ready count, a BV chip per side, Leave and Ready) over three columns, the players
       with the chat docked under them, the forces, and the battlefield. Below 1280 units wide the battlefield folds
       into a rail at the right edge that opens it in the centre as a view with Back (or Escape), in the forces'
       place; the Swing room opened it over the forces' right side instead. The columns hold placeholders until
       their steps come: each owner's units and BV in the forces, the map's size in the battlefield
       (`LobbyTopBar`, `LobbyColumns`, `LobbyLayout`, `LobbyCentre` and the column panels). The start screen's art
       is not behind it: dimmed enough to read over, almost nothing of it showed, so it only cost loading time.
     - L3 ~~Players and chat~~. The players column now lists each team under a header: an edge bar (the accent for
       your team, danger for the others), the team's name and its BV, tonnage and units, or "hidden" where blind
       drop hides them. Each player is a card with a color swatch, the name with YOU, BOT, GM or Observer chips (and
       Disconnected for a ghost), the start position (with a warning when it is invalid), the forces line, the ready
       state (a toggle on your own card) and a menu button. The menu, also on right-click, holds what ChatLounge's
       player menu offers: settings, camo, team and deployment submenus, game master, bot settings, remove bot, and
       replace a disconnected player with a bot. ChatLounge has no separate "kick bot" action, so neither does this
       menu. The deployment submenu shows no check, because the snapshot carries only the start position's name. Under
       each team "Add ally" or "Add opponent", and "Add on a new team" at the end, open `BotConfigDialog` and put the
       bot on that team once it joins. The actions run on the EDT through `LobbyPlayerController`, which checks
       permissions and calls ChatLounge's `LobbyCommands` (new `joinTeam` and `localBotClient`). Settings, camo and
       bot setup are still the Swing dialogs, owned by the native window. The chat dock colors each sender by side
       (`ChatLine`), shows server lines muted, keeps to the newest line, sends on Enter (so `/who` and the other
       commands work) and recalls sent lines with Up and Down (`ChatHistory`). Escape closes a menu first, then
       leaves the chat field. Not yet checked live: the Swing dialogs opening from the native window, the bot's team
       move after it joins, and the submenus as drawn (the probe opens only the root menu, `lobby+menu`).
     - L4 ~~Forces~~. The forces column has a Units / Formations switch, a compact toggle, ChatLounge's sort choices
       in a select, and Random army, Add unit and a ⋮ menu in its header. In Units view each owner (you first, then
       by team) has a header with an edge bar and the BV, tonnage and units, or "hidden" under blind drop. A unit card
       shows the lobby sprite in the player's camo, the name, number and BV, the tonnage, weight class, role and
       start, the pilot with gunnery/piloting, and up to three tags (C3, carried, transporting, offboard, hidden,
       damage and configuration warnings in the danger or warning tone) with a "+n" for the rest. Compact rows put
       all of that on one line. Formations view is MegaMek's force tree: a header per formation (fold, glyph, name,
       BV, tonnage, units, owner and ⋮), nested units and subformations, and the units outside any formation under
       their owner. Ctrl and shift click select several units. Dragging units onto a formation, or onto the strip
       between rows, moves or reorders them through ChatLounge's force commands; the target rises (`token-card-drop`)
       and the strip lights while the drag is over it. The unit and formation menus (⋮ and right-click) are
       ChatLounge's own `LobbyMekPopup`, read into `LobbyMenuEntry` trees on the EDT (`LobbySwingMenus`) and drawn as
       nested popovers, so their items, submenus, checks and permissions are ChatLounge's. `LobbyForcesController`
       runs the panel's commands on the EDT with ChatLounge's permission rules. `LobbySnapshot` gained `forces`
       (`LobbyForces`, `LobbyUnit`), built in ChatLounge's sort order with blind drop hiding enemy units as the
       Swing tables do. Add unit opens the unit selector, owned by the native window. Not yet checked live: drag and
       drop and multi-select (the probes do not drag), and the Swing dialogs from the menus. A formation change made
       by another client with no unit change fires no game event, so it shows at the next event, as in ChatLounge.
     - L5 ~~The battlefield~~. The battlefield column shows the map card, the map's size and sheets (or "Rolled at
       launch"), Edit map and a ⋮ menu, then Planetary conditions, Rules (the year and tech level, with up to three
       notable rules as chips and a "+n"), Victory, and a folded Auto-resolve section, each with Change. The map card
       is the minimap drawn once per board or zone change (`LobbyMapThumbnail`, from the reference's
       `StartMapThumbnail`): the server's board, or the one the map settings make of their fixed boards, as
       ChatLounge previews it. Your zone is in the accent edged in `text.primary`, your team's in the accent and the
       others in danger. It is baked on a worker after a short pause and uploaded as a texture. A live 3D view was
       left out: `GpuBattleView` is a whole scene with its own camera, input and asset loading, and has no
       offscreen path, so embedding it would cost far more than a summary card needs; the 3D preview is one click
       away in the ⋮ menu. The card and Edit map open ChatLounge's own map tab in a window owned by the native
       window (`ChatLounge.openMapSetup`), so board size, map choice, map settings, surprise boards, the
       non-tileable nag and who may change the map stay ChatLounge's. The ⋮ menu has random map settings, generate,
       load and save map setup, Preview in 3D and Board Editor. Change runs ChatLounge's buttons for the planetary
       conditions, game options and victory conditions, so their dialogs and permissions are its own. Auto-resolve
       shows when the client setting does and blind drop is not real; its switch, runs and parallelism write
       ChatLounge's controls, and Run presses its button, so the SBF and Alpha Strike conversion prompts are
       unchanged. `LobbyBattlefieldController` runs every action on the EDT and refuses what ChatLounge disables
       (conditions and map files in space, generate while a board is unset or a surprise).
       `LobbySnapshot` gained `battlefield` (`LobbyBattlefield`: the map, conditions, rules, notable rules, victory and auto-resolve lines). The 3D
       preview and the board editor take the window, so the lobby shows again when they close
       (`GpuLobbyWindow.reopenFor`). Probes: `lobby+zones` deploys the player west and the bots east, and
       `lobby+autoresolve` turns on the auto-resolve setting for the run and opens it. Not yet checked live: the
       Swing dialogs from the column, the map setup window, and the preview and editor round trip; MekHQ may flash
       its start screen between the preview and the lobby.
     - L6 ~~Ready~~. The top bar's middle shows "n / m ready", counting only the players the server waits for
       (no ghosts or observers, bots included), success-toned once all are; its tooltip names whom the table waits
       for. Then a Battle Value chip per side in the reference's order: your own team or yourself first (accent
       edge), then the other teams (danger), then the other loners (neutral). Only a real blind drop hides the
       others' BV ("BV ?"), as ChatLounge's team overview does, and the Game Master sees all. Above four sides they
       fold into "Teams: n", which opens a popover listing them. Before Leave and Ready come the warning chips with
       icons, as acting status chips (`UiKit.actingChip`): red for `ReadyCheck`'s blockers (deployment zones that
       overlap under exclusive double-blind deployment, forces without a commander when killing the commander
       wins) and amber for no units, units with problems or needing setup (from the force cards' tags), an
       unfinished map or odd-width boards. They cover the local player, their bots and their units only, as a blind
       drop may hide the others. Each tooltip names who or what; a click shows the first player's card, selects the
       first unit in the forces, or opens the map setup. Past three warnings the rest fold into "+n more", and a
       narrow window folds them all into "Warnings: n"; both open a list. Ready, a primary button, turns green
       with a check once you are ready (`UiTheme.DONE_BUTTON`), as the reference; a click stands down, never
       blocked. Ready while a red warning stands lights the red chips for 700 ms instead of ChatLounge's blocked
       dialog; otherwise it runs `ChatLounge.ready()`, with its GIF question and lobby save. The card and the bar
       show the new state at once and follow the server's echo (or give up after 3 s). The DONE key binding
       (Ctrl+Enter) presses Ready outside text fields, and the tooltips name it. "Unbalanced sides" was left out,
       as the reference has none. `LobbySnapshot` gained `readiness` (`LobbyReadiness`: the sides and the
       warnings), `readyTotal`, `waitingFor` and `withLocalReady`; ChatLounge no longer checks readiness when
       standing down. Probes: `lobby+ready` now readies the player alone a second before the shot, `lobby+start`
       presses Ready as before, `lobby+blocked` turns on the commander victory, `lobby+overlap` exclusive double
       blind deployment, and `lobby+blind` the real blind drop. Not yet checked live: the pulse, the popovers,
       the warning clicks and the key, which the probe does not press.
     - L7 ~~The unit selector~~. The forces column's Add unit opens the Requisition view in the centre
       (`LobbyCentre.REQUISITION`, Back and Escape, which first shuts the Filters drawer, then the dossier). Category
       tabs (All, Meks, Vehicles, Infantry, Battle Armor, ProtoMeks, Aerospace, Large craft) in a scroll strip, the
       search field, the weight chips (Light takes in ultra-light, Assault super-heavy; off for Large craft) and a
       Filters button with its count open the results: a count, a BV / PV toggle and a virtualised list
       (`UiVirtualList`) of rows with the unit's sprite, name, "100 t · Mek · Inner Sphere · 2954" and its BV or PV.
       The Filters drawer holds the dialog's main filters: type within the category, tech base, rules level (none above
       the game's), role, canon, published record sheet, era (from the eras file) and source book, with Clear. A
       picked unit opens the dossier beside the list, or over it in a narrow window: name, details, BV,
       the sprite, move, armor, structure, BV, PV, cost, the merged armament and the details (weight class, tech base,
       role, rules level, year, source). The adding strip (7.4 and 7.5) chooses the owner (you or the bots you may add
       for), skills (Default: the file's; Random, preselected as the dialog's average-skills preference does; Set:
       gunnery and piloting steppers, the shown BV then adjusted) and a quantity up to 12, then Add, which reads
       "Added ✓" a moment; below run the visit's units, BV and tonnage, each added unit with × to take it back out,
       and Sources: Load list… (MUL) and Random army…, the forces column's. The catalogue is the unit cache, filtered
       by the toolkit-free `RequisitionCatalog` exactly as `AbstractUnitSelectorDialog`: the game's year, tech
       level (era-based too), canon-only and invalid units, the search over name, model and the asset card without
       case or accents; `RequisitionSearch` runs it on its own thread, newest query only, the typing debounced by
       150 ms. Adding goes through `LobbyCommands.addUnits`, the dialog's own path (Clan pilots, the asset crew,
       names, `sendAddEntity` and the chat line). Timings on this machine: the first open waits for the unit cache,
       about 1.9 to 2 s (10,920 units); afterwards the full list filters in about 45 ms and "Atlas" in 2 to 3 ms.
       Not ported, being Swing-bound: the TRO readout, Alpha Strike card, factions and damage tabs (since L10), and the
       advanced search (since L11). Probes: `lobby+requisition`, with `+atlas`, `+filters` and `+pick`. Not yet checked live: adding,
       taking back, the owner switch, the keyboard, the dossier's slide and the Sources buttons. MekHQ, MegaMekLab
       and the main menu's unit browser keep `AbstractUnitSelectorDialog`.
     - L8 ~~Dialogs~~. Game options, player settings, bot setup, camo, unit customization, C3, map setup and the load
       and save choosers stay Swing, natively owned by the window so they stay in front of it, as the board's are.
       Those the lobby uses most move to libGDX one at a time afterwards. Every dialog the lobby opens goes through
       one path, `LobbyDialogs`: players (player settings, camo, bot setup, the bot-connect alert, the bot window),
       forces (random army, skills, random names, C3 network, load, save and print list with the MUL check, the
       unit menu's customize, C3, loadout, readout, BV and cost), battlefield (planetary conditions, game options,
       victory conditions, random map, map setup with its preview and size questions, load and save map setup,
       auto-resolve, advanced map search) and the prompts (`SwingLobbyPrompts`: errors, confirmations, the ready
       warning, force names, squadrons, battlefield support, unit editor, training, SBF and AS stats). Each is owned
       by the hidden client frame or a dialog of it; `LobbyDialogs` adopts each one as it shows (also the modal
       questions MekHQ asks with no parent, which Swing gives its shared hidden frame), centres it on the window's
       client bounds rather than the hidden frame, owns it natively (or keeps it on top where it can't), blocks the
       lobby's input while a modal one is open (the floating path did not before), and gives the window the focus
       back when the last one closes if a dialog had it. `ClientGUI.doAlertDialog` sizes its text from the window,
       not the hidden frame. `LobbyDialogsTest` covers the path, `LobbyDialogEntryPointsTest` that no lobby file
       opens a dialog any other way. Probe `lobby+dialog=name` opens one (`gameoptions`, `conditions`, `victory`,
       `randommap`, `mapsetup`, `loadmapsetup`, `camo`, `playersettings`, `botconfig`, `addbot`, `customize`, `c3`,
       `randomarmy`, `skills`, `names`, `loadlist`, `alert`) and photographs the window and its dialogs from the
       screen: all 17 checked on Windows, centred within a pixel, modal ones blocking, the focus back on closing.
       MekHQ needs nothing more: its lobby use is the client API and the parentless questions above. Not checked
       live: the prompts, the save choosers, auto-resolve and advanced map search (the same path, by the source
       test); native ownership and focus on macOS and Linux; dialogs opened from within dialogs, which stay where
       their owner puts them.
     - L9 ~~Removal and checks~~. `ChatLounge` and the Swing lobby's view code are deleted: its player and unit
       tables, force tree, renderers, popups (`LobbyMekPopup`, `PlayerTablePopup`), team overview, key dispatcher and
       `LobbySwingMenus`, 15 files and about 9,200 lines. What the new lobby still took from them moved. The unit and
       force menus are built from the model in `LobbyUnitMenu`, with `LobbyMekPopup`'s items, submenus, checks and
       permissions (a parity test compared the two before the deletion). The sort orders are in `LobbyUnitOrder`,
       and the clean-up of quirks, implants and ammunition when options change is in `LobbyUnitCleanup`. Map setup
       is `MapSetupDialog`, a standalone Swing dialog opened through `LobbyDialogs` with the old map tab's board
       size, map choice, surprise and random boards, generator, previews, load and save and the non-tileable nag.
       Ready (with its GIF question and lobby save), auto-resolve, the battlefield's commands and the menu bar's
       burst-fire and hot-load items are in `SwingLobbyServices`. The commands the unit menus run (customize, C3,
       camo, load, delete, names, skills) are in `SwingLobbyUnitActions`. Copying and pasting units as text (Ctrl+C,
       Ctrl+V in the old table) is gone. ClientGUI no longer builds a lounge panel. If the lobby scene cannot start,
       an error (`Lobby.failure`) shows and the start screen comes back, or MegaMek exits if the window itself
       failed. MekHQ and MegaMekLab use only `LobbyUtility`, `LobbyErrors` and `UIUtil.DOT_SPACER`, which stay, and
       both compile against this MegaMek.
       Checks:
       - A MUL (`MM_LOBBY_UNITS`) loads for the player and a bot.
       - `lobby+start` with a bot readies, answers the GIF question and the bot's readme and reaches the board.
       - `lobby+blind` hides the bot's units and BV.
       - The Game Master's menus and view are covered by `LobbyUnitMenuTest`, `LobbyForcesTest` and
         `LobbySnapshotTest`.
       - A client joining a running game opens the board, and the lobby comes back when the game returns to the
         lounge (`ClientGUIBoardLifecycleTest`).
       - MekHQ's `GameThread` and `AtBGameThread` use only the client API (options, map, conditions, `sendDone`), so
         preloaded games and auto-resolve need no lounge panel, and `CommanderGUI` never had one.
       Not checked live: a real MekHQ game, and a second client reconnecting over the network.
     - L10 ~~Requisition dossier tabs~~. The dossier keeps a slim header (name and details cut with an ellipsis,
       the full name in a tooltip, BV or PV, close; the strip's Add is the only add) over compact `UiTabs`: Overview, Readout, AS card,
       Factions and Damage. The tab picked stays from unit to unit; only the tab in view is built, and it is rebuilt
       only when the unit, its record or the tab's own data change. Once the pointer or keys stay on a unit for
       130 ms, its full record (`MekSummary` to `Entity`) loads on a thread of its own, the newest unit winning
       (`RequisitionLoader`); the stat strip keeps its height meanwhile. The data is built away from libGDX in
       `megamek.client.lobby`:
       - Overview (`RequisitionDossier`): the sprite beside the stat strip (move, heat, armour bar, weapons), then
         role, rules level, introduced, source and cost.
       - Readout (`RequisitionReadout`): the Summary is `EntityReadout`; the TRO is `TROView`'s HTML template
         flattened to the same heading, label and value rows. Both are kit text in a scroll pane. The token
         segmented switch between them is remembered as the client preference `RequisitionReadoutTro`.
       - AS card (`RequisitionAlphaStrike`): built from the summary's Alpha Strike values (type, size, TMM, move,
         damage S/M/L, OV, armour and structure, specials, PV), so it shows at once. It is a kit-built card: the
         Java2D `ASCard` leans on Swing's `UIUtil`, so it was not reused.
       - Factions (`RequisitionFactions`): the Force Generator's (`RATGenerator`) availability for the model, merged
         per era and read on a second thread. It is shown era by era (only the eras that field it), each with the
         factions that field the unit and their rarities as chips. Swing's grid was too wide for the dossier; the
         faction logos are not shown.
       - Damage (`RequisitionDamage`, from `DamageProfile`): maximum, expected and heat-sustained damage at gunnery
         4, or at the adding strip's set gunnery. It shows as a targeting display: a step chart (`UiStepChart`, a
         new kit piece) over S, M and L bands, a readout tile per band, and an arc diagram (`UiArcDiagram`) of the
         expected damage by firing arc round the unit's sprite, after Swing's radar.
       The narrow slide-over and the keyboard still work (LEFT and RIGHT move through the tabs). Checks:
       `RequisitionRowTest`, `RequisitionRecordTest` and `RequisitionFactionsTest`. The on-demand
       `LobbyDossierTabsSmokeTest` clicks the tabs and the switch, and checks that tabs are built lazily, the
       debounce, that the tab stays, that the newest load wins, and the keys. The probe's
       `lobby+requisition+pick+tab=<overview|readout|tro|summary|ascard|factions|damage>` clicks through the Stage,
       photographed at 2560x1400 and narrow. Not checked live: the Force Generator's first load on a slow disk
       (the tab shows a loading line until it is done), and very long TRO texts.
     - L11 ~~Requisition advanced search~~. The old unit selector's Advanced search (Total Warfare, Alpha Strike and
       Battlefield Support) is rebuilt in libGDX rather than opening the Swing dialog. The matching the Swing panels
       held moved out of them into toolkit-free filters, `ASSearchFilter` and `BFSSearchFilter`, which the panels now
       fill and use, so the dialog behaves as before for MekHQ and MegaMekLab; Total Warfare reuses
       `MekSearchFilter` (`allowingAll()` is new). `RequisitionAdvancedSearch` in `megamek.client.lobby` is the
       immutable criteria (field to value: range, level, flag, picks, movement, minimums, equipment expression), with
       its labels and tag texts; it builds the three filters, and `RequisitionCatalog`'s `Filters` carries it, so
       the results and their count honour it. In the Filters drawer, "Advanced search…" (with " · N" while
       criteria are at work) opens `LobbyAdvancedSearchView` in the results' place, under the toolbar and tags; Done,
       Back or Escape (after the drawer) shows the results again. It was picked over a drawer because the criteria
       need the full centre: the view has its title, the live count of matching units, Clear (the tab's criteria)
       and Done over compact `UiTabs` (Total Warfare, Alpha Strike, Battlefield Support), then the tab's titled
       blocks laid out in as many columns as fit (`control.lg` × 8 each, at most three: two at 2560x1400, one narrow),
       in a vertical scroll. The controls are kit only: `UiRangeInput` for from–to numbers, selects, Any/Yes/No
       segmented flags, filter chips for short choices, `UiPickList` for long ones (quirks, specials, engines,
       cockpits), All/Any segmented where the filter supports both, steppers, and the equipment editor (a search over
       the weapons and equipment with their groups, ≥ or <, a count, Add, AND, OR, brackets, Undo and Clear; the
       expression only applies once it is complete). Covered: Total Warfare walk, run, jump, tonnage, year, BV,
       armour level, unit type, tech base and level, motive, armour, structure, engine, cockpit and gyro types,
       quirks and weapon quirks, the unit flags (omni, military, industrial, Clan engine, patchwork, support vehicle,
       mounted infantry, water only, Franken-Mek, official, invalid, failed equipment, the doomed environments) and
       the equipment expression; Alpha Strike unit type, role, size, TMM, move (mode and range), armour, structure,
       threshold, damage S/M/L/E, OV, PV and specials; Battlefield Support all of its panel's (type, move mode, MP,
       TMM, skill, damage, damage per hit and hits, long range, artillery, specials and the minimum counts). Each
       criterion shows as a removable tag beside the drawer's, counts in the Filters button, and goes with Reset
       and Clear filters; a new game gets a new lobby and so none, as the dialog. Same-named Inner Sphere and Clan
       equipment is told apart by its base and internal name. Left out, being costly or rare: tank turrets, lower
       arms and hands, transport bays, doors and carried units, source text, rules references, MUL ID, a separate
       quirk-exclude mode (a quirk chip switches to must-not-have instead), the equipment table's tech and unit-type
       filtering, and saved searches. Kit: `UiRangeInput`, `UiPickList` and the select list's type-ahead. Checks:
       `RequisitionAdvancedSearchTest`, `AdvancedSearchParityTest` (the Swing panels and the lobby model agree on
       representative criteria), and the on-demand `LobbyAdvancedSearchSmokeTest` and `UiPickListSmokeTest`, which
       click the real actors. Probe `lobby+requisition+advanced` (`+as`, `+equip`) clicks Filters, Advanced search
       and the walk field through the Stage: 6,533 units, then 2,857 with walk ≥ 5, then 50 with Alpha Strike size 4
       or 481 with one PPC; photographed at 2560x1400 and 1100x800. Not checked live: every control on the Battlefield
       Support tab, the expression's brackets, and very long pick lists on a slow machine. The Swing dialog's own
       quirks stay: its saved Alpha Strike damage choices do not come back (`ASDamage` has no `equals`),
       `MekSearchFilter`'s copy constructor copies only some fields, and its weapon-quirk exclusion reads the
       include list.
     - L12 ~~Chat fold and kept filters~~. The chat docked under the players gets a header (Chat, a count badge and,
       folded, the latest line cut with an ellipsis) and a fold chevron at the end of the input row, as the
       reference's `ChatDock` (6.5): folded, only the header and the input stay and the players list takes the
       height; the input row never moves, so neither does the button. Lines that arrive while folded tint the title
       `accent.primary` and count on the badge until the chat opens; as in the reference, everything but the local
       player's own lines counts, server and MegaMek notices included. The fold state is the client preference
       `LobbyChatFolded`. `ChatFold` in `megamek.client.lobby` is the toolkit-free state (the first snapshot is
       taken as read; new lines are found by overlap, so the 200-line cap does not miscount). Requisition keeps its
       weights, tech bases and rules levels as the preference `RequisitionFilters` (reference 7.2, format
       `W:…;B:…;L:…`, `Filters.kept()` and `withKept()`): saved on each user change, restored when Requisition
       opens (levels the game does not offer and unknown names are dropped), cleared by Reset and Clear filters;
       type, canon, record sheet, roles, era, source and the advanced search reset each game. Kit: `UiCountBadge`
       (`UiKit.countBadge`). Checks: `ChatFoldTest`, `RequisitionCatalogTest` (round trip and bad input), and the
       on-demand `LobbyFoldAndFiltersSmokeTest`, which clicks the fold and a weight chip. Probe `lobby+chat+fold`
       clicks the fold through the Stage, then the player and both bots chat: 2 unread, the own line not counted;
       photographed at 2560x1400 and narrow. Not checked live: the kept filters across a real restart (unit tested
       only).
     - L13 ~~Lobby dialogs to libGDX~~, one at a time (step 6's `LobbyDialogs` list, most used first).
       - L13a ~~Camo picker and player settings~~. Kit: `UiDialog`, the token dialog surface every later port
         reuses: a `UiModal` layer on `surface.scrim` (no dismissal on the scrim: a form wants an answer) holding
         the `UiKit.dialog` card (title, close button, a vertical-only scrolling body, a footer of leading tools and
         centred primary/secondary buttons), sized `dialog.sm`/`md`/`lg` or the window less `xxxl` margins, faded in
         over `fast`; Enter presses the focused button or the primary, Space the focused button, Escape and the close
         button cancel, Tab and Shift+Tab cycle inside the card only, and the lobby takes no key or click behind it.
         `LobbyNativeDialogs` is the lobby's one entry point for these (the only `new UiDialog` in the lobby, which
         `LobbyDialogEntryPointsTest` enforces, along with no `CamoChooserDialog`/`PlayerSettingsDialog` left in
         `GpuLobbyWindow`). Camo (`LobbyCamoView`, models `CamoCatalog` and `CamoChoice`): the category tree with
         colour camos first, a search of three letters or more, Include subcategories, a virtualised `UiCardGrid` of
         thumbnails read on a daemon thread and uploaded as textures (at most 320 kept), the preview of up to two of
         the units (or the camo itself), rotation and scale sliders, the asset overlay's colour, direction and style
         for Battlefield Support Assets, Refresh directory, and Reset to the owner's camo for units with their own.
         Opened from the player card (swatch and menu), the unit menu's camo action (`LobbyCommands.camoPicker`, so
         `individualCamo` no longer goes through `LobbyPrompts.chooseCamo` while the lobby shows) and the probe. The
         player swatch now shows the camo picture. Player settings (`LobbyPlayerSettingsView`, model
         `PlayerSettingsForm`): every section of the Swing dialog in its order (camo and name; automatic ammunition
         configuration with faction, Autoconfig/Randomize/Restore, Truly random, No nukes, Save/Load loadout; Bot
         settings for bots; deployment area with the start grid, other players' marks and invalid positions, offset,
         width, walk-on and width-restricted notes, the custom NW/SE corners and Apply; initiative modifier;
         minefields when allowed; fortified hexes; ground objects; skill generation; email for humans), sent through
         the same `Player` setters and `Client.sendPlayerInfo` and `sendProxyUpdates`. Differences from Swing:
         numbers are checked (a non-number or a negative, or a corner off the board, marks the field and disables
         OK and Apply, where Swing read 0); ground objects are applied on OK rather than at once; the overlay colour
         is a palette (the starting colour, caution yellow and the player colours) instead of a colour chooser; Use
         Ruler is left out. Still Swing: the loadout file choosers and the start screen's
         briefing camo (`StartBriefingPage`). The Swing dialogs stay for their other users. Checks:
         `CamoCatalogTest`, `CamoChoiceTest`, `PlayerSettingsFormTest`, `LobbyCommandsTest` (a picker answering
         later), `LobbyPlayerControllerTest`, `LobbyDialogEntryPointsTest`, and the on-demand
         `LobbyNativeDialogsSmokeTest` (the scrim blocks the lobby, Enter, Escape and Tab; a camo click and OK; a
         start click, an empty number disabling OK, then OK sending). Probes `lobby+dialog=camo` and
         `lobby+dialog=playersettings` now open these and click through the Stage (a colour camo, then OK: the
         player's and the snapshot's camo change and the swatch shows it; start SE and offset 2, then OK: the game
         and the snapshot read "SE, 2, 3"), photographed at 2560x1400 and 1100x800.
       - L13b ~~Bot setup~~. `LobbyBotSetupView` (models `BotSetupForm` and `BotTargetPicker` in
         `megamek.client.lobby`) replaces the Swing `BotConfigDialog` in the lobby, for a new bot (Add ally, Add
         opponent, Add on a new team, a ghost's replacement) and an existing bot's Bot settings (its card menu and
         the player settings' button, over which it stacks). Every field of the Swing dialog: the name (new bots
         only: blank or taken marks the field and disables OK) and the AI (radios, locked with its tooltip for an
         existing bot, CASPAR only when its preference is on); the Behavior tab with the presets list (previous
         configuration, the stored presets, the other local bots), Save (enabled once a stored preset's fields
         differ), Delete preset and Save as new (a taken name marks the field), all written through
         `BehaviorSettingsFactory` on the Swing thread while the buttons wait; the sliders in two columns (bravery,
         self preservation, aggression, mutual support, piloting caution, facing awareness and precision, and with
         the experimental preference anti-crowding and favour higher TMM) on the kit's token slider; combat posture;
         Exclusive mutual support, I am a pirate, Ignore damage output (and Experimental); forced withdrawal and its
         edge, auto flee and its edge (each select disabled while its switch is off). The Strategic targets tab:
         the priority units picker (owners, forces and units folding, a search, force and unit ticks, a 1-5
         priority select, enemy convoy units always ticked at 1, other targets for picked units not listed, the
         count line) and the hex targets (typed as x, y counted from 1, or chosen from the board's buildings, fuel
         tanks and bridges; a hex without one shows in `status.warning`; each removable). Help opens the Swing
         Princess help. OK on a new bot connects it as the Swing lobby does and puts it on the chosen team; on an
         existing bot it sets the settings on the local bot client and says so in the chat, as Swing does; the
         answer is dropped if the bot left meanwhile. The connect failure alert is a libGDX message
         (`LobbyNativeDialogs.message`). `LobbyNativeDialogs` now keeps a stack: camo and player settings replace
         what shows, the bot setup and a message stack over it, and closing the top one gives the one below its
         focus back. Differences from Swing: Delete preset is a button rather than the list's right-click, with no
         confirmation as in Swing; the name is checked as typed; the add-unit-by-ID field is left out (the picker
         lists every unit of the game). The Swing `BotConfigDialog` stays for the start screen, the in-game bot
         settings and MekHQ; its building description now comes from `BotSetupForm.buildingInfo`. Checks:
         `BotSetupFormTest`, `BotTargetPickerTest`, `LobbyPlayerControllerTest` (settings dropped once the bot
         left), `LobbyDialogEntryPointsTest` (no `BotConfigDialog` or `doAlertDialog` in `GpuLobbyWindow`), and the
         on-demand `LobbyNativeDialogsSmokeTest` (a stored preset, a slider click, the pirate switch, a typed name,
         a force tick and a typed hex, a message stacked over the setup, then OK handing all of it back). Probes
         `lobby+dialog=botconfig` and `lobby+dialog=addbot` (with `+targets` for the targets tab) click through
         the Stage: COWARDLY, the pirate switch, bravery to 10, a unit and a hex, then OK; the log shows the
         existing bot with those settings and the chat line, or the new bot on a new team (3) with them,
         photographed at 2560x1400 and 900x700.
       - L13c ~~Planetary conditions and game options~~. `LobbyConditionsView` (model `PlanetaryConditionsForm`)
         replaces the Swing `PlanetaryConditionsDialog` in the lobby: the general section (temperature, gravity,
         light, atmospheric pressure, taint, EMI, terrain affected by weather), the weather section (weather, wind,
         wind direction, fog, blowing sand) and the dynamic wind section (shifting direction and strength, the
         strength's range), with the Swing dialog's dependent-field rules (a vacuum clears and locks the weather,
         wind, taint, fog and sand; trace or thin air the weather and fog; storms force their wind; blowing sand
         raises the wind to a moderate gale; the range only while the strength shifts and clamped to it) and its
         validation messages, shown live on the field and disabling OK; OK sends through
         `Client.sendPlanetaryConditions`. `LobbyGameOptionsView` (model `GameOptionsForm` over a new toolkit-free
         `GameOptionsCatalog`, which the Swing `GameOptionsPane`/`GameOptionsPresentation` now share) replaces the
         Swing `GameOptionsDialog`: a `dialog.lg + dialog.sm` card with the search (narrowing the group tree, a
         match count), the group tree (categories and pages, the landing page with the rules system's guidance),
         the unofficial and legacy switches (hiding asks first, as Swing does), the password, and on the right the
         chosen page: its sections and every option type (boolean check box, choice select, int, float and string
         fields) with its tooltip and its Important, Unofficial, Legacy and Changed chips, the rules system's
         description, and the Swing dialog's enabling rules between options; Defaults, Save and Load (the Swing
         file chooser; load into a copy that shows as changes), then OK, which sends the changed options through
         `Client.sendGameOptions` and saves them with `GameOptions.saveOptions` as Swing does; the BA grab bars option
         sets the units' grab bars as it changes, as in Swing. A server change of the options reloads the open dialog (the edits dropped,
         the search and password kept), as `ClientGUI.gameSettingsChange` does for Swing. Choices: one page shown
         at a time in a scroll pane rather than a `UiVirtualList` (which takes the focus from text fields); numbers
         are checked and a bad one disables OK and Save, where Swing sent it; the victory options are left to the
         Victory Conditions dialog, as in the Swing lobby; the important mark is a chip. Known gap: a bad number on
         a page not shown is not flagged in the tree. Both are routed through `LobbyNativeDialogs` from the
         battlefield column's Change buttons, the menus and the `lobby+dialog=conditions`/`gameoptions` probes;
         the Swing dialogs stay for the start screen, the game, MekHQ and MegaMekLab (whose API did not change;
         `GameOptionsDialog` only gains a static `selectGameOptionsFile`). Checks: `PlanetaryConditionsFormTest`,
         `GameOptionsFormTest`, `LobbyPackageToolkitFreeTest`, `LobbyDialogEntryPointsTest` (no
         `PlanetaryConditionsDialog`/`GameOptionsDialog` in `GpuLobbyWindow`), the Swing panel tests, and the
         on-demand `LobbyNativeDialogsSmokeTest` (the weather and a vacuum chosen with the pointer and keys, the
         weather locked, 500 degrees disabling OK, then OK sending; a search, a page click, the double blind check
         box marking Changed, hiding the unofficial rules asking, then OK sending only that option). Probes click
         through the Stage: light two down and weather one down, then OK, and the snapshot's battlefield reads
         "Full Moon Night · Light Rain"; a search for double blind, its page and check box, then OK, and the server
         echo shows the Double blind rules chip; photographed at 2560x1400 and 1100x800.
       - L13 is done for the lobby's own dialogs. Still Swing in the lobby, as follow-ups: unit customization
         (`CustomMekDialog`, including the multi-unit edit), C3 network setup, map setup (board selection, map
         size, random map settings, load and save map setup), the Victory Conditions dialog, the random army,
         random skills, random names and the load list dialogs, the Princess help, the unit read-out and other
         unit-action prompts through `SwingLobbyPrompts`, and every file chooser (game options, loadouts, unit
         lists, map setups).
      - Review fixes after L10–L13 (the user's review):
        - Players: "+ Add on a new team" reads "Add a new team" (the + icon stays).
        - Requisition strip: Qty sits right before Add at the strip's right end, on one row with For, Skills and the
          G and P steppers when it fits, which wrap in narrow windows. `UiStepper` takes a caption with equal `xs` gaps, groups `md` apart.
        - Filter chips (weights and the Filters drawer): the check's reserve is kept at both ends, so labels are
          centred; a group is as wide as its widest label (Assault fits), and the search field gives up width.
        - Forces unit cards: a wide card shows a second block (movement, armor and structure, heat sinks, weapons cut
          at three with "+n" and a tooltip) from `LobbyUnit.Sheet`; it drops when narrow; compact rows stay one line.
        - Requisition results: aligned columns (move, armor, role, rules level) between the name and the BV, under a
          header, dropping right to left as the list narrows (`RequisitionRow.Column`), virtualised as before.
        - Dossier Readout: every table shares column widths, the first wide enough for "Center Torso (rear)"; long
          fluff (Overview, Capabilities, Deployment, History) shows as an overline heading over full-width text; the
          blocks reveal in sequence with `UiReveal` on a unit's first show (`RequisitionReadout.blocks`).
        - AS card: MegaMek's own `ASCard` drawing, rendered off the GPU thread at the pixels the tab takes and
          redrawn when they change (`RequisitionCardImage`), with the kit card while it draws or if it fails.
        - Factions: the abbreviation is its own column (no tooltip), and an open era reads "3150–".
      - Review fixes, round 2:
        - Forms: every select, text, range input and pick list in a labelled form column fills the column
          (`UiKit.fillsFormColumn`, `UiKit.formRow`, `UiFormField`), so right edges line up: advanced search, player
          settings, bot setup (the posture takes the presets column's width, the retreat edges share theirs) and
          planetary conditions; the Filters drawer's role, era and source share its width equally. No maximum width
          was needed at 2560 wide.
        - Dossier header: "+ Add to force" is gone; the strip's Add (owner, skills, quantity) is the single add, and
          double click and Enter on a row still add.
        - Damage tab: a targeting display. Expected damage is an accent area, heat-sustained a bright line and the
          maximum a faint dashed ceiling, over S, M and L bands with the hexes below and a light grid; three readout
          tiles (short, medium, long: expected large, maximum and heat-sustained small) replace the range table, and
          an arc diagram round the sprite (six arcs, intensity by value, a segmented S/M/L switch) replaces the
          direction table. The blocks reveal with `UiReveal`, the chart sweeps in and the arcs fade in turn; reduce
          motion shows all at once.
        - Factions tab: the eras reveal one after another on the first show; eras without the unit are hidden;
          several rarities show as wrapping token chips within at most 45% of the row, never over the name; the
          code column is as wide as the widest code, with an `lg` gap.
        Checks: `UiArcDiagramTest`, `UiStepChartTest`, `UiFormRowTest`, `RequisitionRecordTest` (range bands) and
        `RequisitionFactionsTest` (hidden eras, ratings); the on-demand `LobbyDossierTabsSmokeTest` checks the tiles,
        the arcs and their band switch, the factions reveal and chips, and the strip's single Add. The probe takes
        `search=<text>` (underscores for spaces) to pick a given unit. Not seen on screen: an era hidden between two
        shown (the units photographed had none), and a capture of the factions reveal mid-way (its data arrives at a
        varying time).
     - ~~No lobby flash before a scenario~~ (reported by exeea after #34). Every client used to open the lobby scene
       as it initialised, before the server had sent a phase, and the board replaced it once the phase arrived; a
       scenario, which starts in `STARTING_SCENARIO` and never enters the lounge, showed the lobby for about 0.7 s
       between the briefing and the board (and its battlefield logged a null-board warning, as no map had arrived).
       Now `ClientGUI` opens no scene until the server's first phase: the window keeps what it shows (the
       briefing's "Starting…", or nothing yet for MekHQ), the lounge then opens the lobby and any other phase the
       board, and between the lounge and the first board phase the lobby stays as before. Checks:
       `ClientGUIBoardLifecycleTest` (a scenario goes straight to the board, and nothing opens before the first
       phase). Live: the start screen's `briefing+play` probe (it presses the briefing's Start once the scenario has
       loaded) on Final Exam goes from the start screen to the board's own loading card with no lobby frame between
       (window titles and frames captured from MegaMek's window only), with no null-board warning; `lobby+start`
       still goes from the start screen to the lobby and on to the board. The lobby probe now readies only once the
       bots' units have reached the game, as a unit arriving later un-readied the player and left the game waiting.
     - Still open after #34, for a later round once #33 and #34 are merged:
       - L14 Settings check: the lobby's part of step 7. Every client and GUI preference the old Swing lobby read or
         wrote is honoured by the libGDX lobby, persists across restarts and appears in the settings dialog where it
         did before; preferences with no remaining reader are removed.
       - L15 Unit dialogs in libGDX: unit customization (`CustomMekDialog`, including the multi-unit edit) and the
         C3 network setup.
       - L16 Map setup in libGDX: board selection, map size, random map settings, and loading and saving a map
         setup.
       - L17 The other lobby dialogs in libGDX: Victory Conditions, the random army, random skills, random names,
         the load list, the Princess help, the unit readout and the remaining prompts through `SwingLobbyPrompts`.
       - L18 File choosers: game options, loadouts, unit lists and map setups open the system file dialog (as the
         start screen does) instead of a Swing chooser.
       - Live checks still to do: a real MekHQ game, a network reconnect, saving and loading game options and bot
         presets through the real file chooser, a password-protected game and a second human player.
       - Each Swing dialog the lobby stops using stays while MekHQ, MegaMekLab or the in-game screens still open it;
         retire it once its last user moves.
  7. A settings check across the startup path (the lobby's part is L14 in step 6).
- If the developers accept it, move hud-v3 onto the tokens and the new libGDX controls (including the `UiTheme`
  rename pass). Until then, hud-v3 code does not change.
