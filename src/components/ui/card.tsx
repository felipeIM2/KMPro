import * as React from 'react';
import { Text, View, type TextProps, type ViewProps } from 'react-native';
import { cn } from '../../lib/utils';

export function Card({ className, ...props }: ViewProps) {
  return (
    <View
      className={cn('rounded-xl border border-border bg-card', className)}
      {...props}
    />
  );
}

export function CardHeader({ className, ...props }: ViewProps) {
  return <View className={cn('gap-1.5 p-4', className)} {...props} />;
}

export function CardTitle({ className, ...props }: TextProps) {
  return (
    <Text
      className={cn('text-base font-semibold text-foreground', className)}
      {...props}
    />
  );
}

export function CardDescription({ className, ...props }: TextProps) {
  return (
    <Text
      className={cn('text-xs text-muted-foreground', className)}
      {...props}
    />
  );
}

export function CardContent({ className, ...props }: ViewProps) {
  return <View className={cn('p-4 pt-0', className)} {...props} />;
}

export function CardFooter({ className, ...props }: ViewProps) {
  return (
    <View className={cn('flex-row items-center gap-2 p-4 pt-0', className)} {...props} />
  );
}
