# Ventana única de descargas — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace InstaTray's main window and its "Cola e historial" window with one downloads window (link field,
filter chips, one row per job with aligned action buttons, contextual footer, collapsible details), plus a small
Ajustes dialog, following the system light/dark mode.

**Architecture:** Presentation rules (which filter a job belongs to, which buttons a row shows, its grey status line,
footer actions, button geometry) live in a pure, headless-testable class `JobView`. The Swing window
(`DownloadsWindow`) and the dialog (`SettingsDialog`) only paint and dispatch; they talk to the app through a
`WindowHost` interface that `InstaTray` implements and that a stub implements for `--preview` PNG renders.
`Theme` holds the light and dark palettes and tracks the system appearance.

**Tech Stack:** Java 21 (`javac --release 21`), Swing/AWT only, no libraries. Tests are plain `main` classes run by
`test.sh` (no JUnit: the build has no dependency manager and must stay that way).

**Spec:** `docs/superpowers/specs/2026-10-26-ventana-unica-design.md`. Read it before starting any task.

## Global Constraints

- No new dependencies. Compile with `--release 21` as `build.sh` does.
- UI strings and README in Spanish; code, comments and logs in English (CLAUDE.md "Conventions").
- The menu bar (tray icon, badge, tooltip, menu) does not change visually. Its items "Abrir InstaTray" and
  "Cola e historial…" keep their text and both open the new window.
- The window never opens by itself (only tray menu, second launch, Dock reopen). Success never notifies.
- "Borrar"/"Quitar" only remove rows from the list; they never delete files.
- Never touch `~/Downloads` on the EDT (cookie status is computed on the `poller` thread).
- Orbs use `AeroIcon` with `link.site().palette()`.
- Control channel commands keep working: `queue`, `tooltip`, `get`, `pause`, `resume`, `top`, `cancel`, empty line.
- Icons in buttons are drawn with Java2D (vector glyphs), not emoji: Swing's emoji rendering is unreliable.
- Default window ~720×520 pt, resizable, minimum 560×360.
- Light and dark palettes for everything painted by hand; switching the macOS appearance while the window is open
  restyles it within a few seconds, without restart.

## Review Focus

1. **Very long titles/URLs/messages** (300+ chars, no spaces): text ellipsizes; buttons keep their column. → Task 2
   `buttonBoundsIgnoreText`, Task 5 preview with a 300-char title.
2. **Old `jobs.tsv` lines** (7 or 8 columns, no title) and titles with tabs/newlines load without losing rows.
   → Task 1 `oldLinesStillLoad`, `titleWithTabsRoundTrips`.
3. **A "Listo" job whose file is gone** (user moved/deleted it) or that has no file at all: "▶ Ver" must not fail
   silently. → Task 2 `doneWithoutFilesOffersPage`; Task 5 PLAY on a missing file shows the inline message
   «No encuentro el archivo; quizá lo moviste o lo borraste.» and reveals the folder.
4. **The downloading job** can never be removed with 🗑, ⌫ or "Quitar todas": only cancelled. → Task 2
   `downloadingIsNotDeletable`; Task 3 `remove` ignores `current`.
5. **Job changes state under the user's selection** (finishes while selected, or leaves the active filter):
   selection follows the `Job` object, never the row index; a row that left the filter just disappears without
   selecting a neighbour. → Task 5 Step "selection survives reload".

---

### Task 0: Branch

- [ ] **Step 1:** `git switch -c ventana-unica` (repo is on `main`). Expected: `Switched to a new branch 'ventana-unica'`.

### Task 1: Test runner + persisted title and file size in `Job`

**Files:**
- Create: `test.sh`, `test/JobTsvTest.java`
- Modify: `src/Job.java` (fields, `toLine`, `fromLine`)

**Interfaces:**
- Produces: `Job.title` persisted as the 9th TSV column; `volatile long bytes = -1` on `Job` (size of `files.get(0)`
  in bytes, -1 = unknown/missing; not persisted). `test.sh` compiles `src/*.java test/*.java` into
  `build/test-classes` and runs every `test/*Test.java` main with `-Djava.awt.headless=true`; exits non-zero if
  any fails. Each test class prints `PASS name` / `FAIL name: detail` and calls `System.exit(1)` on any failure.

