// The check box displays its verdict only when every note it read was right (it throws otherwise).
for (let i = 0; i < 60; i++) {
  if (document.querySelector('#kotlin-line-notes-ok')) return { ok: true };
  await new Promise((r) => setTimeout(r, 250));
}
return { ok: false, reason: 'the check box did not display its verdict' };
