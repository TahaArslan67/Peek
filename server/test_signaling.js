'use strict';

/**
 * Lokal integration test: iki WebSocket peer aynı odaya katılır,
 * signaling akışını (join -> peer-status -> offer -> answer -> ice -> leave)
 * doğrular. Çalıştırma: node test_signaling.js
 */

const WebSocket = require('ws');

const URL = 'ws://localhost:8080/ws';
let passed = 0;
let failed = 0;

function assert(cond, msg) {
  if (cond) {
    console.log(`  PASS: ${msg}`);
    passed++;
  } else {
    console.log(`  FAIL: ${msg}`);
    failed++;
  }
}

function openPeer(name) {
  const ws = new WebSocket(URL);
  return new Promise((resolve, reject) => {
    ws.on('open', () => {
      console.log(`[${name}] bağlandı`);
      resolve(ws);
    });
    ws.on('error', reject);
  });
}

function recv(ws, timeoutMs = 2000) {
  return new Promise((resolve, reject) => {
    const to = setTimeout(() => reject(new Error('timeout')), timeoutMs);
    ws.once('message', (raw) => {
      clearTimeout(to);
      resolve(JSON.parse(raw.toString()));
    });
  });
}

function send(ws, obj) {
  ws.send(JSON.stringify(obj));
}

(async () => {
  const a = await openPeer('A');
  const b = await openPeer('B');

  // 1) A odaya katılır
  send(a, { type: 'join', roomCode: '123456' });
  const aJoined = await recv(a);
  assert(aJoined.type === 'joined' && aJoined.roomCode === '123456', 'A joined onayı');
  assert(aJoined.peerCount === 1, 'A odaya ilk kişi olarak girdi');

  // 2) B aynı odaya katılır -> A peer-status:joined almalı
  send(b, { type: 'join', roomCode: '123456' });
  const bJoined = await recv(b);
  assert(bJoined.type === 'joined' && bJoined.peerCount === 2, 'B joined, peerCount=2');
  const aPeerJoined = await recv(a);
  assert(aPeerJoined.type === 'peer-status' && aPeerJoined.status === 'joined', 'A peer-status:joined aldı');

  // 3) A offer gönderir -> B almalı
  send(a, { type: 'offer', sdp: 'FAKE_SDP_OFFER' });
  const bOffer = await recv(b);
  assert(bOffer.type === 'offer' && bOffer.sdp === 'FAKE_SDP_OFFER', 'B offer aldı (relay)');

  // 4) B answer gönderir -> A almalı
  send(b, { type: 'answer', sdp: 'FAKE_SDP_ANSWER' });
  const aAnswer = await recv(a);
  assert(aAnswer.type === 'answer' && aAnswer.sdp === 'FAKE_SDP_ANSWER', 'A answer aldı (relay)');

  // 5) A ICE candidate gönderir -> B almalı
  send(a, { type: 'ice-candidate', candidate: 'FAKE_ICE', sdpMid: 'audio', sdpMLineIndex: 0 });
  const bIce = await recv(b);
  assert(bIce.type === 'ice-candidate' && bIce.candidate === 'FAKE_ICE', 'B ice-candidate aldı (relay)');

  // 6) B leave eder -> B 'left' alır, A peer-status:left alır
  //    (her iki receiver'ı da mesaj gönderilmeden önce kur)
  const bLeftP = recv(b);
  const aPeerLeftP = recv(a);
  send(b, { type: 'leave' });
  const bLeft = await bLeftP;
  assert(bLeft.type === 'left', 'B left onayı aldı');
  const aPeerLeft = await aPeerLeftP;
  assert(aPeerLeft.type === 'peer-status' && aPeerLeft.status === 'left', 'A peer-status:left aldı');

  // 7) Üçüncü kişi dolu odaya giremez
  const c = await openPeer('C');
  send(c, { type: 'join', roomCode: '999999' });
  const c1 = await recv(c);
  send(c, { type: 'join', roomCode: '999999' }); // ikinci katılım (aynı peer) - oda dolmadan
  // Üçüncü farklı peer
  const d = await openPeer('D');
  send(d, { type: 'join', roomCode: '999999' });
  // C ve D odaya girdi (2 kişi). Şimdi E katılırsa dolu hatası almalı.
  const e = await openPeer('E');
  send(e, { type: 'join', roomCode: '999999' });
  const eMsg = await recv(e);
  assert(eMsg.type === 'error', '3. kişi dolu odaya giremez (error)');

  // 8) Odaya katılmadan offer relay edilemez
  const f = await openPeer('F');
  send(f, { type: 'offer', sdp: 'NO_ROOM' });
  const fMsg = await recv(f);
  assert(fMsg.type === 'error', 'Odasız offer -> error');

  // 9) Geçersiz JSON
  f.send('{not json');
  const fErr = await recv(f);
  assert(fErr.type === 'error', 'Geçersiz JSON -> error');

  // 10) Bilinmeyen tip
  send(f, JSON.stringify({ type: 'unknown' }));
  const fUnknown = await recv(f);
  assert(fUnknown.type === 'error', 'Bilinmeyen tip -> error');

  // 11) view-request/accept/reject/stop relay testi
  const g = await openPeer('G');
  const h = await openPeer('H');
  send(g, { type: 'join', roomCode: 'VIEW01' });
  await recv(g); // joined
  send(h, { type: 'join', roomCode: 'VIEW01' });
  await recv(h); // joined
  await recv(g); // peer-status:joined

  // G -> H view-request
  send(g, { type: 'view-request' });
  const hReq = await recv(h);
  assert(hReq.type === 'view-request', 'H view-request aldı (relay)');

  // H -> G view-accept
  send(h, { type: 'view-accept' });
  const gAccept = await recv(g);
  assert(gAccept.type === 'view-accept', 'G view-accept aldı (relay)');

  // G -> H view-stop
  send(g, { type: 'view-stop' });
  const hStop = await recv(h);
  assert(hStop.type === 'view-stop', 'H view-stop aldı (relay)');

  // H -> G view-reject (yeni istek simülasyonu)
  send(g, { type: 'view-request' });
  await recv(h); // h view-request
  send(h, { type: 'view-reject' });
  const gReject = await recv(g);
  assert(gReject.type === 'view-reject', 'G view-reject aldı (relay)');

  // 12) Odasız view-request -> error
  const i = await openPeer('I');
  send(i, { type: 'view-request' });
  const iErr = await recv(i);
  assert(iErr.type === 'error', 'Odasız view-request -> error');

  a.close(); b.close(); c.close(); d.close(); e.close(); f.close();
  g.close(); h.close(); i.close();

  console.log(`\nSonuç: ${passed} passed, ${failed} failed`);
  process.exit(failed === 0 ? 0 : 1);
})().catch((e) => {
  console.error('Test hatası:', e.message);
  process.exit(1);
});
