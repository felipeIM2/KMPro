#!/bin/bash
# Observability harness to compare how Gigu (co.gigu.app) and KMPro react to a
# ride offer appearing on screen.
#
# Gigu is a release build and is NOT debuggable, so its own Log.* output is
# invisible to `adb logcat`. What IS observable from an unrooted device:
#
#   * its MediaProjection session  -> when Gigu actually starts looking at the
#     screen (isMediaProjectionEnabled in its Flutter/pigeon API)
#   * its overlay windows         -> when its card is on screen, per window state
#   * its /proc/<pid>/stat        -> the CPU fingerprint of a continuous OCR
#     stream (dense usage) versus a polled one (spikes every OCR_MIN_INTERVAL_MS)
#
# KMPro is a debug build, so its own pipeline stages are logged with
# millisecond timestamps and can be timed end to end.
#
# The sampler runs ON DEVICE (pushing it once) so a 120 ms tick costs one adb
# round trip instead of eight; uiautomator is ~3.5 s and is never used here.
#
# Usage:
#   ./kmpro-spy.sh preflight [serial]        # grants, capture state, versions
#   ./kmpro-spy.sh watch     [serial]        # live timeline until Ctrl-C
#   ./kmpro-spy.sh session   [serial] [sec]  # screenrecord + timeline -> spy-out/
#   ./kmpro-spy.sh report    [dir]          # reaction-time table from a session
set -u

SERIAL_DEFAULT="RXGL10544WH"
GIGU_PKG="co.gigu.app"
KMP_PKG="com.anonymous.KMPro"
RIDE_PKGS="com.ubercab.driver com.app99.driver"
REMOTE_SAMPLER="/data/local/tmp/kmpro_spy_sampler.sh"
REMOTE_LOG="/data/local/tmp/kmpro_spy_timeline.log"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_ROOT="$ROOT/spy-out"

adb_() { adb -s "$SERIAL" "$@"; }

die() { echo "ERRO: $*" >&2; exit 1; }

resolve_serial() {
  if [ "${1:-}" != "" ]; then
    SERIAL="$1"
  else
    local first
    first=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
    [ -n "$first" ] || die "nenhum dispositivo adb conectado"
    SERIAL="$first"
  fi
  adb_ get-state >/dev/null 2>&1 || die "dispositivo $SERIAL nao responde"
}

# ---------------------------------------------------------------- preflight --

