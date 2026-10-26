#!/bin/bash
# InstaTray launcher (Contents/MacOS/InstaTray): makes sure every requirement is installed, then starts
# the Java app detached.
APP_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
RES="$APP_DIR/Contents/Resources"
STATE="$HOME/Library/Application Support/InstaTray"
LOG="$STATE/setup.log"
mkdir -p "$STATE"

# Messages in the app's language (Ajustes → Idioma): the "language" setting, else the system's first language
# (Spanish only if it is Spanish), like Lang.Choice in the app.
lang_english() {
  local s first
  s=$(sed -n 's/^language=//p' "$HOME/Library/Application Support/InstaTray/settings.properties" 2>/dev/null | tail -1 | tr -d '\r' | tr '[:upper:]' '[:lower:]')
  case "$s" in en) return 0 ;; es) return 1 ;; esac
  first=$(defaults read -g AppleLanguages 2>/dev/null | sed -n '2p' | tr -d ' ",')
  case "$first" in es*) return 1 ;; *) return 0 ;; esac
}
LANG_EN=0
lang_english && LANG_EN=1
# msg "español" "English"
msg() { if [ "$LANG_EN" = 1 ]; then printf '%s' "$2"; else printf '%s' "$1"; fi; }

notify() { osascript -e "display notification \"$1\" with title \"InstaTray\"" >/dev/null 2>&1; }

BREW=""
for b in /opt/homebrew/bin/brew /usr/local/bin/brew; do
  [ -x "$b" ] && BREW="$b" && break
done
# test hook: INSTATRAY_FAKE_NO_BREW=1 behaves as if Homebrew were missing
[ -n "${INSTATRAY_FAKE_NO_BREW:-}" ] && BREW=""

if [ -z "$BREW" ]; then
  # Homebrew's installer asks for the administrator password, so it runs in Terminal where it can be typed
  text="$(msg "InstaTray necesita Homebrew para instalar Java, yt-dlp, gallery-dl y ffmpeg.

Se abrirá la Terminal con el instalador oficial de Homebrew; te pedirá tu contraseña de administrador." \
"InstaTray needs Homebrew to install Java, yt-dlp, gallery-dl and ffmpeg.

Terminal will open with Homebrew's official installer; it will ask for your administrator password.")"
  cancel="$(msg "Cancelar" "Cancel")"
  install="$(msg "Instalar" "Install")"
  answer=$(osascript -e "button returned of (display dialog \"$text\" buttons {\"$cancel\", \"$install\"} default button \"$install\" with title \"InstaTray\" with icon note)" 2>/dev/null)
  [ "$answer" = "$install" ] && open -a Terminal "$RES/setup.command"
  exit 0
fi

# Opening the .app from Finder can run this script under Rosetta; Homebrew refuses installs into /opt/homebrew then.
brew_native() {
  if [ "$BREW" = /opt/homebrew/bin/brew ] && [ "$(uname -m)" = x86_64 ]; then
    arch -arm64 "$BREW" "$@"
  else
    "$BREW" "$@"
  fi
}

PREFIX="$(brew_native --prefix)"
missing=()
[ -x "$PREFIX/opt/openjdk/bin/java" ] || missing+=(openjdk)
for tool in yt-dlp gallery-dl ffmpeg deno; do
  [ -x "$PREFIX/bin/$tool" ] || missing+=("$tool")
done
# test hook: INSTATRAY_FORCE_INSTALL="deno" treats that package as missing
for f in ${INSTATRAY_FORCE_INSTALL:-}; do missing+=("$f"); done

if [ ${#missing[@]} -gt 0 ]; then
  notify "$(msg "Instalando lo que falta: ${missing[*]}. Puede tardar unos minutos." \
    "Installing what is missing: ${missing[*]}. This can take a few minutes.")"
  echo "$(date '+%F %T') brew install ${missing[*]}" >>"$LOG"
  if ! HOMEBREW_NO_AUTO_UPDATE=1 brew_native install "${missing[@]}" >>"$LOG" 2>&1; then
    notify "$(msg "No se pudo instalar: ${missing[*]}. El detalle está en setup.log" \
      "Could not install: ${missing[*]}. Details are in setup.log")"
    open -R "$LOG"
    exit 1
  fi
  echo "$(date '+%F %T') installed ${missing[*]}" >>"$LOG"
fi

# Java must run detached from the bundle process, otherwise macOS 26 never shows its menu-bar item
JAVA="$PREFIX/opt/openjdk/bin/java"
if [ "$PREFIX" = /opt/homebrew ] && [ "$(uname -m)" = x86_64 ]; then
  nohup arch -arm64 "$JAVA" -Dapple.awt.UIElement=true -Xdock:name=InstaTray \
    -jar "$RES/InstaTray.jar" >/dev/null 2>&1 &
else
  nohup "$JAVA" -Dapple.awt.UIElement=true -Xdock:name=InstaTray \
    -jar "$RES/InstaTray.jar" >/dev/null 2>&1 &
fi
