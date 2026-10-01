import AsyncStorage from '@react-native-async-storage/async-storage';
import { router } from 'expo-router';
import * as React from 'react';
import { ActivityIndicator, View } from 'react-native';

export const ONBOARDING_KEY = '@kmpro/onboarding/done';

export async function hasCompletedOnboarding(): Promise<boolean> {
  const value = await AsyncStorage.getItem(ONBOARDING_KEY);
  return value === 'yes';
}

export async function completeOnboarding(): Promise<void> {
  await AsyncStorage.setItem(ONBOARDING_KEY, 'yes');
}

export default function IndexGate() {
  React.useEffect(() => {
    let active = true;
    void (async () => {
      const done = await hasCompletedOnboarding();
      if (!active) return;
      router.replace(done ? '/copiloto/copiloto' : '/onboarding');
    })();
    return () => {
      active = false;
    };
  }, []);

  return (
    <View className="flex-1 items-center justify-center bg-background">
      <ActivityIndicator size="small" color="#10b981" />
    </View>
  );
}