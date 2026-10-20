#!/bin/bash
# InstaTray launcher (Contents/MacOS/InstaTray): makes sure every requirement is installed, then starts
# the Java app detached.
APP_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
RES="$APP_DIR/Contents/Resources"
STATE="$HOME/Library/Application Support/InstaTray"
LOG="$STATE/setup.log"
mkdir -p "$STATE"

notify() { osascript -e "display notification \"$1\" with title \"InstaTray\"" >/dev/null 2>&1; }

BREW=""
for b in /opt/homebrew/bin/brew /usr/local/bin/brew; do
  [ -x "$b" ] && BREW="$b" && break
done
# test hook: INSTATRAY_FAKE_NO_BREW=1 behaves as if Homebrew were missing
[ -n "${INSTATRAY_FAKE_NO_BREW:-}" ] && BREW=""

if [ -z "$BREW" ]; then
  # Homebrew's installer asks for the administrator password, so it runs in Terminal where it can be typed
  answer=$(osascript -e 'button returned of (display dialog "InstaTray necesita Homebrew para instalar Java, yt-dlp y ffmpeg.

Se abrirá la Terminal con el instalador oficial de Homebrew; te pedirá tu contraseña de administrador." buttons {"Cancelar", "Instalar"} default button "Instalar" with title "InstaTray" with icon note)' 2>/dev/null)
  [ "$answer" = "Instalar" ] && open -a Terminal "$RES/setup.command"
  exit 0
fi

PREFIX="$("$BREW" --prefix)"
missing=()
[ -x "$PREFIX/opt/openjdk/bin/java" ] || missing+=(openjdk)
for tool in yt-dlp ffmpeg deno; do
  [ -x "$PREFIX/bin/$tool" ] || missing+=("$tool")
done
# test hook: INSTATRAY_FORCE_INSTALL="deno" treats that package as missing
for f in ${INSTATRAY_FORCE_INSTALL:-}; do missing+=("$f"); done

if [ ${#missing[@]} -gt 0 ]; then
  notify "Instalando lo que falta: ${missing[*]}. Puede tardar unos minutos."
  echo "$(date '+%F %T') brew install ${missing[*]}" >>"$LOG"
  if ! HOMEBREW_NO_AUTO_UPDATE=1 "$BREW" install "${missing[@]}" >>"$LOG" 2>&1; then
    notify "No se pudo instalar: ${missing[*]}. El detalle está en setup.log"
    open -R "$LOG"
    exit 1
  fi
  echo "$(date '+%F %T') installed ${missing[*]}" >>"$LOG"
fi

# Java must run detached from the bundle process, otherwise macOS 26 never shows its menu-bar item
nohup "$PREFIX/opt/openjdk/bin/java" -Dapple.awt.UIElement=true -Xdock:name=InstaTray \
  -jar "$RES/InstaTray.jar" >/dev/null 2>&1 &
