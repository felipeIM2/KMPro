import KmproOfferListenerModule from './KmproOfferListenerModule';
import type {
  KmproOfferListenerEvents,
  RideOffer,
} from './KmproOfferListener.types';

const native = KmproOfferListenerModule;

/** Faixas de Ajustes > Metas usadas para colorir as métricas do cartão. */
export type CardGoalsInput = {
  gainKmMin: number;
  gainKmMax: number;
  gainHourMin: number;
  gainHourMax: number;
  ratingMin: number;
};

export function isListenerAvailable(): boolean {
  return native != null;
}

export async function checkNotificationAccess(): Promise<boolean> {
  return native ? native.isPermissionGranted() : false;
}

export async function requestNotificationAccess(): Promise<boolean> {
  return native ? native.openNotificationAccessSettings() : false;
}

export async function checkListenerConnected(): Promise<boolean> {
  return native ? native.isListenerConnected() : false;
}

export async function checkAccessibilityAccess(): Promise<boolean> {
  return native ? native.isAccessibilityGranted() : false;
}

export async function checkAccessibilityConnected(): Promise<boolean> {
  return native ? native.isAccessibilityConnected() : false;
}

export async function requestAccessibilityAccess(): Promise<boolean> {
  return native ? native.openAccessibilitySettings() : false;
}

export async function checkPostNotificationsGranted(): Promise<boolean> {
  return native ? native.isPostNotificationsGranted() : false;
}

export async function requestPostNotifications(): Promise<boolean> {
  return native ? native.requestPostNotifications() : false;
}

export async function openPostNotificationsSettings(): Promise<boolean> {
  return native ? native.openPostNotificationsSettings() : false;
}

export async function setWatchedPackages(packages: string[]): Promise<void> {
  if (native) await native.setWatchedPackages(packages);
}

export async function getWatchedPackages(): Promise<string[]> {
  return native ? native.getWatchedPackages() : [];
}

export async function getPendingOffers(): Promise<RideOffer[]> {
  return native ? native.getPendingOffers() : [];
}

export async function setDriverSettings(
  costPerKm: number,
  goalPerKm: number,
  goalPerHour: number
): Promise<void> {
  if (native) await native.setDriverSettings(costPerKm, goalPerKm, goalPerHour);
}

export async function setCardAppearance(
  metricOrder: string[],
  cardPosition: string,
  screenDurationSeconds: number
): Promise<void> {
  if (native)
    await native.setCardAppearance(
      metricOrder,
      cardPosition,
      screenDurationSeconds
    );
}

export async function setCardGoals(goals: CardGoalsInput): Promise<void> {
  if (native)
    await native.setCardGoals(
      goals.gainKmMin,
      goals.gainKmMax,
      goals.gainHourMin,
      goals.gainHourMax,
      goals.ratingMin
    );
}

export async function setOverlayEnabled(enabled: boolean): Promise<void> {  if (native) await native.setOverlayEnabled(enabled);
}

export async function setCopilotoActive(active: boolean): Promise<void> {
  if (native) await native.setCopilotoActive(active);
}

export async function isOverlayEnabled(): Promise<boolean> {
  return native ? native.isOverlayEnabled() : false;
}

export async function isOverlayPermissionGranted(): Promise<boolean> {
  return native ? native.isOverlayPermissionGranted() : false;
}

export async function openOverlayPermissionSettings(): Promise<boolean> {
  return native ? native.openOverlayPermissionSettings() : false;
}

type OfferListener = (offer: RideOffer) => void;
type ConnectionListener = (connected: boolean) => void;

function subscribe<EventName extends keyof KmproOfferListenerEvents>(
  eventName: EventName,
  listener: KmproOfferListenerEvents[EventName]
): () => void {
  if (!native) return () => {};
  const subscription = native.addListener(eventName, listener);
  return () => subscription.remove();
}

export function subscribeToOffers(listener: OfferListener): () => void {
  return subscribe('onOffer', listener);
}

export function subscribeToListenerStatus(listener: ConnectionListener): () => void {
  return subscribe('onListenerConnected', (payload) => {
    listener(payload.connected);
  });
}

export function subscribeToAccessibilityStatus(listener: ConnectionListener): () => void {
  return subscribe('onAccessibilityConnected', (payload) => {
    listener(payload.connected);
  });
}