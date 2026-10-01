import * as React from 'react';
import { Text, View, type ViewProps } from 'react-native';
import { cn } from '../../lib/utils';

const containerVariants = {
  default: 'bg-primary/15',
  secondary: 'bg-secondary',
  outline: 'border border-border',
  success: 'bg-primary/15',
  destructive: 'bg-destructive/15',
  warning: 'bg-warning/15',
  muted: 'bg-secondary',
} as const;

const dotVariants = {
  default: 'bg-primary',
  secondary: 'bg-muted-foreground',
  outline: 'bg-muted-foreground',
  success: 'bg-primary',
  destructive: 'bg-destructive',
  warning: 'bg-warning',
  muted: 'bg-muted-foreground',
} as const;

const labelVariants = {
  default: 'text-primary',
  secondary: 'text-muted-foreground',
  outline: 'text-foreground',
  success: 'text-primary',
  destructive: 'text-destructive',
  warning: 'text-warning',
  muted: 'text-muted-foreground',
} as const;

type Variant = keyof typeof containerVariants;

type BadgeProps = ViewProps & {
  label: string;
  variant?: Variant;
  dot?: boolean;
  className?: string;
  labelClassName?: string;
  uppercase?: boolean;
};

export function Badge({
  label,
  variant = 'default',
  dot,
  className,
  labelClassName,
  uppercase = true,
  ...props
}: BadgeProps) {
  return (
    <View
      className={cn(
        'flex-row items-center gap-1.5 self-start rounded-full px-2 py-1',
        containerVariants[variant],
        className
      )}
      {...props}
    >
      {dot ? (
        <View className={cn('h-1.5 w-1.5 rounded-full', dotVariants[variant])} />
      ) : null}
      <Text
        className={cn(
          'text-[10px] font-semibold',
          uppercase && 'uppercase',
          labelVariants[variant],
          labelClassName
        )}
      >
        {label}
      </Text>
    </View>
  );
}