- [ ] **Step 1: Write `test/JobTsvTest.java`** with these cases (build `Link` with
  `new InstaTray.Link(InstaTray.Site.YOUTUBE, "yt:abc", "https://youtu.be/abc")`):
  - `titleRoundTrips`: `job.title = "Pan casero"; status DONE` → `Job.fromLine(job.toLine()).title.equals("Pan casero")`.
  - `titleWithTabsRoundTrips`: title `"a\tb\nc"` → loaded title `"a b c"`, and `toLine().split("\t", -1).length == 9`.
  - `oldLinesStillLoad`: a 7-column and an 8-column line (copy the format `toLine()` produced before this change)
    load non-null with `title.equals("")`.
- [ ] **Step 2:** Write `test.sh` (bash, `set -euo pipefail`, same `JDK` resolution as `build.sh`). Run `./test.sh`.
  Expected: compile OK, `FAIL titleRoundTrips` (title not persisted), exit 1.
- [ ] **Step 3:** In `Job`: append `clean(title)` as the 9th column in `toLine()`; in `fromLine` read `f[8]` when
  `f.length > 8`; add `volatile long bytes = -1;` with a one-line comment.
- [ ] **Step 4:** `./test.sh` → all `PASS`, exit 0.
- [ ] **Step 5:** `git add test.sh test/JobTsvTest.java src/Job.java && git commit -m "Persist video title in jobs.tsv; add test runner"`

### Task 2: `JobView` — presentation rules

**Files:**
- Create: `src/JobView.java`, `test/JobViewTest.java`

**Interfaces:**
- Produces (all `static`, package-private, no Swing state):
  - `enum Filter { ALL("Todas"), DOWNLOADING("Descargando"), QUEUED("En cola"), DONE("Listas"), FAILED("Fallidas") }`
    with `final String label`.
  - `boolean matches(Filter f, Job j)`: DOWNLOADING = status DOWNLOADING; QUEUED = status QUEUED (includes waiting
    retries); DONE = status DONE; FAILED = FAILED or CANCELLED; ALL = everything.
  - `enum Action { CANCEL("Cancelar"), TOP("Poner primero"), REMOVE("Quitar"), PLAY("Ver"), REVEAL("Mostrar en Finder"), OPEN_PAGE("Abrir página"), RETRY("Reintentar") }` with `label`.
  - `Action primary(Job j)` / `Action secondary(Job j)` (null = empty slot):
    DOWNLOADING → null / CANCEL · QUEUED → TOP / REMOVE · DONE with files → PLAY / REVEAL ·
    DONE without files → OPEN_PAGE / null · FAILED or CANCELLED → OPEN_PAGE / RETRY.
    Only `primary` is drawn blue (accent). For DOWNLOADING the single CANCEL sits in the secondary slot.
  - `boolean deletable(Job j)`: true for FAILED/CANCELLED only (the 🗑 slot). `boolean removableByKey(Job j)`:
    FAILED, CANCELLED, DONE, QUEUED (⌫ key); never DOWNLOADING.
  - `String title(Job j)`: `j.title` if not blank, else `j.link.url()`.
  - `String subtitle(Job j, int queuePosition, long nowMs)`, exact copy:
    DOWNLOADING `"YouTube · 45 % · 2.1 MB/s · quedan 0:12"` (omit unknown parts; label = `j.link.label()`);
    QUEUED `"Instagram · en cola (2.º)"`, or `"Instagram · reintento en 12 s"` when `notBefore > now`;
    DONE `"✓ Listo · hace 5 min · 12.0 MB"` (relative time: `ahora`, `hace N min`, `hace N h`, `ayer`, else `dd/MM`;
    size omitted when `bytes < 0`); FAILED `"✕ " + message` (`"✕ Falló"` if blank); CANCELLED `"Cancelado"`.
  - `enum Tone { NORMAL, OK, BAD, MUTED }`, `Tone tone(Job j)`: DONE OK, FAILED BAD, CANCELLED MUTED, else NORMAL.
  - `enum FooterAction { PAUSE, CANCEL_ALL, RETRY_ALL, DELETE_ALL_FAILED, REMOVE_ALL_DONE, DETAILS }`,
    `List<FooterAction> footer(Filter f)`: ALL → PAUSE, DETAILS · DOWNLOADING and QUEUED → PAUSE, CANCEL_ALL, DETAILS ·
    FAILED → RETRY_ALL, DELETE_ALL_FAILED, DETAILS · DONE → REMOVE_ALL_DONE, DETAILS.
    Labels: «⏸ Pausar»/«▶ Reanudar», «✕ Cancelar todas», «↻ Reintentar todas», «🗑 Borrar todas»,
    «🗑 Quitar todas de la lista», «Detalles ▸»/«Detalles ▾» (footer text is plain text; these glyphs render fine
    in the system font, unlike the button icons).
  - `String emptyText(Filter f)`: ALL «Copia el enlace de un video y aparecerá aquí.»; DOWNLOADING «No se está
    descargando nada.»; QUEUED «No hay nada en cola.»; DONE «Todavía no hay descargas listas.»; FAILED «No hay
    descargas fallidas.»
  - `List<Job> ordered(List<Job> queue, List<Job> finished)`: queue as given (current first, then pending order),
    then finished sorted by `createdAt` descending.
  - `Rectangle[] buttonBounds(int rowWidth, int rowHeight)`: `[primary, secondary, trash]`, right-aligned with a
    14 pt right inset, buttons 134×24 pt, 6 pt gap, trash 26×24 pt last; vertically centred. Same for every row.
