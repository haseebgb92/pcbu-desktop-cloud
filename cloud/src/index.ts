export interface Env { DB: D1Database; ALLOWED_ORIGIN: string }

const enc = new TextEncoder();
const json = (data: unknown, status = 200) => Response.json(data, { status, headers: { "cache-control": "no-store" } });
const now = () => Math.floor(Date.now() / 1000);
const hex = (b: ArrayBuffer) => [...new Uint8Array(b)].map(x => x.toString(16).padStart(2, "0")).join("");
const random = (bytes = 32) => { const b = new Uint8Array(bytes); crypto.getRandomValues(b); return hex(b.buffer); };
const sha256 = async (s: string) => hex(await crypto.subtle.digest("SHA-256", enc.encode(s)));
const passwordHash = async (password: string, salt: string) => {
  const key = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  const first = await crypto.subtle.deriveBits({ name: "PBKDF2", salt: enc.encode(`pcbu-password-v1:first:${salt}`), iterations: 100_000, hash: "SHA-256" }, key, 256);
  const secondKey = await crypto.subtle.importKey("raw", first, "PBKDF2", false, ["deriveBits"]);
  return hex(await crypto.subtle.deriveBits({ name: "PBKDF2", salt: enc.encode(`pcbu-password-v1:second:${salt}`), iterations: 100_000, hash: "SHA-256" }, secondKey, 256));
};
const bearer = (r: Request) => r.headers.get("authorization")?.match(/^Bearer ([a-f0-9]{64})$/i)?.[1];
async function body(r: Request) { try { return await r.json<Record<string, unknown>>(); } catch { throw new Error("INVALID_JSON"); } }
function textField(v: unknown, name: string, min: number, max: number) {
  if (typeof v !== "string" || v.length < min || v.length > max) throw new Error(`INVALID_${name.toUpperCase()}`);
  return v;
}
async function userId(r: Request, env: Env) {
  const token = bearer(r); if (!token) return null;
  const row = await env.DB.prepare("SELECT user_id FROM sessions WHERE token_hash=? AND expires_at>?").bind(await sha256(token), now()).first<{user_id:string}>();
  return row?.user_id ?? null;
}
async function device(r: Request, env: Env) {
  const token = bearer(r); if (!token) return null;
  return env.DB.prepare("SELECT id,user_id FROM devices WHERE token_hash=?").bind(await sha256(token)).first<{id:string,user_id:string}>();
}

