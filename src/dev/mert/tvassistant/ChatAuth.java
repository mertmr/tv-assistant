package dev.mert.tvassistant;

import android.content.*;
import android.net.Uri;
import android.util.Base64;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import org.json.*;

final class ChatAuth {
  interface Listener {
    void ready(String url);

    void result(String message);
  }

  static final String RESOURCE = "https://api.openai.com/v1",
      TOKEN = "https://auth.openai.com/api/accounts/oauth/token";
  private final Context context;
  private final Vault vault;
  private JSONObject store;
  private ServerSocket server;

  ChatAuth(Context c) throws Exception {
    context = c.getApplicationContext();
    vault = new Vault(c);
    store = vault.load();
    if (!store.has("host")) {
      store.put("host", "urn:uuid:" + UUID.randomUUID());
      vault.save(store);
    }
  }

  synchronized JSONArray profiles() {
    return store.optJSONArray("profiles");
  }

  synchronized JSONObject active() {
    JSONArray p = profiles();
    String id = store.optString("active");
    for (int i = 0; i < p.length(); i++) {
      JSONObject a = p.optJSONObject(i);
      if (a.optString("client_id").equals(id)) return a;
    }
    return null;
  }

  synchronized void select(String id) throws Exception {
    store.put("active", id);
    vault.save(store);
  }

  synchronized void saveProfile(JSONObject a) throws Exception {
    JSONArray next = new JSONArray();
    for (int i = 0; i < profiles().length(); i++) {
      JSONObject p = profiles().optJSONObject(i);
      if (!p.optString("client_id").equals(a.optString("client_id"))) next.put(p);
    }
    next.put(a);
    store.put("profiles", next);
    store.put("active", a.getString("client_id"));
    vault.save(store);
  }