- Consumes: `Job` (Task 1).

- [ ] **Step 1: Write `test/JobViewTest.java`** covering, one named case each:
  `filtersPartitionStatuses` (every status matches ALL and exactly one other filter; CANCELLED → FAILED),
  `actionsPerStatus` (the table above), `doneWithoutFilesOffersPage`, `downloadingIsNotDeletable`
  (`deletable` and `removableByKey` false), `subtitleDownloading` (percent 45, speed `"2.1 MB/s"`, eta `"0:12"` →
  exact string above), `subtitleRetryWait`, `subtitleDoneRelative` (created 5 min ago, bytes 12 582 912 →
  `"✓ Listo · hace 5 min · 12.0 MB"`), `subtitleFailedBlankMessage`, `footerPerFilter`, `orderedQueueThenNewest`,
  `buttonBoundsAligned` (all rows of widths 600 and 900: primary and secondary widths equal; trash right edge =
  width − 14; same y), `buttonBoundsIgnoreText` (bounds depend only on width/height — call twice, equal).
  Size uses the existing `InstaTray.formatBytes` (no new formatter), so the exact expected string is
  `"✓ Listo · hace 5 min · 12.0 MB"`.
- [ ] **Step 2:** `./test.sh` → FAIL (class `JobView` missing).
- [ ] **Step 3:** Implement `src/JobView.java` with the signatures above.
- [ ] **Step 4:** `./test.sh` → all PASS.
- [ ] **Step 5:** `git add src/JobView.java test/JobViewTest.java && git commit -m "Add JobView presentation rules for the downloads window"`

### Task 3: `WindowHost` + model operations in `InstaTray`

**Files:**
- Create: `src/WindowHost.java`
- Modify: `src/InstaTray.java` (queue section ~1088–1240, settings setters ~763–820, control channel ~730–750,
  download success ~1900, `loadJobs` ~1333)

**Interfaces:**
- Produces `interface WindowHost` (all methods public, implemented by `InstaTray`):
  ```java
  List<Job> queueSnapshot();  List<Job> finishedSnapshot();
  void submit(String text, Runnable onNothingFound);          // poller thread; onNothingFound runs on EDT
  void move(Job j, int delta); void cancel(Job j); void cancelAll(); void retry(Job j);
  void remove(Job j);                                         // queued or finished; ignores the current job
  void removeFinished(java.util.function.Predicate<Job> which);
  boolean isPaused(); void setPaused(boolean on);
  boolean isWatching(); void setWatching(boolean on);
  String videosFolderLabel();                                 // displayPath(videosDir) (+ "/<red social>")
  void openVideosFolder(); void revealInFinder(Path file); void openUrl(String url); void copyToClipboardSilently(String text);
  boolean subfolders(); void setSubfolders(boolean on); boolean embeds(); void setEmbeds(boolean on);
  boolean notifications(); void setNotifications(boolean on);
  int maxRetries(); void setMaxRetries(int n); int retryDelaySec(); void setRetryDelaySec(int sec);
  void chooseVideosDir(java.awt.Window owner);
  void cookiesSummary(java.util.function.Consumer<String> onEdt); // computes cookiesLabel() on poller
  boolean detailsOpen(); void setDetailsOpen(boolean open);       // setting "details", default false
  ```
