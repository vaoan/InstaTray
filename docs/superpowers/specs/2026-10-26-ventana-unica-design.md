# InstaTray — una sola ventana de descargas (diseño)

Fecha: 2026-10-26 · Estado: borrador para aprobar

## Intención y por qué

El usuario quiere mejor UX/UI en **todo lo que no es la barra de menú** (el ícono del tray, su badge, su tooltip y su
menú no se tocan visualmente). Cuando abre InstaTray va a hacer tres cosas: **ver cómo va la descarga**, **abrir el
video** y **abrir la URL que falló**. Hoy eso está repartido en dos ventanas:

- La ventana principal es una columna plana donde enlace, estado, cookies, carpeta, opciones y botones (incluido
  «Salir») tienen el mismo peso; no muestra progreso ni título del video y es muy ancha (la frase de bienvenida va en
  una línea).
- «Cola e historial» usa dos pestañas con tablas: filas sin título (sitio truncado, URL), progreso solo como texto,
  7 botones que se parten en dos filas y casi siempre están desactivados, sin doble clic ni menú contextual, tablas
  vacías sin mensaje y un panel «Qué pasó» con la salida cruda de yt-dlp ocupando ~40 % de la ventana.

## Lo que decidió el usuario

Probado con bocetos en el navegador (`.superpowers/brainstorm/…/content/layout-c-v4.html` y `borrar-fallidas.html`).

1. **Una sola ventana** (opción C) que reemplaza a la ventana principal *y* a «Cola e historial».
2. **Arriba**: campo «Pega un enlace…» + botón principal «Descargar». En la barra de título, «⚙︎ Ajustes».
3. **Lista** de descargas, una fila por trabajo: orbe del color del sitio · título en negrita (o la URL si no hay
   título) · línea gris con sitio y estado · barra de progreso fina mientras descarga.
4. **Botones por fila con texto + ícono, todos del mismo ancho, en una columna fija a la derecha** («la 1 con los
   íconos de la 2»). Siempre dos espacios; si una fila usa uno solo, el otro queda vacío para que las columnas no se
   muevan. El principal va en azul:
   - Descargando: «✕ Cancelar»
   - En cola: «⇡ Poner primero» · «✕ Quitar»
   - Listo: «▶ Ver» (azul) · «🔍 Mostrar en Finder»
   - Fallido: «🌐 Abrir página» (azul) · «↻ Reintentar»
5. **Filtros rápidos** con contador encima de la lista: Todas · Descargando · En cola · Listas · Fallidas.
6. **Borrar fallidas una a una**: una 🗑 al final de cada fila fallida (las demás filas reservan ese espacio vacío).
7. **Borrar todas las fallidas**: no como botón rojo suelto (no le gustó), sino en la **línea de abajo como texto
   discreto, según el filtro** (opción C):
   - Fallidas: «↻ Reintentar todas · 🗑 Borrar todas · Detalles ▸»
   - Listas: «🗑 Quitar todas de la lista · Detalles ▸»
   - En cola / Descargando: «⏸ Pausar · ✕ Cancelar todas · Detalles ▸»
   - Todas: «⏸ Pausar · Detalles ▸»
   - A la izquierda, siempre: «● Vigilando portapapeles · Documentos/Videos».
8. **⚙︎ Ajustes** queda solo para opciones (no para acciones de la lista).
9. Cómo se ven Ajustes y Detalles: **lo deja al criterio del builder** (ver «Decisiones mías» y «Lo que queda»).
10. **Sigue el modo del sistema**: claro u oscuro según macOS (su Mac está en oscuro). Todo lo pintado a mano (filas,
    botones, filtros, barras, línea de abajo, Ajustes, Detalles) necesita una paleta clara y una oscura; el cambio de
    modo con la ventana abierta debe aplicarse sin reiniciar (basta con detectarlo al mostrar la ventana y en el poll
    de 500 ms). Detección a criterio del builder (p. ej. `defaults read -g AppleInterfaceStyle` fuera del EDT, o
    `apple.awt.application.appearance=system` + leer colores del sistema). Verificar con renders PNG en ambos modos.

## Objetivos

- Las tres tareas (ver progreso, abrir video, abrir URL fallida) se hacen con **un clic** desde la ventana.
- Botones alineados y consistentes; ninguna fila mueve las columnas.
- El título del video aparece también en descargas de sesiones anteriores.
- Ventana de ancho razonable (~720 pt por defecto, redimensionable, mínimo ~560 pt).

## No-objetivos

- Barra de menú: ícono, badge, tooltip, menú — sin cambios visuales.
- Miniaturas de video, búsqueda de texto, ordenar columnas, arrastrar para reordenar.
- Cambiar la lógica de descarga, la cola, los reintentos o qué sitios se aceptan.

## Anti-objetivos (sería un fracaso aunque funcione)

- La app deja de ser silenciosa: la ventana nunca se abre sola (solo desde el menú o al abrir la app otra vez); el
  éxito nunca notifica.
