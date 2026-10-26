# InstaTray — notes for Claude

macOS menu-bar app (Java/AWT + Swing) that watches the clipboard for video links of 30 sites (`enum Site` +
`RULES` table in InstaTray.java) and downloads them through its own ordered queue with one of two engines
(`Site.engine`): yt-dlp for video sites, gallery-dl for artists' own platforms (Pixiv, FANBOX, Fantia, DeviantArt,
Misskey, Mastodon). Two catch-all sites (`OTHER` = yt-dlp, `OTHER_GALLERY` = gallery-dl limited to
`CREATOR_PLATFORMS`) take any other URL the libraries accept; see "Catch-all sites" below. Each site uses the newest `*cookie*.txt` in
`~/Downloads` whose name mentions it (`enum Site`). Output: one folder, default `~/Documents/Videos`, user-changeable,
optional per-site subfolders. History keys are `ig:`, `yt:`, `x:`, `twclip:`, `twvod:` + id.
User-facing docs (Spanish) are in README.md — keep it in sync with every feature change.

## Files
- `src/InstaTray.java` — tray, clipboard, sites/regexes, queue (`pending` + worker thread), download,
  cookies, settings, control channel; implements `WindowHost`.
- `src/Job.java` — one download: status (QUEUED/DOWNLOADING/DONE/FAILED/CANCELLED), percent, retry attempt,
  `notBefore`, title, yt-dlp output (session only), announced destinations; persisted as TSV (9 columns, the 9th is
  the title; older 7/8-column lines still load). `bytes` (file size) is computed at load/after download, not saved.
- `src/DownloadsWindow.java` — the only window: link field, filter chips, one painted row per job (buttons in a fixed
  column from `JobView.buttonBounds`), contextual footer, collapsible "Detalles" (yt-dlp output); polls every 500 ms;
  selection follows `Job` objects. Talks to the app only through `WindowHost`.