- Behaviour changes: `remove(j)`: if in `pending` → take out of `pending`, `queued`, `jobs`; if finished → out of
  `jobs`; `saveJobs()`; `updateTrayIcon()`; log `Removed <url>`. `cancelAll()` now `remove`s every pending job and
  `cancel`s only the current one (spec: only the downloading job ends as "Cancelado"). `removeFinished(p)` removes
  finished jobs matching `p`, then saves. After a successful download, set `job.bytes` from `Files.size(files.get(0))`
  on the worker thread; in `loadJobs()` (main thread, never EDT) set `bytes` for DONE jobs whose first file exists.
- Settings setters stop touching `win*` fields (Task 7 deletes them); they keep updating tray menu items and
  `saveSetting`. Add `maxRetries`/`retryDelaySec` setters that `saveSetting` like today's combo listeners.
- New control command `remove KEY` (same lookup as `cancel`, but over `jobsSnapshot()` + `queueSnapshot()`).

- [ ] **Step 1:** Add `WindowHost.java`; `InstaTray implements WindowHost`; make the listed methods public, add the
  missing ones. `./build.sh` must compile (the old windows still exist and still work).
- [ ] **Step 2: Verify with the running app** (`pkill -f InstaTray.jar; open InstaTray.app`). Pause first so jobs stay
  queued: `printf 'pause\n' | nc -w 3 127.0.0.1 48623`; queue two public test videos with `get URL`;
  `printf 'remove yt:<id2>\n' | nc …` → `ok`; `queue` no longer lists it and `jobs.tsv` has no row for it.
  `printf 'cancel …'`/`resume` behave as before. Then clean up per CLAUDE.md (videos, `downloaded.txt`, `jobs.tsv`).
- [ ] **Step 3:** `./test.sh` still PASS.
- [ ] **Step 4:** `git commit -am "Add WindowHost interface, remove-from-list and file sizes"` (add `src/WindowHost.java`).

### Task 4: `Theme` — palettes and system appearance

**Files:**
- Create: `src/Theme.java`
- Modify: `src/InstaTray.java` `main` (property before any AWT call)

**Interfaces:**
- Produces: `record Palette(Color window, Color row, Color rowSelected, Color text, Color secondary, Color separator, Color accent, Color accentText, Color ok, Color bad, Color button, Color buttonBorder, Color buttonText, Color chip, Color chipOn, Color chipOnText, Color track, Color footer)`
  nested in `final class Theme`; `static final Palette LIGHT, DARK`; `static Palette current()`;
  `static boolean isDark()`; `static void watch(Runnable onChangeEdt)` — a daemon thread runs
  `defaults read -g AppleInterfaceStyle` every 3 s (exit 0 + "Dark" ⇒ dark) and invokes `onChangeEdt` on the EDT
  only when the value flips. Light values follow the mockup (`#ffffff` rows, `#1d1d1f` text, `#86868b` secondary,
  `#0a84ff` accent, `#248a3d` ok, `#d70015` bad); dark uses macOS dark equivalents (`#1e1e1e` window, `#2a2a2a`
  rows, `#f5f5f7` text, `#98989d` secondary, `#0a84ff` accent, `#32d74b` ok, `#ff453a` bad).
- `main`: `System.setProperty("apple.awt.application.appearance", "system")` next to `apple.awt.UIElement` so title
  bars and native dialogs follow the system.

- [ ] **Step 1:** Write `test/ThemeTest.java`: `palettesComplete` (no null
  component in LIGHT/DARK via `record` accessors) and `textContrast` (relative-luminance contrast of `text` on `row`
  ≥ 7 and `secondary` on `row` ≥ 3 in both palettes). Run → FAIL.
