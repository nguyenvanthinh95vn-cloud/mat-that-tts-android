# Mật Thất TTS — Android

App đọc truyện offline bằng giọng **Ngọc Huyền** (model Piper/VITS tự train),
bản APK của app Windows **Mật Thất TTS v1.0**. Chạy hoàn toàn trên điện thoại,
không mạng, không đăng nhập, không giới hạn ký tự.

## Tính năng (giống bản Windows)

- **Đọc văn bản**: dán truyện dài bao nhiêu cũng được — app tự chia đoạn
  (tối đa 800 ký tự/đoạn, cắt ở ranh giới câu) rồi nối thành 1 file.
  Tốc độ đọc 0.5x–2.0x, nghe thử đoạn đầu, nút Dừng, đếm ký tự.
- **SRT → Voice**: đọc file `.srt` — giữ đúng timestamp từng dòng
  (mặc định, như bản Windows) hoặc đọc liền mạch.
- **Cài đặt**: nghỉ sau dấu câu (phẩy 0.25s, chấm 0.45s, hai chấm 0.3s,
  chấm phẩy 0.4s, ngắt đoạn 0.5s, ngắt đoạn văn 1.0s), noise_scale/noise_w,
  âm lượng đỉnh, định dạng file M4A (AAC, mặc định) hoặc WAV.
