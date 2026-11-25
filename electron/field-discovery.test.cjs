const { deepEqual } = require("node:assert/strict");
const { test } = require("node:test");
const { tailnetCandidates } = require("./field-discovery.cjs");

test("tailnet discovery uses online peer addresses and the Field and Serve ports", () => {
  const status = {
    BackendState: "Running",
    Self: { TailscaleIPs: ["100.64.1.1"] },
    Peer: {
      studio: { Online: true, HostName: "Studio", DNSName: "studio.example.ts.net.", TailscaleIPs: ["100.64.1.2"] },
      render: { Online: true, HostName: "Render", DNSName: "outside.example.org.", TailscaleIPs: ["fd7a:115c:a1e0::2"] },
      offline: { Online: false, TailscaleIPs: ["100.64.1.3"] },
      expired: { Online: true, Expired: true, TailscaleIPs: ["100.64.1.4"] },
      self: { Online: true, TailscaleIPs: ["100.64.1.1"] },
      outside: { Online: true, DNSName: "outside.example.ts.net.", TailscaleIPs: ["192.168.1.2", "100.128.1.2", "::1"] },
    },
  };
  deepEqual(tailnetCandidates(status), [
    { name: "Studio", ip: "100.64.1.2", port: 9092, url: "https://100.64.1.2:9092" },
    { name: "Studio", ip: "100.64.1.2", port: 443, dns: "studio.example.ts.net", url: "https://studio.example.ts.net" },
    { name: "Studio", ip: "100.64.1.2", port: 8443, dns: "studio.example.ts.net", url: "https://studio.example.ts.net:8443" },
    { name: "Render", ip: "fd7a:115c:a1e0::2", port: 9092, url: "https://[fd7a:115c:a1e0::2]:9092" },
  ]);
  deepEqual(tailnetCandidates({ ...status, BackendState: "Stopped" }), []);
});
