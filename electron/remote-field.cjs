const { X509Certificate } = require("node:crypto");

function remoteFieldUrl(address) {
  const url = new URL(address);
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password) {
    throw new Error("Enter an HTTP or HTTPS Field URL without credentials.");
  }
  return url;
}

function isRemoteFieldUrl(address, origin) {
  try {
    return remoteFieldUrl(address).origin === origin;
  } catch (_) {
    return false;
  }
}

function selfSignedFingerprint(pem) {
  try {
    const certificate = new X509Certificate(pem);
    const now = Date.now();
    if (now < Date.parse(certificate.validFrom) || now > Date.parse(certificate.validTo)) return null;
    if (!certificate.checkIssued(certificate) || !certificate.verify(certificate.publicKey)) return null;
    return certificate.fingerprint256;
  } catch (_) {
    return null;
  }
}

function configureRemoteSession(session, origin, confirmCertificate) {
  session.setPermissionCheckHandler(() => false);
  session.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));

  const url = new URL(origin);
  if (url.protocol !== "https:") return;
  const hostname = url.hostname.replace(/^\[|\]$/g, "");
  let approvedFingerprint = null;
  let pending = null;

  session.setCertificateVerifyProc((request, callback) => {
    if (request.hostname !== hostname || request.errorCode === 0) return callback(-3);
    if (!["net::ERR_CERT_AUTHORITY_INVALID", "net::ERR_CERT_COMMON_NAME_INVALID"].includes(request.verificationResult)) {
      return callback(-3);
    }
    const fingerprint = selfSignedFingerprint(request.certificate?.data);
    if (!fingerprint) return callback(-3);
    if (approvedFingerprint) return callback(fingerprint === approvedFingerprint ? 0 : -3);

    // Chromium may verify the same certificate for several connections during startup.
    if (pending && pending.fingerprint !== fingerprint) return callback(-3);
    if (!pending) {
      const decision = confirmCertificate(fingerprint).then(approved => {
        if (approved) approvedFingerprint = fingerprint;
        return approved;
      }).catch(() => false).finally(() => { pending = null; });
      pending = { fingerprint, decision };
    }
    pending.decision.then(approved => callback(approved ? 0 : -3));
  });
}

module.exports = { remoteFieldUrl, isRemoteFieldUrl, configureRemoteSession };
