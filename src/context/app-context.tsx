import * as React from 'react';
import {
  DEFAULT_COST_SETTINGS,
  DEFAULT_METRIC_ORDER,
  RIDE_APPS,
  type AppId,
  type CostSettings,
  type MetricId,
} from '@/constants';
import { setCardGoals } from '../../modules/kmpro-offer-listener';

type MonitoringState = Record<AppId, boolean>;

export const SCREEN_DURATIONS = [4, 6, 8, 10] as const;
export type ScreenDuration = (typeof SCREEN_DURATIONS)[number];

export const CARD_POSITIONS = ['esquerda', 'centro', 'direita'] as const;
export type CardPosition = (typeof CARD_POSITIONS)[number];

export const CARD_POSITION_LABEL: Record<CardPosition, string> = {
  esquerda: 'Esquerda',
  centro: 'Centro',
  direita: 'Direita',
};

type AutoAcceptMetric = {
  enabled: boolean;
  value: number;
};

/**
 * Faixas de Ajustes > Metas. Alimentam a cor de cada métrica do cartão:
 * abaixo do mínimo é vermelho, entre o mínimo e o máximo é amarelo, e no
 * máximo ou acima é verde. Um limite em 0 significa "sem meta" e a métrica
 * fica neutra.
 */
export type PerformanceGoals = {
  gainKm: [number, number];
  gainHour: [number, number];
  rating: number;
};

export const DEFAULT_GOALS: PerformanceGoals = {
  gainKm: [1.5, 2],
  gainHour: [30, 50],
  rating: 4.85,
};

type AutoAcceptSettings = Record<MetricId, AutoAcceptMetric>;

type AppState = {
  monitoring: MonitoringState;
  setMonitoring: (app: AppId, value: boolean) => void;
  monitoredApps: (typeof RIDE_APPS)[number][];
  metricOrder: MetricId[];
  setMetricOrder: (order: MetricId[]) => void;
  screenDuration: ScreenDuration;
  setScreenDuration: (value: ScreenDuration) => void;
  cardPosition: CardPosition;
  setCardPosition: (value: CardPosition) => void;
  goals: PerformanceGoals;
  setGoals: (value: PerformanceGoals) => void;
  copilotoActive: boolean;
  setCopilotoActive: (value: boolean) => void;
  costSettings: CostSettings;
  setCostSettings: React.Dispatch<React.SetStateAction<CostSettings>>;
  autoAcceptEnabled: boolean;
  setAutoAcceptEnabled: (value: boolean) => void;
  autoAccept: AutoAcceptSettings;
  setAutoAccept: React.Dispatch<React.SetStateAction<AutoAcceptSettings>>;
};

const DEFAULT_MONITORING: MonitoringState = {
  uber: true,
  '99': true,
};

const DEFAULT_AUTO_ACCEPT: AutoAcceptSettings = {
  ganhoKm: { enabled: true, value: 1.5 },
  lucro: { enabled: false, value: 10 },
  ganhoHora: { enabled: true, value: 30 },
  lucroHora: { enabled: false, value: 20 },
};

const AppContext = React.createContext<AppState | null>(null);

export function AppProvider({ children }: { children: React.ReactNode }) {
  const [monitoring, setMonitoringState] = React.useState<MonitoringState>(
    DEFAULT_MONITORING
  );
  const [metricOrder, setMetricOrder] = React.useState<MetricId[]>(
    DEFAULT_METRIC_ORDER
  );
  const [copilotoActive, setCopilotoActive] = React.useState(false);
  const [costSettings, setCostSettings] = React.useState<CostSettings>(
    DEFAULT_COST_SETTINGS
  );
  const [autoAcceptEnabled, setAutoAcceptEnabled] = React.useState(false);
  const [autoAccept, setAutoAccept] =
    React.useState<AutoAcceptSettings>(DEFAULT_AUTO_ACCEPT);
  const [screenDuration, setScreenDuration] =
    React.useState<ScreenDuration>(6);
  const [cardPosition, setCardPosition] =
    React.useState<CardPosition>('centro');
  const [goals, setGoals] =
    React.useState<PerformanceGoals>(DEFAULT_GOALS);

  const value = React.useMemo<AppState>(
    () => ({
      monitoring,
      setMonitoring: (app, next) =>
        setMonitoringState((prev) => ({ ...prev, [app]: next })),
      monitoredApps: RIDE_APPS.filter((app) => monitoring[app.id]),
      metricOrder,
      setMetricOrder,
      screenDuration,
      setScreenDuration,
      cardPosition,
      setCardPosition,
      goals,
      setGoals,
      copilotoActive,
      setCopilotoActive,
      costSettings,
      setCostSettings,
      autoAcceptEnabled,
      setAutoAcceptEnabled,
      autoAccept,
      setAutoAccept,
    }),
    [
      monitoring,
      metricOrder,
      screenDuration,
      cardPosition,
      goals,
      copilotoActive,
      costSettings,
      autoAcceptEnabled,
      autoAccept,
    ]
  );

  // As metas vivem aqui e o cartão nativo é quem pinta as métricas; então o
  // sync precisa acontecer no provider, e não só na tela de Ajustes, para que
  // editar em Metas já chegue no card sem passar por Ajustes.
  React.useEffect(() => {
    setCardGoals({
      gainKmMin: goals.gainKm[0],
      gainKmMax: goals.gainKm[1],
      gainHourMin: goals.gainHour[0],
      gainHourMax: goals.gainHour[1],
      ratingMin: goals.rating,
    }).catch(() => {});
  }, [goals]);

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useApp() {
  const context = React.useContext(AppContext);
  if (!context) {
    throw new Error('useApp precisa estar dentro de <AppProvider>');
  }
  return context;
}
