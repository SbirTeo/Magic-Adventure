/* Skin del profilo in 3D, girabile col mouse (o col dito).
   Se la libreria non parte — niente WebGL, script bloccato, navigazione senza JS —
   resta l'immagine ferma che c'era prima: la pagina non perde niente. */
(function () {
  var box = document.getElementById('avatar3d');
  if (!box || typeof skinview3d === 'undefined') return;

  var tela = box.querySelector('canvas');
  var ripiego = box.querySelector('.profilo-skin');
  var skin = box.dataset.skin;
  if (!tela || !skin) return;

  try {
    var vista = new skinview3d.SkinViewer({
      canvas: tela,
      width: box.clientWidth || 170,
      height: 260,
      skin: skin
    });

    // Si gira e basta: niente zoom con la rotella (rubava lo scorrimento della pagina)
    // e niente spostamento, che farebbe uscire il personaggio dal riquadro.
    vista.controls.enableRotate = true;
    vista.controls.enableZoom = false;
    vista.controls.enablePan = false;
    vista.zoom = 0.9;
    vista.animation = new skinview3d.IdleAnimation();
    vista.animation.speed = 0.6;

    // Sfondo trasparente: il riquadro del pannello si vede dietro
    vista.renderer.setClearColor(0x000000, 0);

    tela.hidden = false;
    if (ripiego) ripiego.hidden = true;
    box.classList.add('e-girabile');

    // Il riquadro cambia larghezza col telefono ruotato o la finestra ridimensionata
    window.addEventListener('resize', function () {
      vista.width = box.clientWidth || 170;
    });
  } catch (e) {
    // Qualsiasi intoppo: resta l'immagine ferma
    if (ripiego) ripiego.hidden = false;
    if (tela) tela.hidden = true;
  }
})();
