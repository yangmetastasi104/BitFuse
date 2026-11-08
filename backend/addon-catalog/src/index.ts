interface D1Result<T = unknown> {
  success: boolean;
  results?: T[];
  meta?: { changes?: number };
}

interface D1PreparedStatement {
  bind(...values: unknown[]): D1PreparedStatement;
  first<T = unknown>(): Promise<T | null>;
  all<T = unknown>(): Promise<D1Result<T>>;
  run(): Promise<D1Result>;
}

interface D1Database {
  prepare(query: string): D1PreparedStatement;
  batch(statements: D1PreparedStatement[]): Promise<D1Result[]>;
}

interface RateLimit {
  limit(options: { key: string }): Promise<{ success: boolean }>;
}

interface Env {
  DB: D1Database;
  AUTH_LIMITER: RateLimit;
  ACTIVATION_PASSWORD: string;
  TOKEN_PEPPER: string;
  TELEGRAM_ADMIN_CHAT_ID: string;
  TELEGRAM_CHANNEL_ID: string;
  TELEGRAM_WEBHOOK_SECRET: string;
  MAX_DEVICES?: string;
}

interface AddonRow {
  id: string;
  name: string;
  base_url: string;
  sort_order: number;
}

interface TelegramPost {
  chat?: { id?: number };
  text?: string;
}

interface TelegramUpdate {
  channel_post?: TelegramPost;
  /** Private-chat administration, for setups that do not use a channel. */
  message?: TelegramPost;
}

const jsonHeaders = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
};

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: jsonHeaders });
}

async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function constantTimeEqual(left: string, right: string): boolean {
  const a = new TextEncoder().encode(left);
  const b = new TextEncoder().encode(right);
  let difference = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    difference |= (a[i] ?? 0) ^ (b[i] ?? 0);
  }
  return difference === 0;
}

function randomToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

export function parseAdminPost(text: string):
  | { kind: "add"; id: string; name: string; baseUrl: string }
  | { kind: "remove"; id: string }
  | null {
  const lines = text.trim().split(/\r?\n/).map((line) => line.trim());
  const inline = lines[0]?.split("|").map((part) => part.trim()) ?? [];
  const remove = lines[0]?.match(/^REMOVE\s+([a-z0-9][a-z0-9_-]{0,47})$/i);
  if (remove) {
    return /^[a-z0-9][a-z0-9_-]{0,47}$/.test(remove[1])
      ? { kind: "remove", id: remove[1] }
      : null;
  }
  const add = lines[0]?.match(/^ADD\s+([a-z0-9][a-z0-9_-]{0,47})(?:\s*\|.*)?$/i);
  if (!add) return null;
  const id = add[1];
  const inlineFields = inline.length === 3 && /^ADD\s+[a-z0-9][a-z0-9_-]{0,47}$/i.test(inline[0])
    ? [inline[1], inline[2]]
    : null;
  const name = (inlineFields?.[0] ?? lines[1] ?? "").slice(0, 100);
  const rawUrl = inlineFields?.[1] ?? lines[2];
  if (!name || !rawUrl) return null;
  if (!/^[a-z0-9][a-z0-9_-]{0,47}$/.test(id) || !name) return null;
  try {
    const url = new URL(rawUrl);
    if (url.protocol !== "https:" || !url.hostname || url.username || url.password) return null;
    if (url.hostname === "localhost" || url.hostname.endsWith(".local")) return null;
    return { kind: "add", id, name, baseUrl: url.toString().replace(/\/$/, "") };
  } catch {
    return null;
  }
}

async function listCatalog(db: D1Database): Promise<{ revision: number; addons: AddonRow[] }> {
  const [meta, result] = await Promise.all([
    db.prepare("SELECT revision FROM catalog_meta WHERE singleton = 1").first<{ revision: number }>(),
    db.prepare("SELECT id, name, base_url, sort_order FROM addons ORDER BY sort_order, id").all<AddonRow>(),
  ]);
  return { revision: meta?.revision ?? 1, addons: result.results ?? [] };
}

