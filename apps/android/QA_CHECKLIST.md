# QA manual guiada — «Hermes para mamá»

Checklist de 25 escenarios para ejecutar **antes del primer envío a la usuaria**
(ROADMAP, tarea J3). La recorre quien mantiene la app, idealmente acompañado de una
persona no técnica: cada paso está escrito para poder leerse en voz alta y seguirse
sin conocimientos de Android.

## Prerrequisitos

- **Dos móviles físicos**: uno con **Android 10** y otro con **Android 14**. Cada
  escenario se ejecuta y se marca en los dos.
- El **APK release** `app-mama-release.apk` de la GitHub Release que se va a enviar
  (instalación paso a paso en `INSTALACION.md`). No vale un build de desarrollo.
- Un **`hermes serve` de pruebas** encendido y accesible desde los móviles (HTTPS o
  Tailscale), configurado como en ROADMAP §2.7:
  - `dashboard.basic_auth` con un usuario de pruebas (`usuario`);
  - `browser.extension_control.enabled: true`;
  - perfil del servidor respondiendo en español y en frases cortas;
  - una **tienda de pruebas sin datos reales** («Tienda Ejemplo») donde exista una
    factura PDF descargable, y una dirección de correo de pruebas.
- Wi-Fi y datos móviles disponibles en los dos móviles.
- Para volver a la pantalla de conexión sin reinstalar (flavor `mama`): en la lista
  de chats, **mantener pulsado el logo 3 segundos**.

## Cómo marcar

- `☐` pendiente · `☑` superado · `✗` fallo.
- Un escenario sólo se marca `☑` si **todo** lo del bloque «Esperado» ocurre tal cual.
- Si falla en un móvil y no en el otro, es un fallo: anótalo junto al escenario y en
  la tabla del final.
- Anota en la tabla: `versionName` del APK, fecha y modelo de cada móvil.

## Escenarios

### 1. Primer arranque y conexión
**Preparación**: app recién instalada, nunca abierta. A mano: dirección del servidor
de pruebas (`https://hermes.example.invalid` en los ejemplos) y usuario/contraseña.
**Pasos**:
1. Abre la app desde el icono «Hermes».
2. En la pantalla de conexión, escribe dirección, usuario y contraseña.
3. Toca el ojo de la contraseña para comprobar que está bien escrita.
4. Pulsa «Probar» y espera el mensaje de conectado.
5. Pulsa «Guardar».
6. Cierra la app del todo y vuelve a abrirla.

**Esperado**: «Probar» confirma con un mensaje amable («Conectado como usuario») y
«Guardar» lleva a la lista de chats, vacía, con «Aún no hay chats» y el botón «Nuevo
chat». No se ve ningún dato técnico (códigos, rutas, errores raros). Al reabrir, la
app entra directa a la lista: **no vuelve a pedir los datos**.

### 2. No consigo conectar (contraseña mal o servidor apagado)
**Preparación**: app recién instalada o tras el escenario 25, en la pantalla de
conexión.
**Pasos**:
1. Escribe bien la dirección y el usuario, pero una contraseña incorrecta. Pulsa «Probar».
2. Lee el mensaje y corrige la contraseña.
3. Ahora escribe mal la dirección (p. ej. `https://no-existo.example.invalid`) o apaga
   el servidor de pruebas. Pulsa «Probar».
4. Restaura la dirección buena / enciende el servidor, pulsa «Probar» y «Guardar».

**Esperado**: con la contraseña mal, un mensaje tipo «Usuario o contraseña
incorrectos»; con el servidor apagado o la dirección mal, «No encuentro a Hermes.
¿Está encendido el servidor?». **Nunca** aparecen códigos (`401`, `timeout`) ni texto
técnico. Tras corregir, conecta y guarda con normalidad.

### 3. Abrir la app sin red
**Preparación**: app ya conectada, con al menos un chat con conversación.
**Pasos**:
1. Pon el móvil en modo avión.
2. Abre la app, entra en un chat que ya tuviera mensajes y vuelve a la lista.
3. Quita el modo avión y espera unos segundos sin tocar nada.

**Esperado**: la lista y el chat se abren mostrando lo último que había (la caché del
móvil). Arriba se ve una franja discreta «Sin conexión. Reintentando…». Al volver la
red, la franja desaparece sola y la app **reconecta sin cerrarla ni tocar nada**.

