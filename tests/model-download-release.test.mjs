import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Script } from 'node:vm';

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const activity = read('../app/src/main/java/com/xena/ai/MainActivity.kt');
const loader = read('../app/src/main/java/com/xena/ai/ModelAutoLoader.kt');
const transfer = read('../app/src/main/java/com/xena/ai/ModelDownloadManager.kt');
const catalog = read('../app/src/main/java/com/xena/ai/ModelCatalog.kt');
const prefs = read('../app/src/main/java/com/xena/ai/AppPrefs.kt');
const connectors = read('../app/src/main/java/com/xena/ai/Connectors.kt');
const groqConnector = read('../app/src/main/java/com/xena/ai/KonektorGroq.kt');
const updater = read('../app/src/main/java/com/xena/ai/UpdateChecker.kt');
const html = read('../app/src/main/assets/index.html');
const modelUi = read('../app/src/main/assets/model-manager.js');
const manifest = read('../app/src/main/AndroidManifest.xml');
const gradle = read('../app/build.gradle.kts');
const workflow = read('../.github/workflows/build.yml');

test('all embedded model UI scripts parse before they are packaged', () => {
  const scripts = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)]
    .map((match) => match[1])
    .filter((source) => source.trim());
  assert.ok(scripts.length > 0);
  scripts.forEach((source, index) => assert.doesNotThrow(() => new Script(source, { filename: `index-inline-${index}.js` })));
  assert.doesNotThrow(() => new Script(modelUi, { filename: 'model-manager.js' }));
});

