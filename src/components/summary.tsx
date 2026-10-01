import * as React from 'react';
import { Text, View } from 'react-native';
import { formatBRL, formatInt, formatNumber } from '@/lib/format';
import { cn } from '@/lib/utils';

type StatTileProps = {
  label: string;
  value: string;
  hint?: string;
  tone?: 'default' | 'primary' | 'muted';
  className?: string;
};

const toneClasses = {
  default: 'text-foreground',
  primary: 'text-primary',
  muted: 'text-muted-foreground',
} as const;

export function StatTile({
  label,
  value,
  hint,
  tone = 'default',
  className,
}: StatTileProps) {
  return (
    <View className={cn('flex-1 gap-1', className)}>
      <Text className="text-[9px] font-semibold uppercase tracking-wider text-muted-foreground">
        {label}
      </Text>
      <Text className={cn('text-base font-bold', toneClasses[tone])}>
        {value}
      </Text>
      {hint ? (
        <Text className="text-[10px] text-muted-foreground">{hint}</Text>
      ) : null}
    </View>
  );
}

type SummaryProps = {
  gross: number;
  profit: number;
  rides: number;
  km: number;
  goal?: number;
};

export function SummaryCard({ gross, profit, rides, km, goal }: SummaryProps) {
  const pct = goal ? Math.min(gross / goal, 1) : 0;

  return (
    <View className="gap-4 rounded-xl border border-primary/25 bg-primary/5 p-4">
      <View className="flex-row items-end justify-between gap-3">
        <View className="gap-1">
          <Text className="text-[10px] font-semibold uppercase tracking-wider text-primary">
            Bruto acumulado
          </Text>
          <Text className="text-3xl font-bold tracking-tight text-foreground">
            {formatBRL(gross)}
          </Text>
        </View>
        <View className="items-end gap-1">
          <Text className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
            Lucro
          </Text>
          <Text className="text-lg font-bold text-primary">
            {formatBRL(profit)}
          </Text>
        </View>
      </View>

      {goal ? (
        <View className="gap-1.5">
          <View className="h-1.5 w-full overflow-hidden rounded-full bg-border">
            <View
              className="h-full rounded-full bg-primary"
              style={{ width: `${pct * 100}%` }}
            />
          </View>
          <Text className="text-[10px] text-muted-foreground">
            {formatInt(pct * 100)}% da meta de {formatBRL(goal)}
          </Text>
        </View>
      ) : null}

      <View className="flex-row gap-3 border-t border-border pt-3">
        <StatTile label="Corridas" value={formatInt(rides)} />
        <StatTile label="Km rodado" value={formatNumber(km, 1)} />
        <StatTile
          label="Lucro"
          value={formatBRL(profit)}
          tone="primary"
        />
      </View>
    </View>
  );
}
