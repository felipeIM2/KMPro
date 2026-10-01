import * as React from 'react';
import Animated, { FadeIn } from 'react-native-reanimated';
import { Pressable, Text, View, type ViewProps } from 'react-native';
import { ChevronDown } from 'lucide-react-native';

type CollapsibleProps = ViewProps & {
  open: boolean;
  children: React.ReactNode;
  className?: string;
};

export function Collapsible({ open, children, className, ...props }: CollapsibleProps) {
  if (!open) return null;
  return (
    <Animated.View
      entering={FadeIn.duration(160)}
      className={className}
      {...props}
    >
      {children}
    </Animated.View>
  );
}

type CollapsibleRowProps = {
  icon?: React.ReactNode;
  title: string;
  open: boolean;
  onPress: () => void;
  right?: React.ReactNode;
};

export function CollapsibleRow({
  icon,
  title,
  open,
  onPress,
  right,
}: CollapsibleRowProps) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ expanded: open }}
      onPress={onPress}
      className="flex-row items-center gap-3 p-4 active:opacity-70"
    >
      {icon}
      <View className="flex-1 gap-0.5">
        <Text className="text-base font-semibold text-foreground">{title}</Text>
        <Text className="text-xs text-muted-foreground">
          {open ? 'Toque para recolher' : 'Toque para configurar'}
        </Text>
      </View>
      {right}
      <ChevronDown
        size={18}
        color="#a3a3a3"
        style={open ? { transform: [{ rotate: '180deg' }] } : undefined}
      />
    </Pressable>
  );
}
