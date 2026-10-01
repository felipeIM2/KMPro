#!/usr/bin/env python3
"""Turn a kmpro-spy session into a reaction-time comparison.

Two clocks arrive in a session and neither is the other's:

  * the on-device sampler runs on the boot clock (/proc/uptime)
  * logcat -v epoch runs on absolute epoch seconds

Both are rebased onto the moment the session opened, recorded in meta.txt and on
the sampler's START line. Everything downstream is relative to t=0, so the two
sources are directly comparable without ever converting between clocks.

Gigu is a release build and is not debuggable, so its own Log.* output never
reaches logcat. Its half of the report comes from the sampler (MediaProjection
session, overlay window visibility) and from the screen recording.
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

RIDE_RE = re.compile(r"(com\.ubercab\.driver|com\.app99\.driver|uber|99pop)")

# KMPro log lines worth timing, in pipeline order. First match wins per line.
STAGE_PATTERNS = [
    ("disparo do OCR", re.compile(r"\[KMPro\]\[Ocr\] iniciando captura")),
    ("takeScreenshot", re.compile(r"\[KMPro\]\[Ocr\] chamando takeScreenshot")),
    ("screenshot recebido", re.compile(r"\[KMPro\]\[Ocr\] onSuccess")),
    ("ocr concluido", re.compile(r"\[KMPro\]\[Ocr\] reason=.*linhas=")),
    # The commit point is the OCR path's VALID OFFER, not the parser's own log:
    # offerMap() feeds OfferManager.addOffer() directly and the parser's message
    # only covers the accessibility-tree path.
    ("oferta validada", re.compile(r"\[KMPro\]\[Ocr\] VALID OFFER")),
    ("oferta recusada", re.compile(r"\[KMPro\]\[OfferParser\] NOT AN OFFER")),
    ("cardao enfileirado", re.compile(r"cartao em fila")),
    ("cardao na tela", re.compile(r"KMProOverlay.*addView ok")),
    ("cardao atualizado", re.compile(r"KMProOverlay.*atualizando")),
]


@dataclass
class Mark:
    at_ms: float
    who: str
    what: str
    detail: str = ""


@dataclass
class Timeline:
    marks: list[Mark] = field(default_factory=list)
    cpu: dict[str, list[int]] = field(default_factory=lambda: {"gigu": [], "kmpro": []})
    uptime_start_ms: float | None = None


def num(value: str | None, default: float = 0.0) -> float:
    try:
        return float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return default


def load_timeline(path: Path) -> Timeline:
    tl = Timeline()
    t0: float | None = None
    for raw in path.read_text(errors="replace").splitlines():
        parts = raw.split("|", 2)
        if len(parts) < 3:
            continue
        at = num(parts[0], -1.0)
        event, detail = parts[1], parts[2]
        if at < 0:
            continue
        if event == "START":
            t0 = at
            tl.uptime_start_ms = at
            continue
        if t0 is None:
            t0 = at
        rel = at - t0

        if event == "FG":
            tl.marks.append(Mark(rel, "ambiente", "foreground", detail))
        elif event == "CAPTURE":
            on = detail.rstrip().endswith("1")
            tl.marks.append(
                Mark(rel, "gigu", "captura de tela LIGADA" if on else "captura de tela DESLIGADA", detail)
            )
        elif event == "GIGU_CARD":
            on = "VISIVEL" in detail
            tl.marks.append(Mark(rel, "gigu", "cartao NA TELA" if on else "cartao fora", detail))
        elif event == "KMPRO_CARD":
            on = "VISIVEL" in detail
            tl.marks.append(Mark(rel, "kmpro", "cartao NA TELA" if on else "cartao fora", detail))
        elif event == "CPU":
            mg = re.search(r"gigu_pid=(\S*)\s+ticks=(\d+)", detail)
            mk = re.search(r"kmpro_pid=(\S*)\s+ticks=(\d+)", detail)
            if mg:
                tl.cpu["gigu"].append(int(mg.group(2)))
            if mk:
                tl.cpu["kmpro"].append(int(mk.group(2)))
    return tl


def load_kmpro_logcat(path: Path, epoch_start: float | None) -> list[Mark]:
    if not path.exists():
        return []
    marks: list[Mark] = []
    # logcat -v epoch right-pads each line with leading spaces to a fixed width
    # and separates fields with single spaces, so the line is stripped before
    # matching rather than anchored at column 0.
    line_re = re.compile(r"^(\d+\.\d+)\s+(\d+)\s+(\d+)\s+[VDIWEF]\s+(\S+?):\s?(.*)$")
    for raw in path.read_text(errors="replace").splitlines():
        if "KMPro" not in raw:
            continue
        m = line_re.match(raw.strip())
        if not m:
            continue
        epoch = float(m.group(1))
        body = m.group(5).strip()
        for label, pat in STAGE_PATTERNS:
            if pat.search(body):
                rel = (epoch - epoch_start) * 1000.0 if epoch_start is not None else 0.0
                marks.append(Mark(rel, "kmpro", label, body[:110]))
                break
    return marks


def read_meta(d: Path) -> float | None:
    f = d / "meta.txt"
    if not f.exists():
        return None
    m = re.search(r"epoch=([0-9.]+)", f.read_text(errors="replace"))
    return float(m.group(1)) if m else None


def sec(value: float) -> str:
    return f"{value / 1000:7.2f}s"


def head(title: str) -> None:
    print(f"\n\033[1m{title}\033[0m")


def first_after(marks: list[Mark], anchor: float, who: str, needle: str) -> Mark | None:
    for mk in marks:
        if mk.who == who and mk.at_ms >= anchor - 1 and needle in mk.what:
            return mk
    return None


def main() -> int:
    if len(sys.argv) < 2:
        print("uso: kmpro-spy-report.py <dir-da-sessao>", file=sys.stderr)
        return 1
    d = Path(sys.argv[1])
    if not (d / "timeline.txt").exists():
        print(f"sem timeline.txt em {d}", file=sys.stderr)
        return 1

    tl = load_timeline(d / "timeline.txt")
    logs = load_kmpro_logcat(d / "logcat.txt", read_meta(d))
    marks = sorted(tl.marks + logs, key=lambda m: m.at_ms)

    if not marks:
        print("nada capturado nesta sessao.")
        return 1

    head("LINHA DO TEMPO (t=0 = inicio da sessao)")
    who_tag = {"gigu": "GIGU ", "kmpro": "KMPro", "ambiente": "AMBI"}
    # A polled OCR emits the same stages forever; printing 200 poll cycles tells
    # us nothing. Only the ride-app transitions and the offers are itemised here -
    # the steady-state cost is in the reaction table below.
    notable = {"gigu", "ambiente"}
    for mk in marks:
        if mk.who == "kmpro" and mk.what not in ("oferta validada", "oferta recusada", "cardao na tela"):
            continue
        if mk.who not in notable and mk.what not in ("oferta validada", "oferta recusada", "cardao na tela"):
            continue
        print(f"  {sec(mk.at_ms)}  {who_tag.get(mk.who, mk.who)}  {mk.what}")
        if mk.detail and mk.what != "foreground":
            print(f"                     {mk.detail}")
    polls = [m for m in marks if m.who == "kmpro" and m.what == "disparo do OCR"]
    if polls:
        print(f"  ({len(polls)} sondagens de OCR periodicas omitidas; ver tabela abaixo)")

    rides = [m for m in marks if m.who == "ambiente" and RIDE_RE.search(m.detail)]

    head("TEMPO DE REACAO")
    if not rides:
        print("  O app de corrida nao entrou em foreground durante a sessao.")
        print("  Rode com ele aberto e dispare uma oferta dentro da janela.")
    for ride in rides:
        print(f"\n  --- {ride.detail} entrou em foreground {sec(ride.at_ms)} ---")
# Stage list, and the definition of "a reaction". The first OCR poll after the
    # ride app appears is not a reaction: it reads the screen before the offer
    # exists and returns nothing. The reaction starts at the first screenshot
    # that follows a change in the OCR line count, because that change is the
    # offer drawing itself.
    steps = [
        ("gigu", "captura de tela LIGADA"),
        ("gigu", "cartao NA TELA"),
        ("kmpro", "disparo do OCR"),
        ("kmpro", "takeScreenshot"),
        ("kmpro", "ocr concluido"),
        ("kmpro", "oferta validada"),
        ("kmpro", "cardao na tela"),
    ]

    first_offer = next((m for m in logs if m.what == "oferta validada" and m.at_ms >= ride.at_ms), None)

    # What is genuinely measurable here is the latency of KMPro's own pipeline:
    # the screenshot that carried the offer, the OCR that read it, and the commit.
    # "When the offer appeared on screen" has no timestamp available - nothing on
    # this device reports it - so the chain is reported from the first capture
    # that saw the offer, and the poll that would have caught it is derived from
    # the poll interval rather than guessed.
    polls = [m for m in logs if m.what == "ocr concluido" and m.at_ms >= ride.at_ms]
    shots = [m for m in logs if m.what == "takeScreenshot" and m.at_ms >= ride.at_ms]

    print(f"    oferta mais recente: {first_offer.detail if first_offer else 'nenhuma'}")
    if first_offer:
        shot = [s for s in shots if s.at_ms <= first_offer.at_ms]
        if shot:
            s0 = shot[-1]
            print(f"    captura que a enxergou        {sec(s0.at_ms)}"
                  f"   (+{(first_offer.at_ms - s0.at_ms) / 1000:.2f}s ate o commit)")
        print(f"    leitura + parse + commit     {sec(first_offer.at_ms)}"
              f"   (nao ha carimbo de 'oferta apareceu' observavel)")

    # Every KMPro row is read from the same anchor - the capture that carried the
    # offer - so the rows are comparable with each other and land after it.
    table_anchor = ride.at_ms
    if first_offer:
        carried = [s for s in shots if s.at_ms <= first_offer.at_ms]
        table_anchor = carried[-1].at_ms if carried else ride.at_ms

    # Per-cycle pairing, not independent "first at or after" lookups. The stages
    # of one OCR pass are logged out of order (the takeScreenshot call is stamped
    # when the capture is requested, the result line when recognition returns), so
    # matching each stage independently pairs a shot with the *next* pass's result
    # and can print a negative duration.
    cycles: list[dict[str, Mark]] = []
    for ocr_mark in [m for m in logs if m.what == "ocr concluido" and m.at_ms >= ride.at_ms]:
        prior = [s for s in logs
                 if s.what == "takeScreenshot" and ride.at_ms <= s.at_ms <= ocr_mark.at_ms]
        cycles.append({
            "disparo do OCR": prior[-1] if prior else None,
            "takeScreenshot": prior[-1] if prior else None,
            "ocr concluido": ocr_mark,
            "oferta validada": next((m for m in logs
                                     if m.what == "oferta validada"
                                     and ocr_mark.at_ms <= m.at_ms <= ocr_mark.at_ms + 500), None),
            "cardao na tela": next((m for m in logs
                                    if m.what == "cardao na tela"
                                    and ocr_mark.at_ms <= m.at_ms <= ocr_mark.at_ms + 3000), None),
        })

    print(f"    {len(cycles)} ciclos de OCR medidos")
    costs = [(c["ocr concluido"].at_ms - c["takeScreenshot"].at_ms) / 1000
             for c in cycles if c["takeScreenshot"] and c["ocr concluido"]]
    if costs:
        costs_sorted = sorted(costs)
        print(f"    latencia captura->texto: min {min(costs):.2f}s  "
              f"mediana {costs_sorted[len(costs) // 2]:.2f}s  max {max(costs):.2f}s")

    # Pre-seeded with every step so the summary below can be unconditional:
    # a session with no offer must report n/a, not raise.
    found: dict[str, Mark | None] = {needle: None for _, needle in steps}
    if first_offer:
        for key in ("disparo do OCR", "takeScreenshot", "ocr concluido",
                    "oferta validada", "cardao na tela"):
            found[key] = next((c[key] for c in cycles if c["oferta validada"] is not None
                               and c[key] is not None), None)
        found["cartao NA TELA"] = first_after(marks, ride.at_ms, "gigu", "cartao NA TELA")
        found["captura de tela LIGADA"] = first_after(marks, ride.at_ms, "gigu", "captura de tela LIGADA")

        for who, needle in steps:
            mk = found.get(needle)
            rel = "   n/a" if mk is None else f"{(mk.at_ms - ride.at_ms) / 1000:7.2f}s"
            tag = "GIGU " if who == "gigu" else "KMPro"
            print(f"    {tag}  {needle:<26} {rel}")

        shot = found["takeScreenshot"]
        ocr = found["ocr concluido"]
        if shot and ocr:
            print(f"           -> custo do OCR        {(ocr.at_ms - shot.at_ms) / 1000:7.2f}s")
    parsed = found["oferta validada"]
    card = found["cardao na tela"]
    if parsed and card:
        print(f"           -> parse + render      {(card.at_ms - parsed.at_ms) / 1000:7.2f}s")
        g = found["cartao NA TELA"]
        k = found["cardao na tela"]
        if g and k:
            diff = (g.at_ms - k.at_ms) / 1000
            side = "GIGU mais rapido" if diff < 0 else "KMPro mais rapido"
            print(f"\n    DIFERENCA gigu - kmpro   {diff:+7.2f}s   ({side})")
        elif g and not k:
            print(f"\n    GIGU mostrou cartao em {(g.at_ms - ride.at_ms) / 1000:.2f}s; "
                  f"KMPro nao mostrou nesta janela.")
        elif k and not g:
            print(f"\n    KMPro mostrou cartao em {(k.at_ms - ride.at_ms) / 1000:.2f}s; "
                  f"Gigu nao mostrou nesta janela.")

    head("O QUE O KMPRO DECIDIU")
    accepted = [m for m in marks if m.who == "kmpro" and m.what == "oferta validada"]
    rejected = [m for m in marks if m.who == "kmpro" and m.what == "oferta recusada"]
    for mk in accepted:
        print(f"  ACEITOU  {sec(mk.at_ms)}  {mk.detail}")
    for mk in rejected:
        print(f"  RECUSOU  {sec(mk.at_ms)}  {mk.detail}")
    if not accepted and not rejected:
        print("  O parser nao registrou nenhuma oferta nesta sessao.")
        print("  Veja o rejectReason no logcat; a causa usual e o OCR pausado")
        print("  (KMPro nao relê a tela enquanto o proprio cartao esta visivel).")

    head("IMPRESSAO DIGITAL DE CPU")
    print("  (ticks de CPU por amostra; OCR continuo mantem a media alta,")
    print("   OCR por sondagem produz poucas rajadas)\n")
    for who in ("gigu", "kmpro"):
        vals = tl.cpu[who]
        if not vals:
            print(f"  {who:<6} sem amostras")
            continue
        active = [v for v in vals if v > 0]
        pct = 100.0 * len(active) / len(vals)
        print(
            f"  {who:<6} amostras={len(vals):<6} ativo={pct:5.1f}%  "
            f"ticks/smedio={sum(vals) / len(vals):6.2f}  max={max(vals)}"
        )

    video = d / "screen.mp4"
    if video.exists():
        size = video.stat().st_size / 1e6
        print(f"\n\033[1mVIDEO\033[0m {video} ({size:.1f} MB)")
        if size > 0.01:
            print("  os valores que o Gigu exibiu estao aqui. Este relatorio mede")
            print("  tempo, nao pixels. Para ler o cartao dele:")
            print(f"    ffmpeg -i {video} -vf fps=5 frames/%05d.png")
        else:
            print("  AVISO: video vazio; a sessao de tela nao gravou.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())