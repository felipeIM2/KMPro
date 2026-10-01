import {
  DEFAULT_COST_SETTINGS,
  getApp,
  totalCostPerKm,
  type AppId,
  type RideStatus,
} from './config';

type RawTrip = {
  id: string;
  app: AppId;
  status: RideStatus;
  value: number;
  km: number;
  minutes: number;
  origin: string;
  destination: string;
  time: string;
  dayOffset: number;
  rating?: number;
  driver?: string;
  vehicle?: string;
};

export type Trip = RawTrip & {
  date: Date;
  dateKey: string;
  appName: string;
  duration: string;
  cost: number;
  profit: number;
  gainPerKm: number;
  gainPerHour: number;
  profitPerHour: number;
};

const RAW_TRIPS: RawTrip[] = [
  // Hoje
  { id: 't01', app: 'uber', status: 'aceita', value: 18.4, km: 4.3, minutes: 13, origin: 'Centro', destination: 'República', time: '14:32', dayOffset: 0, rating: 5, driver: 'Marcos A.', vehicle: 'HB2' },
  { id: 't02', app: '99', status: 'aceita', value: 27.9, km: 9.4, minutes: 24, origin: 'Vila Mariana', destination: 'Aeroporto Guarulhos', time: '13:05', dayOffset: 0, rating: 4.9, driver: 'Renata S.', vehicle: 'Onix' },
  { id: 't03', app: 'uber', status: 'recusada', value: 9.6, km: 6.2, minutes: 19, origin: 'Pinheiros', destination: 'Morumbi', time: '12:48', dayOffset: 0 },
  { id: 't04', app: '99', status: 'aceita', value: 14.2, km: 5.1, minutes: 16, origin: 'Moema', destination: 'Itaim Bibi', time: '11:20', dayOffset: 0, rating: 4.8, driver: 'Cláudio M.', vehicle: 'Argo' },
  { id: 't05', app: 'uber', status: 'recusada', value: 6.4, km: 4.8, minutes: 15, origin: 'Bela Vista', destination: 'Consolação', time: '10:12', dayOffset: 0 },
  { id: 't06', app: '99', status: 'aceita', value: 32.7, km: 11.6, minutes: 27, origin: 'Tatuapé', destination: 'Santana', time: '09:15', dayOffset: 0, rating: 5, driver: 'Wagner L.', vehicle: 'Kicks' },

  // Ontem
  { id: 't07', app: 'uber', status: 'aceita', value: 21.3, km: 6.4, minutes: 18, origin: 'Brooklin', destination: 'Berrini', time: '20:10', dayOffset: 1, rating: 4.9, driver: 'Patrícia G.', vehicle: 'Civic' },
  { id: 't08', app: '99', status: 'aceita', value: 12.8, km: 5.4, minutes: 17, origin: 'Lapa', destination: 'Perdizes', time: '19:02', dayOffset: 1, rating: 4.7, driver: 'Jonas P.', vehicle: 'Polo' },
  { id: 't09', app: 'uber', status: 'recusada', value: 15.5, km: 7.9, minutes: 21, origin: 'Jardins', destination: 'Campo Belo', time: '17:44', dayOffset: 1 },
  { id: 't10', app: 'uber', status: 'aceita', value: 44.1, km: 15.2, minutes: 31, origin: 'Congonhas', destination: 'Guarulhos', time: '15:20', dayOffset: 1, rating: 4.9, driver: 'Sérgio R.', vehicle: 'Corolla' },
  { id: 't11', app: '99', status: 'recusada', value: 8.9, km: 5.6, minutes: 18, origin: 'Higienópolis', destination: 'Santa Cecília', time: '14:31', dayOffset: 1 },

  // Anteontem
  { id: 't12', app: 'uber', status: 'aceita', value: 16.9, km: 5.8, minutes: 19, origin: 'Vila Madalena', destination: 'Sumaré', time: '22:40', dayOffset: 2, rating: 5, driver: 'Bruno T.', vehicle: 'Fit' },
  { id: 't13', app: '99', status: 'aceita', value: 19.7, km: 7.1, minutes: 20, origin: 'Butantã', destination: 'Pinheiros', time: '18:25', dayOffset: 2, rating: 4.8, driver: 'Aline C.', vehicle: 'Onix' },
  { id: 't14', app: '99', status: 'recusada', value: 11.2, km: 6.6, minutes: 20, origin: 'Santana', destination: 'Tucuruvi', time: '12:05', dayOffset: 2 },
  { id: 't15', app: 'uber', status: 'aceita', value: 11.1, km: 4.1, minutes: 12, origin: 'Paraíso', destination: 'Liberdade', time: '08:50', dayOffset: 2, rating: 4.6, driver: 'Diego F.', vehicle: 'Voyage' },

  // 3 dias atrás
  { id: 't16', app: '99', status: 'aceita', value: 25.4, km: 8.9, minutes: 23, origin: 'Anhanguera', destination: 'Lapa', time: '21:15', dayOffset: 3, rating: 4.9, driver: 'Carla M.', vehicle: 'Cronos' },
  { id: 't17', app: 'uber', status: 'recusada', value: 7.2, km: 4.3, minutes: 14, origin: 'Mooca', destination: 'Brás', time: '16:40', dayOffset: 3 },
  { id: 't18', app: 'uber', status: 'aceita', value: 13.6, km: 5.4, minutes: 17, origin: 'Vila Olímpia', destination: 'Cidade Jardim', time: '11:05', dayOffset: 3, rating: 5, driver: 'Hugo N.', vehicle: 'Argo' },
];

