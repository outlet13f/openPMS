// Test-only OpenID provider for f00-sso.spec.ts. Never for real use: it signs in whoever clicks a name.
// The backend reaches it as the issuer (inside the dev network), the browser only opens its authorize page:
//   podman run -d --name openpms-idp --network openpms-dev -p 127.0.0.1:18081:18080 \
//     -v "$PWD/e2e:/idp:ro" docker.io/library/node:24-alpine node /idp/fake-idp.mjs
import http from "node:http";
import crypto from "node:crypto";

const ISSUER = process.env.IDP_ISSUER ?? "http://openpms-idp:18080";
const BROWSER = process.env.IDP_BROWSER ?? "http://localhost:18081";
const { privateKey, publicKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: "jwk" }), kid: "k1", use: "sig", alg: "RS256" };
const PEOPLE = {
  haneul: { sub: "idp-haneul", email: "haneul.lee@openpms.local", name: "이하늘", email_verified: true },
  newbie: { sub: "idp-newbie", email: "newbie@sso.example", family_name: "정", given_name: "다은", email_verified: true },
  stranger: { sub: "idp-stranger", email: "stranger@sso.example", name: "모르는 사람" },
};
const codes = new Map();
const tokens = new Map();
const b64 = (b) => Buffer.from(b).toString("base64url");

function idToken(claims, nonce, aud) {
  const now = Math.floor(Date.now() / 1000);
  const head = b64(JSON.stringify({ alg: "RS256", kid: "k1", typ: "JWT" }));
  const body = b64(JSON.stringify({ iss: ISSUER, aud, iat: now, exp: now + 300, nonce, ...claims }));
  return `${head}.${body}.${b64(crypto.sign("sha256", Buffer.from(`${head}.${body}`), privateKey))}`;
}

const json = (res, status, body) => (res.writeHead(status, { "Content-Type": "application/json" }), res.end(JSON.stringify(body)));

http
  .createServer(async (req, res) => {
    const url = new URL(req.url, BROWSER);
    if (url.pathname === "/.well-known/openid-configuration")
      return json(res, 200, {
        issuer: ISSUER,
        authorization_endpoint: `${BROWSER}/authorize`,
        token_endpoint: `${ISSUER}/token`,
        userinfo_endpoint: `${ISSUER}/userinfo`,
        jwks_uri: `${ISSUER}/jwks`,
        response_types_supported: ["code"],
        subject_types_supported: ["public"],
        id_token_signing_alg_values_supported: ["RS256"],
        token_endpoint_auth_methods_supported: ["client_secret_basic", "client_secret_post"],
      });
    if (url.pathname === "/jwks") return json(res, 200, { keys: [jwk] });
    if (url.pathname === "/authorize") {
      const q = url.searchParams;
      const who = q.get("who");
      if (!who) {
        // a sign-in page: pick who you are (carries every query parameter along)
        const hidden = [...q].map(([k, v]) => `<input type="hidden" name="${k}" value="${v.replace(/"/g, "&quot;")}">`).join("");
        res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
        return res.end(`<!doctype html><title>Test IdP</title><h1>Test IdP sign-in</h1><form>${hidden}
          ${Object.keys(PEOPLE).map((p) => `<button name="who" value="${p}">${p}</button>`).join(" ")}
          <button name="who" value="__deny">deny</button></form>`);
      }
      const back = new URL(q.get("redirect_uri"));
      back.searchParams.set("state", q.get("state"));
      if (who === "__deny") back.searchParams.set("error", "access_denied");
      else {
        const code = crypto.randomUUID();
        codes.set(code, { claims: PEOPLE[who], nonce: q.get("nonce"), challenge: q.get("code_challenge"), aud: q.get("client_id") });
        back.searchParams.set("code", code);
      }
      res.writeHead(302, { Location: back.toString() });
      return res.end();
    }
    if (url.pathname === "/token" && req.method === "POST") {
      let raw = "";
      for await (const c of req) raw += c;
      const form = new URLSearchParams(raw);
      const c = codes.get(form.get("code"));
      codes.delete(form.get("code"));
      const verifier = form.get("code_verifier") ?? "";
      if (!c || b64(crypto.createHash("sha256").update(verifier).digest()) !== c.challenge) return json(res, 400, { error: "invalid_grant" });
      const access = crypto.randomUUID();
      tokens.set(access, c.claims);
      return json(res, 200, { access_token: access, token_type: "Bearer", expires_in: 300, scope: "openid email profile", id_token: idToken(c.claims, c.nonce, c.aud) });
    }
    if (url.pathname === "/userinfo") {
      const info = tokens.get((req.headers.authorization ?? "").replace("Bearer ", ""));
      return info ? json(res, 200, info) : json(res, 401, { error: "invalid_token" });
    }
    json(res, 404, { error: "not_found" });
  })
  .listen(18080, () => console.log("test idp on 18080"));