- [ ] **Step 2:** Implement `Theme`. Run `./test.sh` → PASS.
- [ ] **Step 3:** `git add src/Theme.java test/ThemeTest.java src/InstaTray.java && git commit -m "Add light/dark Theme following the macOS appearance"`

### Task 5: `DownloadsWindow`

**Files:**
- Create: `src/DownloadsWindow.java`
- Consumes: `WindowHost` (Task 3), `JobView` (Task 2), `Theme` (Task 4), `AeroIcon.render`, `Job`.

**Interfaces:**
- Produces: `DownloadsWindow(WindowHost host, Runnable openSettings)`; `void show(JobView.Filter f)`
  (EDT; selects the chip, shows, `toFront`, `requestForeground`); `void showFieldMessage(String text)`;
  `static void main(String[] args)` — `--preview <dir>` writes `downloads-light.png`, `downloads-dark.png`,
  `downloads-failed-filter-dark.png`, `downloads-empty-light.png`, `downloads-details-dark.png` at 2× using a stub
  `WindowHost` with sample jobs (one downloading 45 % YouTube, one queued Instagram, one done TikTok with bytes,
  one failed generic with the embed-403 message, one failed with a 300-char title).
- Layout (spec items 1–7, «Decisiones mías»): title bar text "InstaTray"; top bar with the field
  (placeholder «Pega un enlace…», Enter submits) + accent «Descargar» button + «Ajustes» button (⌘, also opens it);
  inline field message line under it (secondary/bad colour, clears after 6 s); chips row with counts;
  list = `JList<Job>` with one cell renderer painting orb (28 pt), title (bold, ellipsized), subtitle (tone colour),
  4 pt progress bar while DOWNLOADING with `percent >= 0`, and the buttons at `JobView.buttonBounds`; a
  `MouseListener` hit-tests the same bounds (hover highlight + click). Empty list → `JobView.emptyText(filter)`
  centred. Footer: left «● Vigilando portapapeles · Documentos/Videos» (dot accent; «○ Portapapeles en pausa» when
  not watching; clicking the first part toggles watching, the folder part opens the folder); right the
  `JobView.footer(filter)` links. Details panel below the list, hidden by default, open/closed
  remembered through `host.detailsOpen()` / `host.setDetailsOpen(boolean)`.
- Behaviour: poll every 500 ms while visible (`host.queueSnapshot()`/`finishedSnapshot()` → `JobView.ordered`),
  rebuild the model only when the job list identity changed, else repaint; selection restored by `Job` identity.
  Double-click / Enter = primary action; ⌫/Delete = `remove` on selected jobs where `removableByKey`; right-click
  menu = row actions + «Copiar enlace» + «Detalles». PLAY: `open` the file; if `!Files.exists` →
  `showFieldMessage("No encuentro el archivo; quizá lo moviste o lo borraste.")` and `revealInFinder` on the folder.
  OPEN_PAGE uses `j.page` when not empty, else `j.link.url()`. Confirm dialogs (`JOptionPane`, Spanish) for
  CANCEL_ALL «¿Cancelar la descarga actual y quitar las que esperan?», DELETE_ALL_FAILED «¿Quitar de la lista las N
  descargas fallidas?», REMOVE_ALL_DONE «¿Quitar de la lista las N descargas listas? Los videos se quedan en tu
  carpeta.» The row 🗑 does not confirm. `Theme.watch` → re-apply colours and repaint.
- Details panel text = today's `HistoryWindow.refreshOutput()` content (URL, status, message, files, output), with a
  «Copiar» button; monospaced 11 pt; follows the stream while the same job stays selected.

- [ ] **Step 1:** Implement the class and its `--preview` main.
- [ ] **Step 2:** Render: `javac --release 21 -d build/classes src/*.java && java -cp build/classes DownloadsWindow --preview "$SCRATCH"`.
  Read every PNG. Expected: buttons in one aligned column in every row; 300-char title ellipsized, buttons intact;
  dark render has no white panels; empty render shows «Copia el enlace…»; failed-filter footer shows
  «↻ Reintentar todas · 🗑 Borrar todas · Detalles ▸».
