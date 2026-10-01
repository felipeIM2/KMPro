import {
  METRICS,
  type MetricId,
  type MetricDefinition,
} from '@/constants';
import { formatBRL } from './format';

export type RideLike = {
  value: number;
  km: number;
  minutes: number;
};

export function getMetric(id: MetricId): MetricDefinition {
  return METRICS.find((m) => m.id === id) ?? METRICS[0];
}

export function metricValue(
  id: MetricId,
  ride: RideLike,
  costPerKm: number
): number {
  const hours = ride.minutes / 60;
  switch (id) {
    case 'ganhoKm':
      return ride.km ? ride.value / ride.km : 0;
    case 'lucro':
      return ride.value - ride.km * costPerKm;
    case 'ganhoHora':
      return hours ? ride.value / hours : 0;
    case 'lucroHora':
      return hours ? (ride.value - ride.km * costPerKm) / hours : 0;
    default:
      return 0;
  }
}

export function formatMetric(id: MetricId, value: number): string {
  const metric = getMetric(id);
  if (metric.format === 'brl') return formatBRL(value);
  if (metric.format === 'brlPerKm') return `${formatBRL(value)}/km`;
  return `${formatBRL(value)}/h`;
}
