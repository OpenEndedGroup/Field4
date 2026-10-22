// Runs inside the transparent transition view; keep this function self-contained.
async function paintTransitionFrame({baseSource, guestSource, guestStyle}) {
  const paint = async (canvas, source) => {
    canvas.hidden = !source;
    if (!source) return;
    const image = new Image();
    await new Promise((resolve, reject) => {
      image.onload = resolve;
      image.onerror = () => reject(new Error('Could not load the captured audience frame'));
      image.src = source;
      if (image.complete && image.naturalWidth) resolve();
    });
    canvas.width = image.naturalWidth;
    canvas.height = image.naturalHeight;
    canvas.getContext('2d').drawImage(image, 0, 0);
    canvas.style.visibility = 'visible';
  };
  const base = document.getElementById('base');
  const guest = document.getElementById('guest');
  guest.style.cssText = guestStyle;
  await Promise.all([paint(base, baseSource), paint(guest, guestSource)]);
  // Stay transparent over the outgoing live view until its captured pixels are drawn.
  // Revealing the black backing earlier exposes a frame while Image.onload is pending.
  if (!baseSource) document.body.style.background = '#000';
}

module.exports = {paintTransitionFrame};