cmd_preflight() {
  resolve_serial "${1:-}"
  echo "== KMPro spy :: preflight ($SERIAL) =="

  local sdk rel
  sdk=$(adb_ shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
  echo "   android .............. $sdk (sdk_int $(adb_ shell getprop ro.build.version.sdk | tr -d '\r'))"

  for p in $GIGU_PKG $KMP_PKG; do
    if ! adb_ shell pm path "$p" >/dev/null 2>&1; then
      echo "   $p ................... NAO INSTALADO"
      continue
    fi
    local rel dbg ov live
    rel=$(adb_ shell dumpsys package "$p" 2>/dev/null | grep -m1 versionName | tr -d '\r' | sed 's/.*=//')
    dbg=$(adb_ shell dumpsys package "$p" 2>/dev/null | grep -m1 'pkgFlags=' | grep -c DEBUGGABLE)
    ov=$(adb_ shell appops get "$p" SYSTEM_ALERT_WINDOW 2>/dev/null | grep -qiE 'allow|: *[0-9]+$' && echo sim || echo "default")
    # A debug build loads its JS from Metro over adb reverse; if the tunnel is
    # missing it launches and then shows a red screen instead of the app.
    live=""
    [ "$dbg" = 1 ] && live=$(adb_ reverse --list 2>/dev/null | grep -q 8081 && echo "metro ok" || echo "SEM METRO")
    echo "   $p"
    echo "      versao=$rel  debuggable=$([ "$dbg" = 1 ] && echo sim || echo NAO)  overlay=$ov${live:+  $live}"
  done

  echo
  echo "   -- acessibilidade --"
  adb_ shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r' | tr ':' '\n' | while read -r s; do
    [ -n "$s" ] && echo "      granted: $s"
  done

  # Read back what the system actually bound rather than what the manifest asks
  # for: dumpsys reports the granted capabilities, the event types really
  # delivered and the notification timeout in force. This is the number that
  # decides reaction time, and it is per-service, not a static constant.
  echo
  echo "   -- servicos de acessibilidade vinculados --"
  adb_ shell dumpsys accessibility 2>/dev/null | tr -d '\r' \
    | grep -oE "label=[^,]*, id=[^,]*|capabilities=[0-9]+|notificationTimeout=[0-9]+|eventTypes=\[[^]]*\]" \
    | while read -r tok; do echo "      $tok"; done

  echo
  echo "   -- sessao de captura de tela (Gigu) --"
  local mp
  mp=$(adb_ shell dumpsys media_projection 2>/dev/null | tr -d '\r')
  if echo "$mp" | grep -q "^null$"; then
    echo "      NENHUM app capturando a tela agora."
    echo "      Para o Gigu ler a oferta: abra o Gigu, ligue o Copiloto e aceite a"
    echo "      permissao de captura de tela. Sem isso ele nao le nada."
  else
    echo "$mp" | sed -n '1,12p' | sed 's/^/      /'
  fi

  echo
  echo "   -- apps de corrida instalados --"
  for r in $RIDE_PKGS; do
    adb_ shell pm path "$r" >/dev/null 2>&1 && echo "      $r ok"
  done

  # Can we actually see KMPro's own pipeline logs from here? A release build
  # still writes native Log.d, but if this comes back empty the reaction-time
  # column for KMPro has to come from video only.
  echo
  echo "   -- visibilidade do logcat --"
  adb_ logcat -c 2>/dev/null
  adb_ shell am start -n "$KMP_PKG/.MainActivity" >/dev/null 2>&1
  sleep 3
  local seen
  seen=$(adb_ logcat -d 2>/dev/null | grep -cE 'KMPro(Ocr|Offer|Overlay)')
  if [ "${seen:-0}" -gt 0 ]; then
    echo "      KMPro visivel no logcat ($seen linhas) - tempos de etapa sao mediveis"
  else
    echo "      AVISO: nenhum log do KMPro no logcat."
    echo "      Se for build release, instale o debug para medir as etapas:"
    echo "        npx expo run:android   (ou eas build --profile development)"
    echo "      Sem isso o tempo do KMPro sai so do video."
  fi

  echo
  echo "== proximo passo: ./kmpro-spy.sh watch (deixe rodando e dispare uma oferta) =="
}

# ------------------------------------------------------------------ sampler --

write_sampler() {
  cat >"$ROOT/scripts/.kmpro-spy-sampler.sh" <<'SAMPLER'
#!/system/bin/sh
# On-device timeline sampler. Emitted by scripts/kmpro-spy.sh.
# Line format: <uptime_ms>|<event>|<detail>
#
# The clock is /proc/uptime, not `date +%s%3N`: Android's shell does 32-bit
# arithmetic, so a millisecond epoch overflows to a negative number. The wall
# clock is carried once, on the START line, as a string so the report can line
# the sampler up against logcat.
OUT="${1:-/data/local/tmp/kmpro_spy_timeline.log}"
TICK="${2:-0.08}"
GIGU=co.gigu.app
KMP=com.anonymous.KMPro

up_ms() { awk '{ printf "%d", $1 * 1000 }' /proc/uptime; }
# emit <stamp> <event> <detail> - the stamp is passed in so a probe can log the
# instant it was taken rather than the instant the shell got around to writing.
emit() { echo "$(up_ms)|$2|$3" >>"$OUT"; }

# /proc/<pid>/stat fields 14/15 = utime/stime in clock ticks (100/s on most
# devices). The delta over the interval gives a hardware-independent CPU figure,
# which is the fingerprint of an OCR pipeline that is actually running.
cpu_ticks() {
  [ -n "$1" ] || { echo 0; return; }
  awk '{ print $14 + $15 }' "/proc/$1/stat" 2>/dev/null || echo 0
}

pgid() { pidof "$1" 2>/dev/null | awk '{ print $1 }'; }

# One dumpsys call yields the foreground window and both overlay states.
#
# Two traps, both hit on the real device:
#   * presence of the Window object is meaningless - Gigu keeps its overlay
#     window around with isVisible=false between rides
#   * the app's own Activity is also a window of that package, so "package
#     matches" is not enough either; only a window whose component has no "/"
#     (Window{hash u0 co.gigu.app}) is an overlay, while the activity reads
#     Window{hash u0 co.gigu.app/co.gigu.app.MainActivity}
#
# The component is pulled out with index/substr rather than match(s, re, arr):
# that three-argument form is a gawk extension and toybox awk on the tablet is
# not guaranteed to take it.
window_probe() {
  dumpsys window windows 2>/dev/null | awk -v g="$GIGU" -v k="$KMP" '
    /^  Window #/ {
      if (win != "" && shown) {
        if (mine == g) og = 1
        else if (mine == k) ok = 1
      }
      win = $0; mine = ""; shown = 0; bare = 0
      c = $0
      i = index(c, " u0 ")
      if (i > 0) {
        c = substr(c, i + 4)
        j = index(c, "}")
        if (j > 0) {
          c = substr(c, 1, j - 1)
          if (index(c, "/") == 0) {
            bare = 1
            if (index(c, g) == 1) mine = g
            else if (index(c, k) == 1) mine = k
          }
        }
      }
    }
    /^    mOwnerUid=/ {
      if (bare && index($0, "package=" g) > 0) mine = g
      else if (bare && index($0, "package=" k) > 0) mine = k
    }
    /^    isVisible=/  && /true/ { shown = 1 }
    /^    isOnScreen=/ && /true/ { shown = 1 }
    END {
      if (win != "" && shown) {
        if (mine == g) og = 1
        else if (mine == k) ok = 1
      }
      print (og ? 1 : 0) " " (ok ? 1 : 0)
    }'
}

# mCurrentFocus does not exist in `dumpsys window windows`; it is only in the
# plain `dumpsys window`. grep -m1 closes the pipe on the first hit, which makes
# dumpsys die on SIGPIPE early, so this is cheap enough for every few ticks.
focus_probe() {
  dumpsys window 2>/dev/null | grep -m1 "mCurrentFocus=" \
    | sed 's/.* u0 //; s/}.*//; s/ *$//' | tr -d '\r'
}

capture_state() {
  if dumpsys media_projection 2>/dev/null | grep -q "packageName="; then echo 1; else echo 0; fi
}

: >"$OUT"
# START carries the epoch as an opaque string: <uptime_ms>|START|epoch=<s>s tick=<t>s
echo "$(up_ms)|START|epoch=$(date +%s)s tick=${TICK}s" >"$OUT"

pg_g=$(pgid "$GIGU"); pg_k=$(pgid "$KMP")
t_g=$(cpu_ticks "$pg_g"); t_k=$(cpu_ticks "$pg_k")
ov_g=0; ov_k=0; cap=0; fg=""
last_g=0; last_k=0; last_cap=0; last_fg=""
n=0

while :; do
  sleep "$TICK"
  n=$(( n + 1 ))
  now=$(up_ms)

  # Cheap every tick: /proc reads.
  pg=$(pgid "$GIGU"); [ -n "$pg" ] && pg_g="$pg"
  pk=$(pgid "$KMP");  [ -n "$pk" ] && pg_k="$pk"
  n_g=$(cpu_ticks "$pg_g"); n_k=$(cpu_ticks "$pg_k")
  d_g=$(( n_g - t_g )); t_g=$n_g
  d_k=$(( n_k - t_k )); t_k=$n_k
  emit "$now" CPU "gigu_pid=$pg_g ticks=$d_g kmpro_pid=$pg_k ticks=$d_k"

  # The two dumpsys probes are ~70 ms and ~40 ms on this device. Interleaving
  # them halves the cost: each signal is still sampled every ~2 ticks.
  if [ $(( n % 2 )) -eq 0 ]; then
    probe=$(window_probe)
    s_g=$(echo "$probe" | awk '{ print $1 }')
    s_k=$(echo "$probe" | awk '{ print $2 }')
    if [ "$s_g" != "$ov_g" ]; then
      emit "$now" GIGU_CARD "$([ "$s_g" = 1 ] && echo 'overlay VISIVEL' || echo 'overlay oculto')"
      ov_g=$s_g
    fi
    if [ "$s_k" != "$ov_k" ]; then
      emit "$now" KMPRO_CARD "$([ "$s_k" = 1 ] && echo 'overlay VISIVEL' || echo 'overlay oculto')"
      ov_k=$s_k
    fi
  fi

  # The foreground app changes rarely, so it is polled less often than the
  # overlays; it only has to be caught before an offer shows up.
  if [ $(( n % 4 )) -eq 0 ]; then
    fg=$(focus_probe)
    if [ "$fg" != "$last_fg" ]; then
      [ -n "$fg" ] && emit "$now" FG "$fg"
      last_fg="$fg"
    fi
  fi

  if [ $(( n % 2 )) -eq 1 ]; then
    c=$(capture_state)
    if [ "$c" != "$last_cap" ]; then
      emit "$now" CAPTURE "mediaProjection=$c"
      last_cap=$c
    fi
  fi
done
SAMPLER
}

