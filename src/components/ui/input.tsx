import * as React from 'react';
import { Text, TextInput, View, type TextInputProps } from 'react-native';
import { cn } from '../../lib/utils';

type InputProps = Omit<TextInputProps, 'className'> & {
  className?: string;
  prefix?: string;
  suffix?: string;
  invalid?: boolean;
};

export function Input({
  className,
  prefix,
  suffix,
  invalid,
  ...props
}: InputProps) {
  return (
    <View
      className={cn(
        'h-12 flex-row items-center rounded-lg border border-input bg-secondary px-3',
        invalid && 'border-destructive'
      )}
    >
      {prefix ? (
        <Text className="mr-1 text-sm text-muted-foreground">{prefix}</Text>
      ) : null}
      <TextInput
        placeholderTextColor="#525252"
        selectionColor="#10b981"
        className={cn(
          'flex-1 text-base font-medium text-foreground',
          className
        )}
        {...props}
      />
      {suffix ? (
        <Text className="text-sm text-muted-foreground">{suffix}</Text>
      ) : null}
    </View>
  );
}
