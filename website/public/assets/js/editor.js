function magicWrap(textareaId, before, after) {
  const ta = document.getElementById(textareaId);
  const start = ta.selectionStart, end = ta.selectionEnd;
  const selected = ta.value.substring(start, end);
  ta.setRangeText(before + selected + after, start, end, 'end');
  ta.focus();
}

function magicInsert(textareaId, text) {
  const ta = document.getElementById(textareaId);
  const start = ta.selectionStart, end = ta.selectionEnd;
  ta.setRangeText(text, start, end, 'end');
  ta.focus();
}

function magicInsertImage(textareaId) {
  const url = prompt('URL immagine:');
  if (!url) return;
  magicInsert(textareaId, '<img src="' + url + '" alt="" style="max-width:100%;">');
}

function magicInsertVideo(textareaId) {
  const url = prompt('URL video (link YouTube, oppure link diretto a un file video):');
  if (!url) return;
  const yt = url.match(/(?:youtube\.com\/watch\?v=|youtu\.be\/)([\w-]+)/);
  let embed;
  if (yt) {
    embed = '<iframe width="100%" height="400" src="https://www.youtube.com/embed/' + yt[1] + '" frameborder="0" allowfullscreen></iframe>';
  } else {
    embed = '<video src="' + url + '" controls style="max-width:100%;"></video>';
  }
  magicInsert(textareaId, embed);
}

function magicInsertHtml(textareaId) {
  const html = prompt('Inserisci codice HTML da aggiungere nel testo:');
  if (!html) return;
  magicInsert(textareaId, html);
}
