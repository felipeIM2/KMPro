import * as React from 'react';
import { Text, View } from 'react-native';
import { Star } from 'lucide-react-native';
import type { AppId, MetricId } from '@/constants';
import { totalCostPerKm } from '@/constants';
import { Separator } from './ui/separator';
import { metricValue } from '@/lib/ride-metrics';
import { formatNumber } from '@/lib/format';
import { cn } from '@/lib/utils';

const METRIC_CARD_LABEL: Record<MetricId, string> = {
  ganhoKm: 'Valor/km',
  ganhoHora: 'Valor/h',
  lucro: 'Lucro',
  lucroHora: 'Lucro/h',
};

/** Fundo da badge do valor: mesma cor de tom da borda do card. */
const TONE_BADGE: Record<OfferCardTone, string> = {
  good: 'bg-primary',
  warn: 'bg-warning',
  bad: 'bg-destructive',
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

/** Cor do texto de uma métrica: vermelho/âmbar/verde conforme a faixa de Metas. */
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
  /** Faixa de cada métrica, para a prévia bater com o card nativo. */
  tones?: MetricTones;
  /** Custo por km configurado pelo usuário; sem ele, usa o padrão. */
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

  return (
    <View className="w-[360px] items-center">
      {/* Badge do valor da corrida, pendurada na borda superior do card.
          `-mt-4` + `z-10` reproduzem o topo negativo do overlay nativo. */}
      <Text
        className={cn(
          'z-10 -mb-4 -mt-4 rounded-full px-2.5 py-1 text-[16px] font-bold',
          TONE_BADGE[tone]
        )}
      >
        {formatNumber(offer.value, 2)}
      </Text>

      <View
        className={cn(
          'w-[360px] overflow-hidden rounded-xl border-4',
          border,
          className
        )}
      >
      <View className="flex-row items-center justify-between gap-2 bg-card px-3 py-3.5">
        {order.map((id) => {
          const value = metricValue(id, offer, costPerKm);
          // A faixa de Metas manda na cor; sem faixa, só lucro destaca.
          const metricTone = tones?.[id];

          return (
            <View key={id} className="items-center">
              <Text className="text-[14px] text-muted-foreground">
                {METRIC_CARD_LABEL[id]}
              </Text>
              <Text
                className={cn(
                  'text-[24px] font-bold',
                  metricTone ? TONE_TEXT[metricTone] : 'text-foreground'
                )}
              >
                {formatNumber(value, 2)}
              </Text>
            </View>
          );
        })}
      </View>

      <Separator />

      <View className="gap-2 bg-secondary p-3.5">
        <View className="flex-row items-center gap-2">
          <Text className="text-[14px] font-medium text-foreground">
            {offer.minutes} min
          </Text>
          <View className="h-1 w-1 rounded-full bg-muted-foreground" />
          <Text className="text-[14px] font-medium text-foreground">
            {formatNumber(offer.km, 1)} km
          </Text>

          <View className="flex-1" />

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
        </View>
      </View>
    </View>
  );
}