test('the existing app sections and primary navigation remain in place', () => {
  for (const id of ['t0', 't1', 't2', 't3']) assert.match(html, new RegExp(`id="${id}"`));
  for (const tab of ['Chat', 'Model', 'Konektor', 'Atur']) assert.ok(html.includes(tab));
  assert.match(html, /id="modelCatalog"/);
  assert.match(html, /onclick="loadModel\(\)"/);
  assert.match(html, /onclick="bench\(\)"/);
  assert.match(html, /onclick="startDl\('qwen7b-q3'\)"/);
  assert.match(html, /onclick="startDl\('qwen7b-q4'\)"/);
  assert.match(html, /onclick="moveModelFromDownloads\(\)"/);
  assert.match(html, /onclick="selectModelFromDownloads\(\)"/);
  assert.match(html, /id="groqServerSettings"/);
  assert.match(html, /id="groqApiKey"/);
  assert.match(html, /onclick="saveGroqApiKey\(\)"/);
  assert.match(html, /onclick="clearGroqApiKey\(\)"/);
  assert.match(html, /onclick="toggleGroqServerMode\(\)"/);
  assert.match(html, /onclick="testGroqConnection\(\)"/);
  assert.match(html, /function loadGroqSettings\(\)/);
  assert.match(html, /Android Keystore/);
  assert.match(prefs, /AES\/GCM\/NoPadding/);
  assert.match(prefs, /AndroidKeyStore/);
  assert.match(prefs, /groq_api_key_encrypted/);
  assert.match(prefs, /clearLegacyHfConfig/);
  assert.match(prefs, /Connectors\.clearToken\(context, "huggingface"\)/);
  assert.doesNotMatch(connectors, /id = "huggingface"/);
  assert.doesNotMatch(connectors, /id == "huggingface"/);
  assert.match(groqConnector, /https:\/\/api\.groq\.com\/openai\/v1\/chat\/completions/);
  assert.match(groqConnector, /openai\/gpt-oss-20b/);
  assert.match(groqConnector, /max_completion_tokens/);
  assert.doesNotMatch(groqConnector, /gsk_[A-Za-z0-9]{20,}/);
  assert.doesNotMatch(activity, /KonektorHuggingFace|setHfInferenceToken|pingHfServer/);
  assert.match(activity, /KonektorGroq\.chat/);
  assert.match(activity, /KonektorGroq\.ping/);
  assert.match(activity, /AppPrefs\.clearLegacyHfConfig\(this\)/);
  assert.match(html, /S\.set\('tok_hf',''\)/);
  assert.doesNotMatch(html, /\['hf','Hugging Face','Unduh model GGUF'/);
  assert.doesNotMatch(html, /k==='hf'/);
  assert.match(html, /<script src="model-manager\.js"><\/script>/);
  assert.match(modelUi, /window\.startDl = function/);
});

test('catalog keeps the requested model variants and direct Hugging Face files', () => {
  for (const file of [
    'qwen2.5-coder-1.5b-instruct-q4_k_m.gguf',
    'qwen2.5-coder-3b-instruct-q3_k_m.gguf',
    'Qwen2.5-VL-3B-uncensored-bughunter-v9.Q4_K_M.gguf',
    'qwen2.5-coder-7b-instruct-q2_k.gguf',
    'qwen2.5-coder-7b-instruct-q3_k_m.gguf',
    'qwen2.5-coder-7b-instruct-q4_k_m.gguf',
    'WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-Q2_K.gguf',
    'WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-IQ2_M.gguf',
    'WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-IQ3_XXS.gguf',
    'WhiteRabbitNeo-7B-v1.5a-Q3_K_M.gguf',
    'WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-Q3_K_M.gguf',
    'WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-Q4_K_M.gguf',
    'WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-Q5_K_M.gguf'
  ]) assert.ok(catalog.includes(file), `missing ${file}`);
  assert.equal((catalog.match(/https:\/\/huggingface\.co\//g) || []).length, 13);
  assert.match(catalog, /id = "qwen3b-bughunter"/);
  assert.match(catalog, /id = "wrn-mini"/);
  assert.match(catalog, /id = "wrn-v3-iq3xxs"/);
  assert.match(catalog, /id = "whiterabbitneo-v3-7b-q2"/);
  assert.match(catalog, /title = "WhiteRabbitNeo Mini"/);
  assert.match(catalog, /sizeBytes = 1_929_902_432L/);
  assert.match(catalog, /sizeBytes = 3_015_941_312L/);
  assert.match(catalog, /sizeBytes = 2_780_343_488L/);
  assert.match(catalog, /sizeBytes = 3_114_515_648L/);
});

test('model downloads use resumable byte ranges and validate partials before activation', () => {
  assert.match(transfer, /setRequestProperty\("Range",\s*"bytes=\$offset-"\)/);
  assert.match(transfer, /setRequestProperty\("If-Range"/);
  assert.match(transfer, /HTTP_PARTIAL/);
  assert.match(transfer, /CONTENT_RANGE/);
  assert.match(transfer, /ModelAutoLoader\.isValidGguf\(part\)/);
  assert.match(transfer, /part\.renameTo\(final\)/);
  assert.match(transfer, /pause\(\)/);
  assert.match(transfer, /cancel\(\)/);
  assert.doesNotMatch(transfer, /Math\.random|setInterval/);
});

test('download UI has explicit resume, pause, verification, and searchable local model paths', () => {
  for (const id of ['dlResume', 'dlPause', 'dlVerify', 'modelFileSearch', 'modelFiles']) assert.ok(html.includes(`id="${id}"`));
  assert.match(html, /Lanjut Download/);
  assert.match(html, /Jeda Download/);
  assert.match(html, /Verifikasi Download/);
  assert.match(modelUi, /window\.resumeDl/);
  assert.match(modelUi, /window\.pauseDl/);
  assert.match(modelUi, /window\.verifyDl/);
  assert.match(modelUi, /Android\.getLocalModelFiles/);
  assert.match(activity, /fun getLocalModelFiles\(\)/);
  assert.match(activity, /fun verifyModelDownload\(modelId: String\)/);
  assert.match(transfer, /fun verifyDownload\(modelId: String\? = null\)/);
  assert.match(transfer, /parsial tetap disimpan, tidak diunduh ulang/);
});

test('Downloads import is SAF-first/fallback and GGUF validation prevents loading temporary files', () => {
  assert.match(activity, /ActivityResultContracts\.OpenDocument\(\)/);
  assert.match(activity, /DocumentsContract\.deleteDocument\(contentResolver, uri\)/);
  assert.match(activity, /imported != null && moveSource/);
  assert.match(activity, /importModelFromDownloads/);
  assert.match(loader, /getExternalFilesDir\(null\)/);
  assert.match(loader, /MIN_MODEL_BYTES/);
  assert.match(loader, /version in 2\.\.3/);
  assert.match(loader, /COPY_BUFFER_BYTES = 8 \* 1024 \* 1024/);
  assert.match(loader, /sameContent\(source, installed\)/);
  assert.doesNotMatch(manifest, /MANAGE_EXTERNAL_STORAGE/);
});

test('the 6 GB profile defaults to 1024 context, 32 batch, and 2 threads', () => {
  assert.match(prefs, /getInt\("n_ctx", 1024\)/);
  assert.match(prefs, /getInt\("n_batch", 32\)/);
  assert.match(prefs, /getInt\("n_threads", 2\)/);
  assert.match(catalog, /rekomendasi awal untuk RAM 6 GB/);
  assert.match(html, /id="ctx" min="512" max="2048"[^>]*value="1024"/);
  assert.match(html, /function buildThr\(\)\{const cur=S\.get\('thr',2\)/);
});

test('startup honors opt-in auto-load and blocks likely low-RAM model kills before JNI', () => {
  const onCreate = activity.slice(activity.indexOf('override fun onCreate'), activity.indexOf('fun autoLoadModelIfExists'));
  assert.match(onCreate, /prepareModelIfExists\(\)/);
  assert.doesNotMatch(onCreate, /autoLoadModelIfExists/);
  assert.match(activity, /private fun prepareModelIfExists\(\)/);
  assert.match(activity, /memory\.totalMem <= 6L \* gib \+ gib \/ 2L && modelBytes >= 3L \* gib/);
  assert.match(activity, /memory\.availMem < requiredBytes/);
  assert.match(activity, /val memoryWarning = activity\.modelLoadMemoryWarning\(f\)/);
  assert.match(activity, /activity\.loadModelFile\(f, notifyUi = false\)/);
  assert.match(html, /if\(S\.get\('auto',false\)\)setTimeout\(loadModel,2600\)/);
});

test('force update still refuses same or older versions', () => {
  assert.match(updater, /if \(!isNewer\(info\.tag, local\)\)/);
  assert.match(updater, /hasCompatibleSigningCertificate\(activity, file\)/);
  assert.match(updater, /Kunci APK berbeda/);
  assert.doesNotMatch(updater, /!isNewer\(info\.tag, local\)\s*&&\s*!force/);
  assert.match(updater, /if \(!force && !shouldCheck\(activity\)\)/);
});

test('APK release is version-driven and verifies native llama library packaging', () => {
  assert.match(gradle, /versionCode = 155/);
  assert.match(gradle, /versionName = "1.5.5"/);
  assert.doesNotMatch(gradle, /versionName = "1\.4\.2"/);
  assert.match(gradle, /signingConfig = signingConfigs\.getByName\("release"\)/);
  assert.match(workflow, /steps\.release_meta\.outputs\.tag/);
  assert.match(workflow, /gradle assembleRelease/);
  assert.match(workflow, /secrets\.XENA_SIGNING_KEYSTORE_BASE64/);
  assert.match(workflow, /Compare signing certificate/);
  assert.match(workflow, /lib\/arm64-v8a\/libllama\.so/);
  assert.match(workflow, /Chat corner menu adds a shortcut to Model AI/);
  assert.match(workflow, /RAM cleanup unloads the local model/);
  assert.doesNotMatch(workflow, /tag_name:\s*v1\.3\.5/);
  assert.match(modelUi, /bytesPerSecond/);
  assert.doesNotMatch(modelUi, /Math\.random/);
});
