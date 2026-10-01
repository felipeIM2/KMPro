import * as React from 'react';
import { AppState } from 'react-native';
import {
  checkAccessibilityAccess,
  checkListenerConnected,
  checkNotificationAccess,
  checkPostNotificationsGranted,
  isOverlayEnabled,
  isOverlayPermissionGranted,
  requestNotificationAccess,
  setOverlayEnabled,
  setWatchedPackages,
  subscribeToListenerStatus,
  subscribeToOffers,
  type RideOffer,
} from '../../modules/kmpro-offer-listener';

/**
 * Só observa o estado das permissões e as expõe para as telas. O pedido da
 * permissão de runtime (POST_NOTIFICATIONS) é sempre explícito: acontece quando
 * o usuário toca em "Conceder acesso" na tela de Acessos — nunca automático ao
 * abrir o app ou voltar de uma tela de configurações.
 */
export function useOfferListener() {
  const [accessGranted, setAccessGranted] = React.useState(false);
  const [connected, setConnected] = React.useState(false);
  const [latestOffer, setLatestOffer] = React.useState<RideOffer | null>(null);
  const [overlayEnabled, setOverlayEnabledState] = React.useState(false);
  const [overlayGranted, setOverlayGranted] = React.useState(false);
  const [accessibilityGranted, setAccessibilityGranted] = React.useState(false);
  const [postNotificationsGranted, setPostNotificationsGranted] =
    React.useState(false);

  const check = React.useCallback((): void => {
    Promise.all([
      checkNotificationAccess(),
      checkAccessibilityAccess(),
      checkPostNotificationsGranted(),
    ])
      .then(([notif, a11y, post]) => {
        setAccessGranted(notif);
        setAccessibilityGranted(a11y);
        setPostNotificationsGranted(post);
      })
      .catch(() => {});
    checkListenerConnected()
      .then(setConnected)
      .catch(() => setConnected(false));
    isOverlayEnabled().then(setOverlayEnabledState).catch(() => {});
    isOverlayPermissionGranted().then(setOverlayGranted).catch(() => {});
  }, []);

  const toggleOverlay = React.useCallback((value: boolean) => {
    setOverlayEnabledState(value);
    setOverlayEnabled(value).catch(() => {});
  }, []);

  React.useEffect(() => {
    check();

    const unsubscribe = subscribeToOffers((offer) => {
      if (offer.isOffer) setLatestOffer(offer);
    });
    const unsubscribeConnected = subscribeToListenerStatus(setConnected);
    const appState = AppState.addEventListener('change', (state) => {
      if (state === 'active') check();
    });

    return () => {
      unsubscribe();
      unsubscribeConnected();
      appState.remove();
    };
  }, [check]);

  return {
    accessGranted,
    connected,
    latestOffer,
    overlayEnabled,
    overlayGranted,
    accessibilityGranted,
    postNotificationsGranted,
    toggleOverlay,
    syncWatchedPackages: setWatchedPackages,
    requestAccess: requestNotificationAccess,
    refresh: check,
  };
}