  static String random() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return b64(b);
  }

  static String b64(byte[] b) {
    return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
  }

  static byte[] decode(String s) {
    return Base64.decode(s, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
  }

  synchronized void cancelLogin() {
    if (server != null) {
      try {
        server.close();
      } catch (Exception ignored) {
      }
      server = null;
    }
  }

  void signIn(boolean add, Listener listener) {
    final JSONObject previous = add ? null : active();
    new Thread(
            () -> {
              ServerSocket local = null;
              try {
                cancelLogin();
                String state = random(), nonce = random(), verifier = random();
                local = new ServerSocket();
                local.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
                local.setSoTimeout(600000);
                synchronized (this) {
                  server = local;
                }
                String redirect = "http://127.0.0.1:" + local.getLocalPort() + "/auth/callback";
                String client =
                    previous == null ? "dynamic_agent_client" : previous.getString("client_id");
                Map<String, String> p = new LinkedHashMap<>();
                p.put("client_id", client);
                p.put("ext_agent_host_id", store.getString("host"));
                if (previous == null) p.put("agent_name_hint", "TV Assistant");
                else if (previous.has("id_token"))
                  p.put("id_token_hint", previous.getString("id_token"));
                p.put("response_type", "code");
                p.put("redirect_uri", redirect);
                p.put(
                    "scope",
                    "openid profile email offline_access resource.invoke"
                        + " chatgpt.tokens.use.direct");
                p.put("resource", RESOURCE);
                p.put("state", state);
                p.put("nonce", nonce);
                p.put("code_challenge_method", "S256");
                p.put(
                    "code_challenge",
                    b64(
                        MessageDigest.getInstance("SHA-256")
                            .digest(verifier.getBytes("US-ASCII"))));
                listener.ready("https://auth.openai.com/api/accounts/authorize?" + Net.form(p));
                boolean finished = false;
                while (!finished) {
                  try (Socket socket = local.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader reader =
                        new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), "US-ASCII"));
                    String first = reader.readLine();
                    if (first == null) continue;
                    if (first.length() > 16000) throw new IOException("Invalid callback");
                    String[] pieces = first.split(" ");
                    if (pieces.length < 2) continue;
                    Uri uri = Uri.parse(pieces[1]);
                    if (!"/auth/callback".equals(uri.getPath())
                        || !state.equals(uri.getQueryParameter("state"))) {
                      reply(socket, "This request does not match the sign-in attempt.", 400);
                      continue;
                    }
                    finished = true;
                    try {
                      if (uri.getQueryParameter("error") != null)
                        throw new IOException("Sign-in was declined");
                      String issued = uri.getQueryParameter("client_id");
                      if (previous != null) {
                        if (issued != null && !issued.equals(client))
                          throw new IOException("Account registration mismatch");
                        issued = client;
                      }
                      if (issued == null || issued.equals("dynamic_agent_client"))
                        throw new IOException("Registration did not return a client ID");
                      String code = uri.getQueryParameter("code");
                      if (code == null) throw new IOException("Missing sign-in code");
                      Map<String, String> grant = new LinkedHashMap<>();
                      grant.put("grant_type", "authorization_code");
                      grant.put("client_id", issued);
                      grant.put("code", code);
                      grant.put("code_verifier", verifier);
                      grant.put("redirect_uri", redirect);
                      grant.put("resource", RESOURCE);
                      JSONObject tokens = Net.postForm(TOKEN, grant);
                      JSONObject identity = verify(tokens.getString("id_token"), issued, nonce);
                      if (previous != null
                          && !previous.getString("subject").equals(identity.getString("sub")))
                        throw new IOException(
                            "Signed-in account does not match the selected connection");
                      JSONObject profile =
                          Json.obj(
                              "client_id",
                              issued,
                              "subject",
                              identity.getString("sub"),
                              "email",
                              identity.optString("email", "ChatGPT account"),
                              "id_token",
                              tokens.getString("id_token"));
                      replaceTokens(profile, tokens);
                      saveProfile(profile);
                      reply(
                          socket,
                          "Connected. Return to TV Assistant with the Home button and open the"
                              + " app.",
                          200);
                      listener.result(
                          hasPlan(profile)
                              ? "Connected. ChatGPT plan usage is enabled."
                              : "Signed in. ChatGPT plan usage was not granted.");
                    } catch (Exception e) {
                      reply(
                          socket,
                          "Sign-in could not complete. Return to TV Assistant for details.",
                          400);
                      throw e;
                    }
                  }
                }
              } catch (Exception e) {
                listener.result("Sign-in: " + safe(e));
              } finally {
                if (local != null)
                  try {
                    local.close();
                  } catch (Exception ignored) {
                  }
              }
            },
            "ChatGPTSignIn")
        .start();
  }

  static void reply(Socket s, String message, int status) throws Exception {
    byte[] b =
        ("<!doctype html><meta name=viewport content='width=device-width'><title>TV"
                + " Assistant</title><body style='background:#0b1220;color:white;font:24px"
                + " sans-serif;padding:40px'><h1>TV Assistant</h1><p>"
                + message
                + "</p></body>")
            .getBytes("UTF-8");
    s.getOutputStream()
        .write(
            ("HTTP/1.1 "
                    + status
                    + " OK\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n"
                    + "Content-Length: "
                    + b.length
                    + "\r\nConnection: close\r\n\r\n")
                .getBytes("US-ASCII"));
    s.getOutputStream().write(b);
  }

  static JSONObject verify(String jwt, String client, String nonce) throws Exception {
    return verifyWithKeys(
        jwt,
        client,
        nonce,
        Net.get("https://auth.openai.com/.well-known/jwks.json", null).getJSONArray("keys"));
  }

  static JSONObject verifyWithKeys(String jwt, String client, String nonce, JSONArray keys)
      throws Exception {
    String[] parts = jwt.split("\\.");
    if (parts.length != 3) throw new IOException("Invalid identity token");
    JSONObject header = new JSONObject(new String(decode(parts[0]), "UTF-8"));
    if (!header.optString("alg").equals("RS256"))
      throw new IOException("Unsupported identity signature");
    JSONObject key = null;
    for (int i = 0; i < keys.length(); i++) {
      JSONObject k = keys.getJSONObject(i);
      if (k.optString("kid").equals(header.optString("kid")) && k.optString("kty").equals("RSA")) {
        key = k;
        break;
      }
    }
    if (key == null) throw new IOException("Identity signing key unavailable");
    PublicKey pub =
        KeyFactory.getInstance("RSA")
            .generatePublic(
                new RSAPublicKeySpec(
                    new BigInteger(1, decode(key.getString("n"))),
                    new BigInteger(1, decode(key.getString("e")))));
    Signature sig = Signature.getInstance("SHA256withRSA");
    sig.initVerify(pub);
    sig.update((parts[0] + "." + parts[1]).getBytes("US-ASCII"));
    if (!sig.verify(decode(parts[2]))) throw new IOException("Identity signature check failed");
    JSONObject id = new JSONObject(new String(decode(parts[1]), "UTF-8"));
    Object aud = id.opt("aud");
    boolean match = client.equals(aud);
    if (aud instanceof JSONArray) {
      JSONArray a = (JSONArray) aud;
      for (int i = 0; i < a.length(); i++) match |= client.equals(a.optString(i));
      if (a.length() > 1 && !client.equals(id.optString("azp"))) match = false;
    }
    long now = System.currentTimeMillis() / 1000;
    if (!"https://auth.openai.com".equals(id.optString("iss"))
        || !match
        || id.optLong("exp") <= now
        || id.optLong("iat", now) > now + 120
        || id.optLong("nbf", 0) > now + 120
        || !nonce.equals(id.optString("nonce"))
        || id.optString("sub").isEmpty()) throw new IOException("Identity claims check failed");
    return id;
  }

  static boolean hasPlan(JSONObject p) {
    return p != null
        && Arrays.asList(p.optString("scope").split(" ")).contains("chatgpt.tokens.use.direct");
  }

  static void replaceTokens(JSONObject p, JSONObject t) throws Exception {
    if (!"Bearer".equalsIgnoreCase(t.optString("token_type", "Bearer")))
      throw new IOException("Unsupported token type");
    p.put("access_token", t.getString("access_token"));
    if (t.has("refresh_token")) p.put("refresh_token", t.getString("refresh_token"));
    p.put("expires_at", System.currentTimeMillis() + t.getLong("expires_in") * 1000);
    if (t.has("scope")) p.put("scope", t.getString("scope"));
  }

  synchronized String token() throws Exception {
    JSONObject p = active();
    if (!hasPlan(p) || !p.has("access_token"))
      throw new IOException("Connect a ChatGPT account and enable plan usage in Settings");
    if (p.optLong("expires_at") < System.currentTimeMillis() + 90000) {
      if (!p.has("refresh_token")) throw new IOException("Sign in again to renew this connection");
      Map<String, String> g = new LinkedHashMap<>();
      g.put("grant_type", "refresh_token");
      g.put("client_id", p.getString("client_id"));
      g.put("refresh_token", p.getString("refresh_token"));
      g.put("resource", RESOURCE);
      replaceTokens(p, Net.postForm(TOKEN, g));
      saveProfile(p);
      if (!hasPlan(p)) throw new IOException("ChatGPT plan permission is no longer enabled");
    }
    return p.getString("access_token");
  }

  synchronized String signOut() throws Exception {
    JSONObject p = active();
    if (p == null) return "No active account";
    boolean revoked = false;
    try {
      if (p.has("refresh_token")) {
        Map<String, String> g = new LinkedHashMap<>();
        g.put("token", p.getString("refresh_token"));
        g.put("token_type_hint", "refresh_token");
        g.put("client_id", p.getString("client_id"));
        Net.request(
            "https://auth.openai.com/api/accounts/oauth/revoke",
            null,
            Net.form(g),
            "application/x-www-form-urlencoded");
        revoked = true;
      }
    } catch (Exception ignored) {
    }
    for (String k :
        new String[] {"access_token", "refresh_token", "id_token", "scope", "expires_at"})
      p.remove(k);
    saveProfile(p);
    return revoked
        ? "Signed out"
        : "Signed out locally. Remote revocation was not confirmed; disconnect the app in ChatGPT"
            + " Settings.";
  }

  static String safe(Exception e) {
    String s = e.getMessage();
    return s == null
        ? e.getClass().getSimpleName()
        : Json.clip(
            s.replaceAll(
                "(?i)(access_token|refresh_token|id_token|code_verifier)[\\s\"=:]+[^\\s,}]+",
                "$1=[redacted]"),
            900);
  }
}
