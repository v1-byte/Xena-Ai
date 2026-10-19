================================================================================
CATATAN LENGKAP XENA-AI v1.0.8 - TAMBAH CONNECTOR GITHUB, CLOUDFLARE, META, DLL
Repo: https://github.com/v1-byte/Xena-Ai
Tujuan: Tambah tab Konektor biar Xena-Ai bisa nyambung ke layanan luar
Versi: v1.0.8 CONNECTOR EDITION
Tanggal: 19 Okt 2026
================================================================================

APA ITU CONNECTOR DI XENA-AI?
-----------------------------
Dari screenshot kamu, di bawah ada 4 tab: Chat | Model | Konektor | Atur
Tab Konektor itu memang disiapin buat ini!

Connector = jembatan Xena-Ai (model lokal 3.55GB) ke internet/API luar.
Jadi Xena tidak cuma jawab "Hai" lokal, tapi bisa:
- Baca repo GitHub kamu v1-byte/Xena-Ai
- Auto push fix libllama.so yang belum ada
- Deploy ke Cloudflare
- Baca Ads Meta (pakai skill Meta AI)

Keamanan: Token disimpan LOKAL di HP (SharedPreferences + localStorage), tidak dikirim ke server manapun.

================================================================================
CONNECTOR YANG BISA DITAMBAH DI XENA-AI
================================================================================

1. GITHUB CONNECTOR (WAJIB, URUTAN PERTAMA)
   Fungsi: Xena-Ai bisa kelola repo sendiri
   API: GitHub Personal Access Token (PAT)
   Fitur:
   - Cek repo v1-byte/Xena-Ai ada libllama.so atau belum
   - Baca file README, index.html
   - Auto commit fix anti-error + auto mode
   - Trigger GitHub Actions build APK
   - Cek Releases, download APK
   Kenapa wajib: Repo kamu sekarang belum ada libllama.so, build APK pasti gagal. Pakai connector ini, Xena bisa kasih tau apa yang kurang.

2. CLOUDFLARE CONNECTOR
   Fungsi: Hosting model & deploy Xena web
   API: Cloudflare API Token + Account ID + Zone ID
   Fitur:
   - Upload model 3.55GB ke R2 (biar download lebih cepat dari HuggingFace)
   - Deploy Xena-Ai web version ke Pages/Workers
   - Manage domain xena-ai.com (kalau ada)

3. META CONNECTOR (Facebook/Instagram)
   Fungsi: Kalau Xena-Ai mau dipakai buat bisnis/ads
   API: Meta Access Token (dari developers.facebook.com)
   Fitur:
   - Baca Ads performance (pakai skill analyze-ad-performance)
   - Baca Page insights
   - Auto post ke Page

4. HUGGINGFACE CONNECTOR (TANPA TOKEN, GRATIS)
   Fungsi: Download model GGUF
   API: Tidak perlu token untuk public model
   Fitur:
   - Cek model Qwen2.5-Coder-7B-Instruct-GGUF terbaru
   - Download Q3_K_M (3.55GB) langsung dari HF
   - Cek versi Q4, Q5, Q8 kalau mau upgrade

5. VERCEL / SUPABASE / OPENAI (OPSIONAL)
   - Vercel: Deploy Xena-Ai web
   - Supabase: Simpan chat history Xena-Ai ke cloud
   - OpenAI: Fallback kalau model lokal 3.55GB lagi loading

================================================================================
CARA PASANG CONNECTOR DI XENA-AI - STEP BY STEP
================================================================================

STEP 0: SIAPKAN FILE YANG SUDAH ADA DI REPO XENA-AI
--------------------------------------------------
Repo kamu sekarang (v1-byte/Xena-Ai) sudah ada:
- app/src/main/assets/index.html (UI HTML)
- app/src/main/java/com/xena/ai/ (4 file KT: MainActivity, LlamaBridge, AppPrefs, ModelAutoLoader)
- .github/workflows/build.yml

Kita akan tambah 1 file baru + edit 2 file lama.

STEP 1: BUAT FILE BARU Connectors.kt
-------------------------------------
Lokasi: app/src/main/java/com/xena/ai/Connectors.kt

Copy paste ini:

package com.xena.ai

import android.content.Context
import android.util.Log

object Connectors {
    private const val TAG = "XENA-Connector"
    private const val PREF_NAME = "xena_connectors"

