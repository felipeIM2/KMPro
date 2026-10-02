import * as React from 'react';
import { Pressable, Text, View } from 'react-native';
import {
  AlignCenterHorizontal,
  Check,
  LayoutGrid,
  Timer,
  Zap,
} from 'lucide-react-native';

import { Screen, Section } from '@/components/ui/screen';
import { AppIcon } from '@/components/app-icon';
import { MetricOrder } from '@/components/metric-order';
import { OfferCard } from '@/components/offer-card';
import { Switch } from '@/components/ui/switch';
import { Slider } from '@/components/ui/slider';
import { Collapsible, CollapsibleRow } from '@/components/ui/collapsible';
import { Separator } from '@/components/ui/separator';
import {
  METRICS,
  MOCK_OFFER,
  RIDE_APPS,
  type MetricId,
} from '@/constants';
import { useApp } from '@/context/app-context';
import {
  CARD_POSITIONS,
  CARD_POSITION_LABEL,
  SCREEN_DURATIONS,
  type CardPosition,
  type ScreenDuration,
} from '@/context/app-context';
import { Badge } from '@/components/ui/badge';
import { formatBRL, formatInt, formatNumber } from '@/lib/format';
import { cn } from '@/lib/utils';
import { setCardAppearance } from '../../../../modules/kmpro-offer-listener';
import type { MetricTones, OfferCardTone } from '@/components/offer-card';
import { hourlyCost, totalCostPerKm } from '@/constants';
import { metricValue } from '@/lib/ride-metrics';

/**
 * Mesma regra de faixa do `CardGoals` nativo: abaixo do mínimo é vermelho,
 * entre o mínimo e o máximo é âmbar, e no máximo ou acima é verde. Mantém a
 * prévia com a mesma cor que o cartão sobre o app de corrida.
 */
function toneForRange(
  value: number,
  min: number,
  max: number
): 'good' | 'warn' | 'bad' | undefined {
  if (min <= 0 && max <= 0) return undefined;
  if (max > 0 && value >= max) return 'good';
  if (min > 0 && value < min) return 'bad';
  if (min > 0 && max > 0) return 'warn';
  if (min > 0) return value >= min ? 'good' : 'bad';
  return value >= max ? 'good' : 'bad';
}

/** Pior tom entre os mostradores (bad > warn > good), como o cartão nativo. */
function worstTone(tones: (OfferCardTone | undefined)[]): OfferCardTone {
  if (tones.includes('bad')) return 'bad';
  if (tones.includes('warn')) return 'warn';
  return 'good';
}

function CardShell({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <View
      className={cn(
        'overflow-hidden rounded-xl border border-border bg-card',
        className
      )}
    >
      {children}
    </View>
  );
}

