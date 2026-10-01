import * as React from 'react';
import {
  ActivityIndicator,
  Pressable,
  Text,
  type PressableProps,

} from 'react-native';
import { cn } from '../../lib/utils';

const containerVariants = {
  default: 'bg-primary active:bg-emerald-600',
  secondary: 'bg-secondary active:bg-accent',
  outline: 'border border-border bg-transparent active:bg-secondary',
  ghost: 'bg-transparent active:bg-secondary',
  destructive: 'bg-destructive active:bg-red-700',
} as const;

const labelVariants = {
  default: 'text-primary-foreground',
  secondary: 'text-secondary-foreground',
  outline: 'text-foreground',
  ghost: 'text-muted-foreground',
  destructive: 'text-white',
} as const;

const containerSizes = {
  sm: 'h-8 rounded-md px-3',
  default: 'h-11 rounded-lg px-4',
  lg: 'h-14 rounded-xl px-6',
  icon: 'h-11 w-11 rounded-lg',
} as const;

const labelSizes = {
  sm: 'text-xs',
  default: 'text-sm',
  lg: 'text-base',
  icon: 'text-sm',
} as const;

type Variant = keyof typeof containerVariants;
type Size = keyof typeof containerSizes;

type ButtonProps = Omit<PressableProps, 'children'> & {
  variant?: Variant;
  size?: Size;
  label?: string;
  labelClassName?: string;
  loading?: boolean;
  children?: React.ReactNode;
};

export function Button({
  className,
  variant = 'default',
  size = 'default',
  label,
  labelClassName,
  loading,
  children,
  disabled,
  ...props
}: ButtonProps) {
  return (
    <Pressable
      accessibilityRole="button"
      disabled={disabled || loading}
      className={cn(
        'flex-row items-center justify-center gap-2',
        containerVariants[variant],
        containerSizes[size],
        (disabled || loading) && 'opacity-50',
        className
      )}
      {...props}
    >
      {loading ? (
        <ActivityIndicator
          size="small"
          color={variant === 'default' ? '#022c22' : '#fafafa'}
        />
      ) : null}
      {label ? (
        <Text
          className={cn(
            'font-semibold',
            labelVariants[variant],
            labelSizes[size],
            labelClassName
          )}
        >
          {label}
        </Text>
      ) : null}
      {children}
    </Pressable>
  );
}

type IconButtonProps = Omit<PressableProps, 'children'> & {
  variant?: Variant;
  size?: Size;
  children?: React.ReactNode;
};

export function IconButton({
  className,
  variant = 'secondary',
  size = 'default',
  children,
  ...props
}: IconButtonProps) {
  return (
    <Pressable
      accessibilityRole="button"
      className={cn(
        'items-center justify-center',
        containerVariants[variant],
        containerSizes[size],
        className
      )}
      {...props}
    >
      {children}
    </Pressable>
  );
}

type TextButtonProps = Omit<PressableProps, 'children'> & {
  className?: string;
  children?: React.ReactNode;
};

export function TextButton({ className, children, ...props }: TextButtonProps) {
  return (
    <Pressable
      accessibilityRole="button"
      className={cn('active:opacity-60', className)}
      {...props}
    >
      <Text className="text-sm font-medium text-primary">{children}</Text>
    </Pressable>
  );
}
