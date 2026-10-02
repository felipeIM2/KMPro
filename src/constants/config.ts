export type AppId = 'uber' | '99';
export type RideStatus = 'aceita' | 'recusada';

export type RideApp = {
  id: AppId;
  name: string;
  accent: string;
  /** Android package used by the driver app, watched by the offer listener. */
  androidPackage: string;
};

export const RIDE_APPS: RideApp[] = [
  {
    id: 'uber',
    name: 'Uber',
    accent: '#fafafa',
    androidPackage: 'com.ubercab.driver',
  },
  {
    id: '99',
    name: '99',
    accent: '#f5c518',
    androidPackage: 'com.app99.driver',
  },
];

export const APP_MAP: Record<AppId, RideApp> = RIDE_APPS.reduce(
  (acc, app) => ({ ...acc, [app.id]: app }),
  {} as Record<AppId, RideApp>
);

export function getApp(id: AppId) {
  return APP_MAP[id];
}

export function getAppByPackage(packageName: string) {
  return RIDE_APPS.find((app) => app.androidPackage === packageName);
}

export type MetricId = 'ganhoKm' | 'lucro' | 'ganhoHora' | 'lucroHora';

export type MetricDefinition = {
  id: MetricId;
  label: string;
  short: string;
  format: 'brl' | 'brlPerKm' | 'brlPerHour';
};

export const METRICS: MetricDefinition[] = [
  { id: 'ganhoKm', label: 'Ganho/km', short: 'Ganho/Km', format: 'brlPerKm' },
  { id: 'lucro', label: 'Lucro', short: 'Lucro', format: 'brl' },
  { id: 'ganhoHora', label: 'Ganho/hora', short: 'Ganho/Hora', format: 'brlPerHour' },
  { id: 'lucroHora', label: 'Lucro/hora', short: 'Lucro/Hora', format: 'brlPerHour' },
];

export const DEFAULT_METRIC_ORDER: MetricId[] = [
  'ganhoKm',
  'lucro',
  'ganhoHora',
  'lucroHora',
];

export type FuelId = 'gasolina' | 'etanol' | 'gnv' | 'eletrico';

export const FUEL_TYPES: { id: FuelId; label: string; short: string }[] = [
  { id: 'gasolina', label: 'Gasolina', short: 'gas' },
  { id: 'etanol', label: 'Etanol', short: 'eta' },
  { id: 'gnv', label: 'GNV', short: 'gnv' },
  { id: 'eletrico', label: 'Elétrico', short: 'kWh' },
];

export const DEFAULT_COST_SETTINGS = {
  daysWorked: 6,
  hoursPerDay: 9,
  kmPerDay: 165,
  weeklyGoal: 2600,
  costs: {
    financing: 890,
    maintenance: 320,
    insurance: 210,
    other: 150,
    taxesAnnual: 2400,
  },
  fuel: {
    gasolina: { autonomy: 26, price: 5.79 },
    etanol: { autonomy: 19, price: 3.89 },
    gnv: { autonomy: 14, price: 3.29 },
    eletrico: { autonomy: 7.5, price: 0.89 },
  },
  selectedFuel: 'gasolina' as FuelId,
};

export type CostSettings = typeof DEFAULT_COST_SETTINGS;

const WEEKS_PER_MONTH = 4.3;

export function fixedMonthlyCost(settings: CostSettings = DEFAULT_COST_SETTINGS) {
  return (
    settings.costs.financing +
    settings.costs.maintenance +
    settings.costs.insurance +
    settings.costs.other +
    (settings.costs.taxesAnnual / 12)
  );
}

export function monthlyKm(settings: CostSettings = DEFAULT_COST_SETTINGS) {
  return settings.daysWorked * settings.kmPerDay * WEEKS_PER_MONTH;
}

export function fuelCostPerKm(
  settings: CostSettings = DEFAULT_COST_SETTINGS
) {
  const fuel = settings.fuel[settings.selectedFuel];
  return fuel.price / fuel.autonomy;
}

export function fixedCostPerKm(settings: CostSettings = DEFAULT_COST_SETTINGS) {
  return fixedMonthlyCost(settings) / monthlyKm(settings);
}

export function totalCostPerKm(settings: CostSettings = DEFAULT_COST_SETTINGS) {
  return fuelCostPerKm(settings) + fixedCostPerKm(settings);
}

/**
 * Custo por hora no ritmo planejado (km/dia ÷ horas/dia). É o alvo do lucro/h
 * e, escalado pela duração da oferta, do lucro por viagem.
 */
export function hourlyCost(settings: CostSettings = DEFAULT_COST_SETTINGS) {
  const hours = settings.hoursPerDay || 1;
  return totalCostPerKm(settings) * (settings.kmPerDay / hours);
}

export { WEEKS_PER_MONTH };