async function activation(request: Request, env: Env): Promise<Response> {
  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  if (!(await env.AUTH_LIMITER.limit({ key: `activate:${ip}` })).success) {
    return json({ error: "Too many attempts. Try again later." }, 429);
  }
  const body = await request.json().catch(() => null) as { password?: unknown } | null;
  if (typeof body?.password !== "string" || body.password.length < 1 || body.password.length > 128) {
    return json({ error: "Invalid activation code." }, 401);
  }
  if (!constantTimeEqual(body.password, env.ACTIVATION_PASSWORD)) {
    return json({ error: "Invalid activation code." }, 401);
  }

  const maxDevices = Number(env.MAX_DEVICES ?? 10000);
  const active = await env.DB.prepare("SELECT COUNT(*) AS count FROM devices WHERE revoked_at IS NULL")
    .first<{ count: number }>();
  if ((active?.count ?? 0) >= maxDevices) return json({ error: "Device activation limit reached." }, 403);

  const token = randomToken();
  const tokenHash = await sha256(`${env.TOKEN_PEPPER}:${token}`);
  await env.DB.prepare("INSERT INTO devices (token_hash, created_at) VALUES (?, ?)")
    .bind(tokenHash, Date.now()).run();
  return json({ accessToken: token, ...(await listCatalog(env.DB)) });
}

async function authorizedDevice(request: Request, env: Env): Promise<string | null> {
  const bearer = request.headers.get("authorization")?.match(/^Bearer (.+)$/i)?.[1];
  if (!bearer || bearer.length > 256) return null;
  const tokenHash = await sha256(`${env.TOKEN_PEPPER}:${bearer}`);
  const row = await env.DB.prepare(
    "SELECT token_hash FROM devices WHERE token_hash = ? AND revoked_at IS NULL",
  ).bind(tokenHash).first<{ token_hash: string }>();
  return row?.token_hash ?? null;
}

async function telegramWebhook(request: Request, env: Env): Promise<Response> {
  if (!constantTimeEqual(
    request.headers.get("x-telegram-bot-api-secret-token") ?? "",
    env.TELEGRAM_WEBHOOK_SECRET,
  )) return json({ error: "Forbidden" }, 403);

  const update = await request.json().catch(() => null) as TelegramUpdate | null;
  const channelPost = update?.channel_post;
  const privateMessage = update?.message;
  const acceptedChannel = channelPost &&
    String(channelPost.chat?.id ?? "") === env.TELEGRAM_CHANNEL_ID;
  const acceptedAdmin = privateMessage &&
    String(privateMessage.chat?.id ?? "") === env.TELEGRAM_ADMIN_CHAT_ID;
  const post = acceptedChannel ? channelPost : acceptedAdmin ? privateMessage : null;
  if (!post) return json({ ok: true });
  const command = post.text ? parseAdminPost(post.text) : null;
  if (!command) return json({ ok: true, ignored: true });

  if (command.kind === "add") {
    const existing = await env.DB.prepare("SELECT sort_order FROM addons WHERE id = ?")
      .bind(command.id).first<{ sort_order: number }>();
    const nextOrder = existing?.sort_order ?? (await env.DB.prepare(
      "SELECT COALESCE(MAX(sort_order), -1) + 1 AS next FROM addons",
    ).first<{ next: number }>())?.next ?? 0;
    await env.DB.prepare(
      "INSERT INTO addons (id, name, base_url, sort_order, updated_at) VALUES (?, ?, ?, ?, ?) " +
        "ON CONFLICT(id) DO UPDATE SET name = excluded.name, base_url = excluded.base_url, updated_at = excluded.updated_at",
    ).bind(command.id, command.name, command.baseUrl, nextOrder, Date.now()).run();
  } else {
    await env.DB.prepare("DELETE FROM addons WHERE id = ?").bind(command.id).run();
  }
  await env.DB.prepare("UPDATE catalog_meta SET revision = revision + 1 WHERE singleton = 1").run();
  return json({ ok: true });
}

async function handle(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: jsonHeaders });
  if (url.pathname === "/telegram/webhook" && request.method === "POST") {
    return telegramWebhook(request, env);
  }
  if (url.pathname === "/v1/activate" && request.method === "POST") return activation(request, env);
  if (url.pathname === "/v1/catalog" && request.method === "GET") {
    if (!(await authorizedDevice(request, env))) return json({ error: "Unauthorized" }, 401);
    return json(await listCatalog(env.DB));
  }
  if (url.pathname === "/v1/device" && request.method === "DELETE") {
    const tokenHash = await authorizedDevice(request, env);
    if (!tokenHash) return json({ error: "Unauthorized" }, 401);
    await env.DB.prepare("UPDATE devices SET revoked_at = ? WHERE token_hash = ?")
      .bind(Date.now(), tokenHash).run();
    return json({ ok: true });
  }
  return json({ error: "Not found" }, 404);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    return handle(request, env);
  },
};
