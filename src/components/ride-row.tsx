import * as React from 'react';
import { Text, View } from 'react-native';
import { Star } from 'lucide-react-native';
import type { Trip } from '@/constants';
import { AppIcon } from './app-icon';
import { Badge } from './ui/badge';
import { Separator } from './ui/separator';
import { formatBRL, formatNumber } from '@/lib/format';
import { cn } from '@/lib/utils';

type RideRowProps = {
  trip: Trip;
  showStatus?: boolean;
  className?: string;
};

function MiniStat({ label, value }: { label: string; value: string }) {
  return (
    <View className="flex-1 gap-0.5">
      <Text className="text-[9px] font-semibold uppercase tracking-wider text-muted-foreground">
        {label}
      </Text>
      <Text className="text-xs font-semibold text-foreground">{value}</Text>
    </View>
  );
}

export function RideRow({ trip, showStatus, className }: RideRowProps) {
  const refused = trip.status === 'recusada';

  return (
    <View
      className={cn(
        'gap-3 rounded-xl border border-primary/50 bg-card p-3.5',
        className
      )}
    >
      <View className="flex-row items-start gap-3">
        <AppIcon app={trip.app} size={34} />

        <View className="flex-1 gap-1">
          <View className="flex-row items-center gap-2">
            <Text className="text-xs font-semibold text-foreground">
              {trip.time}
            </Text>
            {showStatus ? (
              <Badge
                label={refused ? 'Recusada' : 'Aceita'}
                variant={refused ? 'destructive' : 'success'}
                dot
              />
            ) : null}
          </View>
          <Text numberOfLines={1} className="text-sm font-medium text-foreground">
            {trip.origin}
            <Text className="text-muted-foreground"> → </Text>
            {trip.destination}
          </Text>
          <Text className="text-[11px] text-muted-foreground">
            {formatNumber(trip.km, 1)} km · {trip.duration}
          </Text>
        </View>

        <View className="items-end gap-1">
          <Text className="text-base font-bold text-foreground">
            {formatBRL(trip.value)}
          </Text>
          <Text
            className={cn(
              'text-[11px] font-semibold',
              refused ? 'text-destructive' : 'text-primary'
            )}
          >
            {refused ? 'não aceita' : `+ ${formatBRL(trip.profit)}`}
          </Text>
        </View>
      </View>

      <Separator />

      <View className="flex-row items-center gap-3">
        <MiniStat
          label="Ganho/km"
          value={`${formatBRL(trip.gainPerKm)}/km`}
        />
        <Separator orientation="vertical" className="h-6" />
        <MiniStat
          label="Ganho/h"
          value={formatBRL(trip.gainPerHour)}
        />
        <Separator orientation="vertical" className="h-6" />
        <MiniStat label="Lucro/h" value={formatBRL(trip.profitPerHour)} />
        {typeof trip.rating === 'number' ? (
          <View className="flex-row items-center gap-1">
            <Star size={11} color="#f59e0b" fill="#f59e0b" />
            <Text className="text-xs font-semibold text-foreground">
              {formatNumber(trip.rating, 1)}
            </Text>
          </View>
        ) : (
          <View />
        )}
      </View>
    </View>
  );
}
