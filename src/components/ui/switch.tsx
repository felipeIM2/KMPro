import * as React from 'react';
import { Switch as RNSwitch, type SwitchProps } from 'react-native';
import { cn } from '../../lib/utils';

type SwitchPropsRN = Omit<SwitchProps, 'className'> & { className?: string };

export function Switch({ className, ...props }: SwitchPropsRN) {
  return (
    <RNSwitch
      trackColor={{ false: '#262626', true: '#065f46' }}
      thumbColor={props.value ? '#10b981' : '#737373'}
      ios_backgroundColor="#262626"
      className={cn(className)}
      {...props}
    />
  );
}
