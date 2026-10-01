import * as React from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import {
  Accessibility,
  BellRing,
  ShieldCheck,
  X,
} from 'lucide-react-native';
import { useOfferListener } from '@/hooks/use-offer-listener';
import {
  requestAccessibilityAccess,
  requestPostNotifications,
} from '../../modules/kmpro-offer-listener';
import { cn } from '@/lib/utils';
import { setSkipAccessGate } from '@/lib/access-gate';

type AccessItemProps = {
  title: string;
  description: string;
  granted: boolean;
  onGrant: () => void;
  icon: React.ReactNode;
  /** Deixa claro para onde o botão leva: popup do sistema ou Configurações. */
  hint?: string;
};

function AccessItem({
  title,
  description,
  granted,
  onGrant,
  icon,
  hint,
}: AccessItemProps) {
  return (
    <View className="gap-3 rounded-xl border border-border bg-card p-4">
      <View className="flex-row items-center gap-3">
        <View
          className={cn(
            'h-10 w-10 items-center justify-center rounded-lg',
            granted ? 'bg-primary/15' : 'bg-secondary'
          )}
        >
          {icon}
        </View>
        <View className="flex-1 gap-0.5">
          <Text className="text-sm font-semibold text-foreground">{title}</Text>
          <Text className="text-xs leading-4 text-muted-foreground">
            {description}
          </Text>
        </View>
        <View
          className={cn(
            'h-6 w-6 items-center justify-center rounded-full',
            granted ? 'bg-primary/20' : 'bg-destructive/20'
          )}
        >
          {granted ? (
            <ShieldCheck size={14} color="#10b981" />
          ) : (
            <X size={14} color="#ef4444" />
          )}
        </View>
      </View>

      {granted ? (
        <View className="items-center rounded-lg bg-secondary py-2.5">
          <Text className="text-xs font-bold uppercase tracking-wider text-primary">
            Acesso concedido
          </Text>
        </View>
      ) : (
        <View className="gap-1.5">
          <Pressable
            accessibilityRole="button"
            onPress={onGrant}
            className="items-center rounded-lg bg-primary px-4 py-2.5 active:opacity-80"
          >
            <Text className="text-sm font-bold text-primary">Conceder acesso</Text>
          </Pressable>
          {hint ? (
            <Text className="text-center text-[11px] text-muted-foreground">
              {hint}
            </Text>
          ) : null}
        </View>
      )}
    </View>
  );
}

export default function Acessos() {
  const insets = useSafeAreaInsets();
  const { accessibilityGranted, postNotificationsGranted } =
    useOfferListener();

  const allGranted = accessibilityGranted;

  const grantAccessibility = React.useCallback(() => {
    requestAccessibilityAccess().catch(() => {});
  }, []);

  // O popup do Android só aparece aqui, no toque explícito do usuário.
  const grantPostNotifications = React.useCallback(() => {
    requestPostNotifications().catch(() => {});
  }, []);

  return (
    <ScrollView
      className="flex-1 bg-background"
      contentContainerStyle={{ paddingTop: insets.top + 12, paddingBottom: 32 }}
      showsVerticalScrollIndicator={false}
    >
      <View className="gap-5 px-4">
        <View className="gap-1">
          <Text className="text-2xl font-bold tracking-tight text-foreground">
            Acessos necessários
          </Text>
          <Text className="text-xs text-muted-foreground">
            O Copiloto precisa destas permissões para avisar sobre as ofertas e
            ler a tela quando elas aparecem
          </Text>
        </View>

        <AccessItem
          title="Notificações"
          description="Permite mostrar o aviso de oferta na central de notificações"
          granted={postNotificationsGranted}
          onGrant={grantPostNotifications}
          icon={<BellRing size={18} color="#a3a3a3" />}
          hint="Abre o pop-up de permissão do próprio Android"
        />

        <AccessItem
          title="Acessibilidade"
          description="Lê o que está na tela quando a oferta aparece"
          granted={accessibilityGranted}
          onGrant={grantAccessibility}
          icon={<Accessibility size={18} color="#a3a3a3" />}
          hint="Abre as Configurações do Android (o Android não tem pop-up para acessibilidade)"
        />


        <Pressable
          accessibilityRole="button"
          accessibilityState={{ disabled: !allGranted }}
          disabled={!allGranted}
          onPress={() => router.replace('/copiloto/copiloto')}
          className={cn(
            'items-center rounded-xl bg-primary py-3.5 active:opacity-80',
            !allGranted && 'opacity-40'
          )}
        >
          <Text className="text-base font-bold text-primary">Continuar</Text>
        </Pressable>

        {!allGranted ? (
          <Text className="text-center text-xs text-muted-foreground">
            Conceda os acessos acima para continuar
          </Text>
        ) : (
          <Text className="text-center text-xs text-muted-foreground">
            Todos os acessos estão liberados
          </Text>
        )}

        <Pressable
          accessibilityRole="button"
          onPress={() => {
            setSkipAccessGate();
            router.replace('/copiloto/copiloto');
          }}
          className="items-center py-1"
        >
          <Text className="text-xs font-medium text-muted-foreground underline">
            Continuar mesmo assim (sem os acessos)
          </Text>
        </Pressable>
      </View>
    </ScrollView>
  );
}