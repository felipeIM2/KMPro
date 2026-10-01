import * as React from 'react';
import { Text, View } from 'react-native';
import type { LucideIcon } from 'lucide-react-native';
import { Slider } from './ui/slider';
import { formatNumber } from '@/lib/format';
import { cn } from '@/lib/utils';

type Tone = 'good' | 'warn' | 'bad';

const MIN_GAP = 0.05;

const TONE_TEXT: Record<Tone, string> = {
  good: 'text-primary',
  warn: 'text-warning',
  bad: 'text-destructive',
};

type ThresholdCardProps = {
  icon: LucideIcon;
  label: string;
  unit: string;
  value: number;
  domain: [number, number];
  min?: number;
  max?: number;
  onChangeMin?: (value: number) => void;
  onChangeMax?: (value: number) => void;
  decimals?: number;
  hint?: string;
};

export function ThresholdCard({
  icon: Icon,
  label,
  unit,
  value,
  domain,
  min,
  max,
  onChangeMin,
  onChangeMax,
  decimals = 2,
  hint,
}: ThresholdCardProps) {
  const isRange = min !== undefined && max !== undefined;
  const step = domain[1] - domain[0] > 20 ? 1 : 0.05;
  const minGap = Math.max(step, MIN_GAP);

  const tone: Tone = !isRange
    ? value >= (min ?? 0)
      ? 'good'
      : 'bad'
    : value >= (max as number)
      ? 'good'
      : value >= (min as number)
        ? 'warn'
        : 'bad';

  const status =
    tone === 'good' ? 'acima da meta' : tone === 'warn' ? 'na faixa' : 'abaixo';

  return (
    <View className="gap-3.5 rounded-xl border border-border bg-card p-4">
      <View className="flex-row items-center justify-between gap-2">
        <View className="flex-row items-center gap-2">
          <Icon size={15} color="#10b981" />
          <Text className="text-sm font-semibold text-foreground">{label}</Text>
        </View>
        <Text
          className={cn('text-lg font-bold', TONE_TEXT[tone])}
          numberOfLines={1}
        >
          {formatNumber(value, decimals)}
          {unit ? <Text className="text-[11px] font-medium">{unit}</Text> : null}
        </Text>
      </View>

      {isRange ? (
        <View className="gap-2">
          <View className="flex-row items-center justify-between">
            <Text className="text-[10px] font-semibold uppercase tracking-wider text-warning">
              mínimo
            </Text>
            <Text className="text-xs font-bold text-warning">
              {formatNumber(min as number, decimals)}
            </Text>
          </View>
          <Slider
            min={domain[0]}
            max={domain[1]}
            step={step}
            value={min as number}
            tone="warning"
            lowerLimit={domain[0]}
            upperLimit={(max as number) - minGap}
            onChange={(next) => onChangeMin?.(Math.min(next, (max as number) - minGap))}
          />
          <View className="flex-row items-center justify-between">
            <Text className="text-[10px] font-semibold uppercase tracking-wider text-primary">
              máximo
            </Text>
            <Text className="text-xs font-bold text-primary">
              {formatNumber(max as number, decimals)}
            </Text>
          </View>
          <Slider
            min={domain[0]}
            max={domain[1]}
            step={step}
            value={max as number}
            tone="primary"
            lowerLimit={(min as number) + minGap}
            upperLimit={domain[1]}
            onChange={(next) => onChangeMax?.(Math.max(next, (min as number) + minGap))}
          />
        </View>
      ) : (
        <View className="gap-2">
          <View className="flex-row items-center justify-between">
            <Text className="text-[10px] font-semibold uppercase tracking-wider text-primary">
              limite mínimo
            </Text>
            <Text className="text-xs font-bold text-primary">
              {formatNumber(min as number, decimals)}
            </Text>
          </View>
          <Slider
            min={domain[0]}
            max={domain[1]}
            step={0.05}
            value={min as number}
            tone="primary"
            onChange={(next) => onChangeMin?.(next)}
          />
        </View>
      )}

      <View className="flex-row items-center justify-between border-t border-border pt-2.5">
        <View className="flex-row items-center gap-2">
          <View
            className={cn(
              'h-1.5 w-1.5 rounded-full',
              tone === 'good'
                ? 'bg-primary'
                : tone === 'warn'
                  ? 'bg-warning'
                  : 'bg-destructive'
            )}
          />
          <Text className="text-[11px] text-muted-foreground">
            {hint ?? status}
          </Text>
        </View>
        <Text className="text-[10px] text-muted-foreground">
          {formatNumber(domain[0], domain[1] > 5 ? 0 : 1)} —{' '}
          {formatNumber(domain[1], domain[1] > 5 ? 0 : 1)}
        </Text>
      </View>
    </View>
  );
}
