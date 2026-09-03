/**
 * АнонЧат — сервер v2.
 *
 * Теперь с аккаунтами:
 *  - Регистрация: POST /api/register {name} -> {id, token, name}
 *    id — уникальный 5-значный номер пользователя, token — секрет для авторизации.
 *  - Список чатов: GET /api/chats?id=&token=
 *    Всегда есть закреплённый общий чат ("general") + список приватных чатов с друзьями.
 *  - Добавить друга: POST /api/friends/add {id, token, friendId}
 *    Создаёт (симметрично) дружбу и приватный чат между двумя пользователями.
 *  - История сообщений: GET /api/messages?chatId=&id=&token=&limit=
 *  - Реальное время: WebSocket ws://host:port/ws?id=&token=
 *    Клиент -> сервер: {"type":"message","chatId":"...","text":"..."}
 *    Сервер -> клиент:
 *      {"type":"message","chatId":"...","id":1,"name":"...","text":"...","timestamp":...}
 *      {"type":"friend_added","chatId":"...","friend":{"id":"...","name":"..."}}
 *
 * Все данные хранятся в простых файлах на диске (data/) — без внешней БД,
 * без нативных зависимостей, легко бэкапится копированием папки data/.
 */

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const http = require("http");
const express = require("express");
const cors = require("cors");
const { WebSocketServer } = require("ws");

loadDotEnv(path.join(__dirname, ".env"));

const PORT = parseInt(process.env.PORT || "8080", 10);
const CHAT_SECRET = process.env.CHAT_SECRET || "";
const HISTORY_LIMIT = parseInt(process.env.HISTORY_LIMIT || "500", 10);

const DATA_DIR = path.join(__dirname, "data");
const CHATS_DIR = path.join(DATA_DIR, "chats");
const USERS_FILE = path.join(DATA_DIR, "users.json");
const FRIENDS_FILE = path.join(DATA_DIR, "friends.json");
const GENERAL_CHAT_ID = "general";

if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
if (!fs.existsSync(CHATS_DIR)) fs.mkdirSync(CHATS_DIR, { recursive: true });

// Миграция со старой версии сервера (общий чат без аккаунтов)
const OLD_HISTORY_FILE = path.join(DATA_DIR, "messages.jsonl");
const GENERAL_CHAT_FILE = path.join(CHATS_DIR, `${GENERAL_CHAT_ID}.jsonl`);
if (fs.existsSync(OLD_HISTORY_FILE) && !fs.existsSync(GENERAL_CHAT_FILE)) {
  fs.copyFileSync(OLD_HISTORY_FILE, GENERAL_CHAT_FILE);
  console.log("Перенесена история старого общего чата в новый формат.");
}
if (!fs.existsSync(GENERAL_CHAT_FILE)) fs.writeFileSync(GENERAL_CHAT_FILE, "");

// --- Хранилища (JSON-файлы) --------------------------------------------
function readJson(filePath, fallback) {
  if (!fs.existsSync(filePath)) return fallback;
  try {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
  } catch (e) {
    return fallback;
  }
}

function writeJson(filePath, data) {
  fs.writeFileSync(filePath, JSON.stringify(data, null, 2));
}

let users = readJson(USERS_FILE, {}); // { "12345": { name, token } }
let friends = readJson(FRIENDS_FILE, {}); // { "12345": ["67890", ...] }

function saveUsers() {
  writeJson(USERS_FILE, users);
}

function saveFriends() {
  writeJson(FRIENDS_FILE, friends);
}

function generateUserId() {
  let id;
  do {
    id = String(Math.floor(10000 + Math.random() * 90000));
  } while (users[id]);
  return id;
}

function generateToken() {
  return crypto.randomBytes(24).toString("hex");
}

function privateChatId(idA, idB) {
  const pair = [idA, idB].sort();
  return `priv_${pair[0]}_${pair[1]}`;
}

function chatFilePath(chatId) {
  return path.join(CHATS_DIR, `${chatId}.jsonl`);
}

function ensureChatFile(chatId) {
  const file = chatFilePath(chatId);
  if (!fs.existsSync(file)) fs.writeFileSync(file, "");
  return file;
}

function readChatTail(chatId, limit) {
  const file = chatFilePath(chatId);
  if (!fs.existsSync(file)) return [];
  const raw = fs.readFileSync(file, "utf8");
  const lines = raw.split("\n").filter((l) => l.trim().length > 0);
  const tail = lines.slice(-limit);
  const result = [];
  for (const line of tail) {
    try {
      result.push(JSON.parse(line));
    } catch (e) {
      // пропускаем битую строку
    }
  }
  return result;
}

function lastMessageOf(chatId) {
  const tail = readChatTail(chatId, 1);
  return tail.length ? tail[0] : null;
}

