# Peek Signaling Server

Peek Android kamera paylaşım uygulaması için WebRTC signaling sunucusu.
Express + `ws` ile WebSocket üzerinden iki cihazı eşleşme koduyla aynı odaya
sokar ve signaling mesajlarını (offer/answer/ICE, görüntü istek) birbirine
relay eder.

## Mesaj Tipleri

| Yön               | type            | Açıklama |
|-------------------|-----------------|----------|
| client -> server  | `join`          | `{ type, roomCode }` odaya katıl |
| server -> client  | `joined`        | `{ type, roomCode, peerCount }` katılım onayı |
| client -> server  | `offer`         | `{ type, sdp }` diğer peere relay edilir |
| client -> server  | `answer`        | `{ type, sdp }` diğer peere relay edilir |
| client -> server  | `ice-candidate` | `{ type, candidate, sdpMid, sdpMLineIndex }` relay |
| client -> server  | `view-request`  | `{ type }` görüntü iste (relay) |
| client -> server  | `view-accept`   | `{ type }` isteği kabul (relay) |
| client -> server  | `view-reject`   | `{ type }` isteği reddet (relay) |
| client -> server  | `view-stop`     | `{ type }` görüntüyü durdur (relay) |
| client -> server  | `leave`         | `{ type }` odadan çık |
| server -> client  | `peer-status`   | `{ type, status: "joined" \| "left" }` |
| server -> client  | `error`         | `{ type, message }` |
| server -> client  | `left`          | `{ type }` çıkış onayı |

Bir odada maksimum 2 cihaz olabilir. Bağlantı koparsa oda otomatik temizlenir.

`view-*` mesajları (Aşama 4) görüntü istek mekanizması içindir: eşleşince
kamera kapalı kalır, görüntü isteğe bağlıdır. Sunucu bu mesajları sadece
relay eder — istek/cevap mantığı Android client'ta.

## Lokalde Çalıştırma

```bash
cd server
npm install
npm start
```

Sunucu `http://localhost:8080` üzerinde açılır:
- Health: `GET http://localhost:8080/health`
- WebSocket: `ws://localhost:8080/ws`

Portu değiştirmek için: `PORT=3000 npm start`

## Render.com Deploy

1. Bu repo'yu (veya en azından `server/` klasörünü) GitHub'a pushlayın.
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
> tekrar uyandırır. Production için starter plan önerilir.

## Test

```bash
cd server
node test_signaling.js
# 18 passed, 0 failed
```

18 testin kapsamı:

1. A `joined` onayı + peerCount=1
2. B `joined` + peerCount=2
3. A `peer-status:joined` alır
4. B `offer` relay alır
5. A `answer` relay alır
6. B `ice-candidate` relay alır
7. B `left` onayı alır
8. A `peer-status:left` alır
9. 3. kişi dolu odaya giremez (error)
10. Odasız `offer` → error
11. Geçersiz JSON → error
12. Bilinmeyen tip → error
13. H `view-request` relay alır
14. G `view-accept` relay alır
15. H `view-stop` relay alır
16. G `view-reject` relay alır
17. Odasız `view-request` → error
18. (implicit) Ani bağlantı kopmasında oda temizliği

`/health` endpoint'i:

```bash
curl http://localhost:8080/health
# {"status":"ok","uptime":...}
```

WebSocket testi için `wscat`:

```bash
npm install -g wscat
wscat -c ws://localhost:8080/ws
> {"type":"join","roomCode":"123456"}
< {"type":"joined","roomCode":"123456","peerCount":1}
```

İkinci bir terminalde aynı kodla katılınca ilk terminal
`{"type":"peer-status","status":"joined"}` alır.
