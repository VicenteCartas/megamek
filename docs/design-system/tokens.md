# Design tokens v0

Status: v0.1, for review. The decisions behind it are in [gap-analysis.md](gap-analysis.md).

The tokens are the one source of truth for the Swing and libGDX look of the MegaMek suite. They live in
[design-tokens.json](../../megamek/resources/megamek/client/ui/design/design-tokens.json) and are read by
[`DesignTokens`](../../megamek/src/megamek/client/ui/design/DesignTokens.java), which has no AWT or libGDX dependency so
both platforms (and the launcher, later) can share it.

## Seeing them

In a dev build, open the lobby and use **Help > UI library (developer)**. The submenu only shows in the lobby.

- **Swing controls...** opens the Swing gallery: every color role in both modes, the accessibility checks, the text
  styles, spacing and shape, and five tabs of real Swing controls drawn by the MegaMek look and feel. A
  toolbar toggles light mode and [game text mode](#presentation-mode), and simulates deuteranopia, protanopia and tritanopia
  over the whole window. While the
  gallery is open the whole app uses MegaMek Dark or Light, and closing it restores the previous theme. This is the
  complete library; Swing controls are added here as they are built.
- **libGDX controls...** opens the native gallery: one tab per group of hud-v3 toolkit elements (colors and type,
  buttons, selection, inputs, lists, containers, status and HUD parts), each shown as the game draws it today beside
  its migrated Token v0 look, filled in tab by tab. The migration restyles these elements and adds no new controls. A
  **Game mode** switch rebuilds the Token v0 controls in app mode, to compare.
  Only one native window can run at a time, so it closes before a board or the board editor opens, and it will not
  open next to one.

The same checks run as a unit test, along with a test that the Swing themes pick the tokens up:

```
./gradlew :megamek:test --tests megamek.client.ui.design.DesignTokensTest --tests megamek.client.ui.design.swing.TokenLafDefaultsTest
```

## Swing themes

**MegaMek Dark** and **MegaMek Light** are FlatLaf look and feels
([`MegaMekDarkLaf`](../../megamek/src/megamek/client/ui/design/swing/MegaMekDarkLaf.java),
[`MegaMekLightLaf`](../../megamek/src/megamek/client/ui/design/swing/MegaMekLightLaf.java)). They are registered next
to the four stock themes, so any suite app can pick them in its settings. MegaMek Dark is MegaMek's default theme
(MegaMekLab and MekHQ keep their own defaults for now). MegaMek's settings list only the two MegaMek themes; the four
stock themes are "classic" themes, listed after them when **Show classic themes** is on, and a player who already uses
one keeps it. They have no properties file: [`TokenLafDefaults`](../../megamek/src/megamek/client/ui/design/swing/TokenLafDefaults.java)
builds the FlatLaf values from the token file when the theme loads, so a token change reaches Swing with no second
copy.

- FlatLaf derives most keys from a few variables (`@background`, `@foreground`, `@accentColor`, `@selectionBackground`
  and so on). These are set from the tokens, and then the keys whose derived value would not match a token.
- Translucent tokens (hover, selection) are composited over their surface, because opaque Swing components must paint
  opaque colors.
- Selection uses `selected.background` and keeps full strength when the control is not focused.
- The button kinds are FlatLaf style classes, see [Buttons](#buttons); the window's default button looks like the
  primary one.
- The default font is Roboto at the `body` size, and FlatLaf's semibold (the `h1` to `h3` headings) is Roboto Medium.
- Keyboard focus follows [Focus](#focus): a 2-wide ring inside the edge, shown only after keyboard use.

## Color roles

Names describe a role, not a hue, so each name has one meaning in both modes. Surfaces also have an `overlay` value
(translucent) for panels drawn over the board; opaque Swing surfaces use the plain value.

| Role | Dark | Light | Use |
| --- | --- | --- | --- |
| `surface.base` | `#0E1314` | `#ECEFEE` | Window background |
| `surface.panel` | `#141A1C` (overlay `#12181AEB`) | `#FFFFFF` | Panels, dialogs, cards |
| `surface.raised` | `#1C2426` (overlay `#1C2426F7`) | `#F4F6F5` | Headers, footers |
| `surface.popover` | `#172022` | `#FFFFFF` | Menus, tooltips |
| `surface.input` | `#1D2426` | `#FFFFFF` | Fields, selects |
| `surface.hover` | `#FFFFFF0F` | `#16201E0D` | Hover wash |
| `surface.inverse` | `#F0F2EE` | `#1C2426` | Primary button face and every "on" control |
| `surface.inverseDisabled` | `#F0F2EE4D` | `#1C242673` | An "on" control while disabled, laid over its surface |
| `surface.scrim` | `#0D1112A3` | `#16201E66` | Dims the window behind a modal card |
| `text.primary` | `#F1F4F0` | `#16201E` | Body text |
| `text.secondary` | `#CBD3D0` | `#3A4542` | Button labels, secondary values |
| `text.muted` | `#9AA6A3` | `#596562` | Captions, hints |
| `text.disabled` | `#5F6B69` | `#98A29F` | Disabled text (exempt from contrast) |
| `text.onInverse` | `#16201E` | `#F1F4F0` | Text on `surface.inverse` |
| `text.onAccent` | `#0F1A18` | `#FFFFFF` | Text on an accent fill |
| `border.control` | `#6B7774` | `#7A8683` | Edges that identify a control |
| `border.subtle` | `#2A3133` | `#D5DBD9` | Decorative dividers only |
| `border.strong` | `#E8F1EB` | `#2A3533` | Hovered edge, full frame |
| `accent.primary` | `#82E2CE` | `#0A5C4C` | Focus, progress, slider fill, links, info, badges |
| `focus.ring` | `#82E2CE` | `#0A5C4C` | Keyboard focus |
| `selected.primary` | `#F0F2EE` | `#1C2426` | Selected item marker, underline and edge (the inverse color) |
| `selected.background` | `#F0F2EE1F` | `#1C24261A` | Selected row wash (neutral) |
| `status.danger` | `#F2777A` | `#B42318` | Errors, destruction, enemy |
| `status.warning` | `#F2C14E` | `#8F5300` | Caution, heat |
| `status.success` | `#8BD56B` | `#1E7B34` | Done, healthy |
| `status.info` | `#82E2CE` | `#0A5C4C` | Neutral information |
| `status.gamemaster` | `#9982D3` | `#5A49C6` | Game Master notices and actions |

Why these values:

- **Accent is mint, and only the accent.** hud-v3 used mint for both "active" and "success". Success is now a
  distinct olive green, and info deliberately shares the accent so there is one fewer hue to tell apart.
- **"On" is cream, selected is neutral, mint is focus.** Three ideas, three looks:
  - **On** (a control's state: checked toggle, selected segment, on chip, check box, radio, switch) is the solid
    `surface.inverse` face with `text.onInverse` marks, hud-v3's `button-checked` and `pill-section-on`. A checked
    toggle looks like the primary button, as in hud-v3; the two rarely meet, since one sits in toolbars and the other
    in dialog footers.
  - **Selected** (an item in a collection: list, table and tree rows, tabs, selectable panels, unit rows, menu and
    select checks) is the neutral `selected.background` wash plus a `selected.primary` marker, underline, edge or check.
    Hover stays a fainter wash with no marker, so the shape tells them apart.
  - **Mint** (`accent.primary`, `focus.ring`) is left for focus, progress, slider fill, links, info and badges, so the
    focus ring is never confused with a state.
- **Why not mint for selection.** Mint selection beside cream "on" controls gave a screen two "chosen" colors. One hue
  family for everything chosen reads calmer, and keeps mint meaningful. Violet stays only for the Game Master
  (`status.gamemaster`), whose notices need a hue of their own.
- **On while disabled** is the faint `surface.inverseDisabled` face with the `text.onInverse` label (about 2.7:1, like
  other disabled text), so a disabled control still shows which option is on.
- **Statuses use the hues players expect.** Red, amber and green in the standard palette; the color-blind safe
  palette (see [Palettes](#palettes)) moves them apart in lightness as well as hue.
- **Borders that identify a control reach 3:1** (WCAG 1.4.11). hud-v3 field and chip borders measured 1.8 and 2.4.
  `border.subtle` is for decoration only and must never be the only cue.
- **Color is never the only cue.** Status chips pair the color with an icon (error, warning, check, info, radio);
  the galleries show this.

The `checks` block in the JSON lists every rule: text contrast 4.5:1, control contrast 3:1, and minimum distances
between the statuses and the accent. Contrast is checked in all four themes; distances follow the `visionRule`.

### Palettes

There are four themes: dark and light, each in a **standard** and a **color-blind safe** palette. The palette is a user
setting of its own (Client settings, "Color-blind safe colors"; `GUIPreferences.COLOR_BLIND_PALETTE`), apart from the
light or dark theme.

| Palette | Changes | Distances checked for |
| --- | --- | --- |
| Standard | The values above | Typical vision |
| Color-blind safe | Only the statuses, from each role's `colorblind` block: danger `#FA878C` / `#8C1C12`, warning `#F2D42D` / `#A24F00`, success `#669C00` / `#357A48` (dark / light) | Typical vision and the three simulated deficiencies |

- **Why two palettes.** Colors that stay apart for every vision type pushed the standard statuses toward odd hues (an
  olive success, a lemon warning). Each palette now does one job well.
- **Contrast never changes.** Every contrast check runs in all four themes.
- Player, team and unit colors are out of scope and stay as they are.
- `DesignTokens.setPalette(Palette)` switches the palette for the whole JVM, and `DesignTokens.shipped()` returns the
  active one. Swing sets the look and feel again; libGDX re-adds its token styles (`GpuBoardSkin.refreshTokens()`), and
  widgets built before keep the old ones. Both galleries have a Color-blind safe switch.
- Whether MekHQ and MegaMekLab share the setting is decided when they adopt the tokens.

### Migrating hud-v3 colors

| hud-v3 color (`UiTheme`) | Token |
| --- | --- |
| `TEXT`, `ACCENT`, `MUTED`, `DISABLED` | `text.primary`, `text.secondary`, `text.muted`, `text.disabled` |
| `PANEL`, `PANEL2` | `surface.panel`, `surface.raised` (overlay values) |
| `POP` | `surface.popover` |
| `FILL`, `MAIN` | `surface.inverse` |
| `FILL_INK` | `text.onInverse` |
| `FILL_MUTED` | `surface.raised` |
| `LINE`, `TRACK`, `QUIET` | `border.subtle` |
| `TICK`, `RAIL` | `border.control` |
| `CORNER`, `HOVER` | `border.strong` |
| `MINT` | `accent.primary`; its success uses move to `status.success` |
| `CORAL`, `AMBER` | `status.danger`, `status.warning` |
| `VIOLET` | `selected.primary` (now the inverse color) for selection; `status.gamemaster` for the Game Master |
| `BLIP` | No token: it stays a HUD game color, like unit and team colors |

No hud-v3 color becomes `surface.base`, `surface.input`, `surface.hover`, `surface.scrim`, `text.onAccent`,
`focus.ring`, `selected.background` or `status.info`; the libGDX gallery lists them as new in Token v0.

## Typography

One family, Roboto, on both platforms; icons are Material Symbols Rounded. Both ship in `mm-data`.

| Style | Size | Weight | Tracking | Caps tracking | Use |
| --- | ---: | ---: | ---: | ---: | --- |
| `display` | 23 | 500 | 0 | .04 | Phase banner, splash titles |
| `headline` | 20 | 500 | 0 | .05 | Page and dialog headings |
| `title` | 16 | 500 | 0 | .06 | Panel and section titles |
| `body` | 13 | 400 | 0 | | Default text |
| `body.strong` | 13 | 500 | 0 | | Emphasis, values, unit and pilot names (the `name` role) |
| `label` | 12.5 | 500 | .01 | .08 | Buttons, tabs, field labels |
| `small` | 11.5 | 400 | 0 | | Dense tables, secondary lines |
| `caption` | 10.5 | 400 | .01 | | Hints, units, timestamps |
| `overline` | 10.5 | 500 | .01 | .1 | Short eyebrows and tags; upper case in both modes |

Tracking is letter spacing in em. **Caps tracking** is the spacing a style uses when its text is upper case: capitals
set at sentence spacing look cramped, so they get more, and small text more than large. Only styles that can be
upper case have it, and the tokens fail to load if a role is upper case in a style without it.

Rules:

- **Case depends on the screen's mode**; see [Presentation mode](#presentation-mode).
- **Two weights**, regular (400) and medium (500). Hierarchy comes from size and weight, not case or width. hud-v3's
  condensed bold styles are retired.
- **Two widths**, regular (`wdth` 100) and condensed (`wdth` 75), set by the screen's mode; see [Type width](#type-width).
- Sizes are logical pixels at scale 1; both platforms multiply by the user's UI scale.

### Presentation mode

Each screen is in one of two presentation modes, game or app. The mode belongs to the screen, not to the app, so MekHQ
or MegaMek can mix both. It sets the text case below and the [slider thumb](#sliders); other differences may join it
later, always as a whole-screen choice rather than per control. It also sets the [type width](#type-width).

| Text | App mode | Game mode |
| --- | --- | --- |
| Phase banner (`display`), panel and dialog titles, section headers, tab labels, button labels | Sentence case | UPPER CASE |
| `overline` tags, chips and badges | UPPER CASE | UPPER CASE |
| Body text, values, tables, tooltips, field labels, helper text | Sentence case | Sentence case |

- Upper case is for short framing labels of one to three words; anything longer stays sentence case in either mode.
- **Game screens to start with:** the in-game libGDX HUD, startup and main menus, MekHQ Immersive dialogs and the
  interstellar map. Every other screen starts in app mode, and may move to game mode when it is reviewed during
  migration.
- **Strings stay sentence case** in the resource bundles. Case is applied only when the text is drawn, with the
  current locale (`toUpperCase(locale)`), so translators never write upper case and changing a screen's mode never
  touches strings.
- **The mode is set once, on the screen's root** (window or top-level panel). Controls inherit it from their nearest
  ancestor, and app is the default, so moving a screen to game mode is a one-line change. Popups follow the component
  that opened them. Hand-painted text (Java2D, libGDX `draw` code) must use the same case helper to follow the mode.
- libGDX in-game UI is always game mode.

Case follows the text's **role**, not its style: field labels share `label` with buttons and tabs but stay sentence
case. The tokens' `typography.case` block maps each role to its style (`display`, `headline`, `title`, `button` and
`tab` on `label`, `overline`, and `name` on `body.strong`) and lists the roles each mode upper-cases; `DesignTokens.upperCase(role, mode)` and
`cased(role, mode, text, locale)` read it.

- **Swing:** `TokenPresentation.setMode(root, Presentation.GAME)` marks a screen; a dialog follows its owner window, whose
  mode may be set on its root pane. The button, tab and segmented factories give their controls the `button` or `tab`
  role (icon buttons get none: their text is a glyph), and `TokenPanel`, `TokenDialogPane` and `TokenStatusCard` give
  their headings the `title` or `headline` role; `TokenPresentation.role` gives one to another button, label or tab pane.
  A control keeps its sentence-case text and shows it upper-cased in the caps-tracked font, so views keep calling
  `setText`, `setTitleAt` and `setFont`; screen readers get the sentence-case text.
- **libGDX:** fonts bake their spacing in, so caps text has its own fonts, condensed as game mode sets it:
  `hud-medium-caps` with a `-caps` copy of each labelled token button, tab and segment style, and `type-<style>-caps`
  for display, headline, title, label and overline. `UiKit.presentation` (game by default) picks them for the buttons,
  tabs, segmented controls and token panel and dialog headers it builds; `UiKit.roleLabel`, `roleTypeLabel` and `cased`
  do the same for other text. The kit depends on no game options, so its view passes in the player's locale
  (`UiKit.locale`).

### Type width

**Final.** Type is Roboto at regular width (`wdth` 100). In game mode the framing roles (`display`, `headline`,
`title`, `button`, `tab`, `overline`) and the `name` role are set in Roboto Condensed (`wdth` 75), at the same weight
(500) and the same tracking rules. Everything else stays regular width: body, small, caption, field labels, tables,
values and running text. App mode is all regular width, its upper-case overline included.

| Text | App mode | Game mode |
| --- | --- | --- |
| Phase banner, headlines, titles and section headers, buttons, tabs, `overline` tags | Regular | Condensed, UPPER CASE |
| Unit and pilot names (`name`, on `body.strong`) | Regular | Condensed, sentence case |
| Body text, values, tables, tooltips, field labels, helper text | Regular | Regular |

- **Why condensed in game mode:** it packs some 15-20% more into the HUD's narrow columns, and reads as technical and
  military, which suits the game.
- **Why not everywhere:** condensed letters crowd at 10-12 px and tire the eye in long text, so it stays off running
  text, tables and app mode.
- **Tracking does not change.** Upper-case condensed text keeps its style's caps tracking; names are sentence case at
  the style's normal tracking (0). hud-v3's wide tracking on mixed case is not adopted: it uses up the space condensed
  saves.
- **One extra face.** Every condensed role is medium (500), so condensed needs a single static face for Swing (see
  [Fonts](#fonts)); the tokens test checks that every condensed role stays at 500.

The tokens' `typography.width` block holds the condensed width and lists the roles each mode condenses (none in app);
`DesignTokens.condensed(role, mode)`, `condensedRoles(mode)` and `condensedWidth()` read it. `name` is a text role like
the framing ones but never upper case.

- **Swing:** `TokenPresentation` swaps a role's font for the condensed face in game mode, upper-cased framing labels at
  their caps tracking and names at normal tracking, and puts the original font back in app mode. Section headers and
  table headers draw their overline condensed on game screens. `TokenFonts.font(style, upperCase, condensed)` gives the
  font directly; give a name label the role with `TokenPresentation.role(label, "name")`.
- **libGDX:** `hud-medium-caps` and the `type-<style>-caps` styles use the variable font's Condensed Medium instance,
  and `type-body.strong-condensed` is the sentence-case name style. `UiKit.roleTypeLabel` (and `condensed(role)`) pick
  them in game mode; hud-v3's own `hud-*` fonts are unchanged.

### Fonts

Java2D can't select instances of the Roboto variable font: asking for medium draws regular. libGDX FreeType reaches
every instance. So `mm-data` also ships two static instances that [make_static_roboto.py](make_static_roboto.py)
generates from the variable font: `Roboto/Roboto-Medium.ttf` (weight 500, width 100, family "Roboto Medium") and
`Roboto/Roboto-CondensedMedium.ttf` (weight 500, width 75, family "Roboto Condensed Medium", for game mode's
[condensed roles](#type-width)). Swing asks for them by family name; regular comes from the variable font's default
instance. MegaMek registers every `.ttf` under `data/fonts` at startup (`FontHandler`), and the build stages the folder
from `mm-data` (`stageDataFonts`). libGDX keeps reading the variable font's named instances. Both platforms therefore
draw the same outlines from one source.

libGDX registers each style as a Label style named `type-` plus the style (`UiTheme.TYPE_STYLES`), beside the hud-v3
fonts it replaces.

### Migrating hud-v3 fonts

| hud-v3 font | Style |
| --- | --- |
| `hud-body`, `hud-small` | `body`, `small` |
| `hud-medium`, `hud-utility`, `hud-mini`, `hud-button`, `hud-main` | `label` (button and tab text) |
| `hud-sub` | `caption` |
| `hud-caption` | `overline` |
| `hud-name` | `body.strong`, as the `name` role: condensed in game mode |
| `hud-title`, `hud-heading`, `hud-phase` | `title`, `headline`, `display` |

The libGDX gallery's Colors and type tab shows each hud-v3 color and font beside the token that replaces it.

## Spacing, shape and motion

| Group | Values |
| --- | --- |
| Space | xxs 2, xs 4, sm 6, md 8, lg 12, xl 16, xxl 24, xxxl 32 |
| Radius | sm 2, md 3 |
| Control height | sm 28, md 36, lg 46 |
| Icon | sm 16, md 20, lg 24 |
| Dialog width | sm 420, md 620, lg 800 |
| Border | 1 |
| Selection | 2 (the selected tab's underline) |
| Focus ring | 2 wide inside the edge; 1 gap outside small indicators and cream faces; `focus.ring` |
| Corner ticks | 8 on controls, 13 on panels; 2 wide |
| Opacity | tint.hover 0.16, tint.pressed 0.28 (a status color washed over a face) |
| Motion | fast 120 ms, medium 200 ms, slow 320 ms; easing cubic-bezier(0.2, 0, 0, 1) |

Regular frames use corner ticks. The framed button (ticks that grow into a full frame on hover) is a special
variant for emphasis moments, not the default button.

## Motion

A game UI moves: controls ease between their states instead of switching, so the interface responds to the pointer.
Every transition uses the `motion` tokens and one easing curve; nothing bounces or overshoots.

| What | How |
| --- | --- |
| Hover | Comes in `fast` (120) and lingers on the way out, `medium` (200), so a sweep across controls leaves a short trail |
| Press | Shows at once; the release fades back over `medium` |
| Buttons, inputs, selects, tabs, segmented controls | Face, text and edge colors ease between states |
| Check box, radio button, switch | Box fill and edge ease `fast`; the check draws along its stroke, the radio dot grows from the center, the switch thumb slides |
| Special button | The corner ticks grow along the edges into the full frame, `slow` (320) in and `medium` out |
| Popups, menus, tooltips | Fade in `fast`; they close at once |
| Collapsible sections | The chevron turns a quarter and the content slides open or closed over `medium` |
| List, tree and table rows | The hover wash fades in and out per row |
| Scrollbars | The rail eases in and the thumb brightens with hover |
| Sliders | The thumb's colors ease and it grows a pixel each side under the pointer |
| Appearing | Nothing animates when it first shows; only changes move |

- **Reduce motion** turns every transition into an instant change, including FlatLaf's own animations and smooth
  scrolling. It is a MegaMek client setting (Client Settings, next to the theme), off by default.
- **Popups only fade where they have their own window**, which is every popup on Windows 11 and any popup that
  reaches past its window elsewhere. One that fits inside its window appears at once.
- **The gallery shows it live** on the Spacing and shape tab: one sample of each kind, and Play runs them all once.

**Swing.** [`TokenMotion`](../../megamek/src/megamek/client/ui/design/swing/TokenMotion.java) is the one animator:
a control asks for the value it would paint for its current state and gets the value on its way there, and one shared
timer repaints moving controls until they arrive. Custom controls use the same calls, `TokenMotion.color(...)` and
`TokenMotion.value(...)`, with `TokenMotion.hover(...)` and `TokenMotion.buttonState(...)` for the durations above.
`TokenMotion.setReduced(...)` applies the setting.

**libGDX.** Nothing new yet; hud-v3 keeps its instant states until the developers accept the system.

## Focus

One focus look for every control, so keyboard users always find the same thing.

- **Keyboard only.** The ring shows after a key press and hides again on a mouse press, like the web's
  `:focus-visible`. Editable text always shows it, because the caret alone is easy to lose.
- **Inside the edge.** The ring is `focus.width` (2) wide and drawn inside the control edge, so focus never changes a
  control's size or moves a layout.
- **Focus wins.** The ring replaces the edge, whatever the control's state. Meaning stays in the text, fill and icons:
  a checked box stays checked, and error and warning fields carry a status icon as well as their colored edge.
- **Cream faces** (the primary button and every "on" control: checked toggle, selected segment, on chip) get the
  mint ring outside the edge, `focus.gap` (1) away, since mint barely shows on cream. Where the ring would be clipped
  (inside a scroll pane's view or a popup) they fall back to a `text.primary` outer line and a `text.onInverse` inner
  line inside the edge.
- **Small indicators** (check box, radio, slider thumb) get the ring around them, `focus.gap` (1) away, so the
  indicator keeps its look. Their label sits far enough away for the ring.
- **Tabs** get the ring inside the selected tab. **Lists and tables** show it on their scroll pane edge.

**Swing.** [`FocusVisibility`](../../megamek/src/megamek/client/ui/design/swing/FocusVisibility.java) tracks keyboard
use for the MegaMek themes; `FocusVisibility.shows(component)` tells custom painters whether to draw the ring.
[`TokenCheckBoxIcon`](../../megamek/src/megamek/client/ui/design/swing/TokenCheckBoxIcon.java),
[`TokenRadioButtonIcon`](../../megamek/src/megamek/client/ui/design/swing/TokenRadioButtonIcon.java) and
[`TokenTabbedPaneUI`](../../megamek/src/megamek/client/ui/design/swing/TokenTabbedPaneUI.java) draw the rings FlatLaf
does not. The gallery's "Keyboard focus" section shows each control frozen in focus.

**libGDX.** Buttons draw the ring now (see [Buttons](#buttons)); scene2d gives buttons keyboard focus only from the
keyboard, so it is keyboard-only already. The other token controls get the ring as they are built. Keyboard
navigation between libGDX controls comes later.

## Buttons

Seven kinds in two sizes replace the 19 hud-v3 styles and the Swing button families. Both galleries show every kind in
every state.

| Kind | Use | Rest | Hover / pressed | Selected |
| --- | --- | --- | --- | --- |
| Primary | The one main action of a view | `surface.inverse` face (hud-v3's cream; dark in light mode), `text.onInverse` label | Face leans toward the label color, further when pressed; strong edge | — |
| Secondary | Every other action (the default) | `surface.raised` face, `border.control` edge, `text.secondary` label | `surface.hover` wash (twice for pressed), `border.strong` edge, `text.primary` label | — |
| Quiet | Low-emphasis actions in toolbars and rows | No face, no edge | As secondary, without the edge | — |
| Danger | Destructive actions | As secondary, `status.danger` label and edge | Danger wash at `tint.hover` / `tint.pressed` | — |
| Toggle | An on/off choice | As secondary | As secondary | On: as primary, the `surface.inverse` face and `text.onInverse` label |
| Icon | A Material Symbols glyph, square; needs a tooltip | As quiet | As quiet | On: the inverse face, `text.onInverse` glyph |
| Special | Emphasis moments (the HUD utility look) | Raised face, `border.control` corner ticks | Full `border.strong` frame | On: the inverse face and frame |

- **Sizes.** Regular is `control.md` (36) high with `lg` side padding, for dialogs and forms; compact is `control.sm`
  (28) with `md` padding, for dense tables and toolbars. Icon glyphs are `icon.md` / `icon.sm`.
- **Labels** are Roboto Medium, sentence case in app mode and upper case in game mode (see [Presentation mode](#presentation-mode)).
  Rest text is `text.secondary` and brightens to `text.primary` under
  the pointer, so the hover reads on every kind.
- **Disabled** is the same for every kind: raised face, `border.subtle` edge, `text.disabled` label. A disabled toggle
  that is on keeps the faint `surface.inverseDisabled` face with a `text.onInverse` label.
- **Focus** follows [Focus](#focus): a 1-wide `focus.ring` border plus a 1-wide inner line, inside the edge. Cream
  faces (primary and on) get the mint ring outside the edge instead, or the two inside lines where it would be clipped.
- **Primary is not mint.** Mint means focus, progress and information; a mint main action would compete with the
  focus ring. The inverse face is neutral, has the highest contrast, and is hud-v3's `hud-main`.
- **On** is the solid inverse face, the strongest change a button can make, so it does not depend on telling two
  greys apart. A checked toggle looks like the primary button, as in hud-v3.
- The primary label keeps at least 4.5:1 against its hover and pressed faces in both modes (tested).

### Using them

**Swing.** [`TokenButtons`](../../megamek/src/megamek/client/ui/design/swing/TokenButtons.java) builds them:
`TokenButtons.button(Kind.DANGER, text, Size.COMPACT)`, `primary(text)`, `toggle(text, size)`,
`icon(codePoint, tooltip, toggle, size)`, `special(text, size)`. The look comes from FlatLaf style classes in the
MegaMek themes (`primary`, `danger`, `quiet`, `icon`, plus `regular` or `compact`), so under another theme they are
plain Swing buttons. `TokenButtons.style(button, kind, size)` restyles an existing button while migrating a view; it
sets the look and padding but keeps the button's natural height, because FlatLaf only applies a minimum height to
buttons with the default margin. Buttons from the factories grow to the size's height themselves.
[`SpecialButton`](../../megamek/src/megamek/client/ui/design/swing/SpecialButton.java) paints itself from the tokens.

**libGDX.** [`UiTheme`](../../megamek/src/megamek/client/ui/gdx/UiTheme.java) registers the `TextButtonStyle`s
`button-<kind>` and `button-<kind>-compact` (`button-primary`, `button-secondary`, `button-quiet`, `button-danger`,
`button-toggle`, `button-icon`, `button-special`), built from the tokens. `UiKit.button(style, icon, text, sub)` makes one.
`UiTheme.DONE_BUTTON` (`button-done`, with `-compact` and `-caps` copies) is a primary button in `status.success` with
its label in `text.onAccent`: the lobby's Ready once the player is ready, with a check before the label.
libGDX gives hover priority over focus, so [`UiButton`](../../megamek/src/megamek/client/ui/gdx/UiButton.java) draws
the focus ring as an overlay when it has keyboard focus; `focused(true)` and `held(true)` freeze those states for the
gallery. The libGDX label is 12.5 px (`hud-medium`) and the Swing one 13 px; aligning them is part of the type pass.

### Migrating hud-v3 styles

| hud-v3 style | New kind |
| --- | --- |
| `hud`, `hud-plain` | secondary |
| `hud-main` | primary |
| `hud-danger` | danger |
| `hud-delete-icon`, `hud-icon` | icon |
| `hud-mini`, `hud-utility-small` | secondary, compact |
| `hud-utility`, `hud-utility-narrow`, `hud-utility-row` | special (the icon-above-label layout stays a HUD composition) |
| `hud-tab`, `hud-tab-caps` | Not buttons: [tabs](#tabs) |
| `hud-seg`, `hud-pill` | Not buttons: the [segmented control](#segmented-control) |
| `hud-row`, `hud-menu-row` | Not buttons: list row and menu item come later |
| `hud-select`, `hud-select-inline` | Not buttons: the [select](#select) |

The hud-v3 styles stay until their views move. Two rules come with the mapping:

- **A secondary button the game holds pressed becomes a toggle.** Secondary has no selected look, so a `hud`,
  `hud-plain`, `hud-mini` or `hud-utility-small` button that stays checked moves to toggle (compact for the compact
  ones). Secondary stays for plain actions.
- **`hud-delete-icon` becomes a plain icon button.** Token v0 has no danger icon color; the `close` glyph and a tooltip
  carry that it deletes.

The libGDX gallery's Buttons tab shows each hud-v3 style in its five states beside the token kind it becomes.

## Inputs

Text, password, search and number inputs, the text area and the form field. Both galleries show every kind in every
state.

| State | Look |
| --- | --- |
| Rest | `surface.input` fill, `border.control` edge, `text.primary` text, `text.muted` placeholder |
| Hover | `border.strong` edge |
| Focus | The focus ring in place of the edge (see [Focus](#focus)); editable text always shows it |
| Error / warning | `status.danger` / `status.warning` edge and a trailing status icon; the form field adds the words |
| Read-only | No fill, `border.subtle` edge, `text.primary` text |
| Disabled | `text.disabled` text and a muted edge |

- **Sizes** match buttons, so a field and a button side by side line up: regular `control.md` (36), compact
  `control.sm` (28). Text sits `md` (8) inside the edge.
- **Precedence**: focus wins over a status, and a status over hover. Under the focus ring the status icon still shows.
- **Form field**: the label above (`text.secondary`), then the input, then a helper (`text.muted`) or a status
  message in the status color with its icon. The message line is only there while it has text. Dense option grids
  may keep labels on the left.

**Swing.** The MegaMek themes give every `JTextField`, `JPasswordField`, `JFormattedTextField`, `JSpinner` and
`JComboBox` the compact height and the hover and read-only edges, so existing views get them without changes; text
areas get them through their scroll pane. [`TokenFields`](../../megamek/src/megamek/client/ui/design/swing/TokenFields.java)
builds regular or compact inputs: `text(size)`, `password(size)`, `search(placeholder, size)` (search glyph, clear
button, accessible name), `number(model, size)` and `area(textArea)`; `status(input, Status.ERROR)` sets a status.
[`TokenFormField`](../../megamek/src/megamek/client/ui/design/swing/TokenFormField.java) is the form field
(`setHelper`, `setError`, `setWarning`); its label is the input's `labelFor` and its message the input's accessible
description. Hover comes from one AWT listener (`FieldEdge`) and custom UI delegates (`FieldUIs`, `FieldBorders`),
because FlatLaf has no hover edge or minimum height for inputs. A read-only spinner is not offered, because its arrows
still step.

**libGDX.** [`UiTheme`](../../megamek/src/megamek/client/ui/gdx/UiTheme.java) registers the `TokenFieldStyle`s
`input<variant>` and `input<variant>-compact` (`UiTheme.INPUT_VARIANTS`: plain, search or reveal, each with error,
warning or read-only). [`UiTextField`](../../megamek/src/megamek/client/ui/gdx/UiTextField.java) adds the hover scene2d lacks,
the status icon, `status(Status)` and `readOnly(true)`; `UiTextField.Area` is the text area. `UiKit` builds them:
`input(text, compact)`, `password(compact)`, `tokenPassword(compact)`, `tokenSearch(placeholder, compact)`,
`area(text, rows)` and `formField(label, input)` ([`UiFormField`](../../megamek/src/megamek/client/ui/gdx/UiFormField.java)), whose input
fills the width the form gives it, down to none, instead of `TextField`'s fixed 150 minimum, and whose status reaches
the field inside a search or password input. `tokenPassword` is Swing's password field with FlatLaf's reveal
button: a compact icon button at the trailing edge, the `show` eye while the text is bullets and the crossed-out `hide`
eye while it shows as typed, in both presentations; it is a keyboard stop after the field and is hidden when the field
is read-only. It is the
in-game subset: there is no number spinner yet (`UiNumber`'s scrub stays the HUD's number control), and read-only text
can not be selected, because scene2d has no read-only field. hud-v3's own fields take the look without new controls:
`UiKit.search(placeholder, true)` builds the search field on `input-search` with a leading glyph and a compact icon
clear button; a plain `TextField` takes the `input` style and `UiKit.hoverEdge(field, root)` for the hover edge; and
`UiNumber.tokenLook()` gives the scrub a text.secondary caption, an `input-compact` field and the token slider. The
dial and hex pickers (`UiAngleDial`, `UiHexSides`) only recolour: `style("token")` reads the token `PickerStyle`
(discs, border.control and border.strong ticks, accent value, text.muted labels that turn text.secondary when chosen
and text.primary on hover). The hub keeps hud-v3's mint ring, `selection.width` (2) wide, and the needle stops at it,
so the value inside sits on a plain base. The `hud` and `hud-search` field styles stay as hud-v3 draws them today.

**Range input.** [`UiRangeInput`](../../megamek/src/megamek/client/ui/gdx/UiRangeInput.java), from
`UiKit.rangeInput(minText, maxText)`, is a from–to pair for whole numbers: two compact `input` fields `control.lg` × 2
(96) wide, their placeholders in `text.muted`, an en dash between them in `text.secondary`, `xs` (4) apart, left
aligned; in a form column that fills, the two ends share its width equally, each at least half its own. Only digits are
taken, six at most; an empty end is open. The fields' own `ChangeEvent`s stop at the range,
which fires one per edit instead, so a listener on a block that holds several controls hears each edit once.
`min()` and `max()` read the ends (null when open); `set(min, max)` fires nothing.

**Form columns.** In a labelled form column every select, text field, range input and pick list takes the column's
whole width, so the right edges of a form's inputs line up; inputs sized to their content give a ragged edge. Check
boxes, radios, switches, segmented controls, buttons, chips, steppers and number inputs keep their own width. In
libGDX, `UiKit.fillsFormColumn(input)` says which, `UiFormField` follows it for its input, and
`UiKit.formRow(form, label, input, labelWidth)` adds a label-beside-input row that does (the advanced search). There is
no maximum width: at 2560 wide the forms already split into columns, and a column's inputs reading as one block looks
right. A row of a few selects with inline labels (the Requisition Filters drawer's role, era and source) shares the
row's width equally instead, so its right edge meets the drawer's. A select beside a column of fields (the bot setup's
combat posture under the presets) takes that column's width.

## Choices

Check box, radio button and switch, with the label on the right. A click on the indicator or its label flips it. Both
galleries show every kind in every state, and a live row to click.

| State | Look |
| --- | --- |
| Off | `surface.input` fill, `border.control` edge |
| On | `surface.inverse` fill and edge with a `text.onInverse` mark: a check, a dot, or the switch's thumb on the right |
| Mixed (check box) | As on, with a dash in place of the check |
| Hover / pressed | `border.strong` edge; off adds the `surface.hover` wash, on leans the fill toward the mark color, further when pressed, like the primary button |
| Focus | The ring around the indicator, `focus.gap` (1) away (see [Focus](#focus)) |
| Disabled | `surface.base` fill, `border.subtle` edge, `text.disabled` mark and label; on keeps the faint `surface.inverseDisabled` fill with a `text.onInverse` mark |

- **Size.** The box and circle are `icon.sm` (16) in every row density; the switch's track is 16 high and 28 long,
  with a 12 thumb. The label sits `xs` (4) beyond the ring's room.
- **Which one.** A check box is for a choice confirmed with the rest of a form; a switch applies at once, like a
  setting; radio buttons pick one of a few, and a group always keeps one selected.
- **Not by color alone.** On always adds a mark or moves the thumb, so the state reads without telling colors apart.
- **Mixed** means some of the items a check box stands for are checked. It reads as unchecked, so a click checks
  everything; nothing clicks back into mixed.

**Swing.** The MegaMek themes give every `JCheckBox` and `JRadioButton` the token look through
[`TokenCheckBoxIcon`](../../megamek/src/megamek/client/ui/design/swing/TokenCheckBoxIcon.java) and
[`TokenRadioButtonIcon`](../../megamek/src/megamek/client/ui/design/swing/TokenRadioButtonIcon.java), so existing views
get it without changes. [`TokenChoices`](../../megamek/src/megamek/client/ui/design/swing/TokenChoices.java) builds
them: `check(text, checked)`, `radio(text, selected)`, `group(radios...)` and `toggleSwitch(text, on)` (a `JCheckBox`
with [`TokenSwitchIcon`](../../megamek/src/megamek/client/ui/design/swing/TokenSwitchIcon.java), announced as a toggle
button); `mixed(box, true)` shows the dash through FlatLaf's indeterminate state. Swing's accessibility API has no
mixed state, so a screen reader hears a mixed box as unchecked.

**libGDX.** [`UiChoice`](../../megamek/src/megamek/client/ui/gdx/UiChoice.java) draws the `choice-<shape><state>`
drawables [`UiTheme`](../../megamek/src/megamek/client/ui/gdx/UiTheme.java) registers (`box`, `radio` and `track`, in
the `UiTheme.CHOICE_STATES`), its mark and the focus ring. `UiKit` builds them: `check(text, checked)`,
`radio(text, checked)`, `toggleSwitch(text, on)` and `radioGroup(radios...)`. A click, or Space or Enter while it has
keyboard focus, fires a `ChangeEvent`; `setChecked` and `mixed(true)` from code fire none. `hovered`, `held` and
`focused` freeze those states for the gallery. hud-v3's `UiKit.checkbox` takes the Token v0 look with
`checkbox(text, ticked, true)`: the `choice-box` faces and a `text.onInverse` check over the inverse fill when ticked.

## Tabs

Labels on the window surface that switch between the pages of one view.

| State | Look |
| --- | --- |
| Rest | `text.secondary` label; a 1-wide `border.subtle` hairline runs under the whole strip |
| Hover | `text.primary` label (libGDX adds the `surface.hover` wash) |
| Selected | `text.primary` label and a `selection.width` (2) `selected.primary` underline, with or without focus |
| Focus | The ring inside the selected tab (see [Focus](#focus)) |
| Disabled | `text.disabled` labels and a `border.subtle` underline |

- **Sizes.** Compact (`control.sm`, 28 high, `md` side padding) is the default for every tab pane, since most sit in
  panels; regular (`control.md`, 36, `lg` padding) is for a window's top-level pages.
- **Labels** are Roboto Medium, like buttons, and follow the screen's [presentation mode](#presentation-mode) mode.
- **Not by color alone.** The underline marks the selected tab as well as the brighter label.

## Segmented control

Two to five short, mutually exclusive options as separate segments, `xs` (4) apart, for switching a view or a mode in
place (map, list or cards). Use tabs to switch between pages, and radio buttons for a choice in a form.

| State | Look |
| --- | --- |
| Rest | Each segment a `surface.raised` box with its own `border.control` edge and `radius.md`; `text.secondary` labels |
| Hover / pressed | A `border.strong` edge and the `surface.hover` wash (twice for pressed) on the segment, `text.primary` label |
| Selected | The `surface.inverse` face and `text.onInverse` label, as a checked toggle; hover and press lean it toward the label |
| Focus | The mint ring around the selected segment, `focus.gap` (1) outside it |
| Disabled | `border.subtle` edges, `text.disabled` labels; the selected segment keeps the faint `surface.inverseDisabled` face and a `text.onInverse` label |

- **Sizes** match buttons: regular `control.md` (36) with `lg` padding, compact `control.sm` (28) with `md`.
- **Width.** The segments share the width equally, the widest label setting it.
- **Separate, not joined.** Each segment reads as a button of its own, so a joined frame's hairlines, which hid beside
  the selection, are gone.
- **Keys.** Tab reaches the control as one stop; Left and Right move the selection, which applies at once.

**Swing.** The MegaMek themes give every `JTabbedPane` the token tabs, compact, so existing views get them without
changes; [`TokenTabs`](../../megamek/src/megamek/client/ui/design/swing/TokenTabs.java) makes a pane of either size
(`tabs(size)`, `size(tabs, size)`). FlatLaf has no pressed tab color, so a pressed tab looks hovered.
[`TokenSegmented`](../../megamek/src/megamek/client/ui/design/swing/TokenSegmented.java) is the segmented control:
`new TokenSegmented(size, labels...)`, `getSelectedIndex()`, `setSelectedIndex(i)` (fires nothing) and
`addActionListener` (user changes only, with the label as the command). Its sections are toggle buttons in a button
group, announced as radio buttons.

**libGDX.** [`UiTabs`](../../megamek/src/megamek/client/ui/gdx/UiTabs.java) and
[`UiSegmented`](../../megamek/src/megamek/client/ui/gdx/UiSegmented.java) share
[`UiOptionBar`](../../megamek/src/megamek/client/ui/gdx/UiOptionBar.java), which keeps the selection and draws the
faces from the `tab-over` and `seg-*` drawables and the `OptionStyle` colors
[`UiTheme`](../../megamek/src/megamek/client/ui/gdx/UiTheme.java) registers. `UiKit` builds them:
`tokenTabs(compact, labels...)` and `tokenSegmented(compact, labels...)`. A click, or Left and Right while the bar has
keyboard focus, fires a `ChangeEvent` from the bar; `select(i)` from code fires none. `option(i).hovered`, `held` and
`focused` freeze those states for the gallery. A view gives the bar keyboard focus itself, since libGDX has no general Tab
traversal yet; the start screen (`GpuStartScreen`) handles it itself: Up, Down and Tab move through every button, check
box, segmented control and text field on the page (its text fields turn their own Tab traversal off with
`setFocusTraversal(false)`), Left and Right switch a segmented control, Enter or Space presses a button, Enter in a text
field submits the page, and Esc returns to the main menu.

hud-v3's own `UiKit.segmented` takes the Token v0 look from the `token-segment` and `token-tab` styles (add
`-compact` for 28 high): `hud-seg` becomes the regular segmented control, `hud-pill` the compact one, and `hud-tab` and
`hud-tab-caps` compact tabs in sentence case. Segments share one width, `xs` apart, each drawn from the `seg-item`,
`seg-over`, `seg-down` and `seg-on` faces; tabs sit on a `border.subtle` hairline with a `selected.primary` underline under the
selected one.

## Select

Picks one value from a list that opens below the face. Use a segmented control or radio buttons when two to five
options should stay visible.

| Part / state | Look |
| --- | --- |
| Face | The input box (see [Inputs](#inputs)): the value in `text.primary`, a `text.secondary` chevron, a `text.muted` placeholder while nothing is chosen |
| Inline face | No box: `text.secondary` value that turns `text.primary` under the pointer, open or focused, over a dotted (2 on, 2 off) `border.control` underline, `border.strong` under the pointer |
| Hover / open | `border.strong` edge |
| Focus | The ring in place of the edge; the inline face gets the ring around it instead of the underline |
| Disabled | As a disabled input; the inline underline turns `border.subtle` |
| Popup list | `surface.popover` frame with a `border.control` edge, padded `xs` (4) top and bottom so the rows' highlight sits `xs` from the edge on every side, `xs` (4) below the face (above it when there is no room), at least as wide as the face; scrolls after 10 rows |
| Rows | `control.sm` (28) high; a `surface.hover` wash, inset `xs`, on the row under the pointer or the keyboard; a `selected.primary` check before the current value, in a leading `icon.sm` slot |

- **Sizes** match inputs: regular `control.md` (36), compact `control.sm` (28). The face is as wide as its widest item
  or placeholder, so it does not jump when the value changes. Held narrower by its layout, it keeps its padding and
  chevron and ends the value with an ellipsis. The inline face is `control.sm` high.
- **Inline** is for a value inside a header or running text, where a box would be too heavy.
- **The check marks the current value**, in `selected.primary`; the wash only follows the pointer.
- **Keys.** Enter, Space or Down opens the list on the current value; Up and Down move, Home and End jump, Enter
  chooses and Escape closes without a choice. The focus comes back to the face.

**Swing.** The MegaMek themes give every `JComboBox` the face, the popup list (check, wash, edge, gap and 10 rows)
through the `ComboBox` delegate in `FieldUIs`, so existing views get them without changes. The popup edge is the
themes' `PopupMenu.borderColor`, so menus get the `border.control` edge too.
[`TokenSelects`](../../megamek/src/megamek/client/ui/design/swing/TokenSelects.java) builds them: `select(size,
items...)`, `inline(items...)` (or `inline(combo)`) and `placeholder(combo, text)`, shown while the selected index is
-1. Swing's list has no disabled rows or separators.

**libGDX.** [`UiSelect`](../../megamek/src/megamek/client/ui/gdx/UiSelect.java) is the face and
[`UiListPopup`](../../megamek/src/megamek/client/ui/gdx/UiListPopup.java) the list, drawn from the input boxes, the
`sel-*` drawables and the `SelectStyle` colors [`UiTheme`](../../megamek/src/megamek/client/ui/gdx/UiTheme.java)
registers. `UiKit` builds them: `tokenSelect(compact, items...)` and `tokenSelectInline(items...)`. The list sits on
the stage's root while open and closes on a press outside it. Choosing another item fires a `ChangeEvent` from the
select; `select(i)` from code fires none, and `placeholder(text)` shows the placeholder until a choice. `hovered`,
`focused` and `opened` freeze those states for the gallery. hud-v3's own `UiKit.select(text, inline, true)` takes the
Token v0 look: the `token-select` style is the input box with a `text.secondary` chevron, and `token-select-inline` a
dotted `border.control` underline that turns `border.strong` on hover.

- **Type-ahead.** While the list is open, printable keys build a prefix and the first row that starts with it,
  ignoring case, becomes active; a pause of a second (`UiListPopup.TYPE_AHEAD_MS`) starts a new prefix, and a space
  counts only inside one. Enter then chooses the row. This makes long lists (equipment, quirks) usable from the keys.
- **Pick list.** [`UiPickList`](../../megamek/src/megamek/client/ui/gdx/UiPickList.java), from
  `UiKit.pickList(placeholder, items, excludes)`, picks several values from a list too long for chips: a compact select
  showing its placeholder, and the picks below it, `xs` (4) apart and wrapping, as acting chips with an `icon.sm`
  close glyph. Choosing an item adds it as a must-have and puts the placeholder back. With `excludes`, a click on a
  chip switches it between must-have (`NORMAL` tone) and must-not-have (`BAD` tone); without, it removes it, and the
  close glyph always removes it. One `ChangeEvent` per change; `picks()` is the index to must-have map and
  `set(picks)` fires nothing. Use filter chips while there are ten choices or fewer.

## Lists and trees

Show many items to pick from or browse. A select is better when only the chosen value needs to stay visible; a table
when items have several comparable columns.

| Part / state | Look |
| --- | --- |
| Frame | The scroll pane: `surface.input` inside a `border.control` edge; it carries the keyboard focus ring |
| Rows | At least `control.sm` (28) high, the shape inset `xs` (4) with a `radius.sm` corner, like the popup list's rows |
| Hover | A `surface.hover` wash on the row under the pointer |
| Selected | The `selected.background` wash, at full strength whether or not the list has focus, and a `selected.primary` marker, `selection.width` (2) by `icon.sm` (16), at the row shape's leading edge |
| Text | `text.primary`, starting `sm` (6) past the marker; `text.disabled` when the list is disabled, which drops the hover and the marker |
| Two-line row | At least `control.lg` (46) high: the title over a `small` detail line in `text.secondary` |
| Empty | A `text.muted` message centered in the first row's place |
| Tree | The list's rows with `text.secondary` chevrons and no connecting lines; each level indents 16, and the leading edge leaves room for the marker |

- **The marker means selected**, and keeps a selection visible without relying on the wash, in multiple selections
  too. Hover is a fainter wash with no marker.
- **Keys** are Swing's: Up and Down move, Home and End jump, Shift and Ctrl extend a multiple selection; Left and Right
  collapse and expand tree nodes.

**Swing.** The MegaMek themes draw every `JList` and `JTree` this way through
[`TokenListUI`](../../megamek/src/megamek/client/ui/design/swing/TokenListUI.java) and
[`TokenTreeUI`](../../megamek/src/megamek/client/ui/design/swing/TokenTreeUI.java), so existing views get them without
changes; a combo box's popup list keeps the select's rows. Lists with their own cell renderers keep their rows'
content and get the hover and the marker, but only the stock renderer gets the rounded selection.
[`TokenLists`](../../megamek/src/megamek/client/ui/design/swing/TokenLists.java) adds `emptyText(list, text)`,
`twoLine(list, title, detail)` and `scroll(view)`.

**libGDX.** hud-v3's own lists take the look without new controls.
[`UiTheme`](../../megamek/src/megamek/client/ui/gdx/UiTheme.java) registers the `token-row` button style (no box, a
hover wash, the selected wash and marker) and the `token-list` scroll pane style (the input frame, padded `xs`
to inset the rows). `UiKit.scrollList(list, true)` frames a list in it;
`new UiList(kit, UiTheme.TOKEN)` gives a reorderable list's flying row and slot token colors for `token-row` rows;
`new UiTree(kit, UiTheme.TOKEN)` draws `token-row` rows 28 high (46 with a second line in `text.secondary`) with
`text.secondary` chevrons; and `UiKit.empty(list, text, true)` centres a `text.muted` message in the first row's place.
In card and choice grids only the parts change: `UiKit.caption(text, true)` is the overline caption, the `token-card`
drawable a plain panel, and the options take the `button-secondary` style.

## Menus

Menu bars, their menus and context menus. They share the select's popup, so every popup in the suite looks the same.

| Part / state | Look |
| --- | --- |
| Popup | The select's popup: `surface.popover` with a `border.control` edge and a `radius.md` corner, padded `xs` (4) top and bottom |
| Rows | `control.sm` (28) high, the highlight inset `xs` (4) with a `radius.sm` corner; text starts `xs` + `md` (12) in, after a check column when any row has one |
| Hover / keyboard | A `surface.hover` wash on the row, `text.primary` label; the open menu in a menu bar gets the same wash |
| Check and radio | A `selected.primary` check or dot in the check column |
| Shortcut | `text.muted`, `text.secondary` on the hovered row, right-aligned |
| Submenu | A `text.secondary` chevron at the end of the row |
| Separator | A `border.subtle` line with `xs` (4) above and below |
| Disabled | `text.disabled` label, shortcut, check and chevron |

- **Hover stays neutral**, as in the select's popup and lists. The selected color only marks a state that is on.
- **Keys** are Swing's: arrows move and open submenus, Enter chooses, Escape closes, and Alt or F10 reaches the menu
  bar.

**Swing.** The MegaMek themes style every `JMenuBar`, `JMenu`, `JMenuItem`, `JCheckBoxMenuItem`,
`JRadioButtonMenuItem` and `JPopupMenu`, so existing menus get the look without changes.

**libGDX.** hud-v3's menu rows take the Token v0 look from the `token-menu-row` style: `UiKit.menuRow(style, ...)`
and `new UiMenuList(kit, UiTheme.TOKEN_MENU_ROW)`. Rows are `control.sm` high with the `sel-row-over` wash for the
pointer and the keyboard highlight, a `selected.primary` check, a `text.muted` shortcut and a `text.secondary` chevron. The list
sits in the `token-popup` frame (the select's popup with `xs` padding, which insets the wash) and its separators are
`border.subtle` hairlines. `UiButton.iconColors` keeps an icon in its own color rather than the text's, and
`TokenButtonStyle.detailFontColor` sets the details' color.

## Tooltips

Tooltips are small info cards, not bare OS labels: they share the popup's surface, edge and corner, and they can
carry a shortcut and a title.

| Part | Look |
| --- | --- |
| Card | `surface.popover` with a `border.control` edge and a `radius.md` corner, padded `sm` (6) top and bottom and `md` (8) at the sides |
| Plain text | `small`, `text.primary`; wraps at 280 (about 45 characters), at spaces and line breaks |
| Info card | A `body.strong` title in `text.primary` over `small` detail in `text.secondary`, `xxs` (2) apart |
| Shortcut | A key chip at the end of the first line: `caption` in `text.secondary` inside a `border.control` edge with a `radius.sm` corner |
| HTML | Keeps its own layout inside the same card |

- **One line of plain text is about `control.sm` (28) tall**, the height of a compact control.
- **Delays stay user settings**: MegaMek's tooltip delay and dismiss preferences still apply. Tooltips fade in `fast`
  (see [Motion](#motion)).

**Swing.** The MegaMek themes draw every tooltip this way. `TokenTooltips.withKey(component, text, key)` adds a
shortcut chip; `TokenTooltips.card(component, title, detail, key)` makes an info card. Both set the plain tooltip text
too, so screen readers and other look and feels still get it.

**libGDX.** `UiKit.tip(actor, true)` gives a hud-v3 tooltip the card: the `token` `TextTooltipStyle` in
[`UiTheme`](../../megamek/src/megamek/client/ui/gdx/UiTheme.java), `small` text in `text.primary` on the `token-tip`
box (`surface.popover`, a `border.control` edge, a `radius.md` corner, padded `sm` by `md`), wrapping at 280. The info
card and the shortcut chip aren't there yet: hud-v3's tooltips are plain text.

## Scrollbars

Scroll bars stay quiet until they're needed: no track or arrow buttons of their own, so a bar sits on the content it
scrolls instead of drawing a stripe beside it.

| Part / state | Look |
| --- | --- |
| Bar | 10 wide (`sm` + 2 × `xxs`), the color of the scrolled content; the corner between two bars matches it |
| Thumb | An `sm` (6) wide pill, `xxs` (2) from the bar's sides, at least `control.sm` (28) long; `border.control` |
| Hover | The pointer anywhere on the bar shows the rail, a `surface.hover` pill over the content, and turns the thumb `text.muted` |
| Dragging | The thumb turns `text.secondary` |
| Disabled | No thumb and no rail |

- **The thumb stays visible on every surface**: `border.control` keeps at least 3:1 against the base, panel, input and
  popover surfaces in both modes.
- **Wheel and keys** are Swing's. Smooth scrolling is on unless reduce motion is set (see [Motion](#motion)).

**Swing.** The MegaMek themes draw every `JScrollBar` and `JScrollPane` this way through
[`TokenScrollBarUI`](../../megamek/src/megamek/client/ui/design/swing/TokenScrollBarUI.java) and
[`TokenScrollPaneUI`](../../megamek/src/megamek/client/ui/design/swing/TokenScrollPaneUI.java), so existing views get
them without changes. A bar takes the color of an opaque view, otherwise the viewport's; a transparent scroll pane
shows what's behind it.

**libGDX.** The `token-list` scroll pane style has the 10-wide bar and the `border.control` pill thumb, `xxs` in from
the bar's sides, which always shows. `UiKit.scrollList(list, true)` gives the bar a strip of its own beside the list,
the bar plus an `xs` (4) gap, so rows, their hover and selection washes and their right-aligned values all end left of
it and nothing draws under the bar. The pointer anywhere on the strip swaps in the `scroll-rail` and the `text.muted`
thumb, and dragging shows the `text.secondary` thumb. hud-v3's 4-wide `hud-list` knob, which sits on the content, stays
as hud-v3 draws it.

## Sliders

A number picked from a range by dragging the thumb, clicking the track (the thumb jumps there) or the arrow keys.

| Part / state | Look |
| --- | --- |
| Track | An `xs` (4) high pill: `accent.primary` up to the thumb, `border.control` past it |
| Thumb | `surface.inverse`, the cream of every handle and "on" face, so the mint focus ring stands out around it. App mode: an `icon.sm` (16) disc. Game mode: hud-v3's flat thin bar, `xs` (4) wide and `icon.sm` (16) tall with square corners. With or without ticks and labels |
| Ticks | `tick.width` (2) pills under the track in `border.control`: major ones `sm` (6) long, minor ones half that |
| Labels | `small` type in `text.muted` under the ticks |
| Hover / dragging | The thumb leans toward `text.onInverse` like the primary button, further while dragging; the disc also gains a `border.strong` edge |
| Focus | The ring around the thumb, `focus.gap` (1) away (see [Focus](#focus)) |
| Disabled | A `border.subtle` track and ticks with no fill, and a `text.disabled` thumb |

- **One look per mode.** Ticks and labels don't change the thumb or drop the fill, so every slider on a screen reads
  the same way; the screen's [presentation mode](#presentation-mode) picks the disc or the bar.
- **Readout.** When the exact value matters (opacity, scale, a count), show it beside the track in `body` type. It
  keeps the width of the longest value so the track doesn't shift as it changes.
- **Not by color alone.** The thumb's position carries the value; the fill only repeats it.
- **Mint fill, cream thumb.** The fill is an amount, like a progress bar, so it keeps the accent; the thumb is a handle,
  so it is cream like the other control faces. The angle dial's hub ring stays mint.

**Swing.** The MegaMek themes draw every `JSlider` this way through
[`TokenSliderUI`](../../megamek/src/megamek/client/ui/design/swing/TokenSliderUI.java), so existing views get it
without changes, including their ticks and standard labels; a custom label table keeps the labels it was given.
[`TokenSliders`](../../megamek/src/megamek/client/ui/design/swing/TokenSliders.java)`.withReadout(slider, format)` adds
the readout and lines it up with the track on the slider's baseline. On a game-mode screen
(`TokenPresentation.setMode`) the thumb is the bar (`Slider.barThumb*` defaults).

**libGDX.** [`UiTheme`](../../megamek/src/megamek/client/ui/gdx/UiTheme.java) registers the `token` `SliderStyle`:
a 4-high pill track, an accent fill and a 16 inverse disc thumb that gains a border.strong edge on hover or press, and
the `token-slider-bar` style with the game-mode bar thumb. `UiKit.tokenSliderStyle()` returns a copy of the one for
the kit's presentation mode (the bar by default, since libGDX is game mode); the tuning panel's `UiKit.sliderStyle()` stays as hud-v3
draws it.

## Tables

Items with several comparable columns, such as units with their tonnage, movement and armor. A list is better when
each item is one line of text.

| Part / state | Look |
| --- | --- |
| Frame | The scroll pane, as for lists |
| Rows | The list's rows stretched across every column: `control.sm` (28) high, inset `xs` (4) with a `radius.sm` corner. No grid lines |
| Zebra | Every other row gets `surface.hover` at half strength, so the eye can follow a wide row |
| Hover | A `surface.hover` wash over the row under the pointer, painted on top so it also shows on stripes and on custom cells |
| Selected | The list's selection: the `selected.background` wash and the `selected.primary` marker at the row's leading edge |
| Cells | `sm` (6) apart; the first column's text starts past the marker and the last one ends `sm` (6) inside the row shape. Numbers align right |
| Header | `overline` labels (upper case) in `text.secondary` on `surface.input`, over a `border.control` line. Each label aligns like its column |
| Header hover / sorted | `text.primary`; the sorted column adds a `text.primary` chevron `sm` (6) after its label. Dragging a column gives it a `surface.hover` fill |
| Empty | A `text.muted` message centered in the first row's place |
| Disabled | `text.disabled` text and headers, with no hover or marker |

- **The marker means selected**, as in lists. A selected cell has no rectangle of its own: the row is the cursor.
- **Sorting.** Click a header to sort by it and again to reverse; the chevron shows the direction.
- **Keys** are Swing's: Up and Down move, Page Up and Page Down scroll, Shift and Ctrl extend a multiple selection.

**Swing.** The MegaMek themes draw every `JTable` and its header this way through
[`TokenTableUI`](../../megamek/src/megamek/client/ui/design/swing/TokenTableUI.java),
[`TokenTableHeaderUI`](../../megamek/src/megamek/client/ui/design/swing/TokenTableHeaderUI.java) and
[`TokenTableCellBorder`](../../megamek/src/megamek/client/ui/design/swing/TokenTableCellBorder.java), so existing views
get them without changes. Tables with their own renderers keep their cells' content and get the hover and the marker;
headers take their alignment from the column's renderer.
[`TokenTables`](../../megamek/src/megamek/client/ui/design/swing/TokenTables.java)`.emptyText(table, text)` adds the
empty message.

**libGDX.** Nothing new yet; hud-v3 has no table.

## Progress bars

How far along a known amount of work is, or that unknown work is still going on.

| Part / state | Look |
| --- | --- |
| Track | An `xs` (4) high `border.control` pill, as on sliders |
| Fill | `accent.primary` from the leading edge, in the same pill shape |
| Text | Above the track, `xs` (4) away, in `small` type and `text.secondary`, leading-aligned; never inside the bar |
| Percentage | Optional: on the text's line at the trailing end, `md` (8) past the text, in `small` type and `text.primary`; the whole percent, rounded down. Room is kept for "100%" so the text doesn't move |
| Indeterminate | An `accent.primary` segment, 30% of the track, enters at the start, crosses and leaves at the end, once every 1.6 s |
| Disabled | A `border.subtle` track with a `text.disabled` fill and text |

- **Text outside the bar.** Status messages ("Round 3: waiting for the other players") are often longer than the bar
  is wide, and text over a thin fill can't keep its contrast.
- **Determinate when possible.** Use the indeterminate bar only when the amount of work is truly unknown.
- **Rounded down.** The percentage shows 100% only when the work is done. An indeterminate bar shows none, but keeps
  the line so its text doesn't jump.

**Swing.** The MegaMek themes draw every horizontal `JProgressBar` this way through
[`TokenProgressBarUI`](../../megamek/src/megamek/client/ui/design/swing/TokenProgressBarUI.java), so existing views get
it without changes; the bar grows by the text's height when it shows text. Vertical bars keep FlatLaf's drawing in the
same colors. The client property `TokenProgressBarUI.SHOW_PERCENT` set to `true` adds the percentage.

**libGDX.** hud-v3's meters take the look without new controls: `UiKit.meter(caption, compact, true)` puts the
caption above in `small` type and `text.secondary` (`caption` type when compact) and the value at the trailing end in
`text.primary`, sentence case, over the `token-track` pill with a pill fill. A meter shows a value rather than progress,
so its caller passes the fill color, a status role such as `status.success` for armor. `GpuHudKit.heatBar(segments,
true)` keeps the heat bar's shape in token colors: a `border.control` track, the heat in `status.warning`, the hatched
forecast, and the ticks in `text.muted`, the next one in `text.primary`. hud-v3 has no indeterminate bar yet.

## Sections

A heading that names the group of fields or content below it, inside a page or a dialog.

| Part / state | Look |
| --- | --- |
| Header | A `control.sm` (28) high row: an `overline` title (upper case) in `text.secondary`, then a 1px `border.subtle` line from `sm` (6) past it to the far edge, level with the middle of the capitals |
| Titled panel | The header across the top of the panel; the content starts below it, flush with the title. No box |
| Collapsible | The header with the tree's chevron `sm` (6) in from its leading edge; the content below opens and closes |
| Collapsible hover | A `surface.hover` wash over the header row with a `radius.sm` corner; the title turns `text.primary` |
| Collapsible focus | The focus ring inside the header row (see [Focus](#focus)) |
| Disabled | A `text.disabled` title; a collapsible section doesn't open or close |
| Separator | The line alone, `border.subtle`, horizontal or vertical |

- **Short titles.** Overline is for labels of one or two words; longer explanations go in the content.
- **Keys.** A collapsible section's header takes the keyboard focus; Enter or Space opens or closes it.
- **No boxes.** Sections are told apart by their headers and space, not by frames around them.

**Swing.** [`TokenSections`](../../megamek/src/megamek/client/ui/design/swing/TokenSections.java) makes the three
forms: `header(title)` is a label to place above a group, `titled(title)` is a
[`TokenTitledBorder`](../../megamek/src/megamek/client/ui/design/swing/TokenTitledBorder.java) for a panel, and
`collapsible(title, content, expanded)` is a
[`TokenCollapsibleSection`](../../megamek/src/megamek/client/ui/design/swing/TokenCollapsibleSection.java) that fires
`expanded` when it opens or closes. Screen readers get the title as written, not in capitals, and the collapsible
header as a toggle that is expanded or collapsed. `JSeparator` already has the separator's look.

Existing borders from `BorderFactory.createTitledBorder` change with the themes but keep the JDK's layout: their title
gets `label` type in `text.secondary` and a `border.subtle` line after it instead of a box, but it stays in mixed case
and sits about 3px further in than the content. Moving a view to `TokenSections.titled` gives it the true look. The
settings dialog's `CollapsibleSectionPanel` is the developers' to move to `TokenSections.collapsible`.

**libGDX.** Nothing new yet; hud-v3 has no collapsible section.

## Split panes

Two areas side by side or stacked, with a divider the user drags to share the space between them.

| Part / state | Look |
| --- | --- |
| Divider | A 1px `border.subtle` line in the middle of an `md` (8) wide grab area |
| Hover / dragging | A `selection.width` (2) `accent.primary` line |
| One-touch arrows | `text.muted`, `text.primary` on hover and `accent.primary` pressed |

**Swing.** The MegaMek themes draw every `JSplitPane` divider this way through
[`TokenSplitPaneUI`](../../megamek/src/megamek/client/ui/design/swing/TokenSplitPaneUI.java), so existing views get it
without changes.

**libGDX.** `UiKit.tokenSplit(split)` gives hud-v3's split pane the `token-split` divider, a 1px `border.subtle` line
in an `md` grab band without hud-v3's grip, and the 2px `accent.primary` line while the pointer is on it or drags it.
scene2d has no one-touch arrows.

## Dialogs

Two tiers. Messages, confirmations and short forms open as a **modal card** over the window; tools and longer forms
that the user moves around or keeps open beside the game open as a **window** with the theme's title bar.

| Part / state | Look |
| --- | --- |
| Scrim | `surface.scrim` over the whole window; it takes every click, and the window's keys stand down |
| Card | `surface.panel` with a 1px `border.subtle` edge and `border.strong` corner ticks, `tick.panel` (13) long and `tick.width` (2) wide; centred in the window |
| Width | `dialog.sm` (420) for messages and confirmations, `dialog.md` (620) for forms, `dialog.lg` (800) for the largest cards, `dialog.xl` (960) for a picture browser such as the camo chooser; never wider than the window less `xxxl` on each side |
| Padding | `xl` (16) above the header, `xxl` (24) elsewhere; a window has `xxl` all round |
| Header | An optional status icon at `icon.lg` (24), the title in `headline`, then help and close icon buttons in the compact size |
| Status | Info `status.info`, success `status.success`, warning `status.warning`, error `status.danger`, question `accent.primary`; always with the icon, never the color alone |
| Body | `body` text in `text.primary` wrapping at the card's width, or any content such as form fields; an optional extra row (for example "Don't ask again") `xl` (16) below it |
| Footer | `xxl` (24) below the content: the buttons centred on the card while a leading tool or check box stays `xxl` (24) clear of them, else at the right edge, `md` (8) apart, all as wide as the widest and at least 88; the main action first, as in Windows (last in right-to-left) |
| Motion | The card and its scrim fade in over `fast` (120 ms); they close at once. Reduce motion skips the fade. A card that greets (the start screen's welcome) may unfold instead: `UiDialog.unfold(true)` opens it from a thin line at its centre over `medium`, the scrim darkening over the same time, and its bands reveal after it |

- **One main action.** A dialog has at most one primary or danger button, and it goes first; Cancel and other choices
  follow it. This is the Windows order ([Deploy] [Cancel]), which most of the suite's users already know.
- **Keys.** Enter presses the focused button or else the main one; Escape and the close button cancel. Tab stays in the
  card.
- **Destructive actions.** With a danger button the focus starts on Cancel, and Enter only presses the danger button
  when the user has moved the focus to it.
- **First focus.** Otherwise the first input of the body takes the focus, then the main action.
- **No stacking by habit.** A card can open over another card, but a flow that needs that is a sign it wants a window.
- **Footer and header.** The footer holds the user's answer to the question the card asks, so only a card that asks
  something has one. The header's trailing end holds controls for the card itself: close, help, or stopping what it
  shows (see [Status cards](#status-cards)).
- **Full panels are not cards.** A screen-filling panel such as the lobby's unit bay, C3 manager or map setup ends in a
  command bar instead of a footer, as a game's screens do: its status or warning line on the left, its buttons in the
  bottom-right corner, `md` apart and of one width, the main action last so it sits in the corner. File and tool
  actions (Load, Save) may sit at the bar's leading end.

**Swing.** Build the content as a
[`TokenDialogPane`](../../megamek/src/megamek/client/ui/design/swing/TokenDialogPane.java): `status`, `width`,
`message` or `body`, `extra` and `help`, then the buttons with `cancel`, `secondary` and one of `primary` or `danger`.
[`TokenDialogs`](../../megamek/src/megamek/client/ui/design/swing/TokenDialogs.java)`.show(owner, pane)` opens it as a
card (a [`TokenModalCard`](../../megamek/src/megamek/client/ui/design/swing/TokenModalCard.java) in the window's layered
pane) and returns the pressed button, or null when cancelled; it waits like a modal dialog. When the window has no
room for the card it opens a window instead, which `TokenDialogs.window(owner, pane)` also does on request. The
shortcuts `message`, `confirm` and `confirmDanger` cover the common cases. A form checks its inputs with
`check(button -> ...)`: giving false keeps the dialog open, so the form can show its errors under the inputs and move
the focus to the first wrong one; cancelling is never checked. While a card is open
`UIUtil.isModalDialogDisplayed()` is true, so MegaMek's hotkeys stand down as they do for a modal dialog.

Existing `JOptionPane` dialogs keep their windows but take the footer's layout through the MegaMek themes: the token
status icons, `xxl` padding, buttons centred, as wide as each other, at least 88 and with the main one first. Their
titles stay in the title bar. Moving them to `TokenDialogs` gives them the card. Other existing dialog windows take
the same footer from `TokenDialogs.footer(buttons...)`: the buttons restyled with `TokenButtons.style`, the main one
first, centred `md` apart, as wide as each other, at least 88 wide and `control.md` high, `xl` below the content and
`xxl` from the window's edges. MegaMek's `AbstractButtonDialog` family uses it; its Host and Connect dialogs are
cards.

**libGDX.** `UiKit.dialog(title, close, true)` is the card: the token panel frame padded `xl` above the header and
`xxl` elsewhere, the title in `headline`, sentence case, and the compact icon button for close, `xl` above the body. Its
owner sets the width (`dialog.sm`) and adds the body and the footer, centred, the buttons `md` apart, as wide as each
other and at least 88, the main one first. `UiModal.fadeIn(seconds)` fades the layer in over `fast` when it opens; its
scrim is already `surface.scrim`. `new UiConfirmation(kit, name, dismissed, UiTheme.TOKEN)` puts the confirmation
beside its trigger in the token popover: a `status.warning` icon, the question in `body.strong` over `text.secondary`
detail, then compact danger and secondary buttons with the focus on Cancel.

`UiDialog` puts these together for a dialog with a form: `new UiDialog(kit, title, cancel).width("dialog.md")` makes
the `UiModal` layer (the `surface.scrim`, which takes every click and key, a click on it not dismissing) and the card,
`body()` is a table in a vertical-only scroll pane under the title, `primary`, `secondary` and `cancelButton` add the
centred footer buttons in that order, and `tool` adds compact secondary buttons at the footer's leading end (such as
Reset or Refresh). The card is the width token or the window less `xxxl` on each side, whichever is narrower, and at
most the window's height less `xxxl` on each side, the body scrolling. `show(stage, motion)` fades it in over `fast`
(at once without motion), focuses the first field, else the primary button, and `close()` gives the focus back. Keys:
Enter presses the focused button or else the primary (a disabled primary does nothing), Space the focused button,
Escape and the close button run `cancel`, and Tab and Shift+Tab cycle through the card's enabled buttons, fields,
selects and sliders only, scrolling the body to the focused one. Actions added to `layer()` before `show` are dropped,
as showing restarts its fade; put per-frame work on an actor of its own. The lobby opens these only through
`LobbyNativeDialogs` (camo, player settings, bot setup, planetary conditions, game options, a confirmation and a plain
message); the bot setup, a confirmation and a message stack over another dialog, which gets its focus back when the top
one closes. A confirmation (`LobbyNativeDialogs.confirm`) is a `dialog.sm` card with the question as body text and
OK/Cancel. The planetary conditions are a `dialog.md` card with captioned sections on one two-column grid, the `md`
gutter split between the columns so they line up from section to section, and check boxes under a blank label so they
sit on the fields' line. The game options are the one larger dialog: `dialog.lg + dialog.sm` wide (1220, or the
window less `xxxl` margins), a 260 navigation column (search, match count, the group tree, the unofficial and legacy
switches, the password) beside a page shown in a scroll pane whose height follows the window (between 280 and 760),
the navigation stacking above the page below a 720 body; an option is its control with its tooltip, then its marks as
non-interactive chips (Important and Unofficial and Legacy in `NORMAL`, Changed in `OK`, shown while it differs), and an
advanced page's title carries a `status.warning` chip; text and number options are at most 320 wide; Defaults, Save
and Load are footer tools. `GpuModalDialog` on the board is still the developers' work.

## Status cards

The progress of a long task, such as printing the board or joining a game, in the dialog card's frame but without the
scrim: the user keeps working while it runs.

| Part / state | Look |
| --- | --- |
| Card | The dialog card's frame, padding and widths (`dialog.sm` by default); no scrim, no shadow |
| Header | The title in `headline`, sentence case, then Cancel at the trailing end as a compact secondary button with text |
| Body | A [progress bar](#progress-bars) with the running step as its text and the percentage at the trailing end; indeterminate when the length is unknown |
| Failed | The bar's fill turns `status.danger` and the step and percentage give way to the error in `small` `text.primary` above it; Cancel becomes Close, with Retry before it, `md` (8) apart, when the task can run again |
| Footer | None: the card asks nothing |

- **Placement is the view's.** The card is a component; the view puts it where it covers nothing the user works on (a
  map view at the bottom centre, for example) and   decides what to do when several tasks run at once. [Toasts](#toasts)
    are a separate pattern.
- **No focus.** Beside the view the card doesn't take the focus; a user who tabs to it gets Escape to stop the task.
- **Cancel, not ✕.** An ✕ reads as hiding the card while the task goes on, so stopping is a word.
- **The end.** On success the view removes the card at once. A failure stays until the user closes it, so the error
  isn't missed.
- **Waiting.** When the user can do nothing until the task ends, the same card shows over the scrim as a modal card,
  with the focus on Cancel.

**Swing.** [`TokenStatusCard`](../../megamek/src/megamek/client/ui/design/swing/TokenStatusCard.java) is the card:
`onCancel` shows Cancel and runs when it is pressed, `step` and `progress` (0 to 1) or `indeterminate` show the work,
`fail(message, retry)` shows an error with Retry when `retry` is not null, `onClose` runs when the user closes a failed
card, and `finish` ends it. Calls must come on the event thread, so a background task uses `SwingUtilities.invokeLater`
or a `SwingWorker`. `TokenDialogs.progress(owner, card)` shows it as a modal card and waits, still handling events,
until the user cancels or closes it or the task calls `finish`.

**libGDX.** Spec only for now. hud-v3's `UiKit.Progress`, the print card in `GpuMapHud`, is close: a panel with corner
ticks, the header and Cancel at the top right, the step and percentage over a bar, at the bottom centre of the map.
Moving it to this spec means sentence case instead of capitals (an open question for the whole HUD), the accent fill
instead of mint, the failed state with Close and Retry, and the modal form for the loading screen.

## Panels

Anything that isn't a dialog, such as a sidebar, a unit card or a grouped area, sits in a panel with the dialog card's
frame.

| Part / state | Look |
| --- | --- |
| Panel | `surface.panel` with `border.strong` corner ticks, `tick.panel` (13) long and `tick.width` (2) wide, and a 1px `border.subtle` edge along the top and bottom only, like a paper roll: the ticks close the sides. No shadow |
| Padding | `lg` (12) all round; `xl` (16) for the roomy option, for panels that hold reading text |
| Header | Optional: a status icon at `icon.md` (20), the title in `title` and `text.primary`, sentence case, then compact quiet or icon buttons at the trailing end; `md` (8) above the body, no divider or raised band |
| Status | The ticks take the status color (`status.danger`, `status.warning`, `status.success` or `status.info`); always with the header icon, never the color alone |
| Selectable, hover | The edge turns `border.control` |
| Selectable, selected | A `selected.background` wash, the edge and ticks in `selected.primary` |
| Selectable, focus | The `focus.ring` 2px ring in place of the edge, the ticks drawn over it |
| Plain | A 1px edge all round, with no fill and no ticks, for groups inside a panel |
| Disabled | The title in `text.disabled`, the edge and ticks in `border.subtle`; a disabled selectable panel can't be selected and Tab skips it |
| Motion | Edge, ticks and wash ease with the [control timings](#motion); reduce motion makes them instant |

- **Only the outermost frame has ticks.** Panels inside a panel, such as the cards in a sidebar, use the plain frame.
  A card inside a screen that has its own corners, such as the start screen's tip card, keeps the panel's fill and
  padding with a 1px `border.subtle` edge all round and no ticks (`token-panel-plain`).
- **Paper roll.** Every ticked frame (panels, dialog cards, status cards) has edges only at the top and bottom. The
  focus ring still goes all round.
- **Selectable panels are controls.** A click, or Space or Enter with the focus on it, selects the panel; in a group
  one is selected at a time, like radio buttons. Screen readers hear a toggle button, or a radio button in a group.
- **The header's actions are for the panel's content**, such as Edit or a ⋮ menu, in the compact size so the header
  keeps the title's height.
- **Overlay.** Over the libGDX board the panel's fill is the overlay surface, translucent like hud-v3's panels today;
  Swing panels are opaque.

**Swing.** [`TokenPanel`](../../megamek/src/megamek/client/ui/design/swing/TokenPanel.java) is the panel:
`new TokenPanel(title)` or `new TokenPanel()` for no header, then `status`, `padding(Padding.ROOMY)`, `plain()`,
`action` for each header button and `body` for the content. `selectable(true)` makes it a control; `setSelected` fires
the `SELECTED` property, and `TokenPanel.selectOne(panels...)` groups them so one is selected at a time. Content inside
must be non-opaque, since the themes' panels paint `surface.base`; `body` clears it on a `JPanel` it is given. To frame
an existing panel without changing its layout, `TokenFrameBorder.apply(panel, TokenFrameBorder.panel(padding))` (or
`plain`) sets the border and makes the panel non-opaque so the border paints the surface.
[`TokenFrame`](../../megamek/src/megamek/client/ui/design/swing/TokenFrame.java) is the painter that dialogs, status
cards and panels share.

**libGDX.** hud-v3's panels take the frame through `UiTheme.HudFrame`, whose outline can now be thinner than its
ticks. `UiKit.panel(true)` is the `token-panel` frame padded `lg`; `UiKit.header(title, count, true, tools...)` the
header, with `closeButton(action, true)` as a tool; `UiKit.footer(panel, true)` a `border.subtle` hairline and a foot
row, each `md` below the one before. `token-panel-danger` replaces `panel-foe` (its header carries the error icon),
`token-frame` is the plain frame, `token-rails` replaces `panel-rails` (the Tactical View chip: the overlay panel
with `border.control` rails), `token-panel-overlay` is the panel on the overlay surface over the board, and `token-divider` replaces `rule`. `new UiPopover(kit, dismissed, UiTheme.TOKEN)` is the popover in the
`token-popup` frame, with a `body.strong` title over `small` detail in `text.secondary` and a `border.subtle` divider.
`UiKit.card()` is a card inside a panel (the lobby's player cards): the `token-card` frame padded `sm`, with `md` on
the leading side. `UiKit.solid(role)` is a flat block of a color token (a team's edge bar, a player's color swatch),
sized by its cell. `token-card-selected` is a selected card (a unit picked with ctrl or shift click): the same
surface under an `accent.primary` edge. `token-card-drop` is the card or formation under a dragged unit: `surface.raised`,
with a doubled `accent.primary` edge; between rows the drop target is a `token-row-slot` strip. `UiKit.pictureCard(text, aspect)` (`UiPictureCard`) is a card that shows a picture and opens something when
clicked (the lobby's map): the `token-picture-card` button, `token-card` at rest and `token-card-over` (a
`border.strong` edge) under the pointer, as high as its width times the picture's aspect up to `maxHeight`, with a
muted line while there is no picture. The lobby added the `more-vert`, `add`, `check-circle`, `radio-unchecked`, `hourglass`, `bot` and
`palette` glyphs to `UiTheme.ICONS`.

The battle HUD's own parts (the HUD parts tab) live in `GpuBoardSkin` and `GpuHudKit`. `GpuHudKit.unitRow(w, h, true)`
is the unit row as a selectable plain panel: the `token-unit-row` style has a `border.subtle` edge at rest,
`border.control` on hover, and the `selected.background` wash with a `selected.primary` edge when selected; an
inspected row takes a `status.danger` edge for an enemy and `accent.primary` for a friend. Its model is in `small`
`text.muted`, the name in `body.strong` `text.primary` in sentence case, and the status line in `small` in its tone's
status color (`text.secondary` for a plain line); an enemy's sprite turns `status.danger`. `letter(letter, card, true)`
keeps the target's color on a `radius.sm` square, icon.md in a pill and icon.lg on a card, with the letter in `label`
`text.onAccent`. `damageTile(color, true)` keeps the damage color inside a `border.control` frame. The log's
`token-disc` and `token-disc-foe` are `control.sm` discs on `surface.raised` with an `accent.primary` or
`status.danger` edge, and the dock's `token-waiting` is a plain frame `control.lg` high. Target, sprite and damage
colors are game data and stay as they are.

## Chips and badges

Chips are small rounded labels: a status chip reports a state, a filter chip narrows a view and a tag chip is a value
the user added. A badge counts something new on a button's corner. All of them have a `radius.md` (6) corner and
their text in `small` (11.5), sentence case.

| Kind / state | Look |
| --- | --- |
| Status chip | `icon.sm` (16) plus `xxs` (2) above and below, 20 high; the status icon at `icon.sm` and a 1px edge in the status color, the text in `text.primary`; padding `xs` (4) before the icon, `md` (8) after the text |
| Status chip, neutral | No icon, the edge in `border.control` and the text in `text.secondary`, `md` padding both sides |
| Filter chip | `control.sm` (28) high, `lg` (12) padding; a `border.control` edge and the text in `text.secondary` |
| Filter chip, hover | A `surface.hover` fill, the edge in `border.strong`, the text in `text.primary` |
| Filter chip, on | The `surface.inverse` fill and edge, a check at `icon.sm` before the text, both in `text.onInverse`; the chip grows by the check. Disabled, the faint `surface.inverseDisabled` fill |
| Tag chip | `control.sm` high, the text in `text.primary` inside a `border.control` edge, then an ✕ in `text.muted`; hovering the ✕ shows a `surface.hover` square with a `radius.sm` corner and the ✕ turns `text.primary` |
| Focus (filter, tag) | The `focus.ring` 2px ring in place of the edge; on an on filter chip, around it `focus.gap` (1) away |
| Disabled (filter, tag) | The edge in `border.subtle`, the text in `text.disabled` |
| Count badge | `icon.sm` high and at least as wide, `xs` padding; the count in the label's medium face at `caption`, `text.onAccent` on `accent.primary`; 99 and up shows 99+ |
| Dot badge | `md` (8) across, in `accent.primary` |
| Badge tone | `status.danger`, `status.warning` or `status.success` instead of the accent when the count is a status, such as damage reports |
| Badge ring | `xxs` (2) in the surface behind, so the badge stands out of the button it overlaps |
| Motion | Filter chips ease their fill and edge with the [control timings](#motion); reduce motion makes them instant |

- **Color is never the only cue.** A status chip always has its icon (info, check, warning, error), and screen
  readers hear the status as the chip's description. A filter chip shows a check when on, not only the fill.
- **Status chips are smaller because they aren't controls.** They sit in rows and cards beside text; the interactive
  chips take the compact control height so they keep a comfortable target.
- **Filters.** A filter chip is a toggle; in a group where one filter applies at a time it behaves like a radio button
  and one is always on.
- **Tags.** Clicking the ✕, or Delete or Backspace with the focus on the chip, removes it and the focus moves on. The ✕
  has the tooltip "Remove …", and screen readers hear a "Remove …" button.
- **Badge placement.** The badge's centre sits `xs` inside the button's top-right corner, or on the icon's top-right
  corner for a button with only an icon; top-left in right-to-left. A count of zero hides the badge. Screen readers
  hear the count, so the button's description should say what it counts ("2 unread messages").

**Swing.** [`TokenChips`](../../megamek/src/megamek/client/ui/design/swing/TokenChips.java) makes the chips:
`status(text, tone)` with a `TokenChips.Tone`, `filter(text)` for a toggle, and `tag(text, onRemove)` for a removable
value whose `onRemove` runs after the chip leaves its parent (`TagChip.removeTag()` removes it from code).
`TokenChips.selectOne(chips...)` groups filter chips so one is on at a time.
[`TokenBadge`](../../megamek/src/megamek/client/ui/design/swing/TokenBadge.java) is the badge: `TokenBadge.count(n)` or
`TokenBadge.dot()`, then `tone` for a status color; `setCount` changes it and hides it at zero.
`TokenBadge.attach(button, badge)` returns a holder to add in the button's place, which places the badge and leaves room
for it.

**libGDX.** `UiKit.chip(text, tone, true)` is the status chip: `Tone.OK`, `WARN` and `BAD` get the check, warning and
error icons and the `token-chip-success`, `-warning` and `-danger` edges, and `Tone.NORMAL` is the neutral chip;
`UiKit.edgeChip(text, edge)` is the status chip without an icon, edged in any `token-chip-*` frame (the lobby's sides).
`UiKit.gameMasterChip(text)` is the Game Master's chip: the `shield-person` icon in `status.gamemaster` and the
`token-chip-gamemaster` edge (the lobby's GM chip on a player card).
`UiKit.actingChip(text, tone)` is the status chip as a button (`token-chip-button-<tone>`): `tint.hover` washes it
under the pointer and `tint.pressed` fills it pressed or checked, so checking it for a moment lights it (the lobby's
warning chips, which a blocked Ready lights for 700 ms). All three set their text in upper case, as the `overline` role in either [presentation](#presentation-mode). A
button in a token style (`button-*`) draws `UiButton.badge` as the count badge, in the `token-badge` face and frame
with its ring in `surface.panel`, and `UiButton.dot` as the dot; `UiKit.countBadge(count)` (`UiCountBadge`) is the
same badge on its own, beside a title (the lobby chat's unread lines), hidden at 0 but keeping its height; hud-v3's buttons keep the amber pill. About 25 calls in
the unit card, the forces panel and overview and the HUD kit still make hud-v3's chips (`UiKit.chip(text, tone)`), also
in capitals. hud-v3 has no tag chips; its target pills (`pill`, `pill-on`) are toggles, so
they take the filter chip's look: `token-pill`, `token-pill-over` and `token-pill-on` in `GpuBoardSkin`, with the
check before the target's letter square when on.
`UiKit.filterChip(text)` makes [`UiFilterChip`](../../megamek/src/megamek/client/ui/gdx/UiFilterChip.java), the filter chip (`token-filter-chip`): `control.sm` high with `lg` padding at its sides, so it matches the compact buttons, search field and selects in its row; the neutral chip in text.secondary at rest; on, it fills in the accent's pressed tint, its text turns text.primary and the check shows in room the chip always keeps, so it never resizes. That room (`lg` side, the check's icon size and its `xs` gap) is kept at both ends, the check drawn in the leading one, so the label is centred on the chip whether checked or not. `UiFilterChip.sameWidth(chips)` gives a group the width of its widest label plus both reserves (the Requisition view's weight chips and the Filters drawer's choices); a search field beside them gives up width rather than the chips.

**Staggered reveal (libGDX).** [`UiReveal`](../../megamek/src/megamek/client/ui/gdx/UiReveal.java) is a column of bands that appear one after another, each fading in and rising `space.md` over `motion.medium` on the token easing, `motion.fast` after the one before (the stagger stops growing after eight bands); it plays once, on the column's first show, and reduce motion shows every band at once. The Requisition dossier's Readout reveals its blocks with it, after the feel of MekHQ's planet details panel, as do the Damage tab (title, chart, range tiles, firing arcs) and the Factions tab (one band per era). `delay(index)` is how long a band waits, 0 without motion, so a widget inside the band can start its own motion as the band appears (the damage chart's sweep and the arc diagram's reveal).

**Steppers and long lists (libGDX).** `UiKit.stepper(value, min, max)` makes [`UiStepper`](../../megamek/src/megamek/client/ui/gdx/UiStepper.java): − value + in `button-icon` compact buttons round the value in body.strong (as wide as its widest value, so the + does not move), disabled at the bounds, with an optional caption before them (`UiKit.stepper(caption, value, min, max)`); caption, −, value and + are all `xs` apart (`UiStepper.gap()`), and a row of steppers puts a larger gap (`md`) between groups, stepped by the arrow keys with the focus, firing a ChangeEvent per step (the Requisition strip's quantity and set skills). [`UiVirtualList`](../../megamek/src/megamek/client/ui/gdx/UiVirtualList.java) is a list of any length with one row height for a scroll pane: only the rows in view exist, reused as it scrolls (`UiVirtualList.visible` works out which), with the token row's hover and selected washes; a click selects, a double click or Enter activates, and Up, Down, Home, End, Page Up and Page Down move the selection (`UiVirtualList.step`) with the scroll pane following. The Requisition view lists its thousands of units in one.

## Toasts

A toast is a short notice that comes and goes over a view, such as a unit's move or a hit on the board. It never asks
anything and never takes the focus.

| Part / state | Look |
| --- | --- |
| Card | `surface.popover` with a 1px `border.subtle` edge and a `radius.md` (6) corner, at most 480 wide; the text wraps beyond that |
| Level | A 3px bar along the leading edge and the level's icon at `icon.md` (20), both in the level's color |
| Levels | Info `status.info` (info icon), success `status.success` (check), warning `status.warning` (warning), danger `status.danger` (error), Game Master `selected.primary` (shield with a person) |
| Unit | Optional: the acting unit's sprite, fitted in 40×34, after the icon |
| Text | `body` in `text.primary`, sentence case |
| Padding | `md` (8) above and below; `lg` (12) after the bar, `md` between the icon, sprite and text, then the ✕ |
| Close | An ✕ in the compact icon button at the trailing end, `xs` (4) from the edge; not in the Tab order |
| Motion | Fades in over `motion.medium` and out over `motion.slow`, eased; reduce motion makes both instant |

- **Color is never the only cue.** Each level has its own icon, and screen readers hear the level before the text
  ("Warning: Locust LCT-1V is running hot"). The Game Master has violet (`status.gamemaster`) and a shield of its own,
  so a referee's action never reads as an ordinary notice.
- **Placement.** Toasts stack at the top centre of the view, `lg` from the edge and `md` apart, the newest at the top
  pushing older ones down. At most five show at once; a sixth drops the oldest at once.
- **Time.** A toast stays for the player's toast duration (3 seconds by default), plus a second for a warning or the
  Game Master and two for danger, then fades out.
- **Hover pauses.** The pointer over a toast stops its time, and brings it back if it was fading; after the pointer
  leaves it stays at least a second more, so it can't vanish under a moving pointer.
- **Clicks pass through.** Only the ✕ takes clicks: a click on or beside a toast reaches the board under it, so
  toasts never block play.
- **No actions.** A notice that needs an answer is a [dialog](#dialogs); a long task's progress is a
  [status card](#status-cards); something that stays true, such as a unit's damage, is a status chip on the unit.

**Swing.** [`TokenToasts`](../../megamek/src/megamek/client/ui/design/swing/TokenToasts.java) holds a view and shows
toasts over it: `new TokenToasts(view)` goes where the view went, then `show(level, text)` or
`show(level, text, unitImage)` from any thread, with a `TokenToast.Level`. `clear()` removes them all.
[`TokenToast`](../../megamek/src/megamek/client/ui/design/swing/TokenToast.java) is the card, for a host that places
toasts itself.

**Swing board.** `BoardToastOverlay` draws today's board toasts: a fill in the level's color at 70%, bold text and no
icon. Moving it to this spec means the popover surface with the bar and icon, the body face, the ✕, hover pause and
the Game Master's icon; `ToastLevel` maps onto `TokenToast.Level`, with `ERROR` as `DANGER`, and the durations stay.

**libGDX.** `new GpuToastStack(kit, source, state, true)` draws Token v0's cards: the `token-toast-<level>` frame with
its bar, the level's icon (`shield-person` for the Game Master), the sprite fitted in 40 by 34, `body` text wrapped at
480 and the compact close button, the only part that takes presses. The cards fade in over `medium` and out over
`slow`. Where the stack sits is still hud-v3's: above the dock with the newest at the bottom. The top centre, the
newest on top and the hover pause are the developers' work.

## Charts

A chart of values by whole step, such as damage by range, is a **step chart**. Each series keeps its value across its
step and turns square into the next, in one of three styles: a **line**; an **area**, the same line over a fill in its
color at `tint.pressed`, for the series that matters most; or a **ceiling**, a dashed line without the turns, for a
bound the others stay under. Three guide lines in `border.subtle` sit behind the series: at nothing, at half, and at
the ceiling. The ceiling rounds up to 1, 2 or 5 times a power of ten above the highest value. The guides are labelled
in `caption` type and `text.muted`. Without bands the first, middle and last step are labelled under the plot. With
**bands** (named runs of steps, such as short, medium and long range) a divider in `border.subtle` marks each band's
start, every other band is washed in `border.subtle` at `tint.hover`, the band's name sits over it in `overline` type
and `text.secondary`, and the steps labelled below are the bands' starts and the last. Series draw in the order given,
so the one that matters most comes last and sits on top. A legend sits above the chart: a swatch of each series' color
beside its name in `small` type. Take series colors from color roles. With motion the chart **sweeps** in once from the
left over `motion.slow` on the token easing, an `accent.primary` scan line at its front; reduce motion draws it at
once.

The Requisition dossier's Damage tab is a targeting display rather than a table: maximum damage is a `text.muted`
ceiling at half the `selection.width`, heat-sustained a `status.warning` line at twice it, and expected damage an
`accent.primary` area at that width, over the S, M and L bands. Below the chart a **readout tile** per band gives the
numbers a screen reader, or a color-blind reader, needs: a `token-card` with the band's name in `overline` and
`text.secondary`, its hexes in `caption` and `text.muted`, the expected damage large in `display` type and the expected
series' color, and the maximum and heat-sustained in `small` type and `text.secondary`. Under the
tiles an **arc diagram** shows the expected damage by firing arc for the band picked in a segmented control.

**Arc diagram.** A ring of arcs round a picture (the unit's sprite, or the unit icon in `text.muted`), the first arc
centred at the top and the rest clockwise, a `space.md` band apart (the band's sides straight and parallel, so the
gap is as wide at the rim as at the hole), the hole `INNER` (0.55) of the ring. Each arc is a
track in `border.subtle` filled in `accent.primary` at an opacity from `tint.hover` up to full by its value against the
peak, nothing for nothing; the peak is taken across all bands, so switching bands keeps one scale. The value sits on
its arc in `title` type, `text.primary`, or `text.onAccent` once the fill reaches `STRONG` (0.6); the arc's name is
outside the ring in `caption` and `text.muted`. With motion the arcs fade in one after another, `motion.fast` apart
over `motion.medium`, and a new band's fills glide from the last over `motion.medium`; reduce motion shows them at
once.

**libGDX.** `UiKit.stepChart()` makes
[`UiStepChart`](../../megamek/src/megamek/client/ui/gdx/UiStepChart.java).
`show(List<UiStepChart.Series>, format)` takes the series and labels the guides with `format`. Each
`Series(values, color, thickness[, style])` holds the values for steps 1 to n; the style is `LINE` unless given.
`bands(starts, names)` names the bands, `sweep(motion[, delay])` draws the series in, and `progress()` reads how far.
`UiStepChart.ceiling(peak)` and `UiStepChart.labelledSteps(steps, starts)` are the scale and the labelled steps, free
of GL. The chart takes the width its cell gives it; give the cell a height. It takes no input. `UiKit.arcDiagram()`
makes [`UiArcDiagram`](../../megamek/src/megamek/client/ui/gdx/UiArcDiagram.java): `centre(actor)`,
`show(names, values, peak, format)` and `reveal(motion[, delay])`; `centreAngle`, `sliceAngles` and `intensity` are
its geometry and fill, free of GL. Swing has no step chart or arc diagram yet.

## Code

| Class | Role |
| --- | --- |
| [`DesignTokens`](../../megamek/src/megamek/client/ui/design/DesignTokens.java) | Loads and exposes the tokens |
| [`ColorMath`](../../megamek/src/megamek/client/ui/design/ColorMath.java) | Contrast, CIEDE2000, compositing, deficiency simulation |
| [`Vision`](../../megamek/src/megamek/client/ui/design/Vision.java) | Typical vision and the three simulated deficiencies |
| [`TokenChecks`](../../megamek/src/megamek/client/ui/design/TokenChecks.java) | Runs the JSON `checks` |
| [`SwingUiLibrary`](../../megamek/src/megamek/client/ui/design/swing/SwingUiLibrary.java) | The Swing gallery |
| [`TokenMotion`](../../megamek/src/megamek/client/ui/design/swing/TokenMotion.java), [`TokenButtonUI`](../../megamek/src/megamek/client/ui/design/swing/TokenButtonUI.java), [`TokenPopupFactory`](../../megamek/src/megamek/client/ui/design/swing/TokenPopupFactory.java), [`RowWash`](../../megamek/src/megamek/client/ui/design/swing/RowWash.java) | Swing motion: the animator, eased buttons, popup fade-in and row hover |
| [`TokenLafDefaults`](../../megamek/src/megamek/client/ui/design/swing/TokenLafDefaults.java) | Turns the tokens into FlatLaf values |
| [`MegaMekDarkLaf`](../../megamek/src/megamek/client/ui/design/swing/MegaMekDarkLaf.java), [`MegaMekLightLaf`](../../megamek/src/megamek/client/ui/design/swing/MegaMekLightLaf.java) | The Swing themes |
| [`TokenButtons`](../../megamek/src/megamek/client/ui/design/swing/TokenButtons.java), [`SpecialButton`](../../megamek/src/megamek/client/ui/design/swing/SpecialButton.java) | The Swing button set |
| [`TokenPresentation`](../../megamek/src/megamek/client/ui/design/swing/TokenPresentation.java), [`TokenFonts`](../../megamek/src/megamek/client/ui/design/swing/TokenFonts.java) | A Swing screen's presentation mode, and the caps-tracked and condensed fonts |
| [`FocusVisibility`](../../megamek/src/megamek/client/ui/design/swing/FocusVisibility.java), [`TokenCheckBoxIcon`](../../megamek/src/megamek/client/ui/design/swing/TokenCheckBoxIcon.java), [`TokenRadioButtonIcon`](../../megamek/src/megamek/client/ui/design/swing/TokenRadioButtonIcon.java), [`TokenTabbedPaneUI`](../../megamek/src/megamek/client/ui/design/swing/TokenTabbedPaneUI.java) | Swing keyboard focus |
| [`UiTheme`](../../megamek/src/megamek/client/ui/gdx/UiTheme.java), [`UiButton`](../../megamek/src/megamek/client/ui/gdx/UiButton.java) | The libGDX button styles and focus ring |
| [`TokenFields`](../../megamek/src/megamek/client/ui/design/swing/TokenFields.java), [`TokenFormField`](../../megamek/src/megamek/client/ui/design/swing/TokenFormField.java) | The Swing inputs and form field |
| [`UiTextField`](../../megamek/src/megamek/client/ui/gdx/UiTextField.java), [`UiFormField`](../../megamek/src/megamek/client/ui/gdx/UiFormField.java) | The libGDX inputs and form field |
| [`TokenChoices`](../../megamek/src/megamek/client/ui/design/swing/TokenChoices.java), [`TokenSwitchIcon`](../../megamek/src/megamek/client/ui/design/swing/TokenSwitchIcon.java) | The Swing check box, radio button and switch |
| [`UiChoice`](../../megamek/src/megamek/client/ui/gdx/UiChoice.java) | The libGDX check box, radio button and switch |
| [`TokenTabs`](../../megamek/src/megamek/client/ui/design/swing/TokenTabs.java), [`TokenSegmented`](../../megamek/src/megamek/client/ui/design/swing/TokenSegmented.java) | The Swing tabs and segmented control |
| [`UiOptionBar`](../../megamek/src/megamek/client/ui/gdx/UiOptionBar.java), [`UiTabs`](../../megamek/src/megamek/client/ui/gdx/UiTabs.java), [`UiSegmented`](../../megamek/src/megamek/client/ui/gdx/UiSegmented.java) | The libGDX tabs and segmented control |
| [`TokenSelects`](../../megamek/src/megamek/client/ui/design/swing/TokenSelects.java), [`FieldUIs`](../../megamek/src/megamek/client/ui/design/swing/FieldUIs.java) | The Swing select and its popup list |
| [`UiSelect`](../../megamek/src/megamek/client/ui/gdx/UiSelect.java), [`UiListPopup`](../../megamek/src/megamek/client/ui/gdx/UiListPopup.java) | The libGDX select and its popup list |
| [`UiStepChart`](../../megamek/src/megamek/client/ui/gdx/UiStepChart.java), [`UiArcDiagram`](../../megamek/src/megamek/client/ui/gdx/UiArcDiagram.java) | The libGDX step chart and arc diagram |
| [`UiVirtualList`](../../megamek/src/megamek/client/ui/gdx/UiVirtualList.java), [`UiStepper`](../../megamek/src/megamek/client/ui/gdx/UiStepper.java), [`UiFilterChip`](../../megamek/src/megamek/client/ui/gdx/UiFilterChip.java), [`UiReveal`](../../megamek/src/megamek/client/ui/gdx/UiReveal.java) | The libGDX virtualised list, stepper, filter chip and staggered reveal |
| [`TokenLists`](../../megamek/src/megamek/client/ui/design/swing/TokenLists.java), [`TokenListUI`](../../megamek/src/megamek/client/ui/design/swing/TokenListUI.java), [`TokenTreeUI`](../../megamek/src/megamek/client/ui/design/swing/TokenTreeUI.java) | The Swing lists and trees |
| [`TokenTooltips`](../../megamek/src/megamek/client/ui/design/swing/TokenTooltips.java), [`TokenToolTipUI`](../../megamek/src/megamek/client/ui/design/swing/TokenToolTipUI.java) | The Swing tooltips |
| [`TokenScrollBarUI`](../../megamek/src/megamek/client/ui/design/swing/TokenScrollBarUI.java), [`TokenScrollPaneUI`](../../megamek/src/megamek/client/ui/design/swing/TokenScrollPaneUI.java) | The Swing scrollbars |
| [`TokenSliderUI`](../../megamek/src/megamek/client/ui/design/swing/TokenSliderUI.java), [`TokenSliders`](../../megamek/src/megamek/client/ui/design/swing/TokenSliders.java) | The Swing sliders and their readout |
| [`TokenTables`](../../megamek/src/megamek/client/ui/design/swing/TokenTables.java), [`TokenTableUI`](../../megamek/src/megamek/client/ui/design/swing/TokenTableUI.java), [`TokenTableHeaderUI`](../../megamek/src/megamek/client/ui/design/swing/TokenTableHeaderUI.java), [`TokenTableCellBorder`](../../megamek/src/megamek/client/ui/design/swing/TokenTableCellBorder.java) | The Swing tables |
| [`TokenProgressBarUI`](../../megamek/src/megamek/client/ui/design/swing/TokenProgressBarUI.java) | The Swing progress bars |
| [`TokenSections`](../../megamek/src/megamek/client/ui/design/swing/TokenSections.java), [`TokenTitledBorder`](../../megamek/src/megamek/client/ui/design/swing/TokenTitledBorder.java), [`TokenCollapsibleSection`](../../megamek/src/megamek/client/ui/design/swing/TokenCollapsibleSection.java) | The Swing section headers, titled panels and collapsible sections |
| [`TokenSplitPaneUI`](../../megamek/src/megamek/client/ui/design/swing/TokenSplitPaneUI.java) | The Swing split panes |
| [`TokenDialogPane`](../../megamek/src/megamek/client/ui/design/swing/TokenDialogPane.java), [`TokenDialogs`](../../megamek/src/megamek/client/ui/design/swing/TokenDialogs.java), [`TokenModalCard`](../../megamek/src/megamek/client/ui/design/swing/TokenModalCard.java) | The Swing dialogs: content, opening and the modal card |
| [`TokenStatusCard`](../../megamek/src/megamek/client/ui/design/swing/TokenStatusCard.java) | The Swing status card |
| [`TokenPanel`](../../megamek/src/megamek/client/ui/design/swing/TokenPanel.java), [`TokenFrameBorder`](../../megamek/src/megamek/client/ui/design/swing/TokenFrameBorder.java), [`TokenFrame`](../../megamek/src/megamek/client/ui/design/swing/TokenFrame.java) | The Swing panel, the frame as a border for existing panels, and the frame painter shared with dialogs |
| [`TokenChips`](../../megamek/src/megamek/client/ui/design/swing/TokenChips.java), [`TokenBadge`](../../megamek/src/megamek/client/ui/design/swing/TokenBadge.java) | The Swing status, filter and tag chips, and count and dot badges |
| [`TokenToasts`](../../megamek/src/megamek/client/ui/design/swing/TokenToasts.java), [`TokenToast`](../../megamek/src/megamek/client/ui/design/swing/TokenToast.java) | The Swing toast stack and toast |
| [`VisionLayerUI`](../../megamek/src/megamek/client/ui/design/swing/VisionLayerUI.java) | Simulates a deficiency over any Swing component |
| [`GpuUiLibraryWindow`](../../megamek/src/megamek/client/ui/clientGUI/boardview/gpu/GpuUiLibraryWindow.java) | The libGDX gallery |

Outside the galleries, only the two Swing themes and the libGDX button, input, choice, tab, segmented and select
styles read the tokens; lists, trees, menus, tooltips, scrollbars, sliders, tables, progress bars, sections, split
panes, dialogs, status cards, panels, chips, badges and toasts are Swing-only for now, and so is motion. MegaMek Dark is
MegaMek's default theme, and the trial of MegaMek's Swing startup path on the new controls is under way (see the
gap analysis's Next steps). The all-caps decision is final ([Presentation mode](#presentation-mode)); hud-v3 moves only
once the developers accept the system.
