import * as React from 'react';
import { AppState, Pressable, ScrollView, Text, View } from 'react-native';
import Animated, {
  Easing,
  useAnimatedStyle,
  useSharedValue,
  withRepeat,
  withTiming,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import { BellOff, Play, Radar, Square } from 'lucide-react-native';
import { AppIcon } from '@/components/app-icon';
import { useApp } from '@/context/app-context';
import { useOfferListener } from '@/hooks/use-offer-listener';
import { totalCostPerKm } from '@/constants';
import { formatInt } from '@/lib/format';
import { consumeSkipAccessGate } from '@/lib/access-gate';
import { cn } from '@/lib/utils';
import {
  checkAccessibilityAccess,
  setCopilotoActive as syncCopilotoActive,
  setDriverSettings,
  setOverlayEnabled,
} from '../../../../modules/kmpro-offer-listener';

function PulseRing({ active }: { active: boolean }) {
  const pulse = useSharedValue(0);

  React.useEffect(() => {
    if (active) {
      pulse.value = withRepeat(
        withTiming(1, { duration: 1600, easing: Easing.out(Easing.ease) }),
        -1,
        false
      );
    } else {
      pulse.value = withTiming(0, { duration: 200 });
    }
  }, [active, pulse]);

  const style = useAnimatedStyle(() => ({
    transform: [{ scale: 1 + pulse.value * 0.35 }],
    opacity: 0.45 * (1 - pulse.value),
  }));

  if (!active) return null;
  return (
    <Animated.View
      pointerEvents="none"
      style={[
        {
          position: 'absolute',
          width: 104,
          height: 104,
          borderRadius: 52,
          backgroundColor: '#10b981',
        },
        style,
      ]}
    />
  );
}

function SpinnerRing({ active }: { active: boolean }) {
  const spin = useSharedValue(0);

  React.useEffect(() => {
    if (active) {
      spin.value = withRepeat(
        withTiming(1, { duration: 900, easing: Easing.linear }),
        -1,
        false
      );
    } else {
      spin.value = withTiming(0, { duration: 200 });
    }
  }, [active, spin]);

  const style = useAnimatedStyle(() => ({
    transform: [{ rotate: `${spin.value * 360}deg` }],
  }));

  if (!active) return null;
  return (
    <Animated.View
      pointerEvents="none"
      style={[
        {
          position: 'absolute',
          width: 104,
          height: 104,
          borderRadius: 52,
          borderWidth: 4,
          borderColor: 'transparent',
          borderTopColor: '#10b981',
          borderRightColor: '#10b981',
        },
        style,
      ]}
    />
  );
}

const START_DELAY = 1200;

export default function Copiloto() {
  const { copilotoActive, setCopilotoActive, monitoredApps, costSettings, autoAccept } =
    useApp();
  const insets = useSafeAreaInsets();
  const [starting, setStarting] = React.useState(false);
  const startTimer = React.useRef<ReturnType<typeof setTimeout> | null>(null);
  const { syncWatchedPackages, postNotificationsGranted } = useOfferListener();

  React.useEffect(
    () => () => {
      if (startTimer.current) clearTimeout(startTimer.current);
    },
    []
  );

  React.useEffect(() => {
    if (consumeSkipAccessGate() || copilotoActive) return;
    let mounted = true;
    const run = () => {
      checkAccessibilityAccess()
        .then((a11y) => {
          if (mounted && !a11y) {
            router.replace('/acessos');
          }
        })
        .catch(() => {});
    };
    run();
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'active') run();
    });
    return () => {
      mounted = false;
      sub.remove();
    };
  }, [copilotoActive]);

  React.useEffect(() => {
    const packages = copilotoActive
      ? monitoredApps.map((app) => app.androidPackage)
      : [];
    syncWatchedPackages(packages);
  }, [copilotoActive, monitoredApps, syncWatchedPackages]);

  const costPerKm = totalCostPerKm(costSettings);
  const goalPerKm = autoAccept.ganhoKm.enabled ? autoAccept.ganhoKm.value : 0;
  const goalPerHour = autoAccept.ganhoHora.enabled ? autoAccept.ganhoHora.value : 0;

  React.useEffect(() => {
    if (copilotoActive) {
      syncCopilotoActive(true).catch(() => {});
      setDriverSettings(costPerKm, goalPerKm, goalPerHour).catch(() => {});
      setOverlayEnabled(true).catch(() => {});
    } else {
      syncCopilotoActive(false).catch(() => {});
    }
  }, [copilotoActive, costPerKm, goalPerKm, goalPerHour]);

  const toggle = React.useCallback(() => {
    if (copilotoActive) {
      if (startTimer.current) clearTimeout(startTimer.current);
      setStarting(false);
      setCopilotoActive(false);
      return;
    }

    if (starting || monitoredApps.length === 0) return;
    setStarting(true);
    startTimer.current = setTimeout(() => {
      startTimer.current = null;
      setStarting(false);
      setCopilotoActive(true);
    }, START_DELAY);
  }, [copilotoActive, monitoredApps.length, setCopilotoActive, starting]);

  const appNames = monitoredApps.map((app) => app.name);
  const appLabel =
    appNames.length === 0
      ? 'Nenhum app'
      : appNames.length === 1
        ? appNames[0]
        : `${appNames[0]} e ${appNames[1]}`;
  const appCount = formatInt(monitoredApps.length);

  const noApps = monitoredApps.length === 0;
  const blocked = starting || noApps;

  const statusLabel = copilotoActive
    ? 'Mapeando ofertas agora'
    : starting
      ? 'Conectando aos apps monitorados'
      : noApps
        ? 'Ative um app em Ajustes para iniciar'
        : 'Copiloto pausado';

  const actionLabel = copilotoActive ? 'Parar' : starting ? 'Iniciando' : 'Iniciar';

  return (
    <ScrollView
      className="flex-1 bg-background"
      contentContainerStyle={{ paddingTop: insets.top + 12, paddingBottom: 32 }}
      showsVerticalScrollIndicator={false}
    >
      <View className="gap-5 px-4">
        <View className="flex-row items-center justify-between">
          <View className="gap-1">
            <Text className="text-2xl font-bold tracking-tight text-foreground">
              Copiloto
            </Text>
            <Text className="text-xs uppercase tracking-wider text-muted-foreground">
              Leitura de ofertas em tempo real
            </Text>
          </View>
          <View className="h-9 w-9 items-center justify-center rounded-lg bg-primary/15">
            <Radar size={16} color="#10b981" />
          </View>
        </View>

        <View className="gap-5 rounded-xl border border-border bg-card p-5">
          <View className="flex-row items-start justify-between gap-3">
            <View className="flex-1">
              <Text className="text-2xl font-bold tracking-tight text-foreground">
                Copiloto Pro
              </Text>
            </View>
            <View
              className={cn(
                'h-12 w-12 items-center justify-center rounded-xl',
                copilotoActive ? 'bg-primary' : 'bg-secondary'
              )}
            >
              <Radar
                size={22}
                color={copilotoActive ? '#022c22' : '#525252'}
              />
            </View>
          </View>

          <View className="gap-3 rounded-lg border border-border bg-secondary p-4">
            <Text className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
              Em monitoramento:
            </Text>

            {monitoredApps.length === 0 ? (
              <Text className="text-xs text-destructive">
                Nenhum app ligado em Ajustes
              </Text>
            ) : (
              <View className="flex-row flex-wrap items-center gap-x-2 gap-y-2">
                {monitoredApps.map((app, index) => (
                  <React.Fragment key={app.id}>
                    {index > 0 ? (
                      <Text className="text-sm font-medium text-muted-foreground">
                        e
                      </Text>
                    ) : null}
                    <AppIcon app={app.id} size={38} showName />
                  </React.Fragment>
                ))}
              </View>
            )}

            <Text className="text-[11px] text-muted-foreground">
              {appLabel} · {appCount} app{monitoredApps.length === 1 ? '' : 's'}{' '}
              conectado{monitoredApps.length === 1 ? '' : 's'}
            </Text>
          </View>
        </View>

        {!postNotificationsGranted ? (
          // A permissão de aviso é pedida só no toque (Acessos). Se faltar,
          // avisa aqui — senão a push é descartada em silêncio e parece bug.
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Permitir avisos do Copiloto"
            onPress={() => router.push('/acessos')}
            className="flex-row items-center gap-3 rounded-xl border border-warning/40 bg-warning/10 p-4 active:opacity-80"
          >
            <BellOff size={18} color="#f59e0b" />
            <View className="flex-1 gap-0.5">
              <Text className="text-sm font-semibold text-foreground">
                Avisos desativados
              </Text>
              <Text className="text-xs leading-4 text-muted-foreground">
                Toque para permitir o aviso de oferta na central de notificações
              </Text>
            </View>
          </Pressable>
        ) : null}

        <View className="items-center gap-3">
          <View className="h-[104px] items-center justify-center">
            <PulseRing active={copilotoActive} />
            <Pressable
              accessibilityRole="button"
              accessibilityState={{ busy: starting, disabled: blocked }}
              accessibilityLabel={
                copilotoActive ? 'Parar copiloto' : 'Iniciar copiloto'
              }
              disabled={blocked}
              onPress={toggle}
              className={cn(
                'h-[104px] w-[104px] items-center justify-center rounded-full border-4 active:opacity-80',
                copilotoActive
                  ? 'border-primary bg-primary'
                  : 'border-border bg-card',
                blocked && 'opacity-60'
              )}
            >
              {copilotoActive ? (
                <Square size={34} color="#022c22" fill="#022c22" />
              ) : (
                <Play size={34} color="#10b981" fill="#10b981" />
              )}
            </Pressable>
            <SpinnerRing active={starting} />
          </View>

          <Text className="text-base font-bold uppercase tracking-widest text-foreground">
            {actionLabel}
          </Text>

          <View className="flex-row items-center gap-2">
            <View
              className={cn(
                'h-2 w-2 rounded-full',
                copilotoActive || starting
                  ? 'bg-primary'
                  : 'bg-muted-foreground'
              )}
            />
            <Text className="text-xs text-muted-foreground">{statusLabel}</Text>
          </View>
        </View>
      </View>
    </ScrollView>
  );
}
