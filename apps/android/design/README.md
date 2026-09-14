# Diseño — "Hermes para mamá"

`mockups/` contiene las pantallas de referencia como HTML plano (`*.dc.html`, una por pantalla;
`canvas.json` da el orden). Ábrelos en un navegador a 390 px de ancho. Todos los datos que aparecen
son ficticios.

| Archivo | Pantalla | Tareas del roadmap |
|---|---|---|
| `Conexion.dc.html` | 1 · Conexión (primera vez) | C2 |
| `Main.dc.html` | 2 · Chats | C3 |
| `Chat.dc.html` | 3 · Chat con respuesta en vivo, chip de actividad, Parar | C4, C5 |
| `Aprobacion.dc.html` | 4 · Tarjeta Sí / No | C6 |
| `Pregunta.dc.html` | 5 · Hermes pregunta (clarify) | C7 |
| `Navegador.dc.html` | 6 · Navegador visible con overlay | F4 |
| `Documento.dc.html` | 7 · Documento listo (hoja inferior) | G1, G2, G3 |
| `QuePone.dc.html` | 8 · ¿Qué pone aquí? | H2 |

## Tokens (Compose `MamaTheme`)

| Token | Valor | Uso |
|---|---|---|
| `background` | `#FBF8F3` | fondo de pantalla |
| `surfaceVariant` | `#F3EEE5` | chips, botones secundarios de icono |
| `onBackground` | `#1F1B16` | texto principal |
| `onSurfaceVariant` | `#5B554C` | texto secundario, iconos inactivos |
| `outline` | `#E6DED2` | bordes, separadores |
| `primary` | `#1B7A5F` | botón principal, micrófono, enlaces |
| `primaryContainer` | `#DDEFE7` | burbuja de la usuaria, avatares, banda del navegador |
| `onPrimaryContainer` | `#0F4D3B` | texto/iconos sobre `primaryContainer` |
| `tertiary` | `#B5541C` | botón **Parar**, icono de documento |
| `tertiaryContainer` | `#FBE9DE` | fondo de **Parar** |
| `surface` | `#FFFFFF` | burbuja de Hermes, campos, tarjetas |

Tipografía: **Atkinson Hyperlegible** (Google Fonts, licencia OFL; incluir en `res/font/`).
Escala: cuerpo de chat 19 sp, título de pantalla 22 sp, título de tarjeta 24 sp, etiquetas 16–17 sp,
botones 20 sp negrita. Radios: burbujas 20 dp (esquina "propia" 6 dp), botones 32 dp (píldora),
campos y tarjetas 16 dp, hoja inferior 28 dp. Alturas: botones 64 dp, campos 60 dp, filas de chat
92 dp, barra superior 72 dp. Iconos: trazo 2 dp, 24–28 dp (Material Symbols "outlined" en la app).
Modo oscuro: derivar con la misma paleta (fondo `#1A1714`, superficie `#242019`, acento `#6FCBAA`).