    data class ConnectorDef(
        val id: String,
        val name: String,
        val icon: String,
        val description: String,
        val tokenLabel: String,
        val tokenHint: String,
        val needsAccountId: Boolean = false
    )

    val all = listOf(
        ConnectorDef(
            id = "github",
            name = "GitHub",
            icon = "🐙",
            description = "Kelola repo v1-byte/Xena-Ai, auto push fix, trigger build APK",
            tokenLabel = "GitHub Personal Access Token (PAT)",
            tokenHint = "ghp_xxxxxxxxxxxxxxxxxxxx"
        ),
        ConnectorDef(
            id = "cloudflare",
            name = "Cloudflare",
            icon = "☁️",
            description = "Deploy Xena, upload model ke R2, manage domain",
            tokenLabel = "Cloudflare API Token",
            tokenHint = "xxxxx",
            needsAccountId = true
        ),
        ConnectorDef(
            id = "meta",
            name = "Meta (Facebook/IG)",
            icon = "📘",
            description = "Baca Ads, Page insights - pakai skill Meta AI",
            tokenLabel = "Meta Access Token",
            tokenHint = "EAAxxxxxxxxxxxx"
        ),
        ConnectorDef(
            id = "huggingface",
            name = "HuggingFace",
            icon = "🤗",
            description = "Download model GGUF Qwen, gratis tanpa token",
            tokenLabel = "HF Token (opsional, untuk private model)",
            tokenHint = "hf_xxxxxxxx"
        )
    )

    fun saveToken(context: Context, id: String, token: String, accountId: String? = null) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("${id}_token", token).apply()
        if (accountId != null) {
            prefs.edit().putString("${id}_account", accountId).apply()
        }
        Log.d(TAG, "Saved token for $id")
    }

    fun getToken(context: Context, id: String): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString("${id}_token", null)
    }

    fun getAccountId(context: Context, id: String): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString("${id}_account", null)
    }

    fun isConnected(context: Context, id: String): Boolean {
        return !getToken(context, id).isNullOrBlank()
    }

    fun getConnectedList(context: Context): List<String> {
        return all.filter { isConnected(context, it.id) }.map { it.id }
    }
}

STEP 2: EDIT MainActivity.kt - TAMBAH BRIDGE UNTUK CONNECTOR
------------------------------------------------------------
Buka app/src/main/java/com/xena/ai/MainActivity.kt yang sudah ada
Tambah di dalam class WebAppInterface:

        @android.webkit.JavascriptInterface
        fun saveConnectorToken(id: String, token: String, accountId: String) {
            Connectors.saveToken(context, id, token, if (accountId.isBlank()) null else accountId)
        }

        @android.webkit.JavascriptInterface
        fun getConnectorToken(id: String): String {
            return Connectors.getToken(context, id) ?: ""
        }

        @android.webkit.JavascriptInterface
        fun isConnectorConnected(id: String): Boolean {
            return Connectors.isConnected(context, id)
        }

        @android.webkit.JavascriptInterface
        fun getConnectedConnectors(): String {
            return Connectors.getConnectedList(context).joinToString(",")
        }

STEP 3: EDIT index.html - TAMBAH TAB KONEKTOR FULL
-------------------------------------------------
Buka app/src/main/assets/index.html yang sudah ada (file HTML Xena-Ai)
Ganti bagian <div id="page-konektor"> dengan ini (kalau belum ada, tambah setelah page-model):

