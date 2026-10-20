# InstaTray

App de barra de menú para macOS, escrita en Java. Vigila el portapapeles y, cuando copias un enlace
de un video de **20 sitios** (Instagram, YouTube, TikTok, X, Twitch, Reddit, Facebook y más), lo descarga en
segundo plano con `yt-dlp`, usando las cookies de ese sitio que tengas en Descargas. Si falta algo para
funcionar (Homebrew, Java, yt-dlp, ffmpeg, deno), **lo instala solo**. Está hecha para ser **lo más silenciosa posible**:
arranca sin ventana, no avisa cuando algo sale bien y reintenta sola lo que falla.

## Uso

1. Doble clic en **`~/Documents/InstaTray.app`** (acceso directo a `InstaTray/InstaTray.app`).
   Solo aparece la esfera en la barra de menú, **sin ventana**.
2. Copia el enlace de un video y se descarga solo. El ícono muestra el progreso.
3. Los videos quedan en **`Documentos/Videos`**, o en la carpeta que elijas.

- **Ventana principal:** desde el menú de la esfera (*Mostrar ventana*), o con doble clic en la app cuando ya está abierta (no abre otra copia).
- **Cola e historial:** desde el menú de la esfera o con el botón de la ventana principal.
- **Cerrar ventanas:** cerrarlas no cierra el programa. Para salir del todo usa *Salir*.

## Ícono

Todos los íconos son esferas de vidrio estilo Aero (Windows Vista), dibujadas por código en
`src/AeroIcon.java`, sin imágenes externas. Vista previa de los estados: `icon-states.png`.

| Estado | Esfera |
|---|---|
| Sin descargas | **Aqua** con flecha de descarga blanca. Es el ícono del proyecto, y también el de la `.app` y las ventanas |
| Bajando de Instagram | **Multicolor**: amarillo-naranja abajo, morado-azul arriba |
| Bajando de YouTube | **Roja** pareja, de un solo tono, para no confundirla con Instagram |
| Bajando de X | **Grafito** |
| Bajando de Twitch | **Morada** |
| Los otros 16 sitios | Un color propio cada uno. Todos están en `icon-palettes.png`. Algunos se parecen (Facebook, Streamable y LinkedIn son azules); al pasar el mouse se ve el sitio |

- **Relleno:** mientras baja, la esfera está deslavada y se va **llenando de color saturado de abajo hacia arriba** según el porcentaje, con una línea brillante en el nivel.
- **Número del centro:**
  - con **una** descarga, es su **porcentaje**, con un `%` pequeño arriba a la derecha (`45%`);
  - con **varias**, es **cuántas hay pendientes**, sin `%` (`3`, o `9+` si son más de 9).
- **Al pasar el mouse:** el detalle ("bajando de YouTube 45%, 3 pendientes", o "cola en pausa").

## Sitios soportados

Solo se reconocen enlaces de **un video o post**. Los perfiles, canales, listas y páginas en vivo se
ignoran a propósito, para no bajar cientos de videos ni quedarse grabando sin fin. Las cookies se buscan en
Descargas con un nombre que mencione el sitio (como las exporta la extensión: `www.tiktok.com_cookies.txt`).

| Sitio | Enlaces que reconoce | ¿Necesita cookies? |
|---|---|---|
| Instagram | `/reel/…`, `/reels/…`, `/p/…`, `/tv/…`, con usuario delante; mirrors `kkinstagram` / `ddinstagram` / `iginstagram` | Sí, casi siempre |
| YouTube | `watch?v=`, `/shorts/`, `/live/` (ya terminado), `youtu.be/` | Sí: sin cookies YouTube responde "confirma que no eres un bot" |
| TikTok | `tiktok.com/@usuario/video/…`, `vm.tiktok.com/…`, `vt.tiktok.com/…`, `tiktok.com/t/…` | No, para videos públicos |
| X / Twitter | `x.com/…/status/…`, `twitter.com/…`; mirrors `fxtwitter` / `vxtwitter` / `fixupx` | Recomendado |
| Twitch | clips (`clips.twitch.tv/…`, `/canal/clip/…`) y transmisiones pasadas (`/videos/…`) | No |
| Reddit | `reddit.com/r/…/comments/…`, enlaces para compartir `/r/…/s/…`, `redd.it/…`, `v.redd.it/…` | **Sí**: Reddit ahora pide sesión |
| Facebook | `watch/?v=`, `/reel/…`, `/página/videos/…`, `/share/v/…`, `/share/r/…`, `fb.watch/…` | Sí, normalmente |
| Pinterest | `pinterest.*/pin/…` (cualquier país), `pin.it/…` | No |
| Bluesky | `bsky.app/profile/…/post/…` | No |
| Kick | clips (`/canal/clips/clip_…` o `?clip=clip_…`) y videos pasados | No |
| Vimeo | `vimeo.com/123…`, con hash privado, `player.vimeo.com/video/…` | Solo los privados |
| Dailymotion | `dailymotion.com/video/…`, `dai.ly/…` | No |
| Rumble | `rumble.com/v…-titulo.html`, `/embed/v…` | No |
| Streamable | `streamable.com/abc12` | No |
| Imgur | posts, `/gallery/…`, `/a/…`, `i.imgur.com/….mp4` | No |
| 9GAG | `9gag.com/gag/…` | No |
| Tumblr | `blog.tumblr.com/post/…`, `tumblr.com/blog/…` | No, para posts públicos |
| LinkedIn | `linkedin.com/posts/…-activity-…` | Sí |
| Snapchat | `snapchat.com/spotlight/…` (solo Spotlight públicos) | No |
| Bilibili | `bilibili.com/video/BV…` o `av…`, `b23.tv/…` | No, para calidad normal |