### 4. La red se cae a mitad de respuesta
**Pasos**:
1. En un chat, envía «Cuéntame un cuento largo».
2. Cuando Hermes lleve un rato escribiendo, pon el modo avión durante ~20 segundos.
3. Quita el modo avión y espera.

**Esperado**: durante el corte se ve la franja de «Sin conexión». Al volver la red, la
app se recupera sola: la respuesta continúa hasta el final, o aparece un aviso amable
de que no se pudo terminar. El texto ya escrito no se pierde ni se duplica, la app no
se queda «escribiendo…» para siempre y se puede enviar otro mensaje.

### 5. Cambiar de Wi-Fi a datos móviles a mitad de respuesta
**Preparación**: Wi-Fi y datos móviles activos a la vez.
**Pasos**:
1. Envía un mensaje de respuesta larga.
2. Mientras Hermes escribe, apaga el Wi-Fi: el móvil pasa a datos.
3. Deja terminar la respuesta. En el siguiente mensaje, haz el cambio al revés
   (enciende el Wi-Fi y apaga los datos).

**Esperado**: la respuesta sigue llegando hasta el final en ambos sentidos del cambio.
No se repite texto, no hay aviso de error y no hace falta reenviar el mensaje.

### 6. Una tarjeta Sí/No espera a que vuelva la red
**Pasos**:
1. Pide algo que necesite permiso, p. ej. «Envía un correo de prueba a mi dirección
   de pruebas», para que salga la tarjeta con «Sí, adelante» / «No».
2. **Sin responder**, pon el modo avión 30 segundos y quítalo.
3. Espera a que desaparezca la franja de «Sin conexión».
4. Pulsa «No».

**Esperado**: la tarjeta sigue en pantalla tras la reconexión (no desaparece ni se
duplica) y responde con normalidad: se cierra y se ve «Has dicho que no. No pasa
nada.». En el servidor la petición queda respondida una sola vez.

### 7. Girar el móvil o recibir una llamada a mitad de respuesta
**Pasos**:
1. Envía un mensaje de respuesta larga y, mientras escribe, gira el móvil a
   horizontal y vuelve a vertical.
2. En otra respuesta larga, llama a este móvil desde otro teléfono, descuelga, habla
   medio minuto y cuelga.
3. Vuelve a la app.

**Esperado**: tras girar, la conversación y el texto a medias quedan igual — nada se
pierde ni se desordena. Tras la llamada, la app vuelve al mismo chat con la respuesta
completa o llegando. Nunca se vuelve sola a la pantalla de conexión ni se cierra.

### 8. La app pasa horas en segundo plano
**Pasos**:
1. Envía un mensaje y, sin esperar la respuesta, sal a la pantalla de inicio con el
   botón Home (no cierres la app).
2. Usa el móvil con normalidad o déjalo con la pantalla apagada unas **2 horas**.
3. Vuelve a abrir la app desde el icono o desde la notificación «Hermes está
   trabajando…», si sigue ahí.

**Esperado**: al volver, el chat está donde se quedó y la respuesta de Hermes aparece
completa — si ha pasado mucho rato puede tardar unos segundos en reconectar primero.
No hay pantalla en blanco, no pide conectar de nuevo y la app no se ha cerrado.

### 9. Batería baja
**Pasos**:
1. Deja el móvil por debajo del 20 % de batería o activa «Ahorro de batería» en los
   ajustes rápidos.
2. Envía un mensaje y espera la respuesta.
3. Apaga la pantalla unos minutos con otra respuesta en marcha y vuelve a mirar.

**Esperado**: la app funciona igual que con batería normal: la respuesta llega
completa y la app no se cierra. Android no muestra ningún aviso de que la app gaste
demasiada batería.

### 10. Escribir un mensaje y leer la respuesta
**Pasos**:
1. En la lista de chats, pulsa «Nuevo chat».
2. Escribe «Hola, ¿me das una receta sencilla de lentejas?» y pulsa ➤.
3. Observa todo hasta que termine, y vuelve a la lista de chats.

**Esperado**: tu mensaje aparece en su burbuja; se ve «Hermes está escribiendo…» y,
si usa herramientas, un chip amable («🔎 Buscando en internet», «📧 Leyendo el
correo»…). La respuesta llega escribiéndose poco a poco en una burbuja grande y
legible. En la lista, el chat tiene título y última línea; si Hermes le puso un
nombre mejor, se ve actualizado.

