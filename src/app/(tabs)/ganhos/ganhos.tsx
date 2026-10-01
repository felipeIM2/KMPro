import * as React from 'react';
import { Pressable, Text, View } from 'react-native';
import { ChevronRight, Sparkles } from 'lucide-react-native';
import { Screen, Section } from '@/components/ui/screen';
import { RideRow } from '@/components/ride-row';
import { SummaryCard } from '@/components/summary';
import { ACCEPTED_TRIPS, WEEKS_PER_MONTH, buildStats, type Trip } from '@/constants';
import { useApp } from '@/context/app-context';
import { formatBRL, formatInt } from '@/lib/format';
import { cn } from '@/lib/utils';

function startOfDay(date: Date) {
  const copy = new Date(date);
  copy.setHours(0, 0, 0, 0);
  return copy;
}

function groupByDay(trips: Trip[]) {
  const map = new Map<number, Trip[]>();
  for (const trip of trips) {
    const key = startOfDay(trip.date).getTime();
    const bucket = map.get(key);
    if (bucket) bucket.push(trip);
    else map.set(key, [trip]);
  }
  return [...map.entries()].sort((a, b) => b[0] - a[0]);
}

const ALL_DAYS = groupByDay(ACCEPTED_TRIPS).map(([key]) => key);

function tripsInLastDays(days: number) {
  const allowed = new Set(ALL_DAYS.slice(0, days));
  return ACCEPTED_TRIPS.filter((trip) => allowed.has(startOfDay(trip.date).getTime()));
}

function dayLabel(date: Date) {
  const today = startOfDay(new Date()).getTime();
  const yesterday = today - 86_400_000;
  const day = startOfDay(date).getTime();

  if (day === today) return 'Hoje';
  if (day === yesterday) return 'Ontem';
  return date.toLocaleDateString('pt-BR', {
    weekday: 'short',
    day: '2-digit',
    month: 'short',
  });
}

export default function Ganhos() {
  const [period, setPeriod] = React.useState<7 | 30>(7);
  const { costSettings } = useApp();

  const filtered = React.useMemo(() => tripsInLastDays(period), [period]);

  const stats = React.useMemo(() => buildStats(filtered), [filtered]);
  const days = React.useMemo(() => groupByDay(filtered), [filtered]);

  return (
    <Screen
      title="Ganhos"
      subtitle="Corridas aceitas por você"
      headerRight={
        <View className="h-9 w-9 items-center justify-center rounded-lg bg-primary/15">
          <Sparkles size={16} color="#10b981" />
        </View>
      }
    >
      <SummaryCard
        gross={stats.gross}
        profit={stats.profit}
        rides={stats.rides}
        km={stats.km}
        goal={
          period === 7
            ? costSettings.weeklyGoal
            : costSettings.weeklyGoal * WEEKS_PER_MONTH
        }
      />

      <View className="flex-row gap-2">
        {([7, 30] as const).map((value) => (
          <Pressable
            key={value}
            onPress={() => setPeriod(value)}
            className={cn(
              'flex-1 items-center rounded-lg border py-2 active:opacity-70',
              period === value
                ? 'border-primary bg-primary/10'
                : 'border-border bg-card'
            )}
          >
            <Text
              className={cn(
                'text-xs font-semibold',
                period === value ? 'text-primary' : 'text-muted-foreground'
              )}
            >
              {value === 7 ? '7 dias' : '30 dias'}
            </Text>
          </Pressable>
        ))}
      </View>

      <Section
        title="Corridas aceitas"
        hint={`${formatInt(filtered.length)} no período`}
      >
        {days.length === 0 ? (
          <View className="items-center gap-2 rounded-xl border border-dashed border-border py-10">
            <Text className="text-sm text-muted-foreground">
              Nenhuma corrida aceita ainda
            </Text>
          </View>
        ) : (
          days.map(([key, trips]) => {
            const dayStats = buildStats(trips);
            return (
              <View key={key} className="gap-2.5">
                <View className="flex-row items-center justify-between px-0.5">
                  <Text className="text-xs font-bold uppercase tracking-wider text-foreground">
                    {dayLabel(trips[0].date)}
                  </Text>
                  <View className="flex-row items-center gap-2">
                    <Text className="text-xs font-semibold text-primary">
                      {formatBRL(dayStats.gross)}
                    </Text>
                    <ChevronRight size={13} color="#525252" />
                  </View>
                </View>
                {trips.map((trip) => (
                  <RideRow key={trip.id} trip={trip} />
                ))}
              </View>
            );
          })
        )}
      </Section>
    </Screen>
  );
}
