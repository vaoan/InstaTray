#!/bin/bash
# Opened in Terminal by InstaTray when Homebrew is missing: installs Homebrew with its official installer
# (it asks for the administrator password), then InstaTray's requirements, then opens InstaTray again.
APP_DIR="$(cd "$(dirname "$0")/../.." && pwd)"

echo "== InstaTray: instalando Homebrew (te pedirá tu contraseña de administrador) =="
if ! /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"; then
  echo "No se pudo instalar Homebrew."
  read -r -p "Presiona Enter para cerrar."
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

echo "== Instalando Java, yt-dlp, gallery-dl, ffmpeg y deno =="
if ! brew_native install openjdk yt-dlp gallery-dl ffmpeg deno; then
  echo "No se pudieron instalar los requisitos."
  read -r -p "Presiona Enter para cerrar."
  exit 1
fi

echo "== Listo. Abriendo InstaTray =="
open "$APP_DIR"
