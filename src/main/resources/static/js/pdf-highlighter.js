const byId = id => document.getElementById(id);
const ui = Object.fromEntries([
  'pdf-file', 'previous', 'next', 'page-label', 'zoom', 'undo', 'clear',
  'export', 'status', 'coordinates', 'page-stage', 'pdf-canvas', 'overlay', 'selections'
].map(id => [id, byId(id)]));
const context = ui.overlay.getContext('2d');
let pdfjs, documentPdf, currentPage, viewport, documentInfo;
let pageNumber = 1, busy = false, drag = null, highlights = [];
let pixelRatio = 1;
let pageText = null, textError = false;
const pageCounters = new Map();

function nextHighlightId(page) {
  const next = (pageCounters.get(page) || 0) + 1;
  pageCounters.set(page, next);
  return `${page}-${next}`;
}

// Native PDF-space bounds for one PDF.js text run. Use all four corners
// so rotated text is handled, as well as ordinary horizontal text.
function textRunBounds(item, styles) {
  const [a, b, c, d, x, y] = item.transform;
  const style = styles[item.fontName] || {};
  const baselineLength = Math.hypot(a, b) || 1;
  const fontHeight = Math.hypot(c, d) || item.height || 1;
  let ux = a / baselineLength, uy = b / baselineLength;
  const vx = c / fontHeight, vy = d / fontHeight;
  let length = Math.abs(item.width);
  if (style.vertical) {
    ux = -vx; uy = -vy;
    length = Math.abs(item.height);
  }
  const ascent = (Number.isFinite(style.ascent) ? style.ascent : 0.8) * fontHeight;
  const descent = (Number.isFinite(style.descent) ? style.descent : -0.2) * fontHeight;
  const corners = [
    [x + vx * descent, y + vy * descent],
    [x + vx * ascent, y + vy * ascent],
    [x + ux * length + vx * descent, y + uy * length + vy * descent],
    [x + ux * length + vx * ascent, y + uy * length + vy * ascent]
  ];
  return [Math.min(...corners.map(p => p[0])), Math.min(...corners.map(p => p[1])),
    Math.max(...corners.map(p => p[0])), Math.max(...corners.map(p => p[1]))];
}

function selectedText(rect) {
  if (textError) return { content: '', contentStatus: 'text-extraction-failed' };
  const items = pageText?.items || [];
  const hasText = items.some(item => typeof item.str === 'string' && item.str.trim());
  if (!hasText) return { content: '', contentStatus: 'page-has-no-text' };
  let result = '', previous = null, pendingLineBreak = false;
  for (const item of items) {
    if (typeof item.str !== 'string' || !item.str.trim()) {
      if (item.hasEOL) pendingLineBreak = true;
      continue;
    }
    const bounds = textRunBounds(item, pageText.styles || {});
    const overlaps = bounds[0] < rect[2] && bounds[2] > rect[0]
      && bounds[1] < rect[3] && bounds[3] > rect[1];
    if (overlaps) {
      if (result) {
        const sameLine = previous && Math.abs(previous.y - item.transform[5])
          < Math.max(previous.height, Math.hypot(item.transform[2], item.transform[3]), 1) * 0.5;
        result += pendingLineBreak || !sameLine ? '\n' : ' ';
      }
      result += item.str;
      previous = { y: item.transform[5], height: Math.hypot(item.transform[2], item.transform[3]) };
      pendingLineBreak = false;
    }
    if (item.hasEOL) pendingLineBreak = true;
  }
  const content = result.trim();
  return { content, contentStatus: content ? 'text-found' : 'no-text-in-region' };
}

function status(message, error = false) {
  ui.status.textContent = message;
  ui.status.classList.toggle('error', error);
}

function updateControls() {
  const ready = !!documentPdf && !!viewport && !busy;
  ui['pdf-file'].disabled = busy || !pdfjs;
  ui.previous.disabled = !ready || pageNumber === 1;
  ui.next.disabled = !ready || pageNumber === documentPdf?.numPages;
  ui.zoom.disabled = !ready;
  ui.undo.disabled = !ready || highlights.length === 0;
  ui.clear.disabled = !ready || highlights.length === 0;
  ui.export.disabled = !ready || highlights.length === 0;
  ui.overlay.style.pointerEvents = ready ? 'auto' : 'none';
}

