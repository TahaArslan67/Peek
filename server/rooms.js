'use strict';

/**
 * Peek signaling sunucusu - oda/eşleşme yönetimi.
 *
 * Bir oda = eşleşme kodu (roomCode).
 * Bir odada maksimum 2 cihaz olabilir (iki kişi).
 * Odadaki cihazlar birbirine signaling mesajlarını relay eder.
 */

const MAX_PEERS_PER_ROOM = 2;

// roomCode -> Set<WebSocket>
const rooms = new Map();

// WebSocket -> { roomCode }  (hızlı erişim için)
const peerRoom = new Map();

function joinRoom(ws, roomCode) {
  let room = rooms.get(roomCode);
  if (!room) {
    room = new Set();
    rooms.set(roomCode, room);
  }

  if (room.size >= MAX_PEERS_PER_ROOM) {
    send(ws, { type: 'error', message: 'Oda dolu (maksimum 2 kişi).' });
    return false;
  }

  room.add(ws);
  peerRoom.set(ws, roomCode);

  // Odaya katılan kişiye onay
  send(ws, { type: 'joined', roomCode, peerCount: room.size });

  // Odada zaten bir kişi varsa, ona "peer katıldı" bildir
  if (room.size === 2) {
    broadcastToOthers(ws, roomCode, { type: 'peer-status', status: 'joined' });
  }

  console.log(`[rooms] ${roomCode}: katılım -> toplam ${room.size} kişi`);
  return true;
}

function leaveRoom(ws) {
  const roomCode = peerRoom.get(ws);
  if (!roomCode) return;

  const room = rooms.get(roomCode);
  if (!room) {
    peerRoom.delete(ws);
    return;
  }

  room.delete(ws);
  peerRoom.delete(ws);

  // Kalan kişiye "peer ayrıldı" bildir
  if (room.size > 0) {
    broadcastToOthers(ws, roomCode, { type: 'peer-status', status: 'left' });
  } else {
    // Oda boşaldı, temizle
    rooms.delete(roomCode);
  }

  console.log(`[rooms] ${roomCode}: ayrılma -> kalan ${room.size} kişi`);
}

function getRoom(ws) {
  const roomCode = peerRoom.get(ws);
  if (!roomCode) return null;
  return rooms.get(roomCode) || null;
}

function broadcastToOthers(senderWs, roomCode, message) {
  const room = rooms.get(roomCode);
  if (!room) return;
  for (const peer of room) {
    if (peer !== senderWs && peer.readyState === 1 /* OPEN */) {
      send(peer, message);
    }
  }
}

function send(ws, message) {
  if (ws.readyState === 1 /* OPEN */) {
    ws.send(JSON.stringify(message));
  }
}

module.exports = {
  MAX_PEERS_PER_ROOM,
  rooms,
  peerRoom,
  joinRoom,
  leaveRoom,
  getRoom,
  broadcastToOthers,
  send,
};