- Botones que bailan de posición entre filas, o acciones destructivas en rojo llamativo.
- Que «borrar» elimine videos del disco. Borrar/quitar solo saca filas de la lista.
- Volver a mostrar la salida cruda de yt-dlp por defecto.
- Romper lo verificable: el canal de control (`queue`, `get`, `pause`, `top`, `cancel`, línea vacía → muestra la
  ventana) sigue funcionando igual.

## Restricciones

- Java/Swing puro, sin dependencias nuevas (se compila con `javac --release 21` en `build.sh`; nada de FlatLaf ni
  librerías). El aspecto «tipo macOS» se logra con renderizado propio (celdas pintadas, botones planos redondeados).
- No acceder a `~/Downloads` desde el EDT (TCC). El estado de cookies se sigue calculando fuera del EDT.
- Textos de UI y README en español; código, comentarios y logs en inglés. **Actualizar README.md** con la nueva
  ventana.
- Orbes: usar `AeroIcon` con la `Palette` del sitio (`Site.palette()`), no colores inventados.
- Se refresca con el mismo esquema actual (poll cada 500 ms mientras está visible), conservando selección y scroll.
- Verificación sin captura de pantalla: renderizar la ventana fuera de pantalla a PNG (como se hizo para este
  diseño) y el canal de control.

## Decisiones mías (revisar)

- **Doble clic** en una fila = botón azul de esa fila. Enter con fila seleccionada, igual. Suprimir/⌫ = quitar
  (solo en fallidas, listas y en cola; nunca la que está descargando).
- **Menú contextual** (clic derecho) con las mismas acciones de la fila + «Copiar enlace» + «Detalles».
- **«✕ Quitar» en una fila en cola** la saca de la cola *y* de la lista (nuevo camino «remove» en `InstaTray`, no
  `cancel()`): no aparece en Fallidas. «✕ Cancelar todas» (filtro En cola/Descargando) hace lo mismo con las que
  esperan y cancela la actual; solo la que se estaba descargando queda como «Cancelado».
- **«Cancelado»** cuenta en el filtro **Fallidas** y se ve como fallido en gris («Cancelado»), con «↻ Reintentar».
  Un reintento automático en espera aparece en **En cola** con «Reintento en 12 s».
- **Orden de la lista**: descargando primero, luego en cola (en el orden real de la cola), luego terminadas de la más
  nueva a la más vieja.
- **Título persistido**: `Job.title` se guarda como 9.ª columna de `jobs.tsv` (compatible hacia atrás: líneas viejas
  sin título muestran la URL). Para gallery-dl o cuando yt-dlp no dé título, se usa el nombre del archivo.
- **Línea gris por estado**: Descargando «YouTube · 45 % · 2.1 MB/s · quedan 0:12»; En cola «Instagram · en cola
  (2.º)»; Listo «✓ Listo · hace 5 min · 12 MB» (verde); Fallido «✕ motivo» (rojo, `Job.message`).
- **«Salir»** sale de la ventana: queda solo en el menú de la barra.
- **Ajustes**: ventana pequeña (hoja/diálogo modal a la ventana, ⌘,) con secciones agrupadas:
  *Carpeta* (ruta + «Cambiar…», subcarpetas por sitio) · *Descargas* (vigilar portapapeles, buscar video embebido,
  reintentos «N veces, esperando X») · *Avisos* (notificar si falla) · *Cookies* (estado por sitio, solo lectura).
  Cada opción con una línea de ayuda gris corta debajo.
- **Detalles ▸**: panel plegable bajo la lista (oculto por defecto, se recuerda abierto/cerrado), muestra la fila
  seleccionada: URL, estado, archivos y la salida de yt-dlp/gallery-dl en monoespaciada pequeña. Botón «Copiar».
- **Estados vacíos**: lista vacía → «Copia el enlace de un video y aparecerá aquí.» Filtro vacío → «No hay
  descargas fallidas.» (etc.).
- **Confirmaciones**: «Borrar todas» (fallidas), «Quitar todas de la lista» y «Cancelar todas» piden confirmar;
  la 🗑 de una fila no pide (es una sola fila y no borra archivos).
- **Menú de la barra**: sus ítems «Abrir InstaTray» y «Cola e historial…» siguen ahí con el mismo texto; ambos abren
  esta ventana (el segundo con el filtro «Todas»). Sin cambio visual.
- **Clic en «Documentos/Videos»** de la línea de abajo abre la carpeta; clic en «● Vigilando portapapeles» lo
  activa/desactiva (el punto pasa a gris: «○ Portapapeles en pausa»).
- `HistoryWindow.java` se reemplaza por una clase nueva de la ventana (nombre a criterio del builder); el código de la
  ventana vieja en `InstaTray.buildWindow()` se elimina.

## Lo que queda al builder

- Tamaños exactos, espaciados, tipografía (fuente del sistema), colores concretos del azul principal y grises,
  dentro del estilo del boceto.
- Cómo implementar la lista (JList con renderer propio, o panel de filas) mientras se cumpla: botones clicables,
  columnas fijas, selección múltiple con ⌘/⇧ para las acciones de teclado, sin parpadeo al refrescar.
- Glifos: si algún emoji (🔍 🌐 🗑) no se ve bien en Swing, sustituir por un ícono dibujado con el mismo significado.
