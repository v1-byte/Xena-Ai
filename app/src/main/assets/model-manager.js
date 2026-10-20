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

  function renderCatalog() {
    const host = $('#modelCatalog');
    if (!host) return;
    if (!window.Android || !Android.getModelCatalog || !Android.getInstalledModels) {
      host.innerHTML = '<div class="muted">Katalog unduhan tersedia di aplikasi Android.</div>';
      return;
    }
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

      host.innerHTML = items.map((model) => {
        const isInstalled = Boolean(model.installed);
        const isBusy = transferStatus.modelId === model.id && ['downloading', 'paused', 'failed', 'moving', 'importing', 'verifying'].includes(transferStatus.state);
        const canResume = isBusy && Number(transferStatus.bytes) > 0;
        const action = isInstalled
          ? `<button class="btn sm ${model.active ? '' : 'pri'}" onclick="useInstalledModel('${esc(model.id)}')">${model.active ? 'Aktif' : 'Gunakan'}</button>`
          : `<button class="btn sm pri" onclick="startDl('${esc(model.id)}')">${canResume ? 'Lanjut' : 'Unduh'}</button>`;
        const recommendation = model.recommendation || 'Model GGUF terpasang';
        const ramNote = model.ramNote || '';
        return `<div style="border:1px solid var(--line);border-radius:12px;padding:12px;margin-top:10px;background:var(--card2)">
          <div class="row"><b>${esc(model.title)}</b><span class="sp"></span><span class="pill">${esc(model.quantization)}</span></div>
          <div class="muted" style="font-size:12px;word-break:break-all;margin-top:4px">${esc(model.fileName)}</div>
          <div class="muted" style="font-size:12px;margin-top:5px">${fmtBytes(model.sizeBytes)} · ${esc(recommendation)}</div>
          ${ramNote ? `<div class="muted" style="font-size:12px;margin-top:3px">${esc(ramNote)}</div>` : ''}
          <div class="row" style="margin-top:8px"><span class="muted">${isInstalled ? 'Tersimpan di folder aplikasi' : 'Unduh langsung ke folder aplikasi'}</span><span class="sp"></span>${action}</div>
        </div>`;
      }).join('') || '<div class="muted">Belum ada model di katalog.</div>';
    } catch (error) {
      host.innerHTML = '<div class="muted">Katalog model tidak dapat dibaca di perangkat ini.</div>';
      if (typeof logT === 'function') logT(`Katalog model: ${error.message}`);
    }
  }

  function renderLocalFiles() {
    const host = $('#modelFiles');
    if (!host) return;
    if (!window.Android || !Android.getLocalModelFiles) {
      host.innerHTML = '<div class="muted">Pencarian lokasi file tersedia di APK Android.</div>';
      return;
    }
    try {
      const query = ($('#modelFileSearch')?.value || '').trim().toLowerCase();
      const files = JSON.parse(Android.getLocalModelFiles() || '[]');
      const matches = files.filter((file) => [file.fileName, file.title, file.path, file.status, file.quantization]
        .some((value) => String(value || '').toLowerCase().includes(query)));
      host.innerHTML = matches.map((file) => {
        const partial = file.kind === 'partial';
        const sizeLabel = partial
          ? `${fmtBytes(file.bytes)} / ${fmtBytes(file.expectedBytes)}`
          : fmtBytes(file.bytes);
        const detail = partial
          ? (file.hasResumeValidator ? 'Berkas parsial siap dilanjutkan dengan HTTP Range.' : 'Berkas parsial dipertahankan; server belum memberi validator resume.')
          : (file.headerValid ? 'Header GGUF terdeteksi.' : 'Header GGUF perlu diperiksa.');
        const actions = partial
          ? `<div class="btns" style="margin-top:8px"><button class="btn sm pri" onclick="startDl('${esc(file.id)}')">Lanjut Download</button><button class="btn sm" onclick="verifyDl('${esc(file.id)}')">Verifikasi Download</button></div>`
          : '';
        return `<details style="border:1px solid var(--line);border-radius:12px;padding:10px;margin-top:8px;background:var(--card2)">
          <summary style="cursor:pointer"><b>${esc(file.fileName)}</b> · <span class="pill">${esc(file.status)}</span></summary>
          <div class="muted" style="font-size:12px;word-break:break-all;margin-top:8px">Model: ${esc(file.title)} · ${esc(file.quantization)}<br>Ukuran: ${esc(sizeLabel)}<br>Lokasi: ${esc(file.path)}<br>${esc(detail)}</div>
          <div class="muted" style="font-size:12px;margin-top:6px">GGUF berisi bobot model biner, bukan source code; verifikasi ini mengecek ukuran dan header, bukan checksum kriptografis.</div>
          ${actions}
        </details>`;
      }).join('') || `<div class="muted" style="margin-top:8px">${query ? 'Tidak ada file yang cocok.' : 'Belum ada model tersimpan atau unduhan parsial.'} Gunakan Pilih GGUF untuk mencari file di penyimpanan Android.</div>`;
    } catch (error) {
      host.innerHTML = '<div class="muted">Daftar file model tidak dapat dibaca.</div>';
      if (typeof logT === 'function') logT(`Daftar file model: ${error.message}`);
    }
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
    const name = $('#dlName');
    const bar = $('#dlBar');
    const percent = $('#dlPct');
    const speed = $('#dlSpd');
    const resume = $('#dlResume');
    const pause = $('#dlPause');
    const verify = $('#dlVerify');
    const cancel = $('#dlCancel');
    if (name) name.textContent = snapshot.fileName || snapshot.message || 'Tidak ada unduhan aktif';
    if (bar) {
      bar.style.width = `${pct >= 0 ? pct : (isActive ? 35 : 0)}%`;
      bar.style.opacity = pct < 0 && isActive ? '.55' : '1';
    }
    if (percent) percent.textContent = pct >= 0
      ? `${pct.toFixed(1)}% · ${fmtBytes(bytes)} / ${fmtBytes(total)}`
      : (snapshot.message || snapshot.state || '0%');
    if (speed) speed.textContent = snapshot.state === 'downloading' && Number(snapshot.bytesPerSecond) > 0
      ? fmtRate(snapshot.bytesPerSecond) : '';
    if (resume) resume.disabled = !(['paused', 'failed'].includes(snapshot.state) && bytes > 0 && Boolean(snapshot.modelId));
    if (pause) pause.disabled = snapshot.state !== 'downloading';
    if (verify) verify.disabled = !((bytes > 0 && snapshot.state !== 'downloading') || snapshot.state === 'ready');
    if (cancel) cancel.disabled = !['downloading', 'paused', 'failed'].includes(snapshot.state);

    const logKey = `${snapshot.state}:${pct >= 0 ? Math.floor(pct / 10) : ''}`;
    if (logKey !== lastTransferLog && (['failed', 'ready', 'paused', 'verifying', 'moving', 'importing'].includes(snapshot.state) || pct >= 0)) {
      lastTransferLog = logKey;
      if (typeof logT === 'function') {
        const prefix = snapshot.state === 'failed' ? 'Gagal: ' : snapshot.state === 'ready' ? 'Siap: ' : '';
        logT(`${prefix}${snapshot.message || snapshot.state}${pct >= 0 ? ` · ${Math.floor(pct)}%` : ''}`);
      }
    }
    if (['ready', 'failed', 'cancelled'].includes(snapshot.state)) {
      storage();
      renderCatalog();
      renderLocalFiles();
    } else if (previous.modelId !== snapshot.modelId || previous.state !== snapshot.state) {
      renderCatalog();
      renderLocalFiles();
    }
  }

  window.xenaModelTransferState = onTransfer;
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
  window.searchLocalModels = function () {
    renderLocalFiles();
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
  try {
    if (window.Android && Android.getModelTransferState) onTransfer(JSON.parse(Android.getModelTransferState() || '{}'));
  } catch (_) {}
})();