start_sampler() {
  local tick="${1:-0.12}"
  write_sampler
  adb_ push "$ROOT/scripts/.kmpro-spy-sampler.sh" "$REMOTE_SAMPLER" >/dev/null || die "falha ao enviar o sampler"
  adb_ shell chmod 755 "$REMOTE_SAMPLER"
  adb_ shell pkill -f kmpro_spy_sampler >/dev/null 2>&1
  sleep 0.3
  adb_ shell "nohup $REMOTE_SAMPLER $REMOTE_LOG $tick >/dev/null 2>&1 &"
  sleep 0.8
  adb_ shell "head -1 $REMOTE_LOG" >/dev/null 2>&1 && echo "   sampler ativo: $REMOTE_LOG"
}

stop_sampler() {
  adb_ shell pkill -f kmpro_spy_sampler >/dev/null 2>&1
}

# -------------------------------------------------------------------- watch --

cmd_watch() {
  resolve_serial "${1:-}"
  echo "== KMPro spy :: watch ($SERIAL) =="
  echo "   Monitorando. Abra o app de corrida, ligue o Copiloto do Gigu e"
  echo "   dispare uma oferta. Ctrl-C encerra."
  echo
  start_sampler 0.12

  adb_ logcat -c 2>/dev/null
  ( adb_ logcat -v epoch -s KMProOcr:D KMProOffer:D KMProOverlay:D 2>/dev/null \
      | grep --line-buffered -E "KMPro" \
      | while read -r l; do printf '%s | KMPro | %s\n' "${l%% *}" "$(echo "$l" | sed 's/^[^ ]* [^ ]* [^ ]* [^ ]* [^ ]* [^ ]* [^ ]* [^ ]* [^ ]* [^ ]* //')"; done ) &

  local tail_pid=$!
  adb_ shell "tail -n +2 -f $REMOTE_LOG" 2>/dev/null &
  local samp_pid=$!

  trap 'kill $tail_pid $samp_pid 2>/dev/null; stop_sampler; echo; echo "== watch encerrado =="; exit 0' INT TERM
  wait
}