export default function Ajustes() {
  const {
    monitoring,
    setMonitoring,
    metricOrder,
    setMetricOrder,
    screenDuration,
    setScreenDuration,
    cardPosition,
    setCardPosition,
    autoAcceptEnabled,
    setAutoAcceptEnabled,
    autoAccept,
    setAutoAccept,
    goals,
    costSettings,
  } = useApp();

  const [open, setOpen] = React.useState(false);
  const costPerKm = totalCostPerKm(costSettings);
  const custoHora = hourlyCost(costSettings);

  // Faixas de cada métrica na prévia, com a mesma fórmula do cartão nativo.
  const previewTones: MetricTones = React.useMemo(() => {
    const tones: MetricTones = {};
    const lucroMax = custoHora * (MOCK_OFFER.minutes / 60);
    for (const id of metricOrder) {
      const value = metricValue(id, MOCK_OFFER, costPerKm);
      if (id === 'ganhoKm') {
        const tone = toneForRange(value, goals.gainKm[0], goals.gainKm[1]);
        if (tone) tones.ganhoKm = tone;
      } else if (id === 'ganhoHora') {
        const tone = toneForRange(value, goals.gainHour[0], goals.gainHour[1]);
        if (tone) tones.ganhoHora = tone;
      } else if (id === 'lucroHora') {
        const tone = toneForRange(value, custoHora * 0.9, custoHora);
        if (tone) tones.lucroHora = tone;
      } else if (id === 'lucro') {
        const tone = toneForRange(value, lucroMax * 0.9, lucroMax);
        if (tone) tones.lucro = tone;
      }
    }
    const ratingTone = toneForRange(MOCK_OFFER.rating ?? 0, goals.rating, 0);
    if (ratingTone) tones.rating = ratingTone;
    return tones;
  }, [metricOrder, goals, costPerKm, custoHora]);

  // A borda da prévia segue o pior mostrador (sem a nota), como no nativo.
  const previewTone = worstTone(metricOrder.map((id) => previewTones[id]));

// Keep the floating overlay in sync with this screen, so what the preview
// shows here is exactly what appears over the ride app.
React.useEffect(() => {
  setCardAppearance(metricOrder, cardPosition, screenDuration).catch(() => {});
}, [metricOrder, cardPosition, screenDuration]);

  const updateAutoAccept = (
    id: MetricId,
    patch: Partial<{ enabled: boolean; value: number }>
  ) =>
    setAutoAccept((prev) => ({
      ...prev,
      [id]: { ...prev[id], ...patch },
    }));

  const enabledMetrics = METRICS.filter((m) => autoAccept[m.id].enabled);

  return (
    <Screen title="Ajustes" subtitle="Monitoramento, cartão e automações">
      <Section title="Monitoramento" hint="Apps lidos pelo copiloto">
        <CardShell>
          <View>
            {RIDE_APPS.map((app, index) => (
              <View key={app.id}>
                {index > 0 ? <Separator /> : null}
                <View className="flex-row items-center justify-between gap-3 px-4 py-3.5">
                  <AppIcon
                    app={app.id}
                    size={34}
                    showName
                    dimmed={!monitoring[app.id]}
                  />
                  <Switch
                    value={monitoring[app.id]}
                    onValueChange={(next) => setMonitoring(app.id, next)}
                  />
                </View>
              </View>
            ))}
          </View>
        </CardShell>
      </Section>

      <Section
        title="Aparência do cartão"
        hint="Prévia e ordem das métricas exibidas"
      >
        <CardShell>
          <View className="gap-3 p-5">
            {/* Tela simulada: mostra como o cartão fica sobre o app de corrida
                quando o Copiloto inicia, na posição escolhida. */}
            <View className="relative h-[340px] w-full overflow-hidden rounded-xl bg-secondary">
              <View className="absolute inset-x-0 top-0 h-10 flex-row items-center gap-2 bg-muted px-3">
                <View className="h-2 flex-1 rounded-full bg-muted-foreground/30" />
                <Text className="text-[10px] font-semibold text-muted-foreground">
                  {formatInt(MOCK_OFFER.minutes)} min ·{' '}
                  {formatNumber(MOCK_OFFER.km, 1)} km
                </Text>
              </View>

              <View className="absolute inset-0 items-center justify-center">
                <Text className="text-[10px] font-medium text-muted-foreground/70">
                  App de corrida (fundo)
                </Text>
              </View>

              <View className="absolute inset-x-3 bottom-3 flex-row justify-between">
                <View className="h-8 w-20 items-center justify-center rounded-lg bg-destructive/10">
                  <Text className="text-[10px] font-bold text-destructive">
                    Recusar
                  </Text>
                </View>
                <View className="h-8 w-20 items-center justify-center rounded-lg bg-primary/10">
                  <Text className="text-[10px] font-bold text-primary">
                    Aceitar
                  </Text>
                </View>
              </View>

              {/* Cartão ancorado como no nativo: sempre no topo, logo abaixo
                  da área de notificações, e encostado na esquerda/centro/direita
                  conforme a posição escolhida. */}
              <View className="absolute inset-0">
              <View
                className={cn(
                  'h-full w-full flex-row items-start pt-14',
                  cardPosition === 'esquerda' && 'justify-start pl-3',
                  cardPosition === 'centro' && 'justify-center',
                  cardPosition === 'direita' && 'justify-end pr-3'
                )}
              >
                  <OfferCard
                    offer={MOCK_OFFER}
                    order={metricOrder}
                    tone={previewTone}
                    tones={previewTones}
                    costPerKm={costPerKm}
                  />
                </View>
              </View>
            </View>
            <Text className="text-center text-[10px] text-muted-foreground">
              Assim o cartão fica sobre o app de corrida quando você inicia o
              Copiloto
            </Text>
          </View>

          <View className="gap-3 p-4">
            <View className="flex-row items-center gap-2">
              <LayoutGrid size={14} color="#10b981" />
              <Text className="text-xs font-semibold text-foreground">
                Ordem das métricas
              </Text>
            </View>
            <MetricOrder order={metricOrder} onReorder={setMetricOrder} />
            <Text className="text-[10px] text-muted-foreground">
              Arraste os cartões para reorganizar.
            </Text>

            <Separator />

            <View className="gap-2.5">
              <View className="flex-row items-center gap-2">
                <Timer size={14} color="#10b981" />
                <Text className="text-xs font-semibold text-foreground">
                  Tempo de tela
                </Text>
              </View>
              <View className="flex-row gap-1.5">
                {SCREEN_DURATIONS.map((seconds) => {
                  const active = screenDuration === seconds;
                  return (
                    <Pressable
                      key={seconds}
                      accessibilityRole="radio"
                      accessibilityState={{ selected: active }}
                      accessibilityLabel={`${seconds} segundos`}
                      onPress={() => setScreenDuration(seconds as ScreenDuration)}
                      className={cn(
                        'flex-1 items-center rounded-lg border py-2 active:opacity-70',
                        active
                          ? 'border-primary bg-primary/10'
                          : 'border-border bg-secondary'
                      )}
                    >
                      <Text
                        className={cn(
                          'text-xs font-bold',
                          active ? 'text-primary' : 'text-muted-foreground'
                        )}
                      >
                        {seconds}s
                      </Text>
                    </Pressable>
                  );
                })}
              </View>
            </View>

            <View className="gap-2.5">
              <View className="flex-row items-center gap-2">
                <AlignCenterHorizontal size={14} color="#10b981" />
                <Text className="text-xs font-semibold text-foreground">
                  Posição
                </Text>
              </View>
              <View className="flex-row gap-1.5">
                {CARD_POSITIONS.map((position) => {
                  const active = cardPosition === position;
                  return (
                    <Pressable
                      key={position}
                      accessibilityRole="radio"
                      accessibilityState={{ selected: active }}
                      accessibilityLabel={CARD_POSITION_LABEL[position]}
                      onPress={() => setCardPosition(position as CardPosition)}
                      className={cn(
                        'flex-1 items-center rounded-lg border py-2 active:opacity-70',
                        active
                          ? 'border-primary bg-primary/10'
                          : 'border-border bg-secondary'
                      )}
                    >
                      <Text
                        className={cn(
                          'text-xs font-semibold',
                          active ? 'text-primary' : 'text-muted-foreground'
                        )}
                      >
                        {CARD_POSITION_LABEL[position]}
                      </Text>
                    </Pressable>
                  );
                })}
              </View>
            </View>
          </View>
        </CardShell>
      </Section>

      <Section title="Automação">
        <CardShell>
          <CollapsibleRow
            icon={<Zap size={18} color="#10b981" />}
            title="Aceite automático"
            open={open}
            onPress={() => setOpen((prev) => !prev)}
            right={
              <Badge
                label={autoAcceptEnabled ? 'Ativo' : 'Inativo'}
                variant={autoAcceptEnabled ? 'success' : 'muted'}
                className="self-center"
              />
            }
          />

          <Collapsible open={open}>
            <View className="gap-4 border-t border-border p-4">
              <View className="flex-row items-center justify-between gap-3 rounded-lg border border-border bg-secondary px-3 py-2.5">
                <View className="flex-1 gap-0.5">
                  <Text className="text-sm font-semibold text-foreground">
                    Ativar aceite automático
                  </Text>
                  <Text className="text-[10px] text-muted-foreground">
                    {enabledMetrics.length
                      ? `${enabledMetrics.length} métrica${enabledMetrics.length === 1 ? '' : 's'} em uso`
                      : 'Escolha ao menos uma métrica'}
                  </Text>
                </View>
                <Switch
                  value={autoAcceptEnabled}
                  onValueChange={setAutoAcceptEnabled}
                />
              </View>

              {autoAcceptEnabled ? (
                <View className="gap-3">
                  {METRICS.map((metric, index) => {
                    const item = autoAccept[metric.id];
                    const domain: [number, number] =
                      metric.id === 'ganhoKm'
                        ? [0, 10]
                        : metric.id === 'lucro'
                          ? [0, 60]
                          : [0, 100];

                    return (
                      <View key={metric.id} className="gap-2">
                        {index > 0 ? <Separator /> : null}
                        <View className="flex-row items-center gap-2.5 pt-2">
                          <Pressable
                            accessibilityRole="checkbox"
                            accessibilityState={{ checked: item.enabled }}
                            accessibilityLabel={`Usar ${metric.label}`}
                            onPress={() =>
                              updateAutoAccept(metric.id, {
                                enabled: !item.enabled,
                              })
                            }
                            className={cn(
                              'h-6 w-6 items-center justify-center rounded-md border',
                              item.enabled
                                ? 'border-primary bg-primary'
                                : 'border-border bg-secondary'
                            )}
                          >
                            {item.enabled ? (
                              <Check size={14} color="#022c22" />
                            ) : null}
                          </Pressable>

                          <Text className="flex-1 text-sm font-medium text-foreground">
                            {metric.label}
                          </Text>

                          <Text
                            className={cn(
                              'text-xs font-bold',
                              item.enabled
                                ? 'text-primary'
                                : 'text-muted-foreground'
                            )}
                          >
                            {formatBRL(item.value)}
                          </Text>
                        </View>

                        <View className="pl-9">
                          <Slider
                            min={domain[0]}
                            max={domain[1]}
                            step={0.5}
                            value={item.value}
                            disabled={!item.enabled}
                            onChange={(value) =>
                              updateAutoAccept(metric.id, { value })
                            }
                          />
                        </View>
                      </View>
                    );
                  })}
                </View>
              ) : null}
            </View>
          </Collapsible>
        </CardShell>
      </Section>
    </Screen>
  );
}
