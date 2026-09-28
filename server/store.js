const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const DB_FILE = path.join(__dirname, 'data.json');

function load() {
  if (!fs.existsSync(DB_FILE)) {
    return { devices: {}, notifications: {} };
  }
  try {
    return JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
  } catch (e) {
    return { devices: {}, notifications: {} };
  }
}

let db = load();

function save() {
  fs.writeFileSync(DB_FILE, JSON.stringify(db, null, 2));
}

function genKey() {
  return crypto.randomBytes(24).toString('hex');
}

function genId() {
  return crypto.randomBytes(8).toString('hex');
}

function registerDevice(deviceId, name) {
  if (!db.devices[deviceId]) {
    db.devices[deviceId] = { apiKey: genKey(), name: name || deviceId, createdAt: Date.now() };
    db.notifications[deviceId] = [];
    save();
  }
  return db.devices[deviceId];
}

function isValidKey(deviceId, key) {
  const d = db.devices[deviceId];
  return !!d && d.apiKey === key;
}

function addNotification(deviceId, { title, message, reason, app }) {
  const notif = {
    id: genId(),
    title: title || 'Notificação',
    message: message || '',
    reason: reason || '',
    app: app || 'desconhecido',
    createdAt: Date.now(),
    delivered: false
  };
  if (!db.notifications[deviceId]) db.notifications[deviceId] = [];
  db.notifications[deviceId].push(notif);
  save();
  return notif;
}

function listNotifications(deviceId) {
  return db.notifications[deviceId] || [];
}

function deleteNotification(deviceId, id) {
  if (!db.notifications[deviceId]) return false;
  if (id === 'all') {
    db.notifications[deviceId] = [];
    save();
    return true;
  }
  const before = db.notifications[deviceId].length;
  db.notifications[deviceId] = db.notifications[deviceId].filter(n => n.id !== id);
  save();
  return db.notifications[deviceId].length !== before;
}

function markDelivered(deviceId, id) {
  const list = db.notifications[deviceId] || [];
  const n = list.find(n => n.id === id);
  if (n) {
    n.delivered = true;
    save();
  }
}

module.exports = {
  registerDevice,
  isValidKey,
  addNotification,
  listNotifications,
  deleteNotification,
  markDelivered,
  getDevice: (deviceId) => db.devices[deviceId]
};