- File lưu ra **Music/Mật Thất TTS/** trên máy (thấy trong app Files/Nhạc),
  có nút Nghe lại / Chia sẻ / Lưu ra chỗ khác.

## Giọng đọc chạy bằng gì

**Sherpa-ONNX 1.13.8** (`com.k2fsa.sherpa.onnx`, AAR `sherpa-onnx-static-link-onnxruntime-1.13.8.aar`):
thư viện chạy model Piper/VITS offline trên CPU điện thoại, có sẵn API Kotlin
(`OfflineTts`, `OfflineTtsVitsModelConfig`). Tài liệu:
- Doc chuyển model Piper: https://k2-fsa.github.io/sherpa/onnx/tts/piper.html
- Code mẫu Android của upstream (đã đọc kỹ, app này làm theo đúng mẫu đó):
  https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/android/SherpaOnnxTts/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt
- Release AAR: https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8

File giọng trong app (`app/src/main/assets/piper/`) — chỉ `tokens.txt`
được commit vào git; model và espeak-ng-data **tải về lúc build**
(vì GitHub snapshot push không nhận file nhị phân lớn):

- `ngochuyen.onnx` (60.6 MB) — model epoch 474 v2 (bản tốt nhất, train tiếp từ 337 với thêm 16,4 phút dữ liệu mới), **đã thêm metadata**
  sherpa-onnx yêu cầu (`model_type=vits`, `comment=piper`, `language=Vietnamese`,
  `voice=vi`, `has_espeak=1`, `n_speakers=1`, `sample_rate=22050`).
  Model export từ train KHÔNG có metadata này nên bắt buộc phải thêm
  (xem `tools/convert_model.py`). Tải từ Google Drive lúc build
  (file `MatThat/ngochuyen-v2-sherpa.onnx`,
  chia sẻ "anyone with the link"), link + kích thước kiểm chứng nằm
  trong task `downloadModel` ở `app/build.gradle`.
- `tokens.txt` (154 dòng) — sinh từ `phoneme_id_map` trong file `.onnx.json`.
- `espeak-ng-data/` (19 MB) — dữ liệu phiên âm espeak-ng, dùng chung cho mọi
  giọng Piper, tải từ https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2
  rồi giải nén lúc build (task `downloadEspeakData`); đã kiểm chứng giải nén
  ra giống hệt bản đang dùng (355 file, 17991651 bytes).

**Đã kiểm chứng**: dùng đúng bộ 3 file trên với sherpa-onnx Python 1.13.8
(cùng lõi native với bản Android) đọc đoạn tiếng Việt 466 chữ → ra 18.5 giây
audio thật, không lỗi. Lần đầu mở app trên điện thoại, app copy `espeak-ng-data`
ra bộ nhớ trong (mất vài giây), các lần sau dùng luôn.

## Các bước để có APK (anh làm)

1. Tạo repo **public** mới trên GitHub, tên gợi ý: `mat-that-tts-android`.
2. Tạo file `.github/workflows/build-apk.yml` trong repo đó (tạo file mới trên
   web UI), dán toàn bộ nội dung file `.github/workflows/build-apk.yml`
   trong thư mục này vào.
3. Báo tôi — tôi push toàn bộ code lên repo.
4. Mỗi lần push, GitHub tự build APK (~3–5 phút). Link tải trực tiếp:
   `https://github.com/nguyenvanthinh95vn-cloud/mat-that-tts-android/releases/download/latest-build/app-debug.apk`
   (bản debug, cài trực tiếp, không cần CH Play).
5. Cài lên điện thoại, mở app, chờ lần đầu chuẩn bị giọng đọc xong rồi dùng.

## Đổi giọng mới (khi train xong epoch 500)

Model và espeak-ng-data không nằm trong git — đổi giọng làm 3 bước:

1. Chạy `tools/convert_model.py` với model + config mới (script ghi ra
   file `.onnx` đã thêm metadata + `tokens.txt` mới):

```bash
pip install onnx
python3 tools/convert_model.py \
    --onnx <đường dẫn ngochuyen-epoch500.onnx> \
    --config <đường dẫn ngochuyen-epoch500.onnx.json>
```

2. Upload file `.onnx` vừa tạo lên Drive (folder `MatThat/ngochuyen-onnx/`,
   chia sẻ "anyone with the link") rồi sửa `modelDriveFileId` + `MODEL_SIZE`
   trong `app/build.gradle` theo file mới.
3. Copy `tokens.txt` mới vào `app/src/main/assets/piper/` (file này commit
   vào git) rồi push — GitHub tự build APK giọng mới. File `.onnx.json` gốc
   của giọng đang dùng nằm ở `models/ngochuyen-final.onnx.json` để đối chiếu.

(Lưu ý: `convert_model.py` ghi đè `app/src/main/assets/piper/ngochuyen.onnx`
+ `tokens.txt` ở local — file `.onnx` đó chỉ để đối chiếu/kiểm tra, đừng
commit nó; bản dùng để build là file trên Drive ở bước 2.)

## Cấu trúc

```
mat-that-tts-android/
├── README.md                     (file này)
├── .gitignore
├── settings.gradle / build.gradle / gradle.properties
├── app/
│   ├── build.gradle              (tải AAR + model + espeak-ng-data khi build)
│   ├── src/main/AndroidManifest.xml
│   ├── src/main/java/vn/matthat/tts/
│   │   ├── MainActivity.kt       (giao diện 3 màn hình + luồng đọc/lưu)
│   │   ├── TtsEngine.kt          (bọc sherpa-onnx OfflineTts)
│   │   ├── TextUtils.kt          (chia đoạn, nghỉ dấu câu, đọc SRT — port từ bản Windows)
│   │   ├── AppSettings.kt        (cài đặt, lưu SharedPreferences)
│   │   └── AudioFileWriter.kt    (ghi WAV + mã hóa AAC/M4A)
│   ├── src/main/res/             (giao diện, icon vector, màu)
│   └── src/main/assets/piper/    (chỉ tokens.txt trong git; model +
│                                  espeak-ng-data tải về lúc build)
├── models/                       (file .onnx.json gốc để đối chiếu)
├── tools/convert_model.py        (đổi giọng mới)
└── .github/workflows/build-apk.yml
```

## Điểm chưa chắc chắn (nói thẳng)

- **Chưa test trên máy thật**: code đã rà soát kỹ theo đúng API mẫu của
  upstream + đã kiểm chứng bộ model bằng sherpa-onnx Python, nhưng chưa
  build/install thử trên điện thoại — đã thử build local trên máy này
  (đã cài JDK 17 + Android SDK 34 + Gradle 8.7) nhưng Gradle daemon
  không giao tiếp được qua socket localhost trong môi trường máy ảo này
  (daemon khởi động và lắng nghe bình thường, client đọc được dữ liệu
  rác — đã thử nhiều cấu hình, không sửa được). Lần build đầu trên
  GitHub Actions (môi trường chuẩn) là phép thử thật — nếu lòi lỗi nhỏ
  (vd: tên dependency, quyền file) thì sửa rồi build lại là xong.
- **APK nặng ~120 MB**: model 60 MB + espeak-ng-data 19 MB + thư viện
  sherpa-onnx ~40 MB (gồm cả 3 kiến trúc CPU arm64/x86_64/armv7).
  Cài lần đầu hơi lâu, dùng bình thường.
- **Tốc độ đọc trên điện thoại chưa đo**: trên máy tính 1 đoạn truyện
  23 phút đọc mất ~3 phút; trên điện thoại (CPU yếu hơn) có thể chậm hơn
  vài lần — vẫn xong trong lúc chờ, app có thanh tiến độ + nút Dừng.
- **Định dạng mặc định là M4A** (AAC): file nhỏ, nghe được trên mọi máy.
  Nếu cần MP3 như bản Windows thì đổi sang WAV trong Cài đặt rồi dùng app
  khác chuyển (Android không có sẵn bộ mã hóa MP3).
