import * as React from 'react';
import { Text, View, type TextProps, type ViewProps } from 'react-native';
import { cn } from '../../lib/utils';

export function Label({ className, ...props }: TextProps) {
  return (
    <Text
      className={cn(
        'text-[11px] font-semibold uppercase tracking-wide text-muted-foreground',
        className
      )}
      {...props}
    />
  );
}

type FieldProps = ViewProps & {
  label?: string;
  hint?: string;
  className?: string;
  children: React.ReactNode;
};

export function Field({ label, hint, className, children, ...props }: FieldProps) {
  return (
    <View className={cn('flex-1 gap-1.5', className)} {...props}>
      {label ? <Label>{label}</Label> : null}
      {children}
      {hint ? <Text className="text-[10px] text-muted-foreground">{hint}</Text> : null}
    </View>
  );
}
