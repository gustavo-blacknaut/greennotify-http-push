const express = require('express');
const http = require('http');
const { WebSocketServer } = require('ws');
const store = require('./store');

const PORT = process.env.PORT || 8080;
const ADMIN_KEY = process.env.ADMIN_KEY || 'troque-esta-chave-admin';

const app = express();
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

  // Envia notificações pendentes (ainda não entregues) assim que conectar
  const pending = store.listNotifications(deviceId).filter(n => !n.delivered);
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

// ---------- HTTP (tudo via GET, propositalmente, conforme solicitado) ----------

app.get('/health', (req, res) => res.json({ ok: true }));

// Cadastrar um novo dispositivo/app. Requer a chave de administrador.
// GET /register?adminKey=...&deviceId=meu-celular&name=Pixel%208
app.get('/register', (req, res) => {
  const { adminKey, deviceId, name } = req.query;
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
// GET /notify?key=API_KEY&deviceId=meu-celular&title=Servidor+caiu&message=CPU+em+100%25&reason=Alerta+de+monitoramento&app=Zabbix
app.get('/notify', (req, res) => {
  const { key, deviceId, title, message, reason, app: appName } = req.query;
  if (!deviceId || !store.isValidKey(deviceId, key)) {
    return res.status(401).json({ error: 'deviceId ou key inválidos' });
  }
  const notif = store.addNotification(deviceId, { title, message, reason, app: appName });
  const delivered = sendToDevice(deviceId, { type: 'notification', ...notif });
  res.json({ ok: true, delivered, notification: notif });
});

// Listar notificações de um dispositivo.
// GET /list?key=API_KEY&deviceId=meu-celular
app.get('/list', (req, res) => {
  const { key, deviceId } = req.query;
  if (!deviceId || !store.isValidKey(deviceId, key)) {
    return res.status(401).json({ error: 'deviceId ou key inválidos' });
  }
  res.json({ notifications: store.listNotifications(deviceId) });
});

// Remover uma notificação (ou todas, com id=all).
// GET /delete?key=API_KEY&deviceId=meu-celular&id=abc123
app.get('/delete', (req, res) => {
  const { key, deviceId, id } = req.query;
  if (!deviceId || !store.isValidKey(deviceId, key)) {
    return res.status(401).json({ error: 'deviceId ou key inválidos' });
  }
  if (!id) return res.status(400).json({ error: 'id é obrigatório (ou "all")' });
  const ok = store.deleteNotification(deviceId, id);
  sendToDevice(deviceId, { type: 'delete', id });
  res.json({ ok });
});

// Confirmar entrega/leitura (também pode ser feito via WebSocket).
// GET /ack?key=API_KEY&deviceId=meu-celular&id=abc123
app.get('/ack', (req, res) => {
  const { key, deviceId, id } = req.query;
  if (!deviceId || !store.isValidKey(deviceId, key)) {
    return res.status(401).json({ error: 'deviceId ou key inválidos' });
  }
  store.markDelivered(deviceId, id);
  res.json({ ok: true });
});

server.listen(PORT, () => {
  console.log(`GreenNotify server rodando em http://0.0.0.0:${PORT} (HTTP puro, sem HTTPS)`);
  console.log(`ADMIN_KEY atual: ${ADMIN_KEY} (defina a variável de ambiente ADMIN_KEY em produção)`);
});
