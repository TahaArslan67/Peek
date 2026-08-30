# Peek — Android Kamera Paylaşım Uygulaması

İki kişinin (ör. çiftler) telefonlarını normal kullanırken (WhatsApp,
Instagram vb.) birbirlerinin kamera görüntüsünü ekranda küçük yüzen
bir pencerede canlı görmesini sağlayan Android uygulaması.

## Nasıl Çalışır?

1. **Eşleşme:** Bir kullanıcı 6 haneli kod üretir, diğerine söyler.
   Diğer kullanıcı kodu girer. İki cihaz aynı signaling odasına düşer.
2. **Görüntü İsteği:** Eşleşince kamera KAPALI kalır. Görüntü isteğe bağlı:
   - "Görüntü İste" butonuna bas → karşı tarafa bildirim gider
   - Karşı taraf "Kabul" der → kamerası açılır, görüntü gelir
   - "Reddet" der → istek reddedilir
3. **Overlay Pencere:** Görüntü gelince "Overlay'da Göster" → yüzen pencere
   ekranda görünür. WhatsApp'a geçseniz bile pencere kalır.
   - Sürükle: pencereyi taşı
   - Çift tık: boyut değiştir (Küçük/Orta/Büyük)
   - % butonu: şeffaflık (%100/%70/%40)
   - X: kapat
4. **Görüntüyü Durdur:** "Görüntüyü Durdur" → kamera kapanır, eşleşme korunur.
   Tekrar istek gönderebilirsiniz.
5. **Bağlantıyı Kes:** Eşleşmeyi tamamen koparır.

## Özellikler

- ✅ WebRTC peer-to-peer (düşük gecikme, sunucu sadece signaling)
- ✅ Yüzen overlay pencere (SYSTEM_ALERT_WINDOW)
- ✅ Foreground service (Activity kapansa bile overlay devam eder)
- ✅ Görüntü istek bildirimi (Kabul/Reddet action butonları)
- ✅ Ön/arka kamera geçişi
- ✅ İsteğe bağlı sesli sohbet
- ✅ Karanlık mod (Sistem/Açık/Koyu)
- ✅ Overlay pencere özelleştirme (boyut, şeffaflık, konum hatırlama)
- ✅ Eşleşme kodu ile tek kişi eşleşme
- ✅ Android 12+ (API 31+)

## Teknolojiler

| Katman | Teknoloji |
|--------|-----------|
| Dil | Kotlin |
| WebRTC | `io.getstream:stream-webrtc-android:1.1.0` |
| Signaling | Node.js + Express + `ws` |
| WebSocket client | OkHttp 4.12.0 |
| JSON | Gson 2.11.0 |
| Ayarlar | AndroidX DataStore Preferences |
| Asenkron | Kotlin Coroutines |
| UI | AndroidX AppCompat + Material Components |
| Min SDK | 31 (Android 12) |
| Target SDK | 34 (Android 14) |
| Build | Gradle 8.13 (Kotlin DSL) + JDK 17 |

## Repo Yapısı

```
Peek/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/peek/app/
│       │   ├── PeekApplication.kt
│       │   ├── PeekSessionManager.kt
│       │   ├── camera/CameraCapturerManager.kt
│       │   ├── data/
│       │   │   ├── AppPreferences.kt
│       │   │   └── models/{Session,SignalingMessage}.kt
│       │   ├── notifications/
│       │   │   ├── NotificationHelper.kt
│       │   │   └── ViewRequestActionReceiver.kt
│       │   ├── overlay/OverlayWindowService.kt
│       │   ├── pairing/PairingManager.kt
│       │   ├── permissions/PermissionManager.kt
│       │   ├── ui/
│       │   │   ├── MainActivity.kt
│       │   │   └── settings/SettingsActivity.kt
│       │   └── webrtc/
│       │       ├── ConnectionManager.kt
│       │       ├── PeerConnectionObserver.kt
│       │       ├── SignalingClient.kt
│       │       └── WebRtcClient.kt
│       └── res/
│           ├── drawable/{splash_background,status_badge}.xml
│           ├── layout/{activity_main,activity_settings}.xml
│           ├── menu/main.xml
│           └── values{,-night}/{colors,strings,themes}.xml
├── server/
│   ├── index.js
│   ├── rooms.js
│   ├── package.json
│   ├── test_signaling.js
│   ├── render.yaml
│   ├── .env.example
│   └── README.md
├── README.md
└── gradlew.bat
```