### 11. Parar a Hermes
**Pasos**:
1. Pide algo largo, p. ej. «Cuéntame la historia de mi pueblo».
2. Cuando lleve un rato escribiendo, pulsa el botón «Parar».
3. Envía otro mensaje cualquiera.

**Esperado**: Hermes deja de escribir al poco de pulsar «Parar» y el texto ya escrito
se queda en su burbuja. El chat no se bloquea: el siguiente mensaje se envía y Hermes
responde con normalidad.

### 12. La foto de un ticket
**Preparación**: un ticket de compra o cualquier papel con texto impreso.
**Pasos**:
1. En un chat, pulsa 📎 y elige hacer una foto.
2. Fotografía el ticket; comprueba que se ve la miniatura junto al campo de texto.
3. Escribe «¿Qué pone en este ticket?» y envía.
4. (Opcional) repite eligiendo una foto de la galería en vez de la cámara.

**Esperado**: la foto aparece como miniatura antes de enviar (y se puede quitar con
la ✕ si te equivocas). Tras enviar, Hermes describe lo que pone en el ticket con
palabras normales. Si la foto sale ilegible, lo dice con un mensaje amable — nunca un
error técnico.

### 13. Mandar un documento (y uno que pesa demasiado)
**Preparación**: en Descargas del móvil, un PDF pequeño y un archivo de **más de 8
MB** (p. ej. un vídeo de ~1 min o un escaneo grande; el tamaño exacto se ve en
Detalles del archivo).
**Pasos**:
1. Pulsa 📎 → elegir archivo → selecciona el PDF pequeño.
2. Comprueba que aparece con su nombre junto al mensaje, escribe «guárdame esto» y
   envía.
3. Repite con el archivo de más de 8 MB.

**Esperado**: el PDF se envía como burbuja de documento (icono, nombre, tamaño) y
Hermes confirma que lo ha recibido. El archivo grande **no** se envía: sale un aviso
claro tipo «El archivo es demasiado grande. Como máximo puede pesar 8 MB.» y la app
sigue funcionando igual.

### 14. «Busca mi factura de la lavadora» — el navegador compartido
**Preparación**: servidor de pruebas con `browser.extension_control.enabled: true` y
la tienda de pruebas con una factura localizable. Si la tienda pide login, ten a mano
la contraseña de pruebas.
**Pasos**:
1. Envía «Busca la factura de mi compra en Tienda Ejemplo y descárgamela».
2. Observa: la app avisa «Hermes va a usar el navegador» y la pantalla del navegador
   se abre sola.
3. Mira cómo la página se mueve sola. Si la web pide contraseña, escríbela tú misma
   en la página cuando no haya velo «Un momento…».
4. Pulsa «Volver al chat» y, si quieres, vuelve a mirar el navegador.

**Esperado**: la pantalla Navegador se abre sola tras el aviso; arriba se lee qué
está haciendo Hermes y hay un botón «Parar». Durante cada acción de Hermes se ve el
velo «Un momento…»; entre acciones puedes tocar la web (la contraseña que escribas
nunca aparece en el chat). «Volver al chat» funciona sin romper nada y Hermes sigue
trabajando.

### 15. La factura descargada: abrirla y enviársela a Hermes
**Preparación**: continuación del escenario 14, cuando Hermes llegue al enlace del
PDF.
**Pasos**:
1. Cuando el PDF se descargue, mira la parte inferior de la pantalla: debe salir una
   hoja con el nombre del fichero.
2. Pulsa «Abrir» para verlo con el visor del móvil, y vuelve.
3. Pulsa «Compartir» y comprueba que se puede mandar por las apps de siempre.
4. Pulsa «Enviar a Hermes».

**Esperado**: aparece la hoja «📄 <nombre> — Abrir · Compartir · Enviar a Hermes»; el
fichero está de verdad en la carpeta Descargas del móvil (comprobarlo con la app
Archivos). «Enviar a Hermes» lo sube y en el chat se ve el documento; Hermes confirma
que lo tiene (si quiere reenviarlo por correo pedirá permiso con una tarjeta — es el
escenario 16).

