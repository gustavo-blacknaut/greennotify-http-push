const fs = require('fs');
const path = require('path');

// Painéis como o Pterodactyl nem sempre deixam criar variáveis de ambiente: aceita um .env ao lado.
// Variáveis já definidas no ambiente têm prioridade sobre o arquivo.
function loadEnvFile(file) {
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (!m || process.env[m[1]] !== undefined) continue;
    process.env[m[1]] = m[2].replace(/^(['"])(.*)\1$/, '$2');
  }
}
loadEnvFile(path.join(__dirname, '.env'));

const express = require('express');
const http = require('http');
const { WebSocketServer } = require('ws');
const rateLimit = require('express-rate-limit');
const store = require('./store');

// SERVER_PORT é a porta que o Pterodactyl aloca para o servidor.
const PORT = process.env.PORT || process.env.SERVER_PORT || 8080;
const ADMIN_KEY = process.env.ADMIN_KEY;
if (!ADMIN_KEY || ADMIN_KEY.length < 16) {
  console.error('ADMIN_KEY não definida ou curta demais (mínimo 16 caracteres). Defina ADMIN_KEY no ambiente ou no arquivo .env.');
  process.exit(1);
}

const app = express();

// Tudo é gravado e devolvido em UTF-8. Mas nem todo cliente manda UTF-8: o PowerShell 5 e o curl
// no Windows costumam mandar Windows-1252, e "Notificação" viraria "Notifica��o". Se o corpo não
// for UTF-8 válido, decodifica como Windows-1252 antes de ler o JSON.
const utf8 = new TextDecoder('utf-8', { fatal: true });
const win1252 = new TextDecoder('windows-1252');
app.use(express.raw({ type: '*/*', limit: '100kb' }));
app.use((req, res, next) => {
  if (!Buffer.isBuffer(req.body) || req.body.length === 0) {
    req.body = {};
    return next();
  }
  let text;
  try {
    text = utf8.decode(req.body);
  } catch (_) {
    text = win1252.decode(req.body);
  }
  try {
    req.body = JSON.parse(text.replace(/^﻿/, ''));
  } catch (_) {
    return res.status(400).json({ error: 'JSON inválido' });
  }
  next();
});
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

  ws.lastSeen = Date.now();
  const seen = () => { ws.lastSeen = Date.now(); };
  ws.on('pong', seen);
  ws.on('ping', seen);

  if (!connections.has(deviceId)) connections.set(deviceId, new Set());
  connections.get(deviceId).add(ws);
  console.log(`[ws] dispositivo conectado: ${deviceId}`);

  for (const n of store.listUndelivered(deviceId)) {
    ws.send(JSON.stringify({ type: 'notification', ...n }));
  }

  ws.on('message', (raw) => {
    seen();
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

// Celular que some sem fechar o TCP (troca de rede, bateria, reboot) deixa a conexão meio aberta:
// o servidor acharia que ainda entrega. Qualquer sinal do celular (o app pinga a cada 3 min) conta
// como vivo; o servidor só pinga quem ficou calado por HEARTBEAT_MS e derruba quem passou do dobro.
// Pingar todo mundo com frequência acordaria o rádio do celular à toa e gastaria bateria.
const HEARTBEAT_MS = Number(process.env.HEARTBEAT_MS) || 5 * 60 * 1000;
const heartbeat = setInterval(() => {
  const now = Date.now();
  for (const ws of wss.clients) {
    const idle = now - (ws.lastSeen || 0);
    if (idle > 2 * HEARTBEAT_MS) ws.terminate();
    else if (idle >= HEARTBEAT_MS) ws.ping();
  }
}, Math.min(60 * 1000, HEARTBEAT_MS / 2));

// Grava a notificação e manda na hora para o celular, se ele estiver conectado.
function deliver(deviceId, fields) {
  const notif = store.addNotification(deviceId, fields);
  const delivered = sendToDevice(deviceId, { type: 'notification', ...notif });
  return { notif, delivered };
}

// ---------- HTTP: tudo via POST (corpo em JSON) ----------

app.get('/health', (req, res) => res.json({ ok: true }));

const STATUSES = ['pending', 'done', 'archived'];
const MAX_LENGTHS = { title: 200, message: 4000, reason: 1000, app: 100, link: 2000, name: 100, topic: 100, image: 2000, category: 40 };

function validateFields(body) {
  for (const [field, max] of Object.entries(MAX_LENGTHS)) {
    const value = body[field];
    if (value === undefined || value === null) continue;
    if (typeof value !== 'string') return `${field} deve ser texto`;
    if (value.length > max) return `${field} excede ${max} caracteres`;
  }
  if (body.link && !/^https?:\/\//i.test(body.link)) return 'link deve começar com http:// ou https://';
  if (body.image && !/^https?:\/\//i.test(body.image)) return 'image deve começar com http:// ou https://';
  if (body.status !== undefined && !STATUSES.includes(body.status)) {
    return `status deve ser um de: ${STATUSES.join(', ')}`;
  }
  if (body.id !== undefined && typeof body.id !== 'string') return 'id deve ser texto';
  return null;
}

// Conta só 401 (chave errada): erro de validação (400) de um app com bug não bloqueia o IP.
const failedAuthLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 20,
  skipSuccessfulRequests: true,
  requestWasSuccessful: (req, res) => res.statusCode !== 401,
  message: { error: 'muitas tentativas inválidas, tente de novo mais tarde' }
});

const registerLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 10,
  message: { error: 'muitas tentativas de cadastro, tente de novo mais tarde' }
});

function auth(req, res) {
  const body = req.body || {};
  const { key, deviceId } = body;
  if (typeof deviceId !== 'string' || typeof key !== 'string' || !store.isValidKey(deviceId, key)) {
    res.status(401).json({ error: 'deviceId ou key inválidos' });
    return null;
  }
  const invalid = validateFields(body);
  if (invalid) {
    res.status(400).json({ error: invalid });
    return null;
  }
  return deviceId;
}

// Cadastrar um novo dispositivo/app. Requer a chave de administrador.
// POST /register { adminKey, deviceId, name }
app.post('/register', registerLimiter, (req, res) => {
  const { adminKey, deviceId, name } = req.body || {};
  if (typeof adminKey !== 'string' || !store.safeEqual(adminKey, ADMIN_KEY)) {
    return res.status(401).json({ error: 'adminKey inválida' });
  }
  if (typeof deviceId !== 'string' || !deviceId) {
    return res.status(400).json({ error: 'deviceId é obrigatório' });
  }
  const invalid = validateFields({ name });
  if (invalid) return res.status(400).json({ error: invalid });
  const device = store.registerDevice(deviceId, name);
  res.json({ deviceId, apiKey: device.apiKey, name: device.name });
});

app.use(['/notify', '/list', '/complete', '/move', '/delete', '/ack', '/categories', '/heartbeat'], failedAuthLimiter);

// Enviar notificação (usado pelas suas outras aplicações).
// POST /notify { key, deviceId, title, message, reason, app, link, topic, image, category }
app.post('/notify', (req, res) => {
  const deviceId = auth(req, res);
  if (!deviceId) return;
  const { title, message, reason, app: appName, link, topic, image, category } = req.body;
  const { notif, delivered } = deliver(deviceId, { title, message, reason, app: appName, link, topic, image, category });
  res.json({ ok: true, delivered, notification: notif });
});

// Listar notificações de um dispositivo, mais recentes primeiro.
// POST /list { key, deviceId, status?, category?, limit? (padrão 100, máx 500), offset? }
app.post('/list', (req, res) => {
  const deviceId = auth(req, res);
  if (!deviceId) return;
  const { status, category, limit = 100, offset = 0 } = req.body;
  if (!Number.isInteger(limit) || limit < 1 || limit > 500 || !Number.isInteger(offset) || offset < 0) {
    return res.status(400).json({ error: 'limit deve ser inteiro entre 1 e 500, offset inteiro >= 0' });
  }
  res.json({ notifications: store.listNotifications(deviceId, status, limit, offset, category) });
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
  if (ok) sendToDevice(deviceId, { type: 'delete', id });
  res.json({ ok });
});

// ---------- Categorias (até 4 por dispositivo; viram pastas na tela inicial do app) ----------

function categoryResult(res, result) {
  if (result.error) return res.status(result.error.includes('não encontrada') ? 404 : 400).json({ error: result.error });
  res.json({ ok: true, ...result });
}

// POST /categories/list { key, deviceId } -> { categories: [{ id, name, image, pending, total }], max }
app.post('/categories/list', (req, res) => {
  const deviceId = auth(req, res);
  if (!deviceId) return;
  res.json({ categories: store.listCategories(deviceId), max: store.MAX_CATEGORIES });
});

// POST /categories/create { key, deviceId, name, image? }
app.post('/categories/create', (req, res) => {
  const deviceId = auth(req, res);
  if (!deviceId) return;
  const { name, image } = req.body;
  if (typeof name !== 'string' || !name.trim()) return res.status(400).json({ error: 'name é obrigatório' });
  categoryResult(res, store.createCategory(deviceId, { name, image }));
});

// POST /categories/update { key, deviceId, id, name?, image? }
app.post('/categories/update', (req, res) => {
  const deviceId = auth(req, res);
  if (!deviceId) return;
  const { id, name, image } = req.body;
  if (!id) return res.status(400).json({ error: 'id é obrigatório' });
  categoryResult(res, store.updateCategory(deviceId, id, { name, image }));
});

// POST /categories/delete { key, deviceId, id, deleteNotifications? }
app.post('/categories/delete', (req, res) => {
  const deviceId = auth(req, res);
  if (!deviceId) return;
  const { id, deleteNotifications } = req.body;
  if (!id) return res.status(400).json({ error: 'id é obrigatório' });
  categoryResult(res, store.deleteCategory(deviceId, id, deleteNotifications === true));
});

// POST /categories/clear { key, deviceId, id } — apaga as notificações da categoria, mantém a categoria
app.post('/categories/clear', (req, res) => {
  const deviceId = auth(req, res);
  if (!deviceId) return;
  const { id } = req.body;
  if (!id) return res.status(400).json({ error: 'id é obrigatório' });
  categoryResult(res, store.clearCategory(deviceId, id));
});

// ---------- Vigia: avisa no celular quando um serviço (bot, API...) para de dar sinal ----------

function duracao(ms) {
  const min = Math.max(1, Math.round(ms / 60000));
  if (min < 60) return `${min} min`;
  const h = Math.floor(min / 60);
  return h < 24 ? `${h} h ${min % 60} min` : `${Math.floor(h / 24)} d ${h % 24} h`;
}

function hora(ms) {
  return new Date(ms).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit', timeZone: process.env.TZ || 'America/Sao_Paulo' });
}

function avisoVigia(hb, title, message, reason) {
  deliver(hb.deviceId, {
    title, message, reason, app: hb.name, topic: `Status: ${hb.name}`, category: hb.category || '', image: hb.image || '',
  });
}

// POST /heartbeat { key, deviceId, name, interval? (s, 20–3600, padrão 60), stopping?, category?, image?, remove? }
// O serviço chama a cada `interval` segundos. Sem sinal por 2 intervalos + 30 s: "parou de responder".
// stopping: true = desligou de propósito (avisa na hora). remove: true = para de vigiar esse nome.
app.post('/heartbeat', (req, res) => {
  const deviceId = auth(req, res);
  if (!deviceId) return;
  const { name, interval = 60, stopping, category, image, remove } = req.body;
  if (typeof name !== 'string' || !name.trim() || name.length > 100) return res.status(400).json({ error: 'name é obrigatório (até 100 caracteres)' });
  if (!Number.isInteger(interval) || interval < 20 || interval > 3600) return res.status(400).json({ error: 'interval deve ser inteiro entre 20 e 3600 (segundos)' });
  const nome = name.trim();
  if (remove === true) return res.json({ ok: store.deleteHeartbeat(deviceId, nome) });

  const now = Date.now();
  const antes = store.getHeartbeat(deviceId, nome);
  const hb = {
    deviceId, name: nome, intervalMs: interval * 1000, lastSeen: now, down: 0, downSince: null,
    category: category || antes?.category || '', image: image || antes?.image || '',
  };

  if (stopping === true) {
    hb.down = 1; hb.downSince = now;
    store.saveHeartbeat(hb);
    if (!antes?.down) avisoVigia(hb, `⏹️ ${nome} foi desligado`, `Desligou às ${hora(now)}.`, 'Parado pelo painel ou reiniciando');
    return res.json({ ok: true, status: 'stopped' });
  }

  store.saveHeartbeat(hb);
  // Só avisa a volta se antes tinha avisado a queda (ligar normalmente não gera notificação).
  if (antes?.down) avisoVigia(hb, `✅ ${nome} voltou`, `Ficou fora por ${duracao(now - (antes.downSince || antes.lastSeen))}.`, 'Voltou a dar sinal');
  res.json({ ok: true, status: 'up' });
});

store.graceHeartbeats(Date.now());
const vigia = setInterval(() => {
  const now = Date.now();
  for (const hb of store.overdueHeartbeats(now)) {
    store.saveHeartbeat({ ...hb, down: 1, downSince: hb.lastSeen });
    avisoVigia(hb, `🔴 ${hb.name} parou de responder`,
      `Último sinal às ${hora(hb.lastSeen)} (há ${duracao(now - hb.lastSeen)}). Pode ter caído, travado ou a hospedagem desligou.`,
      'Sem sinal de vida');
  }
}, 30 * 1000);

// Confirmar entrega/leitura (também pode ser feito via WebSocket).
// POST /ack { key, deviceId, id }
app.post('/ack', (req, res) => {
  const deviceId = auth(req, res);
  if (!deviceId) return;
  const { id } = req.body;
  if (!id) return res.status(400).json({ error: 'id é obrigatório' });
  store.markDelivered(deviceId, id);
  res.json({ ok: true });
});

app.use((err, req, res, next) => {
  if (err.type === 'entity.parse.failed') {
    return res.status(400).json({ error: 'JSON inválido' });
  }
  console.error(err);
  res.status(err.status || 500).json({ error: 'erro interno' });
});

server.listen(PORT, () => {
  console.log(`GreenNotify server rodando em http://0.0.0.0:${PORT} (HTTP puro, sem HTTPS)`);
});

function shutdown(signal) {
  console.log(`${signal} recebido, encerrando...`);
  clearInterval(heartbeat);
  clearInterval(vigia);
  for (const ws of wss.clients) ws.close(1001, 'server shutting down');
  server.close(() => {
    store.close();
    process.exit(0);
  });
  setTimeout(() => process.exit(1), 5000).unref();
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
