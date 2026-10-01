import * as React from 'react';
import { Pressable, Text, View } from 'react-native';
import { router } from 'expo-router';
import {
  Droplet,
  Gauge,
  Pencil,
  Star,
  Timer,
  TrendingUp,
  Wallet,
} from 'lucide-react-native';
import { Screen, Section } from '@/components/ui/screen';
import { ThresholdCard } from '@/components/threshold-card';
import {
  ACCEPTED_TRIPS,
  buildStats,
  fuelCostPerKm,
  fixedCostPerKm,
  fixedMonthlyCost,
  monthlyKm,
  totalCostPerKm,
  WEEKS_PER_MONTH,
} from '@/constants';
import { useApp } from '@/context/app-context';
import { formatBRL, formatNumber } from '@/lib/format';
import { cn } from '@/lib/utils';

const todayStats = buildStats(
  ACCEPTED_TRIPS.filter((t) => {
    const start = new Date();
    start.setHours(0, 0, 0, 0);
    return t.date.getTime() >= start.getTime();
  })
);

const GAIN_KM_DOMAIN: [number, number] = [1, 10];
const GAIN_HOUR_DOMAIN: [number, number] = [5, 100];
const RATING_DOMAIN: [number, number] = [1, 5];

type Align = 'left' | 'center' | 'right';

const ALIGN: Record<Align, { container: string; row: string; text: string }> =
  {
    left: {
      container: 'items-start',
      row: 'justify-start',
      text: 'text-left',
    },
    center: { container: 'items-center', row: 'justify-center', text: 'text-center' },
    right: { container: 'items-end', row: 'justify-end', text: 'text-right' },
  };

function StackedStat({
  label,
  value,
  valueClassName,
  icon: Icon,
  align = 'left',
}: {
  label?: string;
  value: string;
  valueClassName?: string;
  icon?: React.ComponentType<{ size?: number; color?: string }>;
  align?: Align;
}) {
  const a = ALIGN[align];

  return (
    <View className={cn('gap-0.5', a.container)}>
      {label ? (
        <View className={cn('flex-row items-center gap-1.5', a.row)}>
          {Icon ? <Icon size={12} color="#10b981" /> : null}
          <Text
            className={cn(
              'text-[9px] font-semibold uppercase tracking-wider text-muted-foreground',
              a.text
            )}
          >
            {label}
          </Text>
        </View>
      ) : null}
      <Text
        className={cn(
          a.text,
          valueClassName ?? 'text-xl font-bold text-foreground'
        )}
      >
        {value}
      </Text>
    </View>
  );
}

export default function Metas() {
  const { costSettings, goals, setGoals } = useApp();

  // As faixas vivem no contexto para que o cartão sobre o app de corrida
  // possa colorir cada métrica com a mesma regra.
  const gainKm = goals.gainKm;
  const gainHour = goals.gainHour;
  const rating = goals.rating;

  const setGainKmRange = React.useCallback(
    (range: [number, number]) => setGoals({ ...goals, gainKm: range }),
    [goals, setGoals]
  );
  const setGainHourRange = React.useCallback(
    (range: [number, number]) => setGoals({ ...goals, gainHour: range }),
    [goals, setGoals]
  );
  const setRatingGoal = React.useCallback(
    (value: number) => setGoals({ ...goals, rating: value }),
    [goals, setGoals]
  );

  const goalGross = costSettings.weeklyGoal * WEEKS_PER_MONTH;
  const profitGoal = Math.max(goalGross - fixedMonthlyCost(costSettings), 0);
  const monthKm = monthlyKm(costSettings);
  const monthCost =
    fixedMonthlyCost(costSettings) + monthKm * fuelCostPerKm(costSettings);
  const difference = profitGoal - monthCost;

  const ratingValue = todayStats.averageRating ?? 5;

  const tiles = [
    {
      icon: Droplet,
      label: 'Comb./km',
      value: fuelCostPerKm(costSettings),
      tone: 'text-foreground' as const,
      align: 'center' as const,
    },
    {
      icon: Wallet,
      label: 'Custo/km',
      value: fixedCostPerKm(costSettings),
      tone: 'text-foreground' as const,
      align: 'center' as const,
    },
    {
      icon: Gauge,
      label: 'Total/km',
      value: totalCostPerKm(costSettings),
      tone: 'text-primary' as const,
      align: 'center' as const,
    },
  ];

  return (
    <Screen title="Metas" subtitle="Planejamento e rentabilidade">
      <View className="gap-4 rounded-xl border border-primary/25 bg-primary/5 p-4">
        <View className="flex-row items-start gap-3">
          <View className="flex-1 gap-2.5">
            <Text className="text-[10px] font-semibold uppercase tracking-widest text-primary">
              Meta de lucro mensal
            </Text>

            <StackedStat label="meta" value={formatBRL(profitGoal)} />
            <StackedStat label="custos" value={formatBRL(monthCost)} />

            <StackedStat
              label="Lucro líquido"
              value={formatBRL(difference)}
              valueClassName={cn(
                'text-xl font-bold',
                difference >= 0 ? 'text-primary' : 'text-destructive'
              )}
            />

            <StackedStat
              label="km para meta"
              value={`${formatNumber(monthKm, 0)} km/mês`}
              valueClassName="text-sm font-semibold text-muted-foreground"
            />
          </View>

          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Editar custos"
            onPress={() => router.push('/custos')}
            className="h-16 w-16 items-center justify-center gap-1 rounded-xl bg-primary active:opacity-80"
          >
            <Pencil size={18} color="#022c22" />
          </Pressable>
        </View>

        <View className="h-px w-full bg-border" />

        <View className="flex-row gap-4">
          {tiles.map((tile) => (
            <View key={tile.label} className="flex-1">
              <StackedStat
                label={tile.label}
                value={formatBRL(tile.value)}
                icon={tile.icon}
                align={tile.align}
                valueClassName={cn('text-sm font-bold', tile.tone)}
              />
            </View>
          ))}
        </View>
      </View>

      <Section
        title="Gatilhos de performance"
        hint="Arraste as réguas para definir os limites"
      >
        <ThresholdCard
          icon={TrendingUp}
          label="Ganho por km"
          unit="/km"
          value={todayStats.gainPerKm}
          domain={GAIN_KM_DOMAIN}
          min={gainKm[0]}
          max={gainKm[1]}
          onChangeMin={(value) => setGainKmRange([value, gainKm[1]])}
          onChangeMax={(value) => setGainKmRange([gainKm[0], value])}
        />

        <ThresholdCard
          icon={Timer}
          label="Ganho por hora"
          unit="/h"
          value={todayStats.gainPerHour}
          domain={GAIN_HOUR_DOMAIN}
          decimals={2}
          min={gainHour[0]}
          max={gainHour[1]}
          onChangeMin={(value) => setGainHourRange([value, gainHour[1]])}
          onChangeMax={(value) => setGainHourRange([gainHour[0], value])}
        />

        <ThresholdCard
          icon={Star}
          label="Nota do passageiro"
          unit=""
          value={ratingValue}
          domain={RATING_DOMAIN}
          min={rating}
          onChangeMin={setRatingGoal}
          hint={`${todayStats.rides} corridas hoje`}
        />
      </Section>
    </Screen>
  );
}