async function route(r: Request, env: Env): Promise<Response> {
  const url = new URL(r.url), p = url.pathname;
  if (r.method === "GET" && p === "/v1/health") return json({ ok: true });
  if (r.method === "POST" && (p === "/v1/auth/register" || p === "/v1/auth/login")) {
    const b = await body(r), login = textField(b.login, "login", 3, 128).trim().toLowerCase();
    const password = textField(b.password, "password", 12, 256);
    let uid: string;
    if (p.endsWith("register")) {
      const salt = random(16); uid = crypto.randomUUID();
      const existing = await env.DB.prepare("SELECT 1 FROM users WHERE login=?").bind(login).first();
      if (existing) return json({ error: "LOGIN_EXISTS" }, 409);
      await env.DB.prepare("INSERT INTO users(id,login,password_hash,password_salt,created_at) VALUES(?,?,?,?,?)")
        .bind(uid, login, await passwordHash(password, salt), salt, now()).run();
    } else {
      const u = await env.DB.prepare("SELECT id,password_hash,password_salt FROM users WHERE login=?").bind(login).first<{id:string,password_hash:string,password_salt:string}>();
      if (!u || await passwordHash(password, u.password_salt) !== u.password_hash) return json({ error: "INVALID_CREDENTIALS" }, 401);
      uid = u.id;
    }
    const token = random(), expires = now() + 30 * 86400;
    await env.DB.prepare("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)").bind(await sha256(token), uid, expires).run();
    return json({ token, accountId: uid, expiresAt: expires });
  }
  if (r.method === "POST" && p === "/v1/devices") {
    const uid = await userId(r, env); if (!uid) return json({ error: "UNAUTHORIZED" }, 401);
    const b = await body(r), name = textField(b.name, "name", 1, 80), id = crypto.randomUUID(), token = random();
    await env.DB.prepare("INSERT INTO devices(id,user_id,name,token_hash,created_at,last_seen_at) VALUES(?,?,?,?,?,?)")
      .bind(id, uid, name, await sha256(token), now(), now()).run();
    return json({ id, deviceToken: token }, 201);
  }
  if (r.method === "GET" && p === "/v1/devices") {
    const uid = await userId(r, env); if (!uid) return json({ error: "UNAUTHORIZED" }, 401);
    const rows = await env.DB.prepare("SELECT id,name,last_seen_at FROM devices WHERE user_id=? ORDER BY created_at").bind(uid).all();
    return json({ devices: rows.results });
  }
  if (r.method === "POST" && p === "/v1/relay/requests") {
    const d = await device(r, env); if (!d) return json({ error: "UNAUTHORIZED" }, 401);
    const b = await body(r), payload = textField(b.payload, "payload", 2, 8192), id = crypto.randomUUID(), t = now();
    await env.DB.batch([
      env.DB.prepare("DELETE FROM relay_messages WHERE expires_at<=?").bind(t),
      env.DB.prepare("INSERT INTO relay_messages(id,device_id,direction,payload,created_at,expires_at) VALUES(?,?,'pc_to_phone',?,?,?)").bind(id,d.id,payload,t,t+120),
      env.DB.prepare("UPDATE devices SET last_seen_at=? WHERE id=?").bind(t,d.id)
    ]);
    return json({ id, expiresAt: t + 120 }, 201);
  }
  const command = p.match(/^\/v1\/relay\/devices\/([^/]+)\/commands$/);
  if (r.method === "POST" && command) {
    const uid = await userId(r, env); if (!uid) return json({ error: "UNAUTHORIZED" }, 401);
    const owned = await env.DB.prepare("SELECT id FROM devices WHERE id=? AND user_id=?").bind(command[1], uid).first();
    if (!owned) return json({ error: "DEVICE_NOT_FOUND" }, 404);
    const b = await body(r), payload = textField(b.payload, "payload", 2, 8192), id = crypto.randomUUID(), t = now();
    await env.DB.batch([
      env.DB.prepare("DELETE FROM relay_messages WHERE expires_at<=?").bind(t),
      env.DB.prepare("INSERT INTO relay_messages(id,device_id,direction,payload,created_at,expires_at) VALUES(?,?,'phone_to_pc',?,?,?)").bind(id,command[1],payload,t,t+120)
    ]);
    return json({ id, expiresAt: t + 120 }, 201);
  }
  if (r.method === "GET" && p === "/v1/relay/commands") {
    const d = await device(r, env); if (!d) return json({ error: "UNAUTHORIZED" }, 401);
    const msg = await env.DB.prepare("SELECT id,payload FROM relay_messages WHERE device_id=? AND direction='phone_to_pc' AND request_id IS NULL AND consumed_at IS NULL AND expires_at>? ORDER BY created_at LIMIT 1")
      .bind(d.id, now()).first<{id:string,payload:string}>();
    if (!msg) return new Response(null, { status: 204 });
    await env.DB.prepare("UPDATE relay_messages SET consumed_at=? WHERE id=?").bind(now(), msg.id).run();
    return json(msg);
  }
  const poll = p.match(/^\/v1\/relay\/devices\/([^/]+)\/requests$/);
  if (r.method === "GET" && poll) {
    const uid = await userId(r, env); if (!uid) return json({ error: "UNAUTHORIZED" }, 401);
    const msg = await env.DB.prepare("SELECT m.id,m.payload,m.created_at FROM relay_messages m JOIN devices d ON d.id=m.device_id WHERE d.id=? AND d.user_id=? AND m.direction='pc_to_phone' AND m.consumed_at IS NULL AND m.expires_at>? ORDER BY m.created_at LIMIT 1")
      .bind(poll[1],uid,now()).first();
    return msg ? json(msg) : new Response(null,{status:204});
  }
  const respond = p.match(/^\/v1\/relay\/requests\/([^/]+)\/response$/);
  if (r.method === "POST" && respond) {
    const uid = await userId(r, env); if (!uid) return json({ error: "UNAUTHORIZED" }, 401);
    const b=await body(r), payload=textField(b.payload,"payload",2,8192), id=crypto.randomUUID(), t=now();
    const req = await env.DB.prepare("SELECT m.device_id FROM relay_messages m JOIN devices d ON d.id=m.device_id WHERE m.id=? AND d.user_id=? AND m.direction='pc_to_phone' AND m.consumed_at IS NULL AND m.expires_at>?").bind(respond[1],uid,t).first<{device_id:string}>();
    if(!req) return json({error:"REQUEST_NOT_FOUND"},404);
    await env.DB.batch([
      env.DB.prepare("UPDATE relay_messages SET consumed_at=? WHERE id=?").bind(t,respond[1]),
      env.DB.prepare("INSERT INTO relay_messages(id,device_id,direction,request_id,payload,created_at,expires_at) VALUES(?,?,'phone_to_pc',?,?,?,?)").bind(id,req.device_id,respond[1],payload,t,t+120)
    ]);
    return json({ok:true},201);
  }
  const result = p.match(/^\/v1\/relay\/requests\/([^/]+)\/response$/);
  if (r.method === "GET" && result) {
    const d=await device(r,env); if(!d) return json({error:"UNAUTHORIZED"},401);
    const msg=await env.DB.prepare("SELECT id,payload FROM relay_messages WHERE request_id=? AND device_id=? AND direction='phone_to_pc' AND consumed_at IS NULL AND expires_at>?").bind(result[1],d.id,now()).first<{id:string,payload:string}>();
    if(!msg) return new Response(null,{status:204});
    await env.DB.prepare("UPDATE relay_messages SET consumed_at=? WHERE id=?").bind(now(),msg.id).run();
    return json({payload:msg.payload});
  }
  return json({ error: "NOT_FOUND" }, 404);
}

export default { async fetch(r: Request, env: Env) {
  if (r.method === "OPTIONS") return new Response(null, { status: 204 });
  try { return await route(r, env); }
  catch (e) { const code = e instanceof Error ? e.message : "BAD_REQUEST"; return json({ error: code }, 400); }
}} satisfies ExportedHandler<Env>;