const COST_PER_KM = totalCostPerKm(DEFAULT_COST_SETTINGS);

const startOfToday = new Date();
startOfToday.setHours(0, 0, 0, 0);

function buildTrip(raw: RawTrip): Trip {
  const [hours, minutes] = raw.time.split(':').map(Number);
  const date = new Date(startOfToday);
  date.setDate(date.getDate() - raw.dayOffset);
  date.setHours(hours, minutes, 0, 0);

  const cost = raw.km * COST_PER_KM;
  const profit = raw.value - cost;
  const hours2 = raw.minutes / 60;

  return {
    ...raw,
    date,
    dateKey: date.toISOString(),
    appName: getApp(raw.app).name,
    duration: `${raw.minutes} min`,
    cost,
    profit,
    gainPerKm: raw.value / raw.km,
    gainPerHour: raw.value / hours2,
    profitPerHour: profit / hours2,
  };
}

export const MOCK_TRIPS: Trip[] = RAW_TRIPS.map(buildTrip).sort(
  (a, b) => b.date.getTime() - a.date.getTime()
);

export const ACCEPTED_TRIPS = MOCK_TRIPS.filter((t) => t.status === 'aceita');

export type PeriodStats = {
  rides: number;
  km: number;
  minutes: number;
  gross: number;
  cost: number;
  profit: number;
  gainPerKm: number;
  gainPerHour: number;
  averageRating: number | null;
};

export function buildStats(trips: Trip[]): PeriodStats {
  const sum = (fn: (t: Trip) => number) => trips.reduce((acc, t) => acc + fn(t), 0);
  const km = sum((t) => t.km);
  const minutes = sum((t) => t.minutes);
  const gross = sum((t) => t.value);
  const rated = trips.filter((t) => typeof t.rating === 'number');

  return {
    rides: trips.length,
    km,
    minutes,
    gross,
    cost: sum((t) => t.cost),
    profit: sum((t) => t.profit),
    gainPerKm: km ? gross / km : 0,
    gainPerHour: minutes ? gross / (minutes / 60) : 0,
    averageRating: rated.length
      ? rated.reduce((acc, t) => acc + (t.rating ?? 0), 0) / rated.length
      : null,
  };
}

export const MOCK_OFFER = {
  app: 'uber' as AppId,
  origin: 'Rua Augusta',
  destination: 'Vila Madalena',
  km: 4.8,
  minutes: 16,
  value: 22.7,
  rating: 4.9,
  driver: 'Ricardo N.',
  vehicle: 'Compacto',
  payment: 'Pix',
};