**No soportados:**
- **Threads:** yt-dlp no tiene extractor.
- **Netflix, Crunchyroll, Disney+, Spotify y similares:** tienen protección anticopia (DRM).

**Formato:**
- Siempre **mp4 H.264 + AAC**, que se reproduce en QuickTime.
- Se prefiere H.264. Si un sitio solo ofrece webm (VP8/VP9, como 9GAG), se convierte a mp4 solo.
- Los parámetros de compartir (`?igsh=`, `?si=`, `?s=46`, `&t=…`) se quitan, y cada enlace pasa a su forma original.

## Opciones (ventana principal)

Todas se guardan en `~/Library/Application Support/InstaTray/settings.properties`.

| Opción | Por defecto | Qué hace |
|---|---|---|
| Vigilar portapapeles | sí | Pausa o reanuda la detección automática de enlaces |
| Avisar si una descarga falla | sí | Notificación **solo** cuando algo falla definitivamente. Lo que sale bien nunca notifica |
| Carpeta de videos (*Cambiar…*) | `Documentos/Videos` | Selector de carpetas nativo de macOS |
| Una subcarpeta por red social | no | `Videos/Instagram`, `Videos/YouTube`, `Videos/X`, `Videos/Twitch` |
| Reintentos automáticos | 2 (0–5) | Si falla por algo temporal, reintenta en silencio |
| Espera entre reintentos | 30 s (10 s, 30 s, 1 min, 5 min) | Crece en cada intento: 1×, 2×, 3×… |

**Qué se reintenta y qué no:**
- **Sí se reintenta:** errores de red, límite de peticiones y errores del servidor.
- **No se reintenta**, porque no se arreglaría: transmisión en vivo, post sin video, falta de sesión (cookies) y yt-dlp no instalado.

## Cola e historial

Ventana con dos pestañas. Abajo, un panel compartido **"Qué pasó"** muestra la salida de yt-dlp de la
descarga seleccionada, en vivo mientras baja.

**Cola**: lo que se está bajando, con su porcentaje, y lo que espera, en orden.
- *Poner primero*, *Subir* y *Bajar*: cambian el orden.
- *Cancelar* y *Cancelar todo* (con confirmación): al cancelar una descarga en curso, se borran sus archivos a medio bajar (`.part`).
- *Pausar cola* / *Reanudar cola*: lo que se está bajando termina, pero no empieza nada nuevo. También está en el menú de la esfera.
- Los reintentos automáticos aparecen en la cola con su cuenta regresiva ("Reintento en 20 s").
- Si cierras el programa con descargas pendientes, **las retoma al volver a abrirlo**.

**Historial**: descargas terminadas (Listo, Falló, Cancelado), con hora y detalle.
- *Reintentar* y *Reintentar fallidos*: también sirven para volver a bajar algo ya descargado.
- *Mostrar video*: lo muestra en el Finder.
- *Copiar enlace*: no dispara una nueva descarga.
- *Abrir enlace*, *Limpiar historial* y *Abrir carpeta*.
- Guarda las últimas 200 descargas, aunque cierres el programa. La salida de yt-dlp solo se conserva durante la sesión.

## Archivos del programa

En `~/Library/Application Support/InstaTray/`:

| Archivo | Qué guarda |
|---|---|
| `settings.properties` | Opciones |
| `jobs.tsv` | Cola e historial (últimas 200) |
| `downloaded.txt` | Claves ya descargadas: un enlace repetido no se vuelve a bajar solo |
| `instatray.log` | Registro detallado (menú de la esfera → *Ver registro*) |
| `cookies-session.txt` | Copia temporal con **solo** las cookies del sitio que se está bajando |

