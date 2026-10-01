import * as React from 'react';
import {
  ScrollView,
  Text,
  View,
  type ScrollViewProps,
  type ViewProps,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { cn } from '../../lib/utils';

type ScreenProps = ScrollViewProps & {
  children: React.ReactNode;
  title: string;
  subtitle?: string;
  headerRight?: React.ReactNode;
  contentClassName?: string;
  scrollClassName?: string;
  footer?: React.ReactNode;
};

export function Screen({
  children,
  title,
  subtitle,
  headerRight,
  contentClassName,
  scrollClassName,
  footer,
  ...props
}: ScreenProps) {
  const insets = useSafeAreaInsets();

  return (
    <View className="flex-1 bg-background">
      <ScrollView
        className={cn('flex-1', scrollClassName)}
        contentContainerClassName={cn('pb-10', contentClassName)}
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
        {...props}
      >
        <View
          className="px-4 pb-4"
          style={{ paddingTop: insets.top + 12 }}
        >
          <View className="flex-row items-center justify-between gap-3">
            <View className="flex-1 gap-1">
              <Text className="text-2xl font-bold tracking-tight text-foreground">
                {title}
              </Text>
              {subtitle ? (
                <Text className="text-xs uppercase tracking-wider text-muted-foreground">
                  {subtitle}
                </Text>
              ) : null}
            </View>
            {headerRight}
          </View>
        </View>
        <View className="gap-4 px-4">{children}</View>
      </ScrollView>
      {footer ? <View style={{ paddingBottom: insets.bottom }}>{footer}</View> : null}
    </View>
  );
}

type SectionProps = ViewProps & {
  title: string;
  hint?: string;
  children: React.ReactNode;
};

export function Section({ title, hint, children, className, ...props }: SectionProps) {
  return (
    <View className={cn('gap-3', className)} {...props}>
      <View className="gap-0.5">
        <Text className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
          {title}
        </Text>
        {hint ? <Text className="text-[11px] text-muted-foreground/70">{hint}</Text> : null}
      </View>
      {children}
    </View>
  );
}
