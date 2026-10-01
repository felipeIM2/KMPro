import * as React from 'react';
import { View, type ViewProps } from 'react-native';
import { cn } from '../../lib/utils';

type SeparatorProps = ViewProps & {
  orientation?: 'horizontal' | 'vertical';
  className?: string;
};

export function Separator({
  orientation = 'horizontal',
  className,
  ...props
}: SeparatorProps) {
  return (
    <View
      accessibilityRole="none"
      className={cn(
        'bg-foreground/15',
        orientation === 'horizontal' ? 'h-px w-full' : 'h-full w-px',
        className
      )}
      {...props}
    />
  );
}