# ------------------------------------------------------------------ session --

cmd_session() {
  resolve_serial "${1:-}"
  local dur="${2:-90}"
  local stamp dir
  stamp=$(date +%Y%m%d-%H%M%S)
  dir="$OUT_ROOT/$stamp"
  mkdir -p "$dir"
  echo "== KMPro spy :: sessao de ${dur}s ($SERIAL) =="
  echo "   saida: $dir"
  echo "   Dispare uma oferta AGORA."
  echo

  # The two clocks in this session are never converted into each other; each is
  # rebased against the instant it opens. meta.txt is that instant for logcat,
  # and the sampler's own START line is it for the on-device probes.
  echo "epoch=$(date +%s.%N) serial=$SERIAL dur=${dur}s" >"$dir/meta.txt"

  start_sampler 0.08

  # screenrecord muxes the mp4 on clean exit only: killing the adb client
  # truncates the file to 0 bytes. Let it finish and only stop the readers.
  #
  # --bugreport makes it tolerate other apps holding a MediaProjection
  # session; without it screenrecord can abort with "already in use" while
  # Gigu's copilot is running, which is exactly when we need the video.
   # The video is the only window onto Gigu's card, and it is also the
   # collector that is slowest to shut down. It therefore outlives the requested
   # window: offers routinely land in the last seconds of a session, and in a
   # 150 s run the one real offer came at 152.9 s - 2.5 s past the end of the
   # video, with a perfectly good log line and no pixels to read.
   local vpad=20
   adb_ shell screenrecord --bugreport --time-limit "$(( dur + vpad ))" \
     --size 1280x800 --bit-rate 8000000 \
     /data/local/tmp/kmpro_spy.mp4 >/dev/null 2>&1 &
   local rec_pid=$!
   adb_ logcat -c 2>/dev/null
   adb_ logcat -v epoch >"$dir/logcat.txt" 2>/dev/null &
   local log_pid=$!

   local t0
   t0=$(date +%s)
   while [ $(( $(date +%s) - t0 )) -lt "$dur" ]; do
     printf '\r   %2ss / %ss   ' "$(( $(date +%s) - t0 ))" "$dur"
     sleep 1
   done
   echo
   # adb_ is a shell function, so $log_pid is the subshell and the real adb
   # client keeps streaming into logcat.txt long after the session "ends" - a
   # 150 s session was still being written 200 s later. Kill the adb itself,
   # scoped to this device, or the report reads timestamps that do not exist in
   # the video it is correlating.
   pkill -f "adb -s $SERIAL logcat -v epoch" 2>/dev/null
   kill "$log_pid" 2>/dev/null
   stop_sampler
   # Now wait out the video's own padding so it muxes past the window we care
   # about; killing the adb client here would truncate the file to 0 bytes.
   local waited=0
   while kill -0 "$rec_pid" 2>/dev/null && [ "$waited" -lt "$vpad" ]; do
     printf '\r   finalizando video... %ss   ' "$waited"
     sleep 1
     waited=$(( waited + 1 ))
   done
   echo
   adb_ pull /data/local/tmp/kmpro_spy.mp4 "$dir/screen.mp4" >/dev/null 2>&1
  # The mp4 runs dur+vpad seconds, so meta.txt now records the real video length:
  # the report needs to know where the trusted window ends before it correlates
  # a log line with a frame.
  printf 'video_s=%.2f\n' "$(ffprobe -v error -show_entries format=duration \
     -of csv=p=0 "$dir/screen.mp4" 2>/dev/null || echo 0)" >>"$dir/meta.txt"
  adb_ shell "cat $REMOTE_LOG" >"$dir/timeline.txt" 2>/dev/null
  adb_ shell "dumpsys window windows" >"$dir/windows-end.txt" 2>/dev/null
  adb_ shell "dumpsys media_projection" >"$dir/mediaprojection-end.txt" 2>/dev/null

  local vsize
  vsize=$(wc -c <"$dir/screen.mp4" 2>/dev/null || echo 0)
  echo
  if [ "${vsize:-0}" -lt 10000 ]; then
    echo "   AVISO: screen.mp4 ficou vazio ($vsize bytes)."
    echo "   A sessao de tela do dispositivo pode estar bloqueada; o timeline e o"
    echo "   logcat continuam validos."
  fi

  echo
  echo "   ok._VIDEO: $dir/screen.mp4"
  echo "   timeline: $dir/timeline.txt"
  echo "   logcat:  $dir/logcat.txt"
  echo
  echo "   proximo passo: ./kmpro-spy.sh report $dir"
}

# ------------------------------------------------------------------- report --

cmd_report() {
  local dir="${1:-}"
  [ -n "$dir" ] || die "informe o diretorio da sessao"
  [ -f "$dir/timeline.txt" ] || die "sem timeline.txt em $dir"

  python3 "$ROOT/scripts/kmpro-spy-report.py" "$dir"
}

# --------------------------------------------------------------------- main --

CMD="${1:-}"
[ -n "$CMD" ] || { echo "uso: $0 {preflight|watch|session|report} [args]" >&2; exit 1; }
shift || true

case "$CMD" in
  preflight) cmd_preflight "${1:-}" ;;
  watch)     cmd_watch     "${1:-}" ;;
  session)   cmd_session   "${1:-}" "${2:-90}" ;;
  report)    cmd_report    "${1:-}" ;;
  *) echo "comando desconhecido: $CMD" >&2; exit 1 ;;
esac