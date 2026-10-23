/* Native model catalog and real-transfer UI. Loaded after the existing Xena screen. */
(function () {
  const $ = (selector) => document.querySelector(selector);
  let transferStatus = { state: 'idle', modelId: '', fileName: '', bytes: 0, totalBytes: 0, bytesPerSecond: 0 };
  let lastTransferLog = '';

  function fmtBytes(value) {
    const n = Number(value) || 0;
    if (n < 1) return '—';
    const gib = n / 1073741824;
    return gib >= 1 ? `${gib.toFixed(2)} GB` : `${(n / 1048576).toFixed(0)} MB`;
  }

  function fmtRate(value) {
    const n = Number(value) || 0;
    return n >= 1073741824 ? `${(n / 1073741824).toFixed(2)} GB/s` : `${(n / 1048576).toFixed(1)} MB/s`;
  }

  function esc(value) {
    return String(value || '').replace(/[&<>"']/g, (ch) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[ch]);
  }

  function storage() {
    const usedLabel = $('#stUsed');
    const freeLabel = $('#stFree');
    const bar = $('#stBar');
    if (!usedLabel || !freeLabel || !bar) return;
    if (!window.Android || !Android.storageInfo) {
      usedLabel.textContent = 'Penyimpanan tersedia di APK';
      freeLabel.textContent = '—';
      bar.style.width = '0';
      return;
    }
    try {
      const info = JSON.parse(Android.storageInfo() || '{}');
      const modelBytes = Number(info.modelUsedBytes) || 0;
      const freeBytes = Number(info.freeBytes) || 0;
      usedLabel.textContent = `${fmtBytes(modelBytes)} model di folder aplikasi`;
      freeLabel.textContent = `${fmtBytes(freeBytes)} tersedia · ${freeBytes > 6 * 1073741824 ? 'Cukup' : freeBytes > 2 * 1073741824 ? 'Terbatas' : 'Hampir penuh'}`;
      freeLabel.style.color = freeBytes > 6 * 1073741824 ? 'var(--ok)' : freeBytes > 2 * 1073741824 ? 'var(--warn)' : 'var(--err)';
      const ratio = modelBytes + freeBytes > 0 ? modelBytes / (modelBytes + freeBytes) * 100 : 0;
      bar.style.width = `${Math.min(100, ratio)}%`;
      bar.style.background = freeBytes < 2 * 1073741824 ? 'var(--err)' : '';
    } catch (_) {
      usedLabel.textContent = 'Tidak dapat membaca penyimpanan';
      freeLabel.textContent = '';
    }
  }

  let _catalogBusy = false;
  function renderCatalog() {
    const host = $('#modelCatalog');
    if (!host) return;
    if (_catalogBusy) return;
    if (!window.Android || !Android.getModelCatalog || !Android.getInstalledModels) {
      host.innerHTML = '<div class="muted">Katalog unduhan tersedia di aplikasi Android.</div>';
      return;
    }
    _catalogBusy = true;
    try {
      const catalog = JSON.parse(Android.getModelCatalog() || '[]');
      const installed = JSON.parse(Android.getInstalledModels() || '[]');
      const installedById = new Map(installed.map((model) => [model.id, model]));
      const items = catalog.map((model) => ({ ...model, ...(installedById.get(model.id) || {}), catalog: true }));
      installed.filter((model) => !catalog.some((entry) => entry.id === model.id))
        .forEach((model) => items.push({ ...model, catalog: false }));

      const active = installed.find((model) => model.active);
      const info = JSON.parse(Android.getModelInfo ? Android.getModelInfo() : '{}');
      if ($('#activeModelName')) $('#activeModelName').textContent = active?.fileName || info.name || 'Belum ada model terpasang';
      if ($('#activeModelSize')) $('#activeModelSize').textContent = active ? fmtBytes(active.sizeBytes) : (info.size || '—');
      if ($('#activeModelQuant')) $('#activeModelQuant').textContent = active?.quantization || '—';
      if ($('#activeModelPill')) $('#activeModelPill').textContent = active?.quantization || '—';
      try { if (typeof updateFabModelLabel === 'function') updateFabModelLabel(!!(active || info.name)); } catch (_) {}

      host.innerHTML = items.map((model) => {
        const isInstalled = Boolean(model.installed);
        const isBusy = transferStatus.modelId === model.id && ['downloading', 'paused', 'failed', 'moving', 'importing', 'verifying'].includes(transferStatus.state);
        const canResume = isBusy && Number(transferStatus.bytes) > 0;
        const action = isInstalled
          ? `<button class="btn sm ${model.active ? '' : 'pri'}" onclick="useInstalledModel('${esc(model.id)}')">${model.active ? 'Aktif ✓' : 'Pakai'}</button>`
          : `<button class="btn pri" style="flex:1;border-radius:12px;padding:11px" onclick="startDl('${esc(model.id)}')">${canResume ? 'Lanjut' : 'Download'}</button>`;
        const recommendation = model.recommendation || 'Model GGUF terpasang';
        const ramNote = model.ramNote || '';
        return `<div data-model-id="${esc(model.id)}" style="border:1px solid var(--line);border-radius:14px;padding:14px;margin-top:10px;background:var(--card)">
          <div class="row"><b>${esc(model.title)}</b><span class="sp"></span><span class="pill">${esc(model.quantization)}</span></div>
          <div class="muted" style="font-size:12px;word-break:break-all;margin-top:4px">${esc(model.fileName)}</div>
          <div class="muted" style="font-size:12px;margin-top:5px">${fmtBytes(model.sizeBytes)} · ${esc(recommendation)}</div>
          ${ramNote ? `<div class="muted" style="font-size:12px;margin-top:3px">${esc(ramNote)}</div>` : ''}
          <div class="row" style="margin-top:10px;gap:8px"><span class="muted" style="font-size:12px">${isInstalled ? 'Tersimpan di app' : fmtBytes(model.sizeBytes)}</span><span class="sp"></span>${action}</div>
        </div>`;
      }).join('') || '<div class="muted">Belum ada model di katalog.</div>';
    } catch (error) {
      host.innerHTML = '<div class="muted">Katalog model tidak dapat dibaca di perangkat ini.</div>';
      if (typeof logT === 'function') logT(`Katalog model: ${error.message}`);
    } finally {
      _catalogBusy = false;
    }
  }

  function renderLocalFiles(force) {
    const host = $('#modelFiles');
    if (!host) return;
    if (!window.Android || !Android.getLocalModelFiles) {
      host.innerHTML = '<div class="muted">Pencarian lokasi file tersedia di APK Android.</div>';
      return;
    }
    try {
      if (force && Android.rescanModelFiles) Android.rescanModelFiles();
      const query = ($('#modelFileSearch')?.value || '').trim().toLowerCase();
      const files = JSON.parse(Android.getLocalModelFiles() || '[]');
      const matches = files.filter((file) => [file.fileName, file.title, file.path, file.status, file.quantization, file.location]
        .some((value) => String(value || '').toLowerCase().includes(query)));
      host.innerHTML = matches.map((file) => {
        const partial = file.kind === 'partial';
        const sizeLabel = partial
          ? `${fmtBytes(file.bytes)} / ${fmtBytes(file.expectedBytes)}`
          : fmtBytes(file.bytes);
        const detail = partial
          ? (file.hasResumeValidator ? 'Berkas parsial siap dilanjutkan dengan HTTP Range.' : 'Berkas parsial dipertahankan; server belum memberi validator resume.')
          : (file.headerValid ? 'Header GGUF terdeteksi.' : 'Header GGUF perlu diperiksa.');
        const where = file.location ? `<br>Tempat: ${esc(file.location)}` : '';
        const actions = partial
          ? `<div class="btns" style="margin-top:8px"><button class="btn sm pri" onclick="startDl('${esc(file.id)}')">Lanjut Download</button><button class="btn sm" onclick="verifyDl('${esc(file.id)}')">Verifikasi Download</button></div>`
          : (file.importable
            ? `<div class="btns" style="margin-top:8px"><button class="btn sm pri" data-path="${esc(file.path)}" onclick="importFoundModel(this.dataset.path)">Pakai file ini</button></div>`
            : '');
        return `<details style="border:1px solid var(--line);border-radius:12px;padding:10px;margin-top:8px;background:var(--card2)">
          <summary style="cursor:pointer"><b>${esc(file.fileName)}</b> · <span class="pill">${esc(file.status)}</span></summary>
          <div class="muted" style="font-size:12px;word-break:break-all;margin-top:8px">Model: ${esc(file.title)} · ${esc(file.quantization)}<br>Ukuran: ${esc(sizeLabel)}${where}<br>Lokasi: ${esc(file.path)}<br>${esc(detail)}</div>
          <div class="muted" style="font-size:12px;margin-top:6px">GGUF berisi bobot model biner, bukan source code; verifikasi ini mengecek ukuran dan header, bukan checksum kriptografis.</div>
          ${actions}
        </details>`;
      }).join('') || `<div class="muted" style="margin-top:8px">${query ? 'Tidak ada file yang cocok.' : 'Belum ada model tersimpan atau unduhan parsial.'} Tekan Pindai, atau gunakan Pilih GGUF.</div>`;
    } catch (error) {
      host.innerHTML = '<div class="muted">Daftar file model tidak dapat dibaca.</div>';
      if (typeof logT === 'function') logT(`Daftar file model: ${error.message}`);
    }
    refreshRam();
  }

  function onTransfer(snapshot) {
    if (!snapshot) return;
    const previous = transferStatus;
    transferStatus = snapshot;
    const activeStates = ['downloading', 'paused', 'verifying', 'moving', 'importing', 'loading'];
    const isActive = activeStates.includes(snapshot.state);
    const total = Number(snapshot.totalBytes) || 0;
    const bytes = Number(snapshot.bytes) || 0;
    const pct = total > 0 ? Math.min(100, bytes / total * 100) : -1;
    const bps = Number(snapshot.bytesPerSecond) || 0;
    const set = (sel, text) => { const el = $(sel); if (el) el.textContent = text; };

    set('#dlName', snapshot.fileName || snapshot.message || 'Tidak ada unduhan aktif');
    set('#dlSource', total > 0 ? `${fmtBytes(bytes)} / ${fmtBytes(total)} · penyimpanan aplikasi` : (snapshot.message || 'Pilih model dari katalog'));
    const bar = $('#dlBar');
    if (bar) {
      bar.style.width = `${pct >= 0 ? pct : (isActive ? 30 : 0)}%`;
      bar.style.transition = 'width .2s linear';
    }
    set('#dlPct', pct >= 0 ? `${pct.toFixed(1)}%` : (isActive ? '…' : '0%'));
    set('#dlSpd', snapshot.state === 'downloading' && bps > 0 ? fmtRate(bps) : (snapshot.state === 'paused' ? 'Dijeda' : '—'));
    const stateMap = {
      downloading: 'Mengunduh…', paused: 'Dijeda', verifying: 'Verifikasi',
      ready: 'Selesai', failed: 'Gagal', cancelled: 'Dibatalkan', idle: 'Idle'
    };
    set('#dlState', stateMap[snapshot.state] || snapshot.state || 'Idle');
    if (snapshot.state === 'downloading' && bps > 0 && total > bytes) {
      const sec = (total - bytes) / bps;
      set('#dlEta', sec > 90 ? `${Math.ceil(sec / 60)} m tersisa` : `${Math.ceil(sec)} d tersisa`);
    } else if (snapshot.state === 'paused') set('#dlEta', 'Siap dilanjutkan');
    else if (snapshot.state === 'ready') set('#dlEta', 'File siap dipakai');
    else if (snapshot.state === 'failed') set('#dlEta', snapshot.message || 'Gagal');
    else set('#dlEta', isActive ? (snapshot.message || 'Memproses…') : 'Menunggu unduhan');

    const live = $('#dlLive');
    if (live) {
      live.classList.remove('busy', 'paused', 'done');
      if (snapshot.state === 'downloading' || snapshot.state === 'verifying') { live.textContent = 'DOWNLOADING'; live.classList.add('busy'); }
      else if (snapshot.state === 'paused') { live.textContent = 'PAUSED'; live.classList.add('paused'); }
      else if (snapshot.state === 'ready') { live.textContent = 'COMPLETE'; live.classList.add('done'); }
      else if (snapshot.state === 'failed') { live.textContent = 'FAILED'; }
      else { live.textContent = 'READY'; }
    }
    const toggle = $('#dlToggle');
    if (toggle) {
      const span = toggle.querySelector('span');
      if (snapshot.state === 'downloading') { if (span) span.textContent = 'Jeda'; }
      else if (snapshot.state === 'paused' || snapshot.state === 'failed') { if (span) span.textContent = 'Lanjut'; }
      else if (snapshot.state === 'ready') { if (span) span.textContent = 'Selesai'; }
      else { if (span) span.textContent = 'Mulai'; }
    }
    const verify = $('#dlVerify');
    const cancel = $('#dlCancel');
    if (verify) verify.disabled = !((bytes > 0 && snapshot.state !== 'downloading') || snapshot.state === 'ready');
    if (cancel) cancel.disabled = !['downloading', 'paused', 'failed'].includes(snapshot.state);

    // Progress di kartu katalog (tanpa rebuild penuh saat downloading)
    const card = document.querySelector(`[data-model-id="${snapshot.modelId}"]`);
    if (card) {
      let row = card.querySelector('.cat-progress');
      if (!row && isActive) {
        row = document.createElement('div');
        row.className = 'cat-progress';
        row.innerHTML = '<div class="bar" style="height:8px;margin:8px 0 4px"><b style="width:0%"></b></div><div class="row muted" style="font-size:11px"><span class="cat-pct"></span><span class="sp"></span><span class="cat-spd"></span></div>';
        card.appendChild(row);
      }
      if (row) {
        const b = row.querySelector('b');
        const p = row.querySelector('.cat-pct');
        const s = row.querySelector('.cat-spd');
        if (b && pct >= 0) b.style.width = pct.toFixed(1) + '%';
        if (p) p.textContent = pct >= 0 ? pct.toFixed(1) + '% · ' + fmtBytes(bytes) + ' / ' + fmtBytes(total) : (snapshot.message || '');
        if (s) s.textContent = snapshot.state === 'downloading' && bps > 0 ? fmtRate(bps) : (stateMap[snapshot.state] || '');
      }
    }

    const logKey = `${snapshot.state}:${pct >= 0 ? Math.floor(pct / 10) : ''}`;
    if (logKey !== lastTransferLog && (['failed', 'ready', 'paused', 'verifying', 'moving', 'importing'].includes(snapshot.state) || pct >= 0)) {
      lastTransferLog = logKey;
      if (typeof logT === 'function') {
        const prefix = snapshot.state === 'failed' ? 'Gagal: ' : snapshot.state === 'ready' ? 'Siap: ' : '';
        logT(`${prefix}${snapshot.message || snapshot.state}${pct >= 0 ? ` · ${Math.floor(pct)}%` : ''}`);
      }
    }
    if (snapshot.state === 'downloading') {
      /* progress sudah di-patch di atas */
    } else if (['ready', 'failed', 'cancelled', 'paused', 'verifying'].includes(snapshot.state)) {
      try { storage(); } catch (_) {}
      requestAnimationFrame(() => { try { renderCatalog(); renderLocalFiles(); } catch (_) {} });
    } else if (previous.modelId !== snapshot.modelId || previous.state !== snapshot.state) {
      requestAnimationFrame(() => { try { renderCatalog(); renderLocalFiles(); } catch (_) {} });
    }
  }

  window.xenaModelTransferState = onTransfer;
  window.xenaInventoryReady = function () {
    requestAnimationFrame(function () {
      try { storage(); } catch (_) {}
      try { renderCatalog(); } catch (_) {}
      try { renderLocalFiles(); } catch (_) {}
    });
  };

  window.xenaModelImported = function (name) {
    if (typeof logT === 'function') logT(`Model diimpor: ${name}`);
    renderCatalog();
    storage();
  };
  window.xenaSelectedModelReady = function (ok, name, error) {
    if (ok) {
      if ($('#activeModelName')) $('#activeModelName').textContent = name;
      if (typeof modelOk === 'function') modelOk(true);
    } else if (typeof modelOk === 'function') modelOk(false, error || 'Gagal memuat model');
    renderCatalog();
    storage();
  };

  window.startDl = function (id) {
    if (!window.Android || !Android.startModelDownload) return typeof toast === 'function' && toast('Unduhan model tersedia di APK Android', 'bad');
    const started = Android.startModelDownload(id);
    if (!started) return typeof toast === 'function' && toast('Unduhan tidak dapat dimulai. Periksa ruang kosong atau batalkan unduhan lain.', 'bad');
    if (typeof toast === 'function') toast('Unduhan dimulai atau dilanjutkan', 'ok');
    renderCatalog();
  };
  window.resumeDl = function () {
    if (!window.Android || !Android.resumeModelDownload || !Android.resumeModelDownload()) {
      if (typeof toast === 'function') toast('Tidak ada unduhan parsial yang bisa dilanjutkan', 'bad');
      return;
    }
    if (typeof toast === 'function') toast('Melanjutkan dari data yang sudah tersimpan', 'ok');
  };
  window.pauseDl = function () {
    if (!window.Android || !Android.pauseModelDownload || !Android.pauseModelDownload()) {
      if (typeof toast === 'function') toast('Tidak ada unduhan aktif untuk dijeda', 'bad');
      return;
    }
    if (typeof toast === 'function') toast('Unduhan dijeda; data parsial tetap disimpan', 'ok');
  };
  window.verifyDl = function (modelId) {
    if (!window.Android || !Android.verifyModelDownload) {
      if (typeof toast === 'function') toast('Verifikasi unduhan hanya tersedia di APK Android', 'bad');
      return;
    }
    const ok = Android.verifyModelDownload(modelId || transferStatus.modelId || '');
    if (!ok && typeof toast === 'function') toast('Jeda unduhan terlebih dahulu atau periksa file parsial', 'bad');
    else if (typeof toast === 'function') toast('Verifikasi selesai; lihat status ukuran dan GGUF di Download Manager', 'ok');
  };
  window.searchLocalModels = function (force) {
    renderLocalFiles(!!force);
  };
  window.cancelDl = function () {
    if (!window.Android || !Android.cancelModelDownload || !Android.cancelModelDownload()) {
      if (typeof toast === 'function') toast('Tidak ada unduhan aktif');
      return;
    }
    if (typeof toast === 'function') toast('Membatalkan unduhan…');
  };
  window.useInstalledModel = function (id) {
    if (!window.Android || !Android.useModel) return typeof toast === 'function' && toast('Pemuatan model tersedia di APK Android', 'bad');
    if (!Android.useModel(id) && typeof toast === 'function') toast('Model belum terpasang atau tidak valid', 'bad');
  };
  window.selectModelFromDownloads = function () {
    if (window.Android && Android.importModelFromDownloads) Android.importModelFromDownloads();
    else if (typeof toast === 'function') toast('Pemilih file tersedia di APK Android', 'bad');
  };
  window.moveModelFromDownloads = function () {
    if (window.Android && Android.moveModelFromDownloads) Android.moveModelFromDownloads();
    else if (typeof toast === 'function') toast('Pemindahan model tersedia di APK Android', 'bad');
  };
  window.showActiveModelDetails = function () {
    try {
      const model = JSON.parse(Android.getModelInfo());
      if (typeof toast === 'function') toast(`${model.name || 'Belum ada model'} · ${model.size || '0 MB'}`, 'ok');
    } catch (_) {
      if (typeof toast === 'function') toast('Detail model tersedia di APK Android', 'bad');
    }
  };

  // Pembersih residual / RAM — UI utama di index.html; jaga agar callable dari mana saja
  if (typeof window.runCleanResiduals !== 'function') {
    window.runCleanResiduals = function () {
      if (!window.Android || !Android.cleanResiduals) {
        if (typeof toast === 'function') toast('Pembersih sisa download hanya di APK', 'bad');
        return;
      }
      try {
        const j = JSON.parse(Android.cleanResiduals() || '{}');
        if (typeof toast === 'function') toast(j.message || 'Selesai', 'ok');
        renderLocalFiles();
        renderCatalog();
      } catch (e) {
        if (typeof toast === 'function') toast('Gagal membersihkan: ' + e.message, 'bad');
      }
    };
  }

  window.importFoundModel = function (path) {
    if (!path || !window.Android || !Android.importFoundModel) {
      if (typeof toast === 'function') toast('Pemakaian file model hanya di APK Android', 'bad');
      return;
    }
    Android.importFoundModel(path);
    if (typeof toast === 'function') toast('Memindahkan GGUF ke folder aplikasi, lalu memuat jika RAM cukup', 'ok');
  };

  function refreshRam() {
    const live = $('#ramLive');
    if (!live || !window.Android || !Android.getRamStatus) return;
    try {
      const ram = JSON.parse(Android.getRamStatus() || '{}');
      live.textContent = `${ram.label || 'RAM'} · bebas ${ram.availMb || 0} MB · proses ${ram.pssMb || 0} MB${ram.loaded ? ' · model di RAM' : ''}`;
    } catch (_) {}
  }

  window.xenaRamPurged = function (report) {
    const status = $('#cleanStatus');
    const freed = $('#cleanFreed');
    if (status && report && report.message) status.textContent = report.message;
    if (freed && report && report.freedBytes > 0 && typeof fmtBytes === 'function') freed.textContent = `${fmtBytes(report.freedBytes)} lepas`;
    refreshRam();
    if (typeof modelOk === 'function') modelOk(false, 'Model dilepas karena RAM mepet');
  };

  // Keep the existing active-model status handling, then refresh the newly added card details.
  if (typeof window.modelOk === 'function' && !window.modelOk.__xenaRefreshWrapped) {
    const oldModelOk = window.modelOk;
    const wrappedModelOk = function (ok, error) {
      oldModelOk(ok, error);
      if (ok !== false && ok !== 0 && ok !== 'false') renderCatalog();
    };
    wrappedModelOk.__xenaRefreshWrapped = true;
    window.modelOk = wrappedModelOk;
  }

  // Replace the old hard-coded 3.55 GB display with the installed model metadata.
  storage();
  renderCatalog();
  renderLocalFiles();
  refreshRam();
  try {
    if (window.Android && Android.getModelTransferState) onTransfer(JSON.parse(Android.getModelTransferState() || '{}'));
  } catch (_) {}
})();