// nextId по каждому чату — считаем по количеству строк в файле + 1
const chatNextId = {};
function getNextMessageId(chatId) {
  if (!(chatId in chatNextId)) {
    const file = chatFilePath(chatId);
    let count = 0;
    if (fs.existsSync(file)) {
      count = fs
        .readFileSync(file, "utf8")
        .split("\n")
        .filter((l) => l.trim().length > 0).length;
    }
    chatNextId[chatId] = count + 1;
  }
  const id = chatNextId[chatId];
  chatNextId[chatId] += 1;
  return id;
}

function addMessage(chatId, userId, name, text) {
  ensureChatFile(chatId);
  const msg = {
    id: getNextMessageId(chatId),
    userId: userId,
    name: sanitizeName(name),
    text: sanitizeText(text),
    timestamp: Date.now(),
  };
  fs.appendFile(chatFilePath(chatId), JSON.stringify(msg) + "\n", (err) => {
    if (err) console.error("Не удалось записать сообщение:", err);
  });
  return msg;
}

function sanitizeName(name) {
  return String(name || "Аноним").slice(0, 60);
}

function sanitizeText(text) {
  return String(text || "").trim().slice(0, 2000);
}

function checkSecret(providedSecret) {
  if (!CHAT_SECRET) return true;
  return providedSecret === CHAT_SECRET;
}

function loadDotEnv(filePath) {
  if (!fs.existsSync(filePath)) return;
  const content = fs.readFileSync(filePath, "utf8");
  for (const rawLine of content.split("\n")) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const idx = line.indexOf("=");
    if (idx === -1) continue;
    const key = line.slice(0, idx).trim();
    let value = line.slice(idx + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    if (!(key in process.env)) process.env[key] = value;
  }
}

// --- Авторизация пользователя ------------------------------------------
function authenticate(id, token) {
  if (!id || !token) return null;
  const user = users[id];
  if (!user || user.token !== token) return null;
  return { id, name: user.name };
}

function isFriendPair(idA, idB) {
  return (friends[idA] || []).includes(idB);
}

function userChatIds(id) {
  const list = [GENERAL_CHAT_ID];
  for (const friendId of friends[id] || []) {
    list.push(privateChatId(id, friendId));
  }
  return list;
}

// --- HTTP / REST ----------------------------------------------------------
const app = express();
app.use(cors());
app.use(express.json());

app.get("/health", (req, res) => {
  res.json({ ok: true, users: Object.keys(users).length });
});

app.post("/api/register", (req, res) => {
  if (!checkSecret(req.header("X-Chat-Secret"))) {
    return res.status(401).json({ error: "unauthorized" });
  }
  const name = sanitizeName(req.body && req.body.name);
  if (!name) return res.status(400).json({ error: "name_required" });

  const id = generateUserId();
  const token = generateToken();
  users[id] = { name, token };
  saveUsers();

  res.json({ id, token, name });
});

app.get("/api/chats", (req, res) => {
  if (!checkSecret(req.header("X-Chat-Secret"))) {
    return res.status(401).json({ error: "unauthorized" });
  }
  const auth = authenticate(req.query.id, req.query.token);
  if (!auth) return res.status(401).json({ error: "invalid_auth" });

  const generalLast = lastMessageOf(GENERAL_CHAT_ID);
  const friendIds = friends[auth.id] || [];
  const friendChats = friendIds
    .filter((fid) => users[fid])
    .map((fid) => {
      const chatId = privateChatId(auth.id, fid);
      const last = lastMessageOf(chatId);
      return {
        chatId,
        friend: { id: fid, name: users[fid].name },
        lastMessage: last ? last.text : null,
        lastTimestamp: last ? last.timestamp : 0,
      };
    })
    .sort((a, b) => b.lastTimestamp - a.lastTimestamp);

  res.json({
    me: { id: auth.id, name: auth.name },
    general: {
      chatId: GENERAL_CHAT_ID,
      name: "Общий чат",
      lastMessage: generalLast ? generalLast.text : null,
      lastTimestamp: generalLast ? generalLast.timestamp : 0,
    },
    friends: friendChats,
  });
});

app.get("/api/messages", (req, res) => {
  if (!checkSecret(req.header("X-Chat-Secret"))) {
    return res.status(401).json({ error: "unauthorized" });
  }
  const auth = authenticate(req.query.id, req.query.token);
  if (!auth) return res.status(401).json({ error: "invalid_auth" });

  const chatId = String(req.query.chatId || "");
  if (chatId !== GENERAL_CHAT_ID) {
    const [, a, b] = chatId.split("_");
    if (!a || !b || ![a, b].includes(auth.id) || !isFriendPair(a, b)) {
      return res.status(403).json({ error: "forbidden" });
    }
  }

  const limit = Math.min(
    parseInt(req.query.limit || String(HISTORY_LIMIT), 10) || HISTORY_LIMIT,
    HISTORY_LIMIT
  );
  res.json(readChatTail(chatId, limit));
});