**Cookies:**
- Por cada sitio se usa el `*cookie*.txt` más reciente de Descargas cuyo nombre menciona el sitio. Si no hay, se usa un export general que tenga cookies de ese sitio.
- yt-dlp siempre recibe una **copia filtrada** con solo las cookies de ese sitio, y el original no se modifica.
- La ventana principal muestra `✓` / `✗` por sitio.
- Cuando un sitio pida sesión, exporta de nuevo **solo las cookies de ese sitio**. No conviene dejar en Descargas un export de todo el navegador.

## Requisitos (se instalan solos)

InstaTray necesita **Homebrew**, **Java** (`openjdk`), **yt-dlp**, **ffmpeg** y **deno**. No hace falta instalarlos a mano:

- **Al abrir la app**, el lanzador (`Contents/MacOS/InstaTray`) revisa todo **antes** de arrancar Java:
  - Si falta algún paquete, avisa con una notificación ("Instalando lo que falta…"), ejecuta `brew install` y luego abre la app.
  - Si falla, avisa y muestra `setup.log` en el Finder.
- **Si falta Homebrew**, pregunta antes de instalar y abre la **Terminal** con el instalador oficial de Homebrew (`setup.command`), porque Homebrew pide tu contraseña de administrador. Al terminar instala los paquetes y vuelve a abrir InstaTray.
- **Con la app abierta**, si yt-dlp, ffmpeg o deno desaparecen, los reinstala con Homebrew. Mientras tanto la cola espera.
- **Una vez por semana** actualiza yt-dlp en silencio (`brew upgrade yt-dlp`), porque los sitios cambian y las versiones viejas dejan de funcionar. La cola espera a que termine la descarga en curso.
- El registro de instalaciones está en `~/Library/Application Support/InstaTray/setup.log`. Las actualizaciones quedan en `instatray.log`.

Para qué sirve cada uno:
- **`openjdk`:** ejecuta la app. macOS solo trae un stub que no funciona.
- **`yt-dlp`:** descarga.
- **`ffmpeg`:** une el video y el audio, y convierte webm a mp4.
- **`deno`:** yt-dlp lo necesita para YouTube.

## Compilar

```
cd ~/Documents/InstaTray
./build.sh
```

`build.sh` hace todo:

1. Compila `src/*.java` para Java 21 o superior (`--release 21`) y genera `build/InstaTray.jar`.
2. Dibuja el ícono con `AeroIcon`, lo empaqueta con `iconutil` en `.icns` y deja `icon.png`.
3. Arma `InstaTray.app`: lanzador `src/launcher.sh` (revisa e instala requisitos), `setup.command` (instalador de Homebrew), `Info.plist` con `LSUIElement` (sin ícono en el Dock) e id `local.instatray`.
4. Firma la app de forma local (ad-hoc, `codesign -s -`).
5. Crea o actualiza el enlace `~/Documents/InstaTray.app`.

Si el programa está abierto, ciérralo antes de recompilar (*Salir*, o `pkill -f InstaTray.jar`).

## Estructura

```
InstaTray/
├── build.sh               # compila y arma la .app + acceso directo
├── src/
│   ├── InstaTray.java     # bandeja, ventana principal, portapapeles, sitios, cola, descargas
│   ├── Job.java           # una descarga: estado, progreso, salida, reintentos
│   ├── HistoryWindow.java # ventana Cola / Historial
│   ├── AeroIcon.java      # dibujo de los íconos por estado (y el .iconset)
│   ├── launcher.sh        # lanzador del .app: instala lo que falte y arranca Java
│   └── setup.command      # instalador de Homebrew + requisitos (se abre en Terminal)
├── icon.png               # ícono de la app 1024 px (generado)
├── icon-states.png        # vista previa de estados (java AeroIcon --preview <png>)
├── icon-palettes.png      # color de cada sitio (java AeroIcon --palettes <png>)
├── build/                 # generado
└── InstaTray.app          # generado
```

## Problemas conocidos y soluciones