### 16. Decir que sí: «Sí, adelante»
**Pasos**:
1. Pide algo que necesite permiso: «Envía un correo de prueba a mi dirección de
   pruebas diciendo que estoy bien».
2. Lee la tarjeta grande que aparece.
3. Pulsa «Sí, adelante».

**Esperado**: la tarjeta explica en palabras normales lo que Hermes quiere hacer
(«Hermes quiere enviar un correo…»), con los botones grandes «Sí, adelante» y «No» —
**no** hay opciones tipo «permitir siempre». Al aceptar, la tarjeta se cierra, se ve
«Has dicho que sí.» y Hermes hace la acción y lo cuenta (llega el correo de prueba).

### 17. Decir que no: «No»
**Pasos**:
1. Repite la petición del escenario 16.
2. Esta vez pulsa «No».

**Esperado**: se ve «Has dicho que no. No pasa nada.», la tarjeta se cierra y Hermes
**no** hace la acción (comprobar: no llega ningún correo). Hermes lo cuenta con
palabras amables y sigue disponible para otra cosa.

### 18. Hermes hace una pregunta con opciones
**Pasos**:
1. Pide algo ambiguo a propósito, p. ej. «Búscame la receta» sin decir cuál.
2. Cuando Hermes pregunte, elige una de las opciones en botones.

**Esperado**: la pregunta sale como una tarjeta «Hermes pregunta» con botones
grandes; al elegir, la tarjeta se cierra («Respuesta enviada.») y Hermes continúa
con la opción elegida. No hace falta escribir nada.

### 19. Varias preguntas seguidas y respuestas largas
**Pasos**:
1. Provoca una tanda de preguntas, p. ej. «Organízame las compras de la semana»
   (en el servidor de pruebas, que pregunte varias cosas seguidas).
2. Respóndelas una a una; donde haya campo de texto, escribe una respuesta **muy
   larga**, de varias líneas.
3. Si alguna admite marcar varias opciones, marca dos y pulsa «Listo».

**Esperado**: se ve el progreso («1 de 3», «2 de 3»…); el texto largo cabe, se puede
escribir con calma y **se envía entero, sin cortarse**; al terminar la tanda Hermes
continúa teniendo en cuenta todas las respuestas.

### 20. Dictar un mensaje (con y sin permiso del micrófono)
**Pasos**:
1. En un chat, mantén pulsado 🎤. La primera vez la app explica para qué lo usa:
   pulsa «Permitir micrófono» y acepta el permiso de Android.
2. Manteniendo pulsado, di en voz normal «Recuérdame comprar pan mañana» y suelta.
3. Revisa el texto entendido y pulsa ➤.
4. Después, en los ajustes de Android (Aplicaciones → Hermes → Permisos), **deniega**
   el micrófono y vuelve a mantener 🎤.

**Esperado**: con permiso, se ve «Escuchando…» y el texto aparece en el campo — no se
envía solo, hay que darle a ➤. Sin permiso, sale una explicación amable y se puede
seguir escribiendo a mano; la app no se cierra ni se queda pillada.

### 21. Dictar con ruido de fondo
**Pasos**:
1. Pon la tele o la radio cerca, a volumen normal de casa.
2. Mantén 🎤 y dicta una frase entre el ruido.
3. Si no te entiende, inténtalo otra vez acercando el móvil, o escribe el mensaje.

**Esperado**: si no entiende, sale un aviso tipo «No te he entendido. Inténtalo de
nuevo.» — la app no se queda «Escuchando…» para siempre ni envía un mensaje vacío o
inventado. Siempre queda la opción de escribir a mano.

### 22. Escuchar las respuestas en voz alta
**Preparación**: activa «Leer las respuestas en voz alta» (vive en la pantalla de
conexión; con la app ya conectada, mantén pulsado el logo unos 3 s para volver a
ella) y sube el volumen del móvil.
**Pasos**:
1. Envía un mensaje y espera la respuesta sin tocar nada.
2. Pulsa el 🔊 de una burbuja anterior de Hermes para oírla otra vez.
3. Pon el móvil en silencio y envía otro mensaje.

**Esperado**: la respuesta nueva se lee sola, en español y entendible (enlaces y
listas se leen como frases, no como símbolos). El 🔊 de cada burbuja la re-lee. Con
el móvil en silencio no suena nada y la app no da error.

