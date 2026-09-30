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
    topic TEXT,
    image TEXT,
    category TEXT,
    status TEXT NOT NULL DEFAULT 'pending',
    delivered INTEGER NOT NULL DEFAULT 0,
    createdAt INTEGER NOT NULL
  );

  CREATE TABLE IF NOT EXISTS categories (
    id TEXT PRIMARY KEY,
    deviceId TEXT NOT NULL,
    name TEXT NOT NULL,
    image TEXT,
    createdAt INTEGER NOT NULL
  );
  CREATE UNIQUE INDEX IF NOT EXISTS idx_categories_device_name ON categories(deviceId, name COLLATE NOCASE);

  DROP INDEX IF EXISTS idx_notifications_device;
  CREATE INDEX IF NOT EXISTS idx_notifications_device_status_created
    ON notifications(deviceId, status, createdAt);
`);

const columns = db.prepare('PRAGMA table_info(notifications)').all().map(c => c.name);
if (!columns.includes('topic')) db.exec("ALTER TABLE notifications ADD COLUMN topic TEXT DEFAULT ''");
if (!columns.includes('image')) db.exec("ALTER TABLE notifications ADD COLUMN image TEXT DEFAULT ''");
if (!columns.includes('category')) db.exec("ALTER TABLE notifications ADD COLUMN category TEXT DEFAULT ''");

// Cada dispositivo tem no máximo 4 categorias (viram as "pastas" da tela inicial do app).
const MAX_CATEGORIES = 4;

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
  // As apiKeys continuam válidas no SQLite; o arquivo seria só uma cópia a mais das chaves.
  const hasDevice = db.prepare('SELECT 1 FROM devices WHERE deviceId = ?');
  const hasNotif = db.prepare('SELECT 1 FROM notifications WHERE id = ?');
  const allImported =
    Object.keys(legacy.devices || {}).every(id => hasDevice.get(id)) &&
    Object.values(legacy.notifications || {}).flat().every(n => hasNotif.get(n.id));
  if (!allImported) {
    fs.renameSync(legacyFile, legacyFile + '.migrated');
    console.warn('data.json importado com divergências; mantido como data.json.migrated para conferência manual');
    return;
  }
  fs.unlinkSync(legacyFile);
  console.log('data.json antigo importado para o SQLite e apagado');
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
  return { ...row, categoryImage: row.categoryImage || '', delivered: !!row.delivered };
}

const SELECT_WITH_CATEGORY = `
  SELECT n.*, c.image AS categoryImage FROM notifications n
  LEFT JOIN categories c ON c.deviceId = n.deviceId AND c.name = n.category COLLATE NOCASE`;

function addNotification(deviceId, { title, message, reason, app, link, topic, image, category }) {
  const notif = {
    id: genId(),
    deviceId,
    title: title || 'Notificação',
    message: message || '',
    reason: reason || '',
    app: app || 'desconhecido',
    link: link || '',
    topic: topic || '',
    image: image || '',
    category: resolveCategory(deviceId, category, image),
    status: 'pending',
    delivered: 0,
    createdAt: Date.now()
  };
  db.prepare(`
    INSERT INTO notifications (id, deviceId, title, message, reason, app, link, topic, image, category, status, delivered, createdAt)
    VALUES (@id, @deviceId, @title, @message, @reason, @app, @link, @topic, @image, @category, @status, @delivered, @createdAt)
  `).run(notif);
  const cat = notif.category ? getCategoryByName(deviceId, notif.category) : null;
  return rowToNotification({ ...notif, categoryImage: cat ? cat.image : '' });
}

function listNotifications(deviceId, status, limit = 100, offset = 0, category) {
  let sql = SELECT_WITH_CATEGORY + ' WHERE n.deviceId = ?';
  const args = [deviceId];
  if (status) { sql += ' AND n.status = ?'; args.push(status); }
  if (category) { sql += ' AND n.category = ? COLLATE NOCASE'; args.push(category); }
  sql += ' ORDER BY n.createdAt DESC LIMIT ? OFFSET ?';
  args.push(limit, offset);
  return db.prepare(sql).all(...args).map(rowToNotification);
}

// ---------- Categorias ----------

function getCategoryByName(deviceId, name) {
  return db.prepare('SELECT * FROM categories WHERE deviceId = ? AND name = ? COLLATE NOCASE').get(deviceId, name);
}

function countCategories(deviceId) {
  return db.prepare('SELECT COUNT(*) AS n FROM categories WHERE deviceId = ?').get(deviceId).n;
}

// Nome enviado pelo app/bot -> nome oficial da categoria. Se ela ainda não existe e há vaga,
// é criada na hora (com a imagem da notificação); sem vaga, a notificação fica sem categoria.
function resolveCategory(deviceId, name, image) {
  name = (name || '').trim();
  if (!name) return '';
  const existing = getCategoryByName(deviceId, name);
  if (existing) return existing.name;
  if (countCategories(deviceId) >= MAX_CATEGORIES) return '';
  return createCategory(deviceId, { name, image }).category.name;
}

function listCategories(deviceId) {
  return db.prepare(`
    SELECT c.*,
      (SELECT COUNT(*) FROM notifications n WHERE n.deviceId = c.deviceId AND n.category = c.name COLLATE NOCASE AND n.status = 'pending') AS pending,
      (SELECT COUNT(*) FROM notifications n WHERE n.deviceId = c.deviceId AND n.category = c.name COLLATE NOCASE) AS total
    FROM categories c WHERE c.deviceId = ? ORDER BY c.createdAt ASC
  `).all(deviceId);
}

function createCategory(deviceId, { name, image }) {
  name = (name || '').trim();
  if (!name) return { error: 'name é obrigatório' };
  if (getCategoryByName(deviceId, name)) return { error: 'já existe uma categoria com esse nome' };
  if (countCategories(deviceId) >= MAX_CATEGORIES) return { error: `limite de ${MAX_CATEGORIES} categorias atingido` };
  const category = { id: genId(), deviceId, name, image: image || '', createdAt: Date.now() };
  db.prepare('INSERT INTO categories (id, deviceId, name, image, createdAt) VALUES (@id, @deviceId, @name, @image, @createdAt)').run(category);
  return { category };
}

// Renomear leva junto as notificações que já estavam na categoria.
function updateCategory(deviceId, id, { name, image }) {
  const current = db.prepare('SELECT * FROM categories WHERE deviceId = ? AND id = ?').get(deviceId, id);
  if (!current) return { error: 'categoria não encontrada' };
  const newName = name === undefined ? current.name : String(name).trim();
  if (!newName) return { error: 'name não pode ficar vazio' };
  const clash = getCategoryByName(deviceId, newName);
  if (clash && clash.id !== id) return { error: 'já existe uma categoria com esse nome' };
  const newImage = image === undefined ? current.image : image;
  db.transaction(() => {
    db.prepare('UPDATE categories SET name = ?, image = ? WHERE id = ?').run(newName, newImage, id);
    if (newName !== current.name) {
      db.prepare('UPDATE notifications SET category = ? WHERE deviceId = ? AND category = ? COLLATE NOCASE').run(newName, deviceId, current.name);
    }
  })();
  return { category: { ...current, name: newName, image: newImage } };
}

// Apaga a categoria. Com deleteNotifications, apaga junto as notificações dela; senão elas ficam sem categoria.
function deleteCategory(deviceId, id, deleteNotifications) {
  const current = db.prepare('SELECT * FROM categories WHERE deviceId = ? AND id = ?').get(deviceId, id);
  if (!current) return { error: 'categoria não encontrada' };
  let removed = 0;
  db.transaction(() => {
    if (deleteNotifications) {
      removed = db.prepare('DELETE FROM notifications WHERE deviceId = ? AND category = ? COLLATE NOCASE').run(deviceId, current.name).changes;
    } else {
      db.prepare("UPDATE notifications SET category = '' WHERE deviceId = ? AND category = ? COLLATE NOCASE").run(deviceId, current.name);
    }
    db.prepare('DELETE FROM categories WHERE id = ?').run(id);
  })();
  return { ok: true, removed };
}

// Apaga todas as notificações de uma categoria (a categoria continua existindo).
function clearCategory(deviceId, id) {
  const current = db.prepare('SELECT * FROM categories WHERE deviceId = ? AND id = ?').get(deviceId, id);
  if (!current) return { error: 'categoria não encontrada' };
  const removed = db.prepare('DELETE FROM notifications WHERE deviceId = ? AND category = ? COLLATE NOCASE').run(deviceId, current.name).changes;
  return { ok: true, removed };
}

function listUndelivered(deviceId) {
  return db.prepare(
    SELECT_WITH_CATEGORY + " WHERE n.deviceId = ? AND n.status = 'pending' AND n.delivered = 0 ORDER BY n.createdAt ASC"
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
  markDelivered,
  listCategories,
  createCategory,
  updateCategory,
  deleteCategory,
  clearCategory,
  MAX_CATEGORIES
};
