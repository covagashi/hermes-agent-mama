// Pasos previos al snapshot de dialog.html (F1): pulsa los tres botones para que
// la página llame a alert/confirm/prompt — ya sustituidos por hermes_snapshot.js,
// que los auto-resuelve (alert→aceptar, confirm/prompt→cancelar) y los anota.
document.getElementById('aviso').click();
document.getElementById('confirmar').click();
document.getElementById('pregunta').click();
