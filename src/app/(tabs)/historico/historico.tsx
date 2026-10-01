import * as React from 'react';
import { Pressable, Text, View } from 'react-native';
import { Check, X } from 'lucide-react-native';
import { Screen } from '@/components/ui/screen';
import { RideRow } from '@/components/ride-row';
import { MOCK_TRIPS, buildStats, type Trip } from '@/constants';
import { formatBRL, formatInt } from '@/lib/format';
import { cn } from '@/lib/utils';

type FilterId = 'todas' | 'aceita' | 'recusada';

const FILTERS: { id: FilterId; label: string }[] = [
  { id: 'todas', label: 'Todas' },
  { id: 'aceita', label: 'Aceitas' },
  { id: 'recusada', label: 'Recusadas' },
];

function startOfDay(date: Date) {
  const copy = new Date(date);
  copy.setHours(0, 0, 0, 0);
  return copy;
}

function dayLabel(date: Date) {
  const today = startOfDay(new Date()).getTime();
  const yesterday = today - 86_400_000;
  const day = startOfDay(date).getTime();

  if (day === today) return 'Hoje';
  if (day === yesterday) return 'Ontem';
  return date.toLocaleDateString('pt-BR', {
    weekday: 'long',
    day: '2-digit',
    month: 'long',
  });
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

export default function Historico() {
  const [filter, setFilter] = React.useState<FilterId>('todas');

  const filtered = React.useMemo(
    () =>
      filter === 'todas'
        ? MOCK_TRIPS
        : MOCK_TRIPS.filter((t) => t.status === filter),
    [filter]
  );

  const days = React.useMemo(() => groupByDay(filtered), [filtered]);
  const stats = React.useMemo(() => buildStats(filtered), [filtered]);
  const accepted = filtered.filter((t) => t.status === 'aceita').length;
  const refused = filtered.length - accepted;
  const acceptance = filtered.length ? accepted / filtered.length : 0;

  return (
    <Screen title="Histórico" subtitle="Todas as ofertas recebidas">
      <View className="flex-row gap-2">
        {FILTERS.map((item) => {
          const active = filter === item.id;
          return (
            <Pressable
              key={item.id}
              onPress={() => setFilter(item.id)}
              className={cn(
                'flex-1 items-center rounded-lg border py-2 active:opacity-70',
                active
                  ? 'border-primary bg-primary/10'
                  : 'border-border bg-card'
              )}
            >
              <Text
                className={cn(
                  'text-xs font-semibold',
                  active ? 'text-primary' : 'text-muted-foreground'
                )}
              >
                {item.label}
              </Text>
            </Pressable>
          );
        })}
      </View>

      <View className="gap-3 rounded-xl border border-border bg-card p-4">
        <View className="flex-row items-baseline justify-between">
          <View className="gap-1">
            <Text className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
              Taxa de aceite
            </Text>
            <Text className="text-2xl font-bold text-foreground">
              {formatInt(acceptance * 100)}%
            </Text>
          </View>
          <View className="flex-row gap-4">
            <View className="items-end gap-0.5">
              <View className="flex-row items-center gap-1">
                <Check size={10} color="#10b981" />
                <Text className="text-[10px] uppercase tracking-wider text-muted-foreground">
                  aceitas
                </Text>
              </View>
              <Text className="text-sm font-bold text-primary">
                {formatInt(accepted)}
              </Text>
            </View>
            <View className="items-end gap-0.5">
              <View className="flex-row items-center gap-1">
                <X size={10} color="#ef4444" />
                <Text className="text-[10px] uppercase tracking-wider text-muted-foreground">
                  recusadas
                </Text>
              </View>
              <Text className="text-sm font-bold text-destructive">
                {formatInt(refused)}
              </Text>
            </View>
          </View>
        </View>

        <View className="h-1.5 w-full flex-row overflow-hidden rounded-full bg-border">
          <View
            className="h-full bg-primary"
            style={{ width: `${acceptance * 100}%` }}
          />
          <View className="h-full flex-1 bg-destructive/60" />
        </View>

        <View className="flex-row items-center justify-between border-t border-border pt-3">
          <Text className="text-[11px] text-muted-foreground">
            {formatInt(filtered.length)} ofertas · {formatInt(stats.km)} km
          </Text>
          <Text className="text-[11px] font-semibold text-foreground">
            {formatBRL(stats.gross)} visualizados
          </Text>
        </View>
      </View>

      {days.map(([key, trips]) => {
        const dayStats = buildStats(trips);
        const dayAccepted = trips.filter((t) => t.status === 'aceita');
        return (
          <View key={key} className="gap-2.5">
            <View className="flex-row items-center justify-between px-0.5">
              <Text className="text-xs font-bold uppercase tracking-wider text-foreground">
                {dayLabel(trips[0].date)}
              </Text>
              <Text className="text-[11px] text-muted-foreground">
                {dayAccepted.length}/{trips.length} aceitas ·{' '}
                {formatBRL(dayStats.gross)}
              </Text>
            </View>
            {trips.map((trip) => (
              <RideRow key={trip.id} trip={trip} showStatus />
            ))}
          </View>
        );
      })}
    </Screen>
  );
}
