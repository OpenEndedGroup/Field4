const { execFile } = require("node:child_process");
const { request } = require("node:https");
const { isIP } = require("node:net");
const { promisify } = require("node:util");
const { remoteFieldUrl } = require("./remote-field.cjs");

const execFileAsync = promisify(execFile);
const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);
const TAILSCALE_APP = "/Applications/Tailscale.app/Contents/MacOS/Tailscale";

async function discoverLanFields(initialUrl, fetchLocal) {
  try {
    const local = remoteFieldUrl(initialUrl);
    if (!LOOPBACK_HOSTS.has(local.hostname)) return [];
    const response = await fetchLocal(new URL("/api/remote-field/lan", local).href, {
      cache: "no-store", credentials: "omit", redirect: "error", signal: AbortSignal.timeout(2500),
    });
    if (!response.ok) return [];
    const fields = await response.json();
    if (!Array.isArray(fields)) return [];
    return fields.flatMap(field => {
      try {
        const url = remoteFieldUrl(field.url);
        if (url.protocol !== "https:" || isIP(url.hostname) !== 4 || url.hostname.startsWith("127.")) return [];
        if (typeof field.name !== "string") return [];
        return [{ name: field.name, url: url.origin, source: "LAN" }];
      } catch (_) {
        return [];
      }
    });
  } catch (_) {
    return [];
  }
}

async function tailscaleStatus() {
  const commands = process.platform === "darwin" ? ["tailscale", TAILSCALE_APP] : ["tailscale"];
  for (const command of commands) {
    try {
      const { stdout } = await execFileAsync(command, ["status", "--json"], {
        env: { ...process.env, TAILSCALE_BE_CLI: "1" }, timeout: 1500, maxBuffer: 4 * 1024 * 1024,
        windowsHide: true,
      });
      return JSON.parse(stdout);
    } catch (_) {
      // Try the macOS app binary when the CLI is absent from PATH.
    }
  }
  return null;
}

function tailnetIp(ip) {
  if (typeof ip !== "string") return false;
  if (isIP(ip) === 4) {
    const [first, second] = ip.split(".").map(Number);
    return first === 100 && second >= 64 && second <= 127;
  }
  return isIP(ip) === 6 && ip.toLowerCase().startsWith("fd7a:115c:a1e0:");
}

function tailnetCandidates(status) {
  if (status?.BackendState !== "Running") return [];
  const selfIps = new Set(status.Self?.TailscaleIPs ?? []);
  return Object.values(status.Peer ?? {}).flatMap(peer => {
    if (!peer.Online || peer.Expired) return [];
    const addresses = (peer.TailscaleIPs ?? []).filter(ip => tailnetIp(ip) && !selfIps.has(ip));
    const ip = addresses.find(ip => isIP(ip) === 4) ?? addresses[0];
    if (!ip) return [];
    const host = isIP(ip) === 6 ? `[${ip}]` : ip;
    const dns = typeof peer.DNSName === "string" ? peer.DNSName.replace(/\.$/, "").toLowerCase() : "";
    const name = peer.HostName || dns || ip;
    const candidates = [{ ip, port: 9092, url: `https://${host}:9092`, name }];
    if (/^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.){2,}ts\.net$/.test(dns)) {
      candidates.push({ ip, dns, port: 443, url: `https://${dns}`, name });
      candidates.push({ ip, dns, port: 8443, url: `https://${dns}:8443`, name });
    }
    return candidates;
  });
}

function probe(candidate, path, method, signal) {
  return new Promise(resolve => {
    // Connect only to the IP supplied by the local Tailscale client. For Serve, use the
    // peer's MagicDNS name for TLS and Host. Direct Field commonly uses its own dev cert.
    const req = request({
      host: candidate.ip, servername: candidate.dns, port: candidate.port, path, method,
      headers: { Host: new URL(candidate.url).host }, rejectUnauthorized: !!candidate.dns,
      agent: false, signal,
    }, response => {
      response.on("error", () => resolve(false));
      if (response.statusCode !== 200) {
        response.destroy();
        resolve(false);
        return;
      }
      if (method === "HEAD") {
        response.resume();
        resolve(/^application\/json(?:\s*;|$)/i.test(response.headers["content-type"] ?? ""));
        return;
      }
      let body = "";
      response.setEncoding("utf8");
      response.on("data", chunk => {
        body += chunk;
        if (body.length > 16) {
          response.destroy();
          resolve(false);
        }
      });
      response.on("end", () => resolve(body.trim() === "ok"));
    });
    req.on("error", () => resolve(false));
    req.end();
  });
}

async function discoverTailnetFields() {
  const candidates = tailnetCandidates(await tailscaleStatus());
  const searchTime = AbortSignal.timeout(4200);
  const results = [];
  let next = 0;
  const worker = async () => {
    while (!searchTime.aborted && next < candidates.length) {
      const candidate = candidates[next++];
      const signal = AbortSignal.any([searchTime, AbortSignal.timeout(1700)]);
      if (await probe(candidate, "/health", "GET", signal) &&
          await probe(candidate, "/api/instance-info", "HEAD", signal)) {
        results.push({ name: candidate.name, url: candidate.url, source: "Tailscale" });
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(12, candidates.length) }, worker));
  return results;
}

async function discoverRemoteFields(initialUrl, fetchLocal) {
  const [lan, tailnet] = await Promise.allSettled([discoverLanFields(initialUrl, fetchLocal), discoverTailnetFields()]);
  const fields = [...(lan.value ?? []), ...(tailnet.value ?? [])];
  return Array.from(new Map(fields.map(field => [field.url, field])).values())
    .sort((a, b) => a.source.localeCompare(b.source) || a.name.localeCompare(b.name) || a.url.localeCompare(b.url));
}

module.exports = { discoverRemoteFields, tailnetCandidates };