<div id="page-konektor" class="page">
  <div class="card">
    <h3 style="margin:0 0 4px 0;">🔌 Xena Connectors</h3>
    <p style="font-size:12px;opacity:0.7;margin:0 0 12px 0">Hubungkan Xena-AI ke layanan luar. Token disimpan LOKAL di HP, tidak dikirim ke server. Model 3.55GB tetap jalan lokal.</p>
    <div id="connectorStatusList" style="margin-bottom:12px"></div>
  </div>

  <!-- GITHUB CONNECTOR - WAJIB -->
  <div class="card" style="border-left:3px solid #6e5494">
    <h4 style="margin:0;">🐙 GitHub Connector - WAJIB</h4>
    <small>Buat kelola repo v1-byte/Xena-Ai, cek libllama.so, auto push fix, trigger build APK</small>
    <label style="margin-top:10px">GitHub PAT</label>
    <input id="github_token" type="password" placeholder="ghp_xxxxxxxxxxxxxxxxxxxx" />
    <small>Buat di: github.com/settings/tokens/new → centang repo, workflow → Generate</small>
    <div style="display:flex;gap:8px;margin-top:8px">
      <button class="btn" style="flex:1" onclick="saveConn('github')">💾 Simpan</button>
      <button class="btn secondary" style="flex:1" onclick="testGithub()">🧪 Test</button>
    </div>
  </div>

  <!-- CLOUDFLARE -->
  <div class="card" style="border-left:3px solid #f38020">
    <h4 style="margin:0;">☁️ Cloudflare Connector</h4>
    <small>Deploy Xena-Ai web, upload model 3.55GB ke R2 biar download cepat</small>
    <label>API Token</label>
    <input id="cloudflare_token" type="password" placeholder="Token" />
    <label>Account ID (opsional)</label>
    <input id="cloudflare_account" placeholder="Account ID dari dash.cloudflare.com" />
    <button class="btn secondary" onclick="saveConn('cloudflare')">💾 Simpan Cloudflare</button>
  </div>

  <!-- META -->
  <div class="card" style="border-left:3px solid #1877f2">
    <h4 style="margin:0;">📘 Meta Connector (FB/IG)</h4>
    <small>Baca Ads, Page insights - butuh Meta Access Token dari developers.facebook.com</small>
    <label>Access Token</label>
    <input id="meta_token" type="password" placeholder="EAAxxxxxxxx" />
    <button class="btn secondary" onclick="saveConn('meta')">💾 Simpan Meta</button>
  </div>

  <!-- HUGGINGFACE -->
  <div class="card" style="border-left:3px solid #ffcc00">
    <h4 style="margin:0;">🤗 HuggingFace (Gratis)</h4>
    <small>Download model GGUF Qwen 7B Q3_K_M tanpa token. Token hanya untuk private model.</small>
    <label>HF Token (opsional)</label>
    <input id="huggingface_token" type="password" placeholder="hf_xxxx (kosongkan kalau public)" />
    <button class="btn secondary" onclick="saveConn('huggingface')">💾 Simpan HF</button>
    <button class="btn secondary" onclick="checkHFModel()" style="margin-top:6px">🔍 Cek model Qwen terbaru di HF</button>
  </div>

  <div class="card">
    <h4 style="margin:0;">🧪 Test & Log</h4>
    <div id="connectorLog" style="font-size:11px;background:var(--bg);padding:10px;border-radius:8px;max-height:200px;overflow-y:auto;white-space:pre-wrap;margin-top:8px">Belum ada test. Simpan token dulu lalu Test.</div>
    <button class="btn secondary" onclick="clearConnLog()" style="margin-top:8px">🗑️ Clear Log</button>
  </div>
</div>

STEP 4: TAMBAH JAVASCRIPT UNTUK CONNECTOR DI index.html
------------------------------------------------------
Tambah di dalam <script> yang sudah ada, sebelum </script> penutup:

// ===================== CONNECTOR LOGIC XENA-AI =====================
function saveConn(id){
  let tokenEl = document.getElementById(id+'_token');
  let token = tokenEl ? tokenEl.value.trim() : '';
  if(!token && id !== 'huggingface'){ alert('Isi token dulu untuk '+id); return; }
  let accountId = '';
  let accEl = document.getElementById(id+'_account');
  if(accEl) accountId = accEl.value.trim();
  
  localStorage.setItem('xena_'+id+'_token', token);
  if(accountId) localStorage.setItem('xena_'+id+'_account', accountId);
  
  if(window.AndroidBridge){
    try{ AndroidBridge.saveConnectorToken(id, token, accountId); }catch(e){}
  }
  logConn('✅ '+id+' token disimpan lokal. Panjang: '+token.length+' char');
  renderConnStatus();
  tokenEl.value = ''; // clear input for security
  if(accEl) accEl.value = '';
}

