const express = require('express');
const http = require('http');
const { WebSocketServer } = require('ws');
const store = require('./store');

const PORT = process.env.PORT || 8080;
const ADMIN_KEY = process.env.ADMIN_KEY || 'troque-esta-chave-admin';

const app = express();
app.use(express.json());
const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws' });

// deviceId -> Set of open sockets
const connections = new Map();

function sendToDevice(deviceId, payload) {
  const sockets = connections.get(deviceId);
  if (!sockets) return false;
  let sent = false;
  for (const ws of sockets) {
    if (ws.readyState === ws.OPEN) {
      ws.send(JSON.stringify(payload));
      sent = true;
    }
  }
  return sent;
}

// ---------- WebSocket: o app Android conecta aqui para receber em tempo real ----------
// (o handshake do WebSocket é sempre feito via GET por especificação do protocolo,
// isso não muda mesmo com o resto da API em POST)
wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://localhost');
  const deviceId = url.searchParams.get('deviceId');
  const key = url.searchParams.get('key');

  if (!deviceId || !store.isValidKey(deviceId, key)) {
    ws.close(4001, 'unauthorized');
    return;
  }

  if (!connections.has(deviceId)) connections.set(deviceId, new Set());
  connections.get(deviceId).add(ws);
  console.log(`[ws] dispositivo conectado: ${deviceId}`);

  const pending = store.listNotifications(deviceId, 'pending').filter(n => !n.delivered);
  for (const n of pending) {
    ws.send(JSON.stringify({ type: 'notification', ...n }));
  }

  ws.on('message', (raw) => {
    try {
      const msg = JSON.parse(raw.toString());
      if (msg.type === 'ack' && msg.id) {
        store.markDelivered(deviceId, msg.id);
      }
    } catch (_) {}
  });

  ws.on('close', () => {
    connections.get(deviceId)?.delete(ws);
    console.log(`[ws] dispositivo desconectado: ${deviceId}`);
  });
});

// ---------- HTTP: tudo via POST (corpo em JSON) ----------

app.get('/health', (req, res) => res.json({ ok: true }));

function auth(req, res) {
  const { key, deviceId } = req.body || {};
  if (!deviceId || !store.isValidKey(deviceId, key)) {
    res.status(401).json({ error: 'deviceId ou key inválidos' });
    return null;
  }
  return deviceId;
}

// Cadastrar um novo dispositivo/app. Requer a chave de administrador.
// POST /register { adminKey, deviceId, name }
app.post('/register', (req, res) => {
  const { adminKey, deviceId, name } = req.body || {};
  if (adminKey !== ADMIN_KEY) {
    return res.status(401).json({ error: 'adminKey inválida' });
  }
  if (!deviceId) {
    return res.status(400).json({ error: 'deviceId é obrigatório' });
  }
  const device = store.registerDevice(deviceId, name);
  res.json({ deviceId, apiKey: device.apiKey, name: device.name });
});

// Enviar notificação (usado pelas suas outras aplicações).
// POST /notify { key, deviceId, title, message, reason, app, link }
app.post('/notify', (req, res) => {
  const deviceId = auth(req, res);
  if (!deviceId) return;
  const { title, message, reason, app: appName, link } = req.body || {};
  const notif = store.addNotification(deviceId, { title, message, reason, app: appName, link });
  const delivered = sendToDevice(deviceId, { type: 'notification', ...notif });
  res.json({ ok: true, delivered, notification: notif });
});

// Listar notificações de um dispositivo. status opcional: pending | done | archived
// POST /list { key, deviceId, status }
app.post('/list', (req, res) => {
  const deviceId = auth(req, res);
  if (!deviceId) return;
  const { status } = req.body || {};
  res.json({ notifications: store.listNotifications(deviceId, status) });
});

// Marcar notificação como concluída.
// POST /complete { key, deviceId, id }
app.post('/complete', (req, res) => {
  const deviceId = auth(req, res);
  if (!deviceId) return;
  const { id } = req.body || {};
  if (!id) return res.status(400).json({ error: 'id é obrigatório' });
  const ok = store.setStatus(deviceId, id, 'done');
  res.json({ ok });
});

// Mover notificação pra outro lugar (ex: arquivada). status: done | pending | archived
// POST /move { key, deviceId, id, status }
app.post('/move', (req, res) => {
  const deviceId = auth(req, res);
  if (!deviceId) return;
  const { id, status } = req.body || {};
  if (!id || !status) return res.status(400).json({ error: 'id e status são obrigatórios' });
  const ok = store.setStatus(deviceId, id, status);
  res.json({ ok });
});

// Remover uma notificação (ou todas, com id="all").
// POST /delete { key, deviceId, id }
app.post('/delete', (req, res) => {
  const deviceId = auth(req, res);
  if (!deviceId) return;
  const { id } = req.body || {};
  if (!id) return res.status(400).json({ error: 'id é obrigatório (ou "all")' });
  const ok = store.deleteNotification(deviceId, id);
  sendToDevice(deviceId, { type: 'delete', id });
  res.json({ ok });
});

// Confirmar entrega/leitura (também pode ser feito via WebSocket).
// POST /ack { key, deviceId, id }
app.post('/ack', (req, res) => {
  const deviceId = auth(req, res);
  if (!deviceId) return;
  const { id } = req.body || {};
  store.markDelivered(deviceId, id);
  res.json({ ok: true });
});

server.listen(PORT, () => {
  console.log(`GreenNotify server rodando em http://0.0.0.0:${PORT} (HTTP puro, sem HTTPS)`);
  console.log(`ADMIN_KEY atual: ${ADMIN_KEY} (defina a variável de ambiente ADMIN_KEY em produção)`);
});