## Backend

### Lokalde Çalıştırma

```bash
cd server
npm install
npm start
```

Sunucu `http://localhost:8080` üzerinde açılır:
- Health: `GET http://localhost:8080/health`
- WebSocket: `ws://localhost:8080/ws`

Portu değiştirmek için: `PORT=3000 npm start`

### Test

```bash
cd server
node test_signaling.js
# 18 passed, 0 failed
```

Test kapsamı: `join`, `peer-status`, `offer`/`answer`/`ice` relay,
`view-request`/`view-accept`/`view-reject`/`view-stop` relay, dolu oda
kontrolü, odasız relay engeli, geçersiz JSON, bilinmeyen tip, ani
bağlantı kopmasında oda temizliği.

### Render.com Deploy

1. Bu repo'yu GitHub'a pushlayın.
2. Render dashboard'ta **New > Web Service** seçin.
3. Repository olarak GitHub repo'nuzu bağlayın.
4. `render.yaml` otomatik algılanır; ayarlar:
   - Runtime: Node
   - Build: `npm install`
   - Start: `npm start`
   - Health check: `/health`
5. Deploy edin. Render size `https://peek-signaling.onrender.com` benzeri
   bir URL verecek. WebSocket adresi: `wss://peek-signaling.onrender.com/ws`.

> Not: Render free planında service bir süre idle olursa uyur; ilk istek
> tekrar uyandırır (~30 sn gecikme). Production için starter plan önerilir.

## Android

### Build

```bash
# Debug APK
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release APK (ProGuard ile)
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release-unsigned.apk
```

Build için **JDK 17 (Temurin)** + Gradle 8.13 gerekir. Android Studio'nun
JBR'i Java 25 ise Gradle 8.13 onu desteklemeyebilir — JDK 17 kullanın.

### Emülatör Testi

1. `cd server && npm start` (signaling sunucusu)
2. İki emülatör aç (API 31+)
3. Ayarlar → Signaling URL: `ws://10.0.2.2:8080` (emülatör→host)
4. Emülatör 1: "Kod Üret" → kodu not al
5. Emülatör 2: kodu gir → "Bağlan"
6. "Görüntü İste" → diğer emülatörde bildirim/dialog → "Kabul"
7. Görüntü gelince "Overlay'da Göster"

### Gerçek Cihaz Testi

1. Signaling sunucusunu Render'a deploy et (veya LAN'da çalıştır)
2. Ayarlar → Signaling URL: `wss://<render-url>.onrender.com/ws`
   (veya `ws://<bilgisayar-LAN-IP>:8080`)
3. İki telefona APK yükle
4. Overlay izni: Ayarlar → "Diğer izinler" → "Görüntü diğer uygulamaların
   üzerinde göster" → izin ver

### İzinler

| İzin | Amaç |
|------|------|
| `INTERNET` | Signaling WebSocket + WebRTC |
| `ACCESS_NETWORK_STATE` | Ağ durumu |
| `CAMERA` | Kamera görüntüsü |
| `RECORD_AUDIO` | Sesli sohbet |
| `FOREGROUND_SERVICE` | Overlay service (arka planda çalışma) |
| `FOREGROUND_SERVICE_CAMERA` | Android 14+ kamera service tipi |
| `POST_NOTIFICATIONS` | Android 13+ bildirim izni |
| `SYSTEM_ALERT_WINDOW` | Diğer uygulamalar üzerinde yüzen pencere |
| `WAKE_LOCK` | Aktif paylaşım sırasında cihaz uyanık kalsın |
| `VIBRATE` | Görüntü istek bildirimi titreşimi |