- [ ] **Step 3: selection survives reload** — add to the preview main a `--selftest` mode (headless-safe, no PNG):
  select the downloading job, switch the stub to report it DONE, call the reload method → selected value is the
  same `Job`; with filter DOWNLOADING the reload leaves the selection empty and no other row selected. Prints
  `PASS`/`FAIL`; `test.sh` runs it (`java -Djava.awt.headless=true -cp … DownloadsWindow --selftest`).
- [ ] **Step 4:** `./test.sh` → PASS.
- [ ] **Step 5:** `git add src/DownloadsWindow.java test.sh && git commit -m "Add the single downloads window"`

### Task 6: `SettingsDialog`

**Files:**
- Create: `src/SettingsDialog.java`

**Interfaces:**
- Consumes: `WindowHost`, `Theme`.
- Produces: `SettingsDialog(java.awt.Frame owner, WindowHost host)`; `void open()` (re-reads every value from the
  host, asks `host.cookiesSummary` and shows «Buscando cookies…» until it answers, then shows the dialog);
  `static void main` `--preview <dir>` → `settings-light.png`, `settings-dark.png` with a stub host.
- Sections and copy (one grey help line under each control):
  *Carpeta*: path label + «Cambiar…»; checkbox «Una subcarpeta por red social» — «Videos/Instagram, Videos/YouTube…».
  *Descargas*: «Vigilar portapapeles» — «Descarga sola cada enlace de video que copies.»; «Buscar video embebido en
  otras páginas» — «Reproductores, HLS y <video> de sitios que no están en la lista.»; row «Si falla, reintentar
  [0–5] veces, esperando [10 s|30 s|1 min|5 min]» — «Cada reintento espera un poco más.»
  *Avisos*: «Avisar con una notificación si una descarga falla» — «Las descargas que salen bien nunca avisan.»
  *Cookies*: the summary text, read-only — «Exporta las cookies de cada sitio a Descargas para videos privados.»
  Modal to the downloads window, ⌘W/Esc closes, changes apply immediately (no OK/Cancel).

- [ ] **Step 1:** Implement; render previews; read both PNGs. Expected: grouped sections, nothing clipped, dark has
  no white areas.
- [ ] **Step 2:** `git add src/SettingsDialog.java && git commit -m "Add Ajustes dialog"`

### Task 7: Wire it in, remove the old windows

**Files:**
- Modify: `src/InstaTray.java` — delete `buildWindow()`, the `window`/`win*` fields and their uses; `history` field →
  `DownloadsWindow downloads`; create `downloads` and `SettingsDialog` in `start()` inside the existing
  `invokeAndWait`; `showWindow()` → `downloads.show(JobView.Filter.ALL)`; tray «Cola e historial…» → same;
  `submit` reports «Ese enlace no tiene un video compatible» through `downloads.showFieldMessage`;
  `chooseVideosDir(owner)` uses the given owner.
- Delete: `src/HistoryWindow.java`.

- [ ] **Step 1:** Make the changes; `./build.sh` → `InstaTray.app` built, no warnings about missing symbols.
- [ ] **Step 2: Real-app check** (no screenshots available; use the control channel + log, CLAUDE.md "Verifying"):
  `pkill -f InstaTray.jar; open InstaTray.app`; `printf '\n' | nc -w 3 127.0.0.1 48623` opens the window — confirm
  with the Swift CGWindowList check that a window owned by the java pid is onscreen, and only one (the old main
  window must be gone). `get <public video URL>` then `queue` shows it downloading; when done, `jobs.tsv` row has
  the title in column 9. Close the window: app keeps running (`queue` still answers). Clean up test files.
- [ ] **Step 3:** `./test.sh` → PASS.
- [ ] **Step 4:** `git add -A src && git commit -m "Use the single downloads window; remove the old windows"`

### Task 8: Docs

**Files:**
- Modify: `README.md` (Spanish: the window section — one window, filters, buttons per state, footer, Detalles,
  Ajustes, follows dark mode, borrar no borra videos), `CLAUDE.md` (Files list: drop `HistoryWindow.java`, add
  `JobView`, `WindowHost`, `Theme`, `DownloadsWindow`, `SettingsDialog`, `test.sh`; Verifying: `--preview` renders,
  `remove KEY` command; jobs.tsv 9th column = title).

- [ ] **Step 1:** Edit both files.
- [ ] **Step 2:** `git commit -am "Document the single downloads window"`
