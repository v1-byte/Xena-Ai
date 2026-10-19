# libllama.so

Belum ada file `libllama.so` di repo/attachments saat release ini.

## Cara pasang (dari APK DT-E-AI / Xena lama di HP)
```bash
# Di PC, setelah cabut APK dari HP:
unzip -l app-lama.apk | grep libllama
unzip app-lama.apk 'lib/arm64-v8a/libllama.so' -d /tmp/extract
# Salin ke repo:
mkdir -p app/src/main/jniLibs/arm64-v8a
cp /tmp/extract/lib/arm64-v8a/libllama.so app/src/main/jniLibs/arm64-v8a/
# Hapus atau nonaktifkan stub CMake jika .so prebuilt dipakai
```

Atau unggah `libllama.so` ke chat / commit ke:
`app/src/main/jniLibs/arm64-v8a/libllama.so`

Saat ini CI membangun **stub** dari `app/src/main/cpp/` agar APK tetap installable.
