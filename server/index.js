'use strict';

/**
 * Peek signaling sunucusu.
 *
 * Express + ws ile:
 *  - GET /health : sağlık kontrolü
 *  - WebSocket   : signaling mesajlaşması
 *
 * Mesaj tipleri:
 *  - join          : { type, roomCode }                      -> odaya katıl
 *  - offer         : { type, sdp }                           -> diğer peere relay
 *  - answer        : { type, sdp }                           -> diğer peere relay
 *  - ice-candidate : { type, candidate, sdpMid, sdpMLineIndex } -> diğer peere relay
 *  - leave         : { type }                                -> odadan çık
 *  - peer-status   : { type, status }                        -> (server -> client)
 *  - view-request  : { type }                                -> görüntü iste (relay)
 *  - view-accept   : { type }                                -> isteği kabul (relay)
 *  - view-reject   : { type }                                -> isteği reddet (relay)
 *  - view-stop     : { type }                                -> görüntüyü durdur (relay)
 *
 * Render.com için port process.env.PORT'tan alınır.
 */

const express = require('express');
const http = require('http');
const { WebSocketServer } = require('ws');
const path = require('path');

const {
  joinRoom,
  leaveRoom,
  getRoom,
  broadcastToOthers,
  send,
} = require('./rooms');

const PORT = process.env.PORT || 8080;

const app = express();

// Sağlık kontrolü
app.get('/health', (req, res) => {
  res.json({ status: 'ok', uptime: process.uptime() });
});

// Basit ana sayfa (browser ile açılınca bilgi verir)
app.get('/', (req, res) => {
  res.type('text/plain').send(
    'Peek signaling sunucusu calisiyor.\n' +
    'WebSocket: ws://<host>/ws\n' +
    'Health:    GET /health\n'
  );
});

const server = http.createServer(app);

// WebSocket sunucusu (HTTP server üzerinde)
const wss = new WebSocketServer({ server, path: '/ws' });

wss.on('connection', (ws, req) => {
  const ip = req.socket.remoteAddress;
  console.log(`[ws] yeni bağlantı: ${ip}`);

  ws.on('message', (raw) => {
    let data;
    try {
      data = JSON.parse(raw.toString());
    } catch (e) {
      send(ws, { type: 'error', message: 'Geçersiz JSON.' });
      return;
    }

    switch (data.type) {
      case 'join': {
        const roomCode = (data.roomCode || '').toString().trim();
        if (!roomCode) {
          send(ws, { type: 'error', message: 'roomCode gerekli.' });
          return;
        }
        joinRoom(ws, roomCode);
        break;
      }

      case 'offer':
      case 'answer':
      case 'ice-candidate':
      case 'view-request':
      case 'view-accept':
      case 'view-reject':
      case 'view-stop': {
        // Bu mesajları odadaki diğer peere olduğu gibi relay et
        if (!getRoom(ws)) {
          send(ws, { type: 'error', message: 'Önce bir odaya katılın (join).' });
          return;
        }
        broadcastToOthers(ws, peerRoomCode(ws), data);
        break;
      }

      case 'leave': {
        leaveRoom(ws);
        send(ws, { type: 'left' });
        break;
      }

      default:
        send(ws, { type: 'error', message: `Bilinmeyen mesaj tipi: ${data.type}` });
    }
  });

  ws.on('close', () => {
    console.log(`[ws] bağlantı kapandı: ${ip}`);
    leaveRoom(ws);
  });

  ws.on('error', (err) => {
    console.error(`[ws] hata: ${err.message}`);
    leaveRoom(ws);
  });
});

// peerRoomCode yardımcı (rooms.js'teki peerRoom map'inden)
const { peerRoom } = require('./rooms');
function peerRoomCode(ws) {
  return peerRoom.get(ws);
}

server.listen(PORT, () => {
  console.log(`Peek signaling sunucusu ${PORT} portunda dinliyor`);
  console.log(`  Health: http://localhost:${PORT}/health`);
  console.log(`  WebSocket: ws://localhost:${PORT}/ws`);
});

// Temiz kapanış
process.on('SIGTERM', () => {
  console.log('SIGTERM alındı, kapatılıyor...');
  wss.close(() => server.close(() => process.exit(0)));
});
process.on('SIGINT', () => {
  console.log('SIGINT alındı, kapatılıyor...');
  wss.close(() => server.close(() => process.exit(0)));
});
