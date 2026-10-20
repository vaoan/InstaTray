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
- `src/InstaTray.java` — tray, main window, clipboard, sites/regexes, queue (`pending` + worker thread), download,
  cookies, settings, control channel.
- `src/Job.java` — one download: status (QUEUED/DOWNLOADING/DONE/FAILED/CANCELLED), percent, retry attempt,
  `notBefore`, yt-dlp output (session only), announced destinations; persisted as TSV.
- `src/HistoryWindow.java` — "Cola" / "Historial" tabs + shared "Qué pasó" output panel; polls every 500 ms.
- `src/AeroIcon.java` — procedural icons; one `Palette` per `Site` with the same enum name (`Site.palette()`).
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
- Pixiv needs `gallery-dl oauth:pixiv` once (token cached by gallery-dl); tray menu "Conectar Pixiv…" opens Terminal.
- Deliberately NOT supported (re-host others' work without permission): Kemono, Coomer, nhentai, ExHentai, Hitomi,
  manga aggregators (e.g. myreadingmanga), boorus. The user asked for myreadingmanga twice; it was declined.

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
- Tray tooltip: live "Site · title — 45% · 2.1 MB/s · quedan 0:12 · +N en cola" (`tooltip()`, throttled
  `refreshTooltip()`); yt-dlp gives title/speed/eta via `--print before_dl:TITLE …` (+ `--no-simulate`) and the
  progress template; back to "sin descargas" when idle.
- Tray badge: **1 pending → download percent with a small superscript "%"** (badge text "45%"; `drawBadge` keeps the
  digits full size); **≥2 pending → pending count, no "%"** (`9+` cap). The "%" is what tells the two apart. The fill level always shows the
  current download's progress (washed-out orb filling with saturated color bottom→top, meniscus line).
- Every icon variant must be a glossy Vista/Aero glass orb. Idle = aqua orb + white download arrow (also the .app
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
`screencapture` and System Events are denied. Instead:
- **Control channel** (loopback 48623, one line): `printf 'queue\n' | nc -w 3 127.0.0.1 48623` →
  `running | yt:ID Descargando 38% | …`. Also `tooltip`, `pause`, `resume`, `top KEY`, `cancel KEY`; an empty line
  shows the window.
- **Log**: `updateTrayIcon()` logs `Tray icon -> PALETTE:badge:fillStep`; cleanup logs `Deleted partial …`.
- **Swift CGWindowList**: layer-25 windows = menu-bar items (count with app running vs. not; all are owned by
  Control Center on macOS 26); windows owned by the java pid = app windows (check `onscreen`).
- `findLinks()`, `findCookies()`, `writeSiteCookies()` are package-private static → testable headless with a tiny main.
- Clean up after tests: test videos in the videos folder, their keys in `downloaded.txt`, their rows in `jobs.tsv`.

## Conventions
- Code, comments, logs in English; UI strings and README in Spanish.
- Preview sheet of icon states: `java -cp <classes> AeroIcon --preview out.png` (copy to `icon-states.png`).
