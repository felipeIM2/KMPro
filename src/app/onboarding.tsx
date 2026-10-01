import { router } from 'expo-router';
import * as React from 'react';
import { AppState, Platform, Pressable, ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Accessibility, ArrowRight, Bell, Check, Radar, ShieldCheck } from 'lucide-react-native';
import {
  checkAccessibilityAccess,
  checkPostNotificationsGranted,
  isListenerAvailable,
  requestAccessibilityAccess,
  requestPostNotifications,
  subscribeToAccessibilityStatus,
} from '../../modules/kmpro-offer-listener';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { completeOnboarding } from './index';

type PermissionState = {
  key: string;
  icon: React.ReactNode;
  title: string;
  description: string;
  granted: boolean;
  required: boolean;
  actionLabel: string;
  onRequest: () => Promise<boolean>;
};

export default function Onboarding() {
  const insets = useSafeAreaInsets();
  const available = isListenerAvailable();
  const isAndroid = Platform.OS === 'android';

  const [postNotifications, setPostNotifications] = React.useState(false);
  const [accessibility, setAccessibility] = React.useState(false);
  const [busy, setBusy] = React.useState(false);

  const refresh = React.useCallback(() => {
    void checkPostNotificationsGranted()
      .then(setPostNotifications)
      .catch(() => setPostNotifications(false));
    void checkAccessibilityAccess()
      .then(setAccessibility)
      .catch(() => setAccessibility(false));
  }, []);

  React.useEffect(() => {
    refresh();
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'active') refresh();
    });
    const unsubAccessibility = subscribeToAccessibilityStatus(() => refresh());
    return () => {
      sub.remove();
      unsubAccessibility();
    };
  }, [refresh]);

  const permissions: PermissionState[] = [
    {
      key: 'post-notifications',
      icon: <Bell size={18} color="#10b981" />,
      title: 'Notificações',
      description:
        'Permite mostrar o aviso de oferta na central de notificações do aparelho.',
      granted: postNotifications,
      required: true,
      actionLabel: postNotifications ? 'Permitido' : 'Conceder acesso',
      onRequest: requestPostNotifications,
    },
    {
      key: 'accessibility',
      icon: <Accessibility size={18} color="#10b981" />,
      title: 'Acessibilidade',
      description:
        'Necessária para exibir o cartão de decisão por cima do app de corrida e aceitar ofertas automaticamente.',
      granted: accessibility,
      required: false,
      actionLabel: accessibility ? 'Permitido' : 'Ativar serviço',
      onRequest: requestAccessibilityAccess,
    },
  ];

  const canContinue = !available || postNotifications;

  const handleContinue = () => {
    setBusy(true);
    void completeOnboarding()
      .catch(() => undefined)
      .finally(() => {
        setBusy(false);
        router.replace('/copiloto/copiloto');
      });
  };

  return (
    <ScrollView
      className="flex-1 bg-background"
      contentContainerStyle={{ paddingTop: insets.top + 24, paddingBottom: insets.bottom + 24 }}
      showsVerticalScrollIndicator={false}
    >
      <View className="gap-5 px-4">
        <View className="items-center gap-4">
          <View className="h-20 w-20 items-center justify-center rounded-full bg-primary/15">
            <Radar size={38} color="#10b981" />
          </View>
          <View className="items-center gap-1">
            <Text className="text-3xl font-bold tracking-tight text-foreground">
              KMPro
            </Text>
            <Text className="text-sm text-muted-foreground">
              Seu copiloto para decidir corridas em tempo real
            </Text>
          </View>
        </View>

        <View className="gap-5 rounded-xl border border-border bg-card p-5">
          <View className="flex-row items-center gap-2">
            <ShieldCheck size={16} color="#10b981" />
            <Text className="text-sm font-bold text-foreground">
              Antes de começar
            </Text>
          </View>
          <Text className="text-xs leading-relaxed text-muted-foreground">
            Para ler ofertas e agir por você, o KMPro precisa de acesso a estes
            recursos do seu aparelho. Você pode revogar ou ajustar tudo depois,
            em Ajustes.
          </Text>

          {!available ? (
            <View className="gap-1 rounded-lg border border-amber-500/40 bg-amber-500/10 p-3">
              <Text className="text-xs font-bold text-amber-500">
                Build de desenvolvimento necessário
              </Text>
              <Text className="text-[11px] leading-relaxed text-amber-500/90">
                As permissões não podem ser verificadas neste ambiente. Rode{' '}
                <Text className="font-mono">npx expo run:android</Text> e
                abra o app de novo.
              </Text>
            </View>
          ) : null}

          {permissions.map((permission) => (
            <View
              key={permission.key}
              className="gap-2 rounded-lg border border-border bg-secondary p-3"
            >
              <View className="flex-row items-start gap-3">
                <View className="h-9 w-9 items-center justify-center rounded-lg bg-primary/15">
                  {permission.icon}
                </View>
                <View className="flex-1 gap-0.5">
                  <View className="flex-row items-center gap-2">
                    <Text className="text-xs font-bold text-foreground">
                      {permission.title}
                    </Text>
                    {permission.required ? (
                      <View className="rounded-full bg-primary/15 px-2 py-0.5">
                        <Text className="text-[9px] font-bold uppercase tracking-wider text-primary">
                          Necessário
                        </Text>
                      </View>
                    ) : null}
                  </View>
                  <Text className="text-[11px] leading-relaxed text-muted-foreground">
                    {permission.description}
                  </Text>
                </View>
              </View>

              {available && permission.granted ? (
                <View className="flex-row items-center justify-between rounded-lg border border-primary/40 bg-primary/10 px-3 py-2">
                  <View className="flex-row items-center gap-1.5">
                    <Check size={13} color="#10b981" />
                    <Text className="text-xs font-bold text-primary">
                      Permissão ativa
                    </Text>
                  </View>
                  <Text className="text-[10px] text-muted-foreground">
                    atenção: você pode revogar em Ajustes
                  </Text>
                </View>
              ) : null}

              {isAndroid && available ? (
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={permission.actionLabel}
                  onPress={() => {
                    void permission.onRequest().catch(() => undefined);
                  }}
                  className={cn(
                    'items-center rounded-lg border px-3 py-2 active:opacity-70',
                    permission.granted
                      ? 'border-border bg-transparent'
                      : 'border-primary bg-primary/10'
                  )}
                >
                  <Text
                    className={cn(
                      'text-xs font-bold',
                      permission.granted ? 'text-foreground' : 'text-primary'
                    )}
                  >
                    {permission.actionLabel}
                  </Text>
                </Pressable>
              ) : null}
            </View>
          ))}

          {isAndroid && available && !accessibility ? (
            <Text className="text-[10px] leading-relaxed text-muted-foreground/80">
              A acessibilidade é recomendada para o cartão sobre o app e o
              aceite automático de ofertas. Você já pode continuar sem ela.
            </Text>
          ) : null}
        </View>

        <Button
          size="lg"
          label="Começar a usar o KMPro"
          disabled={!canContinue}
          loading={busy}
          onPress={handleContinue}
        >
          <ArrowRight size={16} color="#022c22" />
        </Button>
        <Text className="text-center text-[10px] text-muted-foreground">
          Ao continuar, você aceita que o KMPro leia notificações dos apps
          monitorados apenas com o Copiloto ativo.
        </Text>
      </View>
    </ScrollView>
  );
}