export type RideOfferStatus = 'detected';

export type RideOfferAnalysis = {
  netProfit: number;
  gainPerKm: number;
  gainPerHour: number;
  netPerHour: number;
  costPerKm: number;
  goalPerKm: number;
  goalPerHour: number;
  classification: 'green' | 'yellow' | 'red';
  reasons: string[];
};

export type RideOffer = {
  /** Unique id, used for deduplication. */
  id: string;
  /** Always `ride_offer` for offers captured from a screen/notification. */
  type: string;
  /** `uber` | `app99` | package name fallback. */
  platform: string;
  /** Package that produced the offer, e.g. `com.ubercab.driver`. */
  packageName: string;
  /** Parsed fare in BRL (numeric), e.g. `18.5`. */
  fare: number | null;
  /** Parsed distance in km (numeric), e.g. `7.4`. */
  distance: number | null;
  /** Parsed duration in minutes (numeric), e.g. `22`. */
  durationMinutes: number | null;
  /** Passenger rating, e.g. `4.9` (null when not shown on the offer). */
  rating: number | null;
  /** Epoch milliseconds the offer was captured. */
  capturedAt: number;
  /** Initial lifecycle state. */
  status: RideOfferStatus;
  /** Full normalized screen text (truncated), for diagnosis. */
  rawText: string | null;
  /** Display helpers (kept for the existing card). */
  title: string;
  text: string;
  fee: string;
  distanceKm: string;
  etaMin: string;
  isOffer: boolean;
  /** Profit analysis (only when fare is known). */
  analysis: RideOfferAnalysis | null;
  /** Flattened analysis helpers. */
  netProfit?: number;
  gainPerKm?: number;
  gainPerHour?: number;
  netPerHour?: number;
  classification?: 'green' | 'yellow' | 'red';
};

export type KmproOfferListenerEvents = {
  onOffer(payload: RideOffer): void;
  onListenerConnected(payload: { connected: boolean }): void;
  onAccessibilityConnected(payload: { connected: boolean }): void;
};