import { NativeModule, requireOptionalNativeModule } from 'expo';
import type {
  KmproOfferListenerEvents,
  RideOffer,
} from './KmproOfferListener.types';

declare class KmproOfferListenerModule extends NativeModule<KmproOfferListenerEvents> {
  isPermissionGranted(): Promise<boolean>;
  openNotificationAccessSettings(): Promise<boolean>;
  isListenerConnected(): Promise<boolean>;
  isAccessibilityGranted(): Promise<boolean>;
  isAccessibilityConnected(): Promise<boolean>;
  openAccessibilitySettings(): Promise<boolean>;
  isPostNotificationsGranted(): Promise<boolean>;
  requestPostNotifications(): Promise<boolean>;
  openPostNotificationsSettings(): Promise<boolean>;
  setWatchedPackages(packages: string[]): Promise<void>;
  getWatchedPackages(): Promise<string[]>;
  getPendingOffers(): Promise<RideOffer[]>;
  setDriverSettings(
    costPerKm: number,
    goalPerKm: number,
    goalPerHour: number
  ): Promise<void>;
  setCardAppearance(
    metricOrder: string[],
    cardPosition: string,
    screenDurationSeconds: number
  ): Promise<void>;
  setCardGoals(
    gainKmMin: number,
    gainKmMax: number,
    gainHourMin: number,
    gainHourMax: number,
    ratingMin: number,
    custoHora: number
  ): Promise<void>;
  setOverlayEnabled(enabled: boolean): Promise<void>;
  setCopilotoActive(active: boolean): Promise<void>;
  isOverlayEnabled(): Promise<boolean>;
  isOverlayPermissionGranted(): Promise<boolean>;
  openOverlayPermissionSettings(): Promise<boolean>;
}

// Available only in Android development builds. Returns `null` on iOS, web and Expo Go.
export default requireOptionalNativeModule<KmproOfferListenerModule>(
  'KmproOfferListener'
);