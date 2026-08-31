<?php
// Partial: barra di formattazione per un <textarea id="body">.
// Richiede che /assets/js/editor.js sia già stato incluso nella pagina.
?>
<div class="editor-toolbar">
  <button type="button" class="btn btn-ghost btn-small" onclick="magicWrap('body','<b>','</b>')" title="Grassetto"><b>B</b></button>
  <button type="button" class="btn btn-ghost btn-small" onclick="magicWrap('body','<i>','</i>')" title="Corsivo"><i>I</i></button>
  <button type="button" class="btn btn-ghost btn-small" onclick="magicWrap('body','<mark>','</mark>')" title="Evidenziatore">Evidenzia</button>
  <button type="button" class="btn btn-ghost btn-small" onclick="magicWrap('body','<h2>','</h2>')" title="Titolo">Titolo</button>
  <button type="button" class="btn btn-ghost btn-small" onclick="magicInsertImage('body')" title="Inserisci immagine">🖼 Immagine</button>
  <button type="button" class="btn btn-ghost btn-small" onclick="magicInsertVideo('body')" title="Inserisci video">▶ Video</button>
  <button type="button" class="btn btn-ghost btn-small" onclick="magicInsertHtml('body')" title="Inserisci HTML personalizzato">&lt;/&gt; HTML</button>
</div>