### 23. TalkBack y letra muy grande
**Preparación**: (a) Ajustes → Accesibilidad → TalkBack activado; (b) después,
Ajustes → Pantalla → tamaño de fuente al máximo (200 %).
**Pasos**:
1. Con TalkBack y sin mirar la pantalla: recorre la lista de chats, entra en un chat,
   escucha los mensajes, dicta o escribe uno y envíalo.
2. Si aparece una tarjeta Sí/No o una pregunta, respóndela sólo con TalkBack.
3. Desactiva TalkBack, pon la fuente al máximo y repite el recorrido mirando: lista →
   chat → enviar mensaje → responder una tarjeta.

**Esperado**: TalkBack nombra cada cosa con sentido («Nuevo chat», «Dictar un
mensaje. Mantén pulsado y habla.», «Hermes está escribiendo», cada mensaje con su
autor), el foco va en orden lógico y avisa cuando llega la respuesta. Con la fuente al 200 % todo se lee
completo: nada cortado, los botones se pueden pulsar y la tarjeta Sí/No se ve entera
(aunque haya que desplazarse).

### 24. Borrar un chat
**Pasos**:
1. En la lista, desliza hacia la izquierda un chat que no importe.
2. En el aviso «¿Borrar este chat?», pulsa primero «Conservar».
3. Repite el gesto y pulsa «Borrar».
4. Cierra y reabre la app.

**Esperado**: con «Conservar» no pasa nada. Con «Borrar», el chat desaparece de la
lista — también para Hermes en el servidor — y al reabrir sigue sin estar. Ningún
chat se borra sin la confirmación.

### 25. Reinstalar y actualizar
**Pasos**:
1. Con la app instalada y con chats, desinstálala del todo e instala el mismo APK
   otra vez.
2. Ábrela y comprueba lo que pide.
3. Conéctala de nuevo con los mismos datos.
4. Por último, instala **encima** el APK de la versión nueva (sin desinstalar).

**Esperado**: tras reinstalar, la app se abre en la pantalla de conexión — el móvil
no ha guardado ni dirección, ni contraseña, ni mensajes. Al conectar otra vez vuelven
a verse los chats que Hermes guarda en el servidor (eso es lo esperado). Al
actualizar encima, la app conserva la conexión y lo que había, sin pedir nada.

## Tabla resumen

| # | Escenario | Android 10 | Android 14 | Notas |
|---|---|---|---|---|
| 1 | Primer arranque y conexión | ☐ | ☐ | |
| 2 | No consigo conectar | ☐ | ☐ | |
| 3 | Abrir la app sin red | ☐ | ☐ | |
| 4 | La red se cae a mitad de respuesta | ☐ | ☐ | |
| 5 | Cambio Wi-Fi ↔ datos | ☐ | ☐ | |
| 6 | Tarjeta Sí/No espera a la red | ☐ | ☐ | |
| 7 | Giro de pantalla / llamada entrante | ☐ | ☐ | |
| 8 | Horas en segundo plano | ☐ | ☐ | |
| 9 | Batería baja | ☐ | ☐ | |
| 10 | Escribir y leer respuesta | ☐ | ☐ | |
| 11 | Parar a Hermes | ☐ | ☐ | |
| 12 | Foto de un ticket | ☐ | ☐ | |
| 13 | Documento (y demasiado grande) | ☐ | ☐ | |
| 14 | «Busca mi factura» — navegador | ☐ | ☐ | |
| 15 | Descargar PDF y «Enviar a Hermes» | ☐ | ☐ | |
| 16 | «Sí, adelante» | ☐ | ☐ | |
| 17 | «No» | ☐ | ☐ | |
| 18 | Pregunta con opciones | ☐ | ☐ | |
| 19 | Varias preguntas y texto largo | ☐ | ☐ | |
| 20 | Dictar (permiso incluido) | ☐ | ☐ | |
| 21 | Dictar con ruido | ☐ | ☐ | |
| 22 | Leer en voz alta | ☐ | ☐ | |
| 23 | TalkBack y letra grande | ☐ | ☐ | |
| 24 | Borrar un chat | ☐ | ☐ | |
| 25 | Reinstalar y actualizar | ☐ | ☐ | |

Versión probada (`versionName`): ____________  Fecha: ____________
Móvil Android 10: ____________  Móvil Android 14: ____________