| Síntoma | Causa | Solución aplicada |
|---|---|---|
| Doble clic y no pasa nada | La app ya estaba abierta | Instancia única (puerto local `48623`): un segundo doble clic muestra la ventana |
| La ventana abre pero la esfera no sale en la barra | En macOS 26, si Java corre como proceso del `.app` (`exec`), el sistema no crea su ícono de barra | El lanzador arranca Java **desacoplado** (`nohup … &`) y termina |
| El ícono no aparece al primer arranque | macOS pregunta si Java puede leer **Descargas**, y el programa esperaba esa respuesta en el hilo de la interfaz | Las cookies se buscan fuera del hilo de la interfaz. Si aparece el aviso, pulsa **Permitir** |
| El ícono no avanzaba del 0 % | `--print` pone a yt-dlp en modo silencioso y oculta el progreso | Se pasa `--no-quiet --progress` con una plantilla de progreso propia |
| Quedaba un `.part` al cancelar | macOS no deja que el programa **liste** `Documentos`, y además yt-dlp recreaba el archivo si se borraba antes de que muriera | Se espera a que yt-dlp termine del todo y se borran por **ruta exacta** los archivos que anunció |
| "<Sitio> pide sesión" | Cookies vencidas o sin sesión iniciada en ese sitio | Exportar de nuevo las cookies de ese sitio a Descargas |
| "Ese post no tiene video" | El post solo tiene fotos | Nada que hacer: solo se descargan videos |
| "Es una transmisión en vivo: no se graba" | El enlace es un directo en curso | Esperar a que termine y copiar el enlace del video guardado |

**¿Qué es un `.part`?** Es un archivo a medio bajar. yt-dlp escribe ahí mientras descarga y, al terminar,
lo renombra o lo une en el `.mp4` final. Si la descarga se corta, queda ese pedazo incompleto, que no se
puede ver. Al cancelar desde la cola, InstaTray lo borra.

## Pruebas hechas

- **Enlaces:** 39 formas de enlace de los 20 sitios se reconocen con su URL limpia. 12 perfiles, canales y páginas del sitio se ignoran (perfil de TikTok, subreddit, página de Facebook, canal de Kick/Twitch, `streamable.com/login`, `imgur.com/upload`, canal de Vimeo, perfil de Bluesky y de Pinterest, etc.).
- **Sitios nuevos con yt-dlp** (consulta sin descargar):
  - Funcionan: Dailymotion, Streamable, Bilibili, TikTok, 9GAG y Bluesky.
  - Reddit pidió sesión (hacen falta sus cookies).
  - El video de prueba de Vimeo ya no existe.
- **Descargas reales de punta a punta** (copiando el enlace al portapapeles), todas en mp4 H.264:
  - TikTok, 9GAG (convertido desde webm), Facebook, Pinterest, Kick, Imgur (`.gifv`, sin audio por ser un GIF), LinkedIn, Snapchat Spotlight y Rumble.
  - Los enlaces vivos se sacaron de los casos de prueba del propio yt-dlp. Los archivos de prueba se borraron después.
- **Directo de Rumble rechazado bien:** un canal 24/7 (`is_live: True`) falló con "Es una transmisión en vivo: no se graba", como se esperaba.
- **No funcionan hoy sin más:**
  - **Vimeo y Reddit** exigen sesión incluso para videos públicos: hay que exportar sus cookies (`vimeo.com_cookies.txt`, `www.reddit.com_cookies.txt`).
  - **Tumblr** respondió 403 a los 18 enlaces de prueba de yt-dlp: hoy bloquea a yt-dlp.
- **Requisitos:**
  - El lanzador instaló un paquete "faltante" (`deno`, simulado) y arrancó la app.
  - La actualización semanal de yt-dlp corrió sola al abrir.
  - **Sin probar:** el caso sin Homebrew (mostraría un diálogo y abriría la Terminal).
- **Descargas reales:**
  - YouTube, con las cookies correctas elegidas solas, en H.264 + AAC verificado con `ffprobe`.
  - Instagram, X y Twitch: los descargó el usuario.
- **Cookies:** del export general solo pasan las de cada sitio: `.instagram.com` (20), `.youtube.com` (79), `x.com` + `twitter.com` (35) y `.twitch.tv` (26).
- **Ícono:**
  - Una descarga: el número es el porcentaje con `%` en superíndice (`36% → 37% → 38%`).
  - Dos en cola: muestra `2`, y vuelve al porcentaje al quedar una.
  - El relleno sube `5 → 15 → 35 → 70 → 90 → 95 %`.
- **Cola:**
  - Pausar con 3 en cola: ninguna empieza.
  - *Poner primero* reordena, *Cancelar* quita una en espera.
  - Reanudar baja en el nuevo orden.
  - Cancelar a mitad de la descarga borra el `.part` (verificado: 0 restos).
- **Reintentos:** un enlace falso se reintentó 3 veces en silencio (10 s, 20 s y 30 s) y solo notificó al final.
- **Silencio:** al abrir no aparece ninguna ventana (verificado con la lista de ventanas). Un segundo doble clic sí la muestra.

## Arrancar al iniciar sesión

Ajustes del Sistema → General → Ítems de inicio → `+` → `InstaTray.app`.