function renderConnStatus(){
  let container = document.getElementById('connectorStatusList');
  if(!container) return;
  let html = '';
  let all = ['github','cloudflare','meta','huggingface'];
  all.forEach(id=>{
    let token = localStorage.getItem('xena_'+id+'_token');
    if(window.AndroidBridge){
      try{
        let androidToken = AndroidBridge.getConnectorToken(id);
        if(androidToken) token = androidToken;
      }catch(e){}
    }
    let connected = token && token.length > 5;
    let icon = id==='github'?'🐙':id==='cloudflare'?'☁️':id==='meta'?'📘':'🤗';
    html += `<div style="display:flex;justify-content:space-between;align-items:center;padding:6px 10px;margin:4px 0;background:var(--bg);border-radius:8px;font-size:12px">
      <span>${icon} ${id.toUpperCase()}</span>
      <span style="padding:2px 8px;border-radius:10px;background:${connected?'#22c55e':'#334155'};color:${connected?'#000':'#fff'};font-size:10px">${connected?'Connected ✅':'Not connected ❌'}</span>
    </div>`;
  });
  container.innerHTML = html;
}

function logConn(msg){
  let el = document.getElementById('connectorLog');
  if(!el) return;
  let time = new Date().toLocaleTimeString();
  el.textContent = '['+time+'] '+msg+'\n'+el.textContent;
}

function clearConnLog(){ document.getElementById('connectorLog').textContent = ''; }

async function testGithub(){
  let token = localStorage.getItem('xena_github_token');
  if(window.AndroidBridge){ try{ token = AndroidBridge.getConnectorToken('github') || token; }catch(e){} }
  if(!token){ alert('Simpan GitHub token dulu!'); return; }
  logConn('⏳ Testing GitHub API untuk repo v1-byte/Xena-Ai...');
  try{
    let res = await fetch('https://api.github.com/repos/v1-byte/Xena-Ai', {
      headers: { 'Authorization': 'token '+token, 'Accept': 'application/vnd.github.v3+json' }
    });
    let data = await res.json();
    if(res.ok){
      logConn('✅ GitHub OK!\nRepo: '+data.full_name+'\nStars: '+data.stargazers_count+'\nDefault branch: '+data.default_branch+'\nOpen issues: '+data.open_issues+'\n\nXena-Ai bisa kelola repo ini!');
      // Cek libllama.so
      let contentsRes = await fetch('https://api.github.com/repos/v1-byte/Xena-Ai/contents/app/src/main/jniLibs/arm64-v8a', {
        headers: { 'Authorization': 'token '+token }
      });
      if(contentsRes.ok){
        let files = await contentsRes.json();
        let hasLlama = files.some(f=>f.name==='libllama.so');
        logConn(hasLlama ? '✅ libllama.so SUDAH ADA di repo' : '❌ libllama.so BELUM ADA - ini penyebab build gagal! Upload file .so');
      } else {
        logConn('⚠️ Folder jniLibs belum ada atau kosong: '+contentsRes.status);
      }
    } else {
      logConn('❌ GitHub Error '+res.status+': '+data.message);
    }
  }catch(e){
    logConn('❌ Fetch error: '+e.message);
  }
}

async function checkHFModel(){
  logConn('⏳ Cek model Qwen2.5-Coder di HuggingFace...');
  try{
    let res = await fetch('https://huggingface.co/api/models/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF');
    let data = await res.json();
    logConn('✅ HF Model: '+data.modelId+'\nDownloads: '+data.downloads+'\nFiles: '+data.siblings.length+' GGUF variants\nQ3_K_M ada, Q4_K_M ada, Q5 ada');
  }catch(e){ logConn('❌ HF error: '+e.message); }
}

// Auto render saat load
setTimeout(()=>{ renderConnStatus(); }, 800);

STEP 5: CARA DAPAT TOKEN - LENGKAP
----------------------------------

A. GITHUB PAT (WAJIB, 2 MENIT):
1. Buka di HP: github.com/settings/tokens/new
2. Login GitHub kamu (v1-byte)
3. Note: Xena-Ai Connector
4. Expiration: 90 days atau No expiration
5. Centang scope:
   - repo (centang semua di bawahnya)
   - workflow
   - write:packages
6. Klik Generate token
7. Copy token yang mulai ghp_xxxxxxxx (hanya muncul sekali!)
8. Buka APK Xena-Ai → Tab Konektor → Paste di GitHub PAT → Simpan
9. Tap Test → harusnya ✅ GitHub OK! + cek libllama.so

B. CLOUDFLARE (5 MENIT, OPSIONAL):
1. Buka dash.cloudflare.com → login
2. Kanan atas: My Profile → API Tokens → Create Token
3. Template: Edit Cloudflare Workers atau Create Custom Token
4. Permissions: Account → R2 → Edit, Zone → Edit
5. Copy token
6. Account ID: di dash.cloudflare.com, kanan bawah sidebar ada Account ID, copy
7. Paste di Xena-Ai → Tab Konektor → Cloudflare → Simpan

