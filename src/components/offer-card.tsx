import * as React from 'react';
import { Text, View } from 'react-native';
import { Star } from 'lucide-react-native';
import type { AppId, MetricId } from '@/constants';
import { totalCostPerKm } from '@/constants';
import { metricValue } from '@/lib/ride-metrics';
import { formatNumber } from '@/lib/format';
import { cn } from '@/lib/utils';
import { Card, CardHeader, CardContent, CardFooter } from './ui/card';
import { Separator } from './ui/separator';

const METRIC_CARD_LABEL: Record<MetricId, string> = {
  ganhoKm: 'Valor/km',
  ganhoHora: 'Valor/h',
  lucro: 'Lucro',
  lucroHora: 'Lucro/h',
};

export type OfferCardData = {
  app: AppId;
  km: number;
  minutes: number;
  value: number;
  rating?: number;
  driver?: string;
  vehicle?: string;
  payment?: string;
};

export type OfferCardTone = 'good' | 'warn' | 'bad';

const TONE_BORDER: Record<OfferCardTone, string> = {
  good: 'border-primary',
  warn: 'border-warning',
  bad: 'border-destructive',
};

const TONE_BG: Record<OfferCardTone, string> = {
  good: 'bg-primary/5',
  warn: 'bg-warning/5',
  bad: 'bg-destructive/5',
};

const TONE_BADGE: Record<OfferCardTone, string> = {
  good: 'bg-primary/10 text-primary',
  warn: 'bg-warning/10 text-warning',
  bad: 'bg-destructive/10 text-destructive',
};

const TONE_TEXT: Record<OfferCardTone, string> = {
  good: 'text-primary',
  warn: 'text-warning',
  bad: 'text-destructive',
};

export type MetricTones = Partial<Record<MetricId | 'rating', OfferCardTone>>;

type OfferCardProps = {
  offer: OfferCardData;
  order: MetricId[];
  tone?: OfferCardTone;
  tones?: MetricTones;
  costPerKm?: number;
  className?: string;
};

export function OfferCard({
  offer,
  order,
  tone = 'good',
  tones,
  costPerKm: costPerKmProp,
  className,
}: OfferCardProps) {
  const costPerKm = costPerKmProp ?? totalCostPerKm();
  const border = TONE_BORDER[tone];
  const bg = TONE_BG[tone];

  return (
    <View className="w-[360px] items-center">
      <Card className={cn('w-[360px] border-2', border, bg, className)}>
        <CardHeader className="flex-row items-center justify-between pb-2">
          <Text className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            Valor da Oferta
          </Text>
          <View className={cn('rounded-full px-3 py-1', TONE_BADGE[tone])}>
            <Text className={cn('text-base font-bold', TONE_TEXT[tone])}>
              {formatNumber(offer.value, 2)}
            </Text>
          </View>
        </CardHeader>

        <CardContent className="px-4 pb-4 pt-0">
          <View className="flex-row items-center justify-between gap-2">
            {order.map((id) => {
              const value = metricValue(id, offer, costPerKm);
              const metricTone = tones?.[id];

              return (
                <View key={id} className="flex-1 items-center">
                  <Text className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                    {METRIC_CARD_LABEL[id]}
                  </Text>
                  <Text
                    className={cn(
                      'text-[22px] font-bold',
                      metricTone ? TONE_TEXT[metricTone] : 'text-foreground'
                    )}
                  >
                    {formatNumber(value, 2)}
                  </Text>
                </View>
              );
            })}
          </View>
        </CardContent>

        <Separator />

        <CardFooter className="bg-secondary/50 px-4 py-3">
          <View className="flex-row items-center justify-between w-full">
            <View className="flex-row items-center gap-2">
              <Text className="text-[13px] font-medium text-foreground">
                {offer.minutes} min
              </Text>
              <View className="h-1 w-1 rounded-full bg-muted-foreground" />
              <Text className="text-[13px] font-medium text-foreground">
                {formatNumber(offer.km, 1)} km
              </Text>
            </View>

            {offer.rating ? (
              <View className="flex-row items-center gap-1">
                <Star size={13} color="#f59e0b" fill="#f59e0b" />
                <Text
                  className={cn(
                    'text-[13px] font-semibold',
                    tones?.rating
                      ? TONE_TEXT[tones.rating]
                      : 'text-foreground'
                  )}
                >
                  {formatNumber(offer.rating, 1)}
                </Text>
              </View>
            ) : null}
          </View>
        </CardFooter>
      </Card>
    </View>
  );
}
