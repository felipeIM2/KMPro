#!/usr/bin/env node
/**
 * Read the values each app committed to, out of the recorded video.
 *
 * Gigu's card is Flutter: it never publishes its text into the accessibility
 * tree, and uiautomator only sees the active window, so the pixels are the only
 * place its numbers exist. KMPro's card is a plain native overlay but the same
 * applies, and reading both through the same OCR keeps the comparison honest -
 * neither app gets a better extractor than the other.
 *
 * tesseract.js rather than tesseract/CLI: this machine has no pip, no ensurepip
 * and no passwordless sudo, but node is already here.
 *
 * Usage:
 *   node spy-ocr.js <video.mp4> [--fps 5] [--lang por+eng] [--json out.json]
 *                   [--from 36] [--to 46]
 *
 * --from/--to restrict the pass to a window of the session. Reading a whole
 * 90 s video is slow, and the interesting window is always known up front: the
 * seconds around a VALID OFFER in the KMPro log.
 */

const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');
const { createWorker } = require('tesseract.js');

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const video = process.argv[2];
if (!video || !fs.existsSync(video)) {
  console.error('uso: node spy-ocr.js <video.mp4> [--fps N] [--lang L] [--json out] [--from S] [--to S]');
  process.exit(1);
}
const fps = Number(arg('fps', '5'));
const lang = arg('lang', 'por+eng');
const jsonOut = arg('json', null);
const from = arg('from', null);
const to = arg('to', null);
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'spyframes-'));

/**
 * Frame selection: 3 fps is enough to place an appearance on the timeline, and
 * the numbers only have to be read once per *distinct* screen. Frames where
 * nothing moved (the card sitting there) are near-identical, so a cheap
 * signature lets us OCR one frame per visual state instead of all of them -
 * which matters, because tesseract on a 1280x800 frame costs seconds.
 */
function extractFrames() {
  const out = path.join(work, 'f%05d.png');
  const args = ['-loglevel', 'error'];
  if (from !== null) args.push('-ss', from);
  if (to !== null) args.push('-to', to);
  args.push('-i', video, '-vf', `fps=${fps}`, out);
  execFileSync('ffmpeg', args, { stdio: 'inherit' });
  return fs.readdirSync(work).filter((f) => f.endsWith('.png')).sort();
}

/** 16x16 grayscale histogram signature; cheap, stable enough to spot a change. */
function signature(file) {
  const buf = execFileSync('ffmpeg', [
    '-loglevel', 'error', '-i', file,
    '-vf', 'scale=16:16,format=gray',
    '-f', 'rawvideo', '-pix_fmt', 'gray', 'pipe:1',
  ], { maxBuffer: 1 << 20 });
  let h = 0;
  for (let i = 0; i < buf.length; i++) h = (h * 31 + buf[i]) >>> 0;
  return h;
}

function similarity(a, b) {
  if (!a || !b) return 0;
  const diff = Math.abs(a - b) >>> 0;
  return 1 - diff / 4294967295;
}

const BRL = /R\$\s*([0-9]{1,4}(?:[.,][0-9]{2})?)/gi;
const KM = /([0-9]{1,3}(?:[.,][0-9])?)\s*km/gi;
const MIN = /([0-9]{1,3})\s*min/gi;
const GPK = /(?:ganho|lucro)\s*(?:por|\/)\s*(?:km)?\s*R?\$?\s*([0-9]+(?:[.,][0-9]{1,2})?)/gi;

function extract(text) {
  const one = (re) => {
    const m = [...text.matchAll(re)].map((x) => x[1]);
    return m.length ? m : [];
  };
  return {
    brl: one(BRL),
    km: one(KM),
    min: one(MIN),
    ganho: one(GPK),
  };
}

async function main() {
  console.error(`extraindo frames a ${fps} fps de ${path.basename(video)} ...`);
  const frames = extractFrames();
  console.error(`${frames.length} frames em ${work}`);

  const worker = await createWorker(lang);
  const seen = [];
  let prevSig = null;
  let lastKey = null;

  for (const f of frames) {
    const full = path.join(work, f);
    const idx = parseInt(f.replace(/\D/g, ''), 10) - 1;
    // With -ss the frame counter restarts at 1, so the window offset has to be
    // added back or every timestamp would be relative to the cut point.
    const t = (from === null ? 0 : Number(from)) + idx / fps;

    const sig = signature(full);
    const sim = similarity(prevSig, sig);
    prevSig = sig;

    // Only read a frame whose pixels actually changed, and only keep the text
    // if it differs from what we last saw. Between those two filters a 90 s
    // session lands on a handful of OCR passes instead of 270.
    if (lastKey !== null && sim > 0.995) continue;

    const { data } = await worker.recognize(full);
    const text = (data.text || '').replace(/\n{2,}/g, '\n').trim();

    const key = text.replace(/\s+/g, '');
    if (key === lastKey) continue;
    lastKey = key;

    seen.push({
      frame: f,
      t: Number(t.toFixed(2)),
      sim: Number(sim.toFixed(4)),
      text,
      fields: extract(text),
    });
    console.error(`  t=${t.toFixed(2)}s  ${f}  ${text.split('\n').length} linhas`);
  }

  await worker.terminate();

  const report = {
    video: path.basename(video),
    fps,
    window: from === null && to === null ? null : { from, to },
    frames: frames.length,
    states: seen,
  };
  console.log(JSON.stringify(report, null, 2));

  if (jsonOut) {
    fs.writeFileSync(jsonOut, JSON.stringify(report, null, 2));
    console.error(`\njson: ${jsonOut}`);
  }
  fs.rmSync(work, { recursive: true, force: true });
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});