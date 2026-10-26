#!/bin/bash
# Opened in Terminal by InstaTray when Homebrew is missing: installs Homebrew with its official installer
# (it asks for the administrator password), then InstaTray's requirements, then opens InstaTray again.
APP_DIR="$(cd "$(dirname "$0")/../.." && pwd)"

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

echo "== $(msg "InstaTray: instalando Homebrew (te pedirá tu contraseña de administrador)" \
  "InstaTray: installing Homebrew (it will ask for your administrator password)") =="
if ! /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"; then
  msg "No se pudo instalar Homebrew." "Could not install Homebrew."; echo
  read -r -p "$(msg "Presiona Enter para cerrar." "Press Enter to close.")"
  exit 1
fi

BREW=""
for b in /opt/homebrew/bin/brew /usr/local/bin/brew; do
  [ -x "$b" ] && BREW="$b" && break
done

brew_native() {
  if [ "$BREW" = /opt/homebrew/bin/brew ] && [ "$(uname -m)" = x86_64 ]; then
    arch -arm64 "$BREW" "$@"
  else
    "$BREW" "$@"
  fi
}

echo "== $(msg "Instalando Java, yt-dlp, gallery-dl, ffmpeg y deno" "Installing Java, yt-dlp, gallery-dl, ffmpeg and deno") =="
if ! brew_native install openjdk yt-dlp gallery-dl ffmpeg deno; then
  msg "No se pudieron instalar los requisitos." "Could not install the requirements."; echo
  read -r -p "$(msg "Presiona Enter para cerrar." "Press Enter to close.")"
  exit 1
fi

echo "== $(msg "Listo. Abriendo InstaTray" "Done. Opening InstaTray") =="
open "$APP_DIR"
