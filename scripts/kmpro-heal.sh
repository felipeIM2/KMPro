#!/bin/bash
# Re-heal the KMPro capture stack on the tablet.
# The Samsung KNOX layer periodically revokes the accessibility grant and drops
# the adb reverse tunnel, which is the main reason the app "stops working".
#
# Usage: ./kmpro-heal.sh [serial]
set -u

SERIAL="${1:-RXGL105WHHV}"
PKG="com.anonymous.KMPro"
SVC="$PKG/expo.modules.kmproofferlistener.RideAccessibilityService"
ADB="adb -s $SERIAL"

echo "== KMPro heal ($SERIAL) =="

if ! $ADB get-state >/dev/null 2>&1; then
  echo "ERRO: dispositivo nao conectado. Conecte o cabo e tente de novo."
  exit 1
fi

# 1. Metro tunnel: the debug build loads JS from localhost:8081.
$ADB reverse tcp:8081 tcp:8081 >/dev/null 2>&1 && echo "ok  reverse tcp:8081" || echo "AVISO reverse falhou (Metro rodando?)"

# 2. Overlay permission (SYSTEM_ALERT_WINDOW), revoked by the OS on some paths.
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 && echo "ok  overlay permitido" || echo "ERRO overlay"

# 3. Accessibility grant: this is what drives screen reading and OCR.
$ADB shell am force-stop $PKG >/dev/null 2>&1
sleep 2
$ADB shell settings put secure enabled_accessibility_services "$SVC" >/dev/null 2>&1
$ADB shell settings put secure accessibility_enabled 1 >/dev/null 2>&1
sleep 2

ENABLED=$($ADB shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
ACCESS=$($ADB shell settings get secure accessibility_enabled 2>/dev/null | tr -d '\r')

if [ "$ENABLED" = "$SVC" ] && [ "$ACCESS" = "1" ]; then
  echo "ok  acessibilidade concedida"
else
  echo "ERRO grant nao aplicado (enabled=$ENABLED accessibility_enabled=$ACCESS)"
  echo "     O KNOX pode estar bloqueando. Tente togglar em Configuracoes > Acessibilidade."
fi

# 4. Report whether the service actually bound and kept the screenshot bit.
BOUND=$($ADB shell dumpsys accessibility 2>/dev/null | grep -c "$PKG/expo.modules.kmproofferlistener")
CAPS=$($ADB shell dumpsys accessibility 2>/dev/null | grep -A3 "id=$PKG/expo" | grep -oE 'capabilities=[0-9]+' | head -1)
echo "    servicos vinculados: $BOUND   $CAPS (161 = com screenshot)"

# 5. Launch the app so the user lands in a working state.
$ADB shell am start -n $PKG/.MainActivity >/dev/null 2>&1 && echo "ok  app aberto"

echo "== pronto =="