### Ayarlar

- **Bağlantı:** Signaling URL, TURN URL/kullanıcı/şifre, kamera tercihi
- **Görünüm:** Karanlık mod (Sistem/Açık/Koyu), durum göstergesi
- **Overlay Pencere:** Varsayılan boyut (Küçük/Orta/Büyük), şeffaflık (0-100%)

### TURN Sunucu (Opsiyonel)

WebRTC peer-to-peer çalışır ama bazı ağlarda (symmetric NAT,
carrier-grade NAT, kurumsal WiFi) doğrudan bağlantı kurulamaz. TURN relay
bu durumlarda görüntüyü yönlendirir.

Ücretsiz TURN seçenekleri:
- **metered.ca:** Ücretsiz tier, 1GB/ay. `turn:turn.metered.ca:80`
- **OpenRelay:** Açık TURN servisi

Ayarlar'dan TURN URL, kullanıcı adı, şifre girin. Boş bırakılırsa sadece
STUN kullanılır (bazı ağlarda çalışmayabilir).

## Bilinen Sınırlamalar

- Render free plan: 15 dk idle sonra sleep, ilk istek uyandırır (~30 sn gecikme)
- TURN olmadan bazı ağlarda bağlantı kurulamayabilir
- Tek eşleşme (bir kişiyle) — çoklu kişi desteklenmez
- Görüntü kaydı yok (tasarım kararı — gizlilik)
- Sadece Android (iOS desteklenmez)

## Gelecek Geliştirmeler

- Çoklu kişi (arkadaş listesi)
- Görüntü kalitesi ayarı (çözünürlük/fps)
- Bağlantı kalitesi göstergesi
- Widget (hızlı görüntü iste)
- iOS desteği
- Kendi TURN sunucusu (coturn VPS)

## Mimari

```
┌─────────────┐     WebSocket      ┌─────────────┐
│  Cihaz A    │◄──────────────────►│  Cihaz B    │
│ (Android)   │   signaling        │ (Android)   │
│             │                    │             │
│ WebRtcClient│◄────WebRTC────────►│ WebRtcClient│
│ (P2P video) │   direct/TURN      │ (P2P video) │
│             │                    │             │
│ Overlay     │                    │ Kamera      │
│ (floating)  │                    │ (sender)    │
└─────────────┘                    └─────────────┘
       ▲                                   ▲
       │                                   │
       └───────── Signaling Server ────────┘
                  (Render.com)
                  Node.js + ws
```

### Katmanlar

| Dosya | Sorumluluk |
|-------|------------|
| `PeekApplication` | EglBase, PeerConnectionFactory, AppPreferences, darkMode |
| `PeekSessionManager` | Singleton, ConnectionManager sarmalayıcı, ViewState |
| `ConnectionManager` | Signaling + WebRTC orchestrator, görüntü istek |
| `WebRtcClient` | PeerConnection, offer/answer/ICE, track yönetimi |
| `SignalingClient` | WebSocket, mesaj gönderme |
| `CameraCapturerManager` | Camera2 → VideoTrack |
| `OverlayWindowService` | Foreground service, WindowManager overlay |
| `NotificationHelper` | Foreground + görüntü istek bildirimi |
| `ViewRequestActionReceiver` | Bildirim action'ları (Kabul/Reddet) |
| `PermissionManager` | Runtime + overlay izinleri |
| `PairingManager` | Eşleşme kodu üretim/doğrulama |
| `AppPreferences` | DataStore (tüm ayarlar) |
| `MainActivity` | Ana UI, eşleşme, görüntü istek, overlay kontrol |
| `SettingsActivity` | Ayarlar UI |

## Lisans

Kişisel kullanım. Üçüncü parti kütüphaneler kendi lisanslarına tabidir.