app.post("/api/friends/add", (req, res) => {
  if (!checkSecret(req.header("X-Chat-Secret"))) {
    return res.status(401).json({ error: "unauthorized" });
  }
  const auth = authenticate(req.body && req.body.id, req.body && req.body.token);
  if (!auth) return res.status(401).json({ error: "invalid_auth" });

  const friendId = String((req.body && req.body.friendId) || "").trim();
  if (!friendId || !users[friendId]) {
    return res.status(404).json({ error: "user_not_found" });
  }
  if (friendId === auth.id) {
    return res.status(400).json({ error: "cannot_add_self" });
  }

  friends[auth.id] = friends[auth.id] || [];
  friends[friendId] = friends[friendId] || [];

  const alreadyFriends = friends[auth.id].includes(friendId);
  if (!alreadyFriends) {
    friends[auth.id].push(friendId);
    friends[friendId].push(auth.id);
    saveFriends();
  }

  const chatId = privateChatId(auth.id, friendId);
  ensureChatFile(chatId);

  // Если получатель сейчас онлайн — сразу уведомляем его о новом чате
  notifyUser(friendId, {
    type: "friend_added",
    chatId,
    friend: { id: auth.id, name: auth.name },
  });

  res.json({
    chatId,
    friend: { id: friendId, name: users[friendId].name },
  });
});

const server = http.createServer(app);

// --- WebSocket -------------------------------------------------------------
const wss = new WebSocketServer({ server, path: "/ws" });

// userId -> Set<ws>
const connections = new Map();

function registerConnection(userId, ws) {
  if (!connections.has(userId)) connections.set(userId, new Set());
  connections.get(userId).add(ws);
}

function unregisterConnection(userId, ws) {
  const set = connections.get(userId);
  if (!set) return;
  set.delete(ws);
  if (set.size === 0) connections.delete(userId);
}

function notifyUser(userId, payload) {
  const set = connections.get(userId);
  if (!set) return;
  const data = JSON.stringify(payload);
  for (const ws of set) {
    if (ws.readyState === ws.OPEN) ws.send(data);
  }
}

function chatMembers(chatId) {
  if (chatId === GENERAL_CHAT_ID) return null; // всем подключённым
  const parts = chatId.split("_");
  if (parts.length !== 3) return [];
  return [parts[1], parts[2]];
}

wss.on("connection", (ws, req) => {
  const url = new URL(req.url, "http://localhost");
  const secret = req.headers["x-chat-secret"] || url.searchParams.get("secret");
  if (!checkSecret(secret)) {
    ws.close(4001, "unauthorized");
    return;
  }

  const id = url.searchParams.get("id");
  const token = url.searchParams.get("token");
  const auth = authenticate(id, token);
  if (!auth) {
    ws.close(4002, "invalid_auth");
    return;
  }

  ws.userId = auth.id;
  ws.userName = auth.name;
  ws.isAlive = true;
  registerConnection(auth.id, ws);

  ws.on("pong", () => {
    ws.isAlive = true;
  });

  ws.on("message", (data) => {
    let parsed;
    try {
      parsed = JSON.parse(data.toString());
    } catch (e) {
      return;
    }

    if (parsed.type === "message") {
      const chatId = String(parsed.chatId || GENERAL_CHAT_ID);
      const text = sanitizeText(parsed.text);
      if (!text) return;

      if (chatId !== GENERAL_CHAT_ID) {
        const members = chatMembers(chatId);
        if (!members || !members.includes(ws.userId) || !isFriendPair(members[0], members[1])) {
          return; // не участник этого приватного чата
        }
      }

      const msg = addMessage(chatId, ws.userId, ws.userName, text);
      const payload = { type: "message", chatId, ...msg };

      if (chatId === GENERAL_CHAT_ID) {
        broadcastAll(payload);
      } else {
        const members = chatMembers(chatId);
        for (const memberId of members) notifyUser(memberId, payload);
      }
    }
  });

  ws.on("close", () => unregisterConnection(auth.id, ws));
});

function broadcastAll(payload) {
  const data = JSON.stringify(payload);
  wss.clients.forEach((client) => {
    if (client.readyState === client.OPEN) client.send(data);
  });
}

const pingInterval = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) return ws.terminate();
    ws.isAlive = false;
    ws.ping();
  });
}, 30000);

wss.on("close", () => clearInterval(pingInterval));

server.listen(PORT, () => {
  console.log(`АнонЧат-сервер (v2) запущен на порту ${PORT}`);
  console.log(`REST:  http://localhost:${PORT}/api/...`);
  console.log(`WS:    ws://localhost:${PORT}/ws?id=...&token=...`);
});