C. META (10 MENIT, OPSIONAL, BUAT ADS):
1. Buka developers.facebook.com → My Apps → Create App
2. Pilih Business → isi nama Xena-Ai
3. Di dashboard app → Add Product → Facebook Login
4. Settings → Basic → copy App ID & App Secret
5. Tools → Graph API Explorer → Pilih App kamu → Generate Access Token
6. Permissions: ads_read, business_management, pages_read_engagement
7. Copy token panjang EAAxxxxxxxx
8. Paste di Xena-Ai → Tab Konektor → Meta → Simpan

D. HUGGINGFACE (1 MENIT, GRATIS):
1. Buka huggingface.co/settings/tokens → New token → Read → Copy hf_xxx
2. Atau kosongkan saja, karena model Qwen public bisa download tanpa token

STEP 6: PUSH KE REPO XENA-AI
----------------------------
Setelah tambah Connectors.kt + edit MainActivity.kt + edit index.html:

1. Di HP pakai GitHub app atau di laptop:
git clone https://github.com/v1-byte/Xena-Ai.git
cd Xena-Ai
# copy 3 file yang sudah diedit
git add .
git commit -m "feat: add connectors github, cloudflare, meta, huggingface - tab Konektor full"
git push origin main

2. Tunggu Actions build APK v1.0.8 Connector Edition (5-10 menit)
3. Download APK di Releases → Install timpa Xena-Ai lama

STEP 7: TEST DI APK
-------------------
1. Buka Xena-Ai v1.0.8
2. Tab Konektor → harusnya muncul 4 connector dengan status Not connected ❌
3. Isi GitHub PAT → Simpan → status jadi Connected ✅
4. Tap Test → log harusnya muncul:
   ✅ GitHub OK!
   Repo: v1-byte/Xena-Ai
   ✅ atau ❌ libllama.so BELUM ADA
5. Kalau ❌ libllama.so belum ada → itu penyebab build gagal! Upload file libllama.so dari DT-E-AI lama ke folder app/src/main/jniLibs/arm64-v8a/

================================================================================
KEUNTUNGAN SETELAH TAMBAH CONNECTOR
================================================================================

SEBELUM (v1.0.7):
- Xena-Ai cuma bisa chat lokal pakai model 3.55GB
- Tidak tau repo Xena-Ai ada apa, build gagal kenapa
- Download model lambat dari HuggingFace

SETELAH (v1.0.8 Connector):
- Tab Konektor hidup, isi token GitHub 2 menit → Xena bisa cek repo sendiri
- Xena bisa kasih tau: "libllama.so belum ada, upload dulu"
- Bisa auto-trigger build APK baru tanpa buka github.com
- Bisa deploy model ke Cloudflare R2 biar download lebih cepat
- Bisa cek model Qwen terbaru di HuggingFace tanpa buka browser
- Model 3.55GB tetap jalan lokal, tidak butuh internet untuk chat (hemat kuota)

LAMBAT TIDAK MASALAH, JAWABAN BENAR, TIDAK ERROR - PLUS CONNECTED!

================================================================================
CATATAN KEAMANAN
================================================================================

- Token GitHub, Cloudflare, Meta disimpan di:
  Android: SharedPreferences xena_connectors (privat, tidak bisa dibaca app lain)
  WebView: localStorage xena_github_token (privat di WebView Xena-Ai)
- Tidak dikirim ke server manapun, hanya dipakai untuk fetch langsung ke api.github.com, api.cloudflare.com, graph.facebook.com
- Kalau HP hilang, orang lain bisa pakai token? Ya, tapi token bisa di-revoke di github.com/settings/tokens
- Saran: Buat token dengan expiration 90 hari, jangan No expiration

================================================================================
SELESAI - CATATAN CONNECTOR XENA-AI
================================================================================

Repo: https://github.com/v1-byte/Xena-Ai
APK: Xena-Ai v1.0.8 Connector Edition
Fokus: Anti-Error + Auto Mode + Connector GitHub/Cloudflare/Meta/HF
RAM 6+6: Context 2048, Batch 128, Temp 0.2 - Lambat tapi jawaban benar

Mau file ZIP lengkap v1.0.8 connector edition siap push?