- `src/SettingsDialog.java` — "Ajustes" (⌘,), System Settings layout: a rounded `Card` per section, one `option()`
  row each (title + grey help left, control right: `DownloadsWindow.Switch`, `Choice` pill menu, `PillButton`;
  menus and "Cambiar…" share `CONTROL_W`). Folder, clipboard, embeds, retries, notifications, cookie status,
  "Instalar o actualizar" (`installTools`: brew install missing + brew upgrade the four tools; no lock —
  runBrew waits for the running download, which may call ensureTools). No Homebrew → the button installs it too
  (user request): latest release's `Homebrew.pkg` (`homebrewPkgUrl`, github.com only) via `osascript … installer -pkg
  … with administrator privileges` (native password dialog); setup.command in Terminal only if that fails.
- `src/JobView.java` — pure presentation rules (filters, row actions, grey status line, footer, empty texts); tested.
- `src/WindowHost.java` — what the windows need from the app; `DownloadsWindow.PreviewHost` is the stub for previews.
- `src/Theme.java` — light/dark palettes; appearance read at startup and every 3 s only while the window is visible.
- `src/Glass.java` — the Liquid Glass skin (macOS 26 style; user asked for "ultra modern", replacing the earlier
  WMP11/Vista skin): frosted bars, pill controls, system accent blue (`ACCENT_LIGHT/DARK`), `sheen()` = soft top light
  + specular rim. Progress shimmer / indeterminate sweep math (tested).
  The 40 ms animation timer runs only while a download row is shown; the list repaints only when a row changes
  (idle window ≈ 0.7 % CPU — keep it that way).
- `test.sh` + `test/*Test.java` — plain `main` tests (no JUnit), headless. Run `./test.sh` before committing.
- `src/AeroIcon.java` — procedural Liquid Glass squircle icons (name kept from the old orbs); one `Palette` per `Site` with the same enum name (`Site.palette()`).
- `src/NativeBar.java` + `src/InstaTrayBar.swift` (→ `Contents/Resources/InstaTrayBar`, built by `swiftc` in build.sh):
  the menu-bar item drawn natively (user wanted the least margin: TrayIcon is 45pt wide = 21pt + Java's fixed 12pt
  each side; native variable-length item around the 22pt icon = 38pt, the 8pt each side is macOS's own item spacing). Line protocol on
  stdin (`icon` base64 PNG, `tip`, `menu` snapshot, `open` = click it, for capturing the highlight) / stdout
  (`click <index>`). Must stay variable-length: a fixed length drew the click highlight off-centre (user noticed). The AWT PopupMenu stays the model,
  mirrored by a 500 ms EDT timer; clicks fire the AWT item's listeners. Helper missing or dead → AWT TrayIcon.
  Icon size unchanged (user: "el icono que se mantenga como estaba").
- `src/launcher.sh` → `Contents/MacOS/InstaTray`: checks Homebrew/openjdk/yt-dlp/ffmpeg/deno, `brew install`s what is
  missing (log: `setup.log`), then starts java detached. No Homebrew → dialog + `setup.command` in Terminal (official
  installer needs the admin password). Test hooks: `INSTATRAY_FORCE_INSTALL="deno"`, `INSTATRAY_FAKE_NO_BREW=1`.
- `src/setup.command` → `Contents/Resources/`: installs Homebrew + requirements, reopens the app.
- State in `~/Library/Application Support/InstaTray/`: `settings.properties`, `jobs.tsv` (last 200, unfinished jobs
  resume on start), `downloaded.txt`, `instatray.log`, `cookies-session.txt`.

## Build / run
- `./build.sh` — compiles with Homebrew JDK (`/opt/homebrew/opt/openjdk`), renders icon, builds and ad-hoc signs
  `InstaTray.app`, symlinks it to `~/Documents/InstaTray.app`.
- Quit the running copy first: `pkill -f InstaTray.jar`.

## gallery-dl specifics
- Run with `-o output.mode=terminal -o output.shorten=false -o downloader.progress=0`: pipe mode prints no progress
  and terminal mode would truncate paths to 80 columns. Output is read with `readSegment()` (splits on `\r` and `\n`).
  Lines: `  /path` = file starting (→ destinations + tooltip name), ` 45%  1.2MB 300kB/s` = progress,
  `✔ /path` / `* /path` = saved, `# /path` = already there.
- `--filter` keeps only video/animation extensions (zip = Pixiv ugoira, `--ugoira mp4`); non-mp4 results go through
  `toPlayableMp4()` (ffmpeg → H.264/AAC). Filename `{category}_{id}{num:?_//}.{extension}`.
- Pixiv needs `gallery-dl oauth:pixiv` once (token cached by gallery-dl); no menu item (user removed it): the first Pixiv
  download failing for lack of a token opens Terminal (`connectPixiv()`, once per session).
- Deliberately NOT supported (re-host others' work without permission): Kemono, Coomer, nhentai, ExHentai, Hitomi,
  manga aggregators (e.g. myreadingmanga), boorus. The user asked for myreadingmanga twice; it was declined.
  A third try went through EMBED and hit Cloudflare: never add `generic:impersonate` / anti-bot workarounds.
  A fourth try pasted the page HTML (DevTools element): the .mp4 was found but Cloudflare 403'd it — declined again;
  don't reuse `cf_clearance`/browser cookies or impersonation to get past it.

## Catch-all sites
- `otherCandidates()` picks URLs no rule matched and whose host isn't a named site's (so profiles/channels/live
  pages of named sites stay ignored), skipping bare home pages; max 5 per clipboard change.
- `probeLibraries()` runs `YT_DLP_PROBE` / `GALLERY_DL_PROBE` with each formula's own Python (shebang of the
  Homebrew script, `-I`), cached per URL. yt-dlp: first suitable extractor (as yt-dlp picks), not Generic, `working()`,
  `_RETURN_TYPE` "video", or "any" without playlist/user/channel/tab/… in its name. gallery-dl: category in
  `CREATOR_PLATFORMS` and a single-post subcategory. Never widen gallery-dl past creator platforms (policy below).
- Keys: `ytdl:<ExtractorKey>:<id>` (id = `get_temp_id`, else URL hash) and `gdl:<category>:<url>`; `Link.label()`
  shows the extractor/category. Cookies by registrable domain (`baseDomain`, `findCookiesForDomain`).
  `OTHER` adds `--playlist-items 1:10`.
- `EMBED` ("Video embebido", setting `embeds`, default off, user asked for it to study generic embedded-video
  downloads): URLs whose first suitable yt-dlp extractor is Generic (probe prints `*`, kept in `genericUrls`, checked
  after gallery-dl). Key `embed:<host>:<url hash>`. Prints `FOUND …` (`describeFound()`) into "Qué pasó".
  No-video/404 results fail silently (no notification). No site-specific workarounds; never tailor it to piracy
  sites (the user asked for wcostream.tv; declined).
- Pasted HTML (`looksLikeHtml`: page source in the plain text, or the `allHtmlFlavor` of a rich copy via
  `readClipboardHtml()`): `pageVideos()` → media (video/source, og:video, JSON-LD contentUrl, .mp4/.m3u8/.mpd URLs,
  JSON-escaped too, relative paths, `data-video`/`data-hls-src`… attrs, player configs `file:`/`hls:`/`videoUrl:` also
  inside `&quot;`-encoded `data-setup`) + players (iframe/embed, embedUrl, Plyr youtube/vimeo ids) + canonical page.
  Quality lists (`sources: [...]`, `<source size=…>` in one `<video>`, `_720p`/`_1080p` URL twins) keep only the best,
  manifests first; relative URLs resolved with `<base>`/canonical.
  `pageLinks()`: media may go Generic (EMBED) even with `embeds` off; players only via real extractors unless
  embeds/force; canonical URL only as fallback. Page source replaces the plain-text scan (otherwise every css/js URL
  would be probed). blob: URLs and `NOT_VIDEO` extensions
  (images/audio/css/js/subtitles; JSON-LD contentUrl also names images) skipped. No Referer/anti-bot tricks.
  The user asked for a real/headless browser to fetch the "real" video from pasted HTML: declined (same bypass).
  Instead: EMBED 403 → permanent "no deja bajar este video fuera de su página" and `Job.page` (8th TSV column) keeps
  the pasted page, which the row's "Abrir página" opens so the user can save it by hand.
- Window link field (`WindowHost.submit`) and tray "Descargar enlace copiado ahora" call `handleText(text, force=true)`:
  re-download allowed and EMBED allowed even with `embeds` off (`probeLibraries(urls, embeds || force)`). Control channel `get TEXT` does the same.

## Adding a site
1. `Site` entry (engine, label, yt-dlp filename template, cookie-file keys — keys with a dot must start the file name —,
   cookie domains). Only creator-owned platforms or video sites; never re-upload aggregators.
2. `AeroIcon.Palette` entry with the same name (glass colors only, no logos); check `--palettes` sheet.
3. `rule(...)` lines in `RULES`: match only single video/post URLs (never profiles/channels/live), key = prefix:id,
   url = clean canonical URL. Add positive and must-ignore samples to the LinkTest and run it.
4. Check yt-dlp has an extractor (`yt-dlp --list-extractors`) and try a real public URL with `--simulate`.

## User preferences (product decisions)
- **As silent as possible**: starts with no window (tray only); success never notifies; only final failures notify
  (toggle "Avisar si una descarga falla"); transient failures are retried silently (configurable 0–5 times, delay
  10s/30s/1m/5m growing linearly). Permanent failures (live, no video, login/cookies, no yt-dlp) are not retried.
- The window opens only on explicit request: tray menu, or a second launch of the app.
- One window only (user chose it): rows with text+icon buttons of equal width in a fixed column, filter chips,
  every row fills the same grid (user asked for it to "feel complete"): blue `primary` (the downloading row lends
  it to its progress bar), plain `secondary`, icon-only `dismiss` (✕ cancel/remove, 🗑 delete; tooltip names it);
  the toolbar's Descargar/Ajustes sit exactly over the blue/plain columns. Also:
  "borrar todas" as discreet footer text that depends on the filter (a red standalone button was rejected).
  "Borrar"/"Quitar" never delete files. Follows system light/dark mode.
- Tray tooltip: live "Site · title — 45% · 2.1 MB/s · quedan 0:12 · +N en cola" (`tooltip()`, throttled
  `refreshTooltip()`); yt-dlp gives title/speed/eta via `--print before_dl:TITLE …` (+ `--no-simulate`) and the
  progress template; back to "sin descargas" when idle.
- Tray badge: **1 pending → download percent with a small superscript "%"** (badge text "45%"; `drawBadge` keeps the
  digits full size); **≥2 pending → pending count, no "%"** (`9+` cap). The "%" is what tells the two apart. The fill level always shows the
  current download's progress (washed-out tile filling with saturated color bottom→top, meniscus line).
- Every icon variant is a Liquid Glass squircle tile (superellipse, top-left light, specular rim). Idle = aqua tile +
  white download arrow (also the .app
  icon). Busy = active site's palette. Instagram (multicolor, yellow-orange bottom → violet-blue top) and YouTube
  (flat single-tone red) must stay clearly distinct at 22pt. Site colors only, never logos.

## Hard-won constraints (do not regress)
- **Launcher must detach java** (`nohup java … &`), never `exec`. On macOS 26 a java process that *is* the bundle
  process never gets its NSStatusItem shown; detached it works.
- Because the launcher exits, single-instance is enforced in-app via a loopback `ServerSocket` on port 48623.
- **TCC**: this app cannot *list* `~/Documents` (neither can its child `find`), but files can be created/deleted by
  exact path. Partial cleanup on cancel therefore deletes the paths yt-dlp announced (`[download] Destination:`)
  plus `.part`/`.ytdl`, after `killAndWait()` — a dying yt-dlp re-creates the `.part` if you delete too early.
- Never touch `~/Downloads` on the EDT: the first access triggers the TCC prompt and blocks the UI thread.
- `--print after_move:filepath` implies quiet mode, which hides progress: keep `--no-quiet --progress --newline` and the
  `PROG downloaded total` progress template. Video+audio formats download as two files: weight 90/10 (`Progress`).
- Pass yt-dlp a *filtered copy* of the cookies (`writeSiteCookies`): only the site's domains. Never print cookie values.
  The user deleted their full-browser export; per-site exports are what's in Downloads now.
- "Copiar enlace" uses `copyToClipboardSilently()` (sets `lastClip`) so the watcher doesn't re-download it.
- `.app` launched from Finder has a minimal PATH: binaries are resolved from `BIN_DIRS` explicitly.
- Keep `-S vcodec:h264,res,acodec:aac` + `--recode-video mp4` (M1 can't play AV1/VP9/webm in QuickTime; recode only
  runs when the file isn't mp4 already), `--no-playlist`, `--match-filter !is_live`; channel/live URLs never match.
- Requirements are installed automatically (user request). In-app: `ensureTools()` (missing tools) and a weekly
  `brew upgrade yt-dlp`; both go through `runBrew()`, which sets `toolsBusy` so the worker doesn't start downloads.
  `HOMEBREW_NO_AUTO_UPDATE=1` only for installs — an upgrade needs the index refresh to see new versions.
- Compiled with `--release 21` so any recent Homebrew openjdk runs it; the launcher resolves java via `brew --prefix`.
- YouTube needs `deno` on PATH and the user's YouTube cookies (without them YouTube answers "confirm you're not a bot").
- The user's MacBook Air M1 has no notch; don't blame hidden menu-bar items on a notch.

## Verifying without screen-recording/accessibility permissions
`screencapture` works since 2026-10-26 (permission granted): `screencapture -x -R X,0,W,30 out.png` with X/W of the
menu-bar item from the Swift CGWindowList list below (2 px per point). System Events may still be denied. Also:
- **Control channel** (loopback 48623, one line): `printf 'queue\n' | nc -w 3 127.0.0.1 48623` →
  `running | yt:ID Descargando 38% | …`. Also `tooltip`, `get TEXT`, `pause`, `resume`, `top KEY`, `cancel KEY`,
  `remove KEY` (out of the list, like the row 🗑 / "Quitar"), `menu` (opens the menu-bar menu: capture the click
  highlight); an empty line shows the window.
- **Window renders**: `java -cp build/test-classes DownloadsWindow --preview DIR` (light, dark, Fallidas filter,
  Detalles, empty) and `SettingsDialog --preview DIR`, after `./test.sh` compiled them. Read the PNGs.
- **Log**: `updateTrayIcon()` logs `Tray icon -> PALETTE:badge:fillStep`; cleanup logs `Deleted partial …`.
- **Swift CGWindowList**: layer-25 windows = menu-bar items (count with app running vs. not; all are owned by
  Control Center on macOS 26); windows owned by the java pid = app windows (check `onscreen`).
- `findLinks()`, `findCookies()`, `writeSiteCookies()` are package-private static → testable headless with a tiny main.
- Clean up after tests: test videos in the videos folder, their keys in `downloaded.txt`, their rows in `jobs.tsv`.

## Conventions
- Code, comments, logs in English; UI strings and README in Spanish.
- Preview sheet of icon states: `java -cp <classes> AeroIcon --preview out.png` (copy to `icon-states.png`).
