const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const Database = require('better-sqlite3');

const db = new Database(path.join(__dirname, 'data.sqlite'));
db.pragma('journal_mode = WAL');

db.exec(`
  CREATE TABLE IF NOT EXISTS devices (
    deviceId TEXT PRIMARY KEY,
    apiKey TEXT NOT NULL,
    name TEXT,
    createdAt INTEGER NOT NULL
  );

  CREATE TABLE IF NOT EXISTS notifications (
    id TEXT PRIMARY KEY,
    deviceId TEXT NOT NULL,
    title TEXT,
    message TEXT,
    reason TEXT,
    app TEXT,
    link TEXT,
    status TEXT NOT NULL DEFAULT 'pending',
    delivered INTEGER NOT NULL DEFAULT 0,
    createdAt INTEGER NOT NULL
  );

  DROP INDEX IF EXISTS idx_notifications_device;
  CREATE INDEX IF NOT EXISTS idx_notifications_device_status_created
    ON notifications(deviceId, status, createdAt);
`);

function migrateLegacyJson() {
  const legacyFile = path.join(__dirname, 'data.json');
  if (!fs.existsSync(legacyFile)) return;
  const legacy = JSON.parse(fs.readFileSync(legacyFile, 'utf8'));
  const insertDevice = db.prepare(
    'INSERT OR IGNORE INTO devices (deviceId, apiKey, name, createdAt) VALUES (?, ?, ?, ?)'
  );
  const insertNotif = db.prepare(`
    INSERT OR IGNORE INTO notifications (id, deviceId, title, message, reason, app, link, status, delivered, createdAt)
    VALUES (?, ?, ?, ?, ?, ?, '', 'pending', ?, ?)
  `);
  db.transaction(() => {
    for (const [deviceId, d] of Object.entries(legacy.devices || {})) {
      insertDevice.run(deviceId, d.apiKey, d.name || deviceId, d.createdAt || Date.now());
    }
    for (const [deviceId, list] of Object.entries(legacy.notifications || {})) {
      for (const n of list) {
        insertNotif.run(n.id, deviceId, n.title || '', n.message || '', n.reason || '', n.app || '',
          n.delivered ? 1 : 0, n.createdAt || Date.now());
      }
    }
  })();
  fs.renameSync(legacyFile, legacyFile + '.migrated');
  console.log('data.json antigo importado para o SQLite (renomeado para data.json.migrated)');
}

migrateLegacyJson();

function genKey() {
  return crypto.randomBytes(24).toString('hex');
}

function genId() {
  return crypto.randomBytes(8).toString('hex');
}

function registerDevice(deviceId, name) {
  const existing = db.prepare('SELECT * FROM devices WHERE deviceId = ?').get(deviceId);
  if (existing) return existing;
  const device = { deviceId, apiKey: genKey(), name: name || deviceId, createdAt: Date.now() };
  db.prepare('INSERT INTO devices (deviceId, apiKey, name, createdAt) VALUES (@deviceId, @apiKey, @name, @createdAt)')
    .run(device);
  return device;
}

function getDevice(deviceId) {
  return db.prepare('SELECT * FROM devices WHERE deviceId = ?').get(deviceId);
}

function safeEqual(a, b) {
  const bufA = Buffer.from(String(a));
  const bufB = Buffer.from(String(b));
  return bufA.length === bufB.length && crypto.timingSafeEqual(bufA, bufB);
}

function isValidKey(deviceId, key) {
  const d = getDevice(deviceId);
  return !!d && !!key && safeEqual(d.apiKey, key);
}

function rowToNotification(row) {
  if (!row) return row;
  return { ...row, delivered: !!row.delivered };
}

function addNotification(deviceId, { title, message, reason, app, link }) {
  const notif = {
    id: genId(),
    deviceId,
    title: title || 'Notificação',
    message: message || '',
    reason: reason || '',
    app: app || 'desconhecido',
    link: link || '',
    status: 'pending',
    delivered: 0,
    createdAt: Date.now()
  };
  db.prepare(`
    INSERT INTO notifications (id, deviceId, title, message, reason, app, link, status, delivered, createdAt)
    VALUES (@id, @deviceId, @title, @message, @reason, @app, @link, @status, @delivered, @createdAt)
  `).run(notif);
  return rowToNotification(notif);
}

function listNotifications(deviceId, status, limit = 100, offset = 0) {
  const rows = status
    ? db.prepare('SELECT * FROM notifications WHERE deviceId = ? AND status = ? ORDER BY createdAt DESC LIMIT ? OFFSET ?')
        .all(deviceId, status, limit, offset)
    : db.prepare('SELECT * FROM notifications WHERE deviceId = ? ORDER BY createdAt DESC LIMIT ? OFFSET ?')
        .all(deviceId, limit, offset);
  return rows.map(rowToNotification);
}

function listUndelivered(deviceId) {
  return db.prepare(
    "SELECT * FROM notifications WHERE deviceId = ? AND status = 'pending' AND delivered = 0 ORDER BY createdAt ASC"
  ).all(deviceId).map(rowToNotification);
}

function getNotification(deviceId, id) {
  return rowToNotification(
    db.prepare('SELECT * FROM notifications WHERE deviceId = ? AND id = ?').get(deviceId, id)
  );
}

function setStatus(deviceId, id, status) {
  const info = db.prepare('UPDATE notifications SET status = ? WHERE deviceId = ? AND id = ?').run(status, deviceId, id);
  return info.changes > 0;
}

function deleteNotification(deviceId, id) {
  if (id === 'all') {
    const info = db.prepare('DELETE FROM notifications WHERE deviceId = ?').run(deviceId);
    return info.changes > 0;
  }
  const info = db.prepare('DELETE FROM notifications WHERE deviceId = ? AND id = ?').run(deviceId, id);
  return info.changes > 0;
}

function markDelivered(deviceId, id) {
  db.prepare('UPDATE notifications SET delivered = 1 WHERE deviceId = ? AND id = ?').run(deviceId, id);
}

module.exports = {
  close: () => db.close(),
  safeEqual,
  registerDevice,
  getDevice,
  isValidKey,
  addNotification,
  listNotifications,
  listUndelivered,
  getNotification,
  setStatus,
  deleteNotification,
  markDelivered
};
