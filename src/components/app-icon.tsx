import * as React from 'react';
import { Image, Text, View, type ImageSourcePropType } from 'react-native';
import { getApp, type AppId } from '@/constants';
import { cn } from '@/lib/utils';

export const APP_LOGOS: Record<AppId, ImageSourcePropType> = {
  uber: require('@/assets/images/logos/uber.webp'),
  '99': require('@/assets/images/logos/99.webp'),
};

type AppIconProps = {
  app: AppId;
  size?: number;
  className?: string;
  showName?: boolean;
  dimmed?: boolean;
};

export function AppIcon({
  app,
  size = 32,
  className,
  showName = false,
  dimmed = false,
}: AppIconProps) {
  const meta = getApp(app);
  const radius = Math.max(6, Math.round(size * 0.28));

  return (
    <View className={cn('flex-row items-center gap-2', className)}>
      <View
        style={{
          width: size,
          height: size,
          borderRadius: radius,
          overflow: 'hidden',
          borderWidth: 1,
          borderColor: dimmed ? '#262626' : 'rgba(255,255,255,0.1)',
          opacity: dimmed ? 0.45 : 1,
        }}
      >
        <Image
          source={APP_LOGOS[app]}
          style={{ width: '100%', height: '100%' }}
          resizeMode="cover"
          accessibilityIgnoresInvertColors
        />
      </View>
      {showName ? (
        <Text className="text-sm font-medium text-foreground">{meta.name}</Text>
      ) : null}
    </View>
  );
}