function redraw() {
  context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
  context.clearRect(0, 0, ui.overlay.width / pixelRatio, ui.overlay.height / pixelRatio);
  if (!viewport) return;
  for (const item of highlights.filter(h => h.page === pageNumber)) {
    const r = viewport.convertToViewportRectangle(item.rect);
    paintRectangle(Math.min(r[0], r[2]), Math.min(r[1], r[3]),
      Math.abs(r[2] - r[0]), Math.abs(r[3] - r[1]));
  }
  if (drag) {
    paintRectangle(Math.min(drag.start.x, drag.end.x), Math.min(drag.start.y, drag.end.y),
      Math.abs(drag.end.x - drag.start.x), Math.abs(drag.end.y - drag.start.y));
  }
}

function paintRectangle(x, y, width, height) {
  context.fillStyle = 'rgba(255, 221, 0, 0.32)';
  context.strokeStyle = '#b88b00';
  context.lineWidth = 1.5;
  context.fillRect(x, y, width, height);
  context.strokeRect(x, y, width, height);
}

function updateList() {
  ui.selections.replaceChildren();
  for (const item of highlights) {
    const li = document.createElement('li');
    li.textContent = `ID ${item.id} — Page ${item.page}: [${item.rect.map(n => n.toFixed(2)).join(', ')}]`;
    const content = document.createElement('pre');
    content.style.whiteSpace = 'pre-wrap';
    content.textContent = item.content || ({
      'page-has-no-text': 'No embedded text on this page. Scanned pages need OCR.',
      'text-extraction-failed': 'Text extraction failed; coordinates were still saved.',
      'no-text-in-region': 'No embedded text found in this rectangle.'
    }[item.contentStatus] || '');
    li.append(content);
    ui.selections.append(li);
  }
  updateControls();
}

async function renderPage() {
  drag = null;
  viewport = null;
  ui['page-stage'].hidden = true;
  currentPage = await documentPdf.getPage(pageNumber);
  pageText = null;
  textError = false;
  try { pageText = await currentPage.getTextContent(); }
  catch (error) { console.warn('Could not extract page text', error); textError = true; }
  viewport = currentPage.getViewport({ scale: Number(ui.zoom.value) });
  // Cap the backing canvas size on large pages to reduce memory pressure.
  pixelRatio = Math.min(window.devicePixelRatio || 1, 2,
    Math.sqrt(16000000 / (viewport.width * viewport.height)));
  for (const canvas of [ui['pdf-canvas'], ui.overlay]) {
    canvas.width = Math.max(1, Math.floor(viewport.width * pixelRatio));
    canvas.height = Math.max(1, Math.floor(viewport.height * pixelRatio));
    canvas.style.width = `${viewport.width}px`;
    canvas.style.height = `${viewport.height}px`;
  }
  await currentPage.render({
    canvasContext: ui['pdf-canvas'].getContext('2d'), viewport,
    transform: [pixelRatio, 0, 0, pixelRatio, 0, 0]
  }).promise;
  ui['page-stage'].hidden = false;
  ui['page-label'].textContent = `Page ${pageNumber} of ${documentPdf.numPages}`;
  ui.coordinates.textContent = 'Cursor: —';
  redraw();
}

async function runBusy(operation) {
  if (busy) return;
  busy = true;
  updateControls();
  try { await operation(); }
  catch (error) {
    console.error(error);
    status(`Could not display this PDF: ${error.message || 'unknown error'}`, true);
  } finally {
    busy = false;
    updateControls();
  }
}

ui['pdf-file'].addEventListener('change', () => runBusy(async () => {
  const file = ui['pdf-file'].files[0];
  if (!file) return;
  if (!file.name.toLowerCase().endsWith('.pdf') || file.size > 50 * 1024 * 1024) {
    status('Choose a PDF file no larger than 50 MB.', true);
    ui['pdf-file'].value = '';
    return;
  }
  if (highlights.length && !window.confirm('Opening another PDF clears the current selections. Download JSON first if needed. Continue?')) {
    ui['pdf-file'].value = '';
    return;
  }
  status('Opening PDF…');
  viewport = null;
  ui['page-stage'].hidden = true;
  if (documentPdf) await documentPdf.destroy();
  documentPdf = null;
  highlights = [];
  pageCounters.clear();
  updateList();
  const bytes = new Uint8Array(await file.arrayBuffer());
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  documentInfo = {
    name: file.name, sizeBytes: file.size,
    sha256: Array.from(new Uint8Array(digest), n => n.toString(16).padStart(2, '0')).join('')
  };
  // All rendering assets are served by the local Spring Boot app.
  const loading = pdfjs.getDocument({
    data: bytes,
    cMapUrl: '/vendor/pdfjs/web/cmaps/', cMapPacked: true,
    standardFontDataUrl: '/vendor/pdfjs/web/standard_fonts/',
    wasmUrl: '/vendor/pdfjs/web/wasm/',
    isEvalSupported: false
  });
  loading.onPassword = (setPassword, reason) => {
    const password = window.prompt(reason === 2 ? 'Incorrect password. Try again:' : 'Enter the PDF password:');
    if (password === null) { void loading.destroy(); }
    else setPassword(password);
  };
  documentPdf = await loading.promise;
  pageNumber = 1;
  await renderPage();
  status('PDF ready. Click and drag to create a yellow rectangle.');
}));

function pointerPosition(event) {
  const bounds = ui.overlay.getBoundingClientRect();
  return {
    x: Math.max(0, Math.min(viewport.width, (event.clientX - bounds.left) * viewport.width / bounds.width)),
    y: Math.max(0, Math.min(viewport.height, (event.clientY - bounds.top) * viewport.height / bounds.height))
  };
}

ui.overlay.addEventListener('pointerdown', event => {
  if (!viewport || busy || drag || event.button !== 0) return;
  event.preventDefault();
  const start = pointerPosition(event);
  drag = { start, end: start, pointerId: event.pointerId };
  ui.overlay.setPointerCapture(event.pointerId);
});

ui.overlay.addEventListener('pointermove', event => {
  if (!viewport || busy) return;
  const point = pointerPosition(event);
  const pdfPoint = viewport.convertToPdfPoint(point.x, point.y);
  ui.coordinates.textContent = `Cursor in PDF units: (${pdfPoint[0].toFixed(2)}, ${pdfPoint[1].toFixed(2)})`;
  if (drag && drag.pointerId === event.pointerId) {
    drag.end = point;
    redraw();
  }
});

ui.overlay.addEventListener('pointerup', event => {
  if (!drag || drag.pointerId !== event.pointerId) return;
  const start = drag.start;
  const end = pointerPosition(event);
  drag = null;
  ui.overlay.releasePointerCapture(event.pointerId);
  if (Math.abs(end.x - start.x) >= 3 && Math.abs(end.y - start.y) >= 3) {
    const a = viewport.convertToPdfPoint(start.x, start.y);
    const b = viewport.convertToPdfPoint(end.x, end.y);
    const rect = [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1])];
    highlights.push({
      id: nextHighlightId(pageNumber), page: pageNumber,
      rect,
      ...selectedText(rect),
      contentExtraction: 'intersecting-text-runs',
      pageViewBox: Array.from(currentPage.view),
      pageRotation: currentPage.rotate,
      userUnit: currentPage.userUnit,
      color: '#FFFF00'
    });
    updateList();
    status(`${highlights.length} highlight(s). Download JSON to save them.`);
  }
  redraw();
});

for (const name of ['pointercancel', 'lostpointercapture']) {
  ui.overlay.addEventListener(name, () => { drag = null; redraw(); });
}

ui.previous.addEventListener('click', () => runBusy(async () => {
  pageNumber--;
  await renderPage();
}));
ui.next.addEventListener('click', () => runBusy(async () => {
  pageNumber++;
  await renderPage();
}));
ui.zoom.addEventListener('change', () => runBusy(renderPage));
ui.undo.addEventListener('click', () => {
  highlights.pop(); updateList(); redraw();
  status(`${highlights.length} highlight(s) remaining.`);
});
ui.clear.addEventListener('click', () => {
  if (!window.confirm('Clear all highlights from this PDF?')) return;
  highlights = []; updateList(); redraw(); status('All highlights cleared.');
});
ui.export.addEventListener('click', () => {
  const payload = {
    schemaVersion: 2,
    coordinateSystem: 'pdf-user-space',
    rectangleFormat: '[xMin, yMin, xMax, yMax]',
    pageNumbering: 'one-based',
    createdAt: new Date().toISOString(),
    document: { ...documentInfo, pageCount: documentPdf.numPages },
    highlights
  };
  const url = URL.createObjectURL(new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = documentInfo.name.replace(/\.pdf$/i, '') + '.highlights.json';
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
  status('JSON download started. Keep this file with the original PDF.');
});

try {
  pdfjs = await import('/vendor/pdfjs/build/pdf.mjs');
  pdfjs.GlobalWorkerOptions.workerSrc = '/vendor/pdfjs/build/pdf.worker.mjs';
  status('Choose a PDF to begin.');
} catch (error) {
  console.error(error);
  status('PDF.js could not load. Install its files using INSTALL.md, then restart the app and refresh.', true);
}
updateControls();
