import * as React from 'react';
import { Text, type LayoutChangeEvent, View } from 'react-native';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import Animated, {
  runOnJS,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';

const THUMB_SIZE = 22;
const clamp01 = (v: number) => {
  'worklet';
  return v < 0 ? 0 : v > 1 ? 1 : v;
};

export type SliderProps = {
  min: number;
  max: number;
  step?: number;
  value: number;
  onChange: (value: number) => void;
  tone?: 'primary' | 'warning' | 'destructive';
  disabled?: boolean;
  lowerLimit?: number;
  upperLimit?: number;
  className?: string;
  trackClassName?: string;
  label?: string;
  trailing?: React.ReactNode;
};

const TONES = {
  primary: { fill: 'bg-primary', rail: 'bg-border', thumb: '#10b981' },
  warning: { fill: 'bg-warning', rail: 'bg-border', thumb: '#f59e0b' },
  destructive: { fill: 'bg-destructive', rail: 'bg-border', thumb: '#ef4444' },
} as const;

export function Slider({
  min,
  max,
  step = 0.01,
  value,
  onChange,
  tone = 'primary',
  disabled,
  lowerLimit,
  upperLimit,
  className,
  trackClassName,
  label,
  trailing,
}: SliderProps) {
  const width = useSharedValue(0);
  const progress = useSharedValue(0);
  const dragging = useSharedValue(0);
  const lastEmitted = useSharedValue(0);
  const target = useSharedValue(0);

  const ratio = React.useMemo(
    () => (max === min ? 0 : (value - min) / (max - min)),
    [value, min, max]
  );

  const bounds = React.useMemo(() => {
    const span = max - min;
    const lower =
      span > 0 ? Math.min(Math.max(lowerLimit ?? min, min), max) : min;
    const upper =
      span > 0 ? Math.min(Math.max(upperLimit ?? max, lower), max) : max;
    return {
      lower: span > 0 ? (lower - min) / span : 0,
      upper: span > 0 ? (upper - min) / span : 0,
    };
  }, [lowerLimit, upperLimit, min, max]);

  const clampBound = React.useCallback(
    (p: number) => {
      'worklet';
      return Math.min(Math.max(p, bounds.lower), bounds.upper);
    },
    [bounds]
  );

  React.useEffect(() => {
    const next = clampBound(ratio);
    target.value = next;
    if (dragging.value === 0 && Math.abs(next - progress.value) > 0.0005) {
      progress.value = withTiming(next, { duration: 120 });
      lastEmitted.value = next;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ratio]);

  const emit = React.useCallback(
    (p: number) => {
      const raw = min + p * (max - min);
      const stepped = Math.round(raw / step) * step;
      const decimals = step < 0.1 ? 2 : step < 1 ? 1 : 0;
      onChange(Number(stepped.toFixed(decimals)));
    },
    [max, min, onChange, step]
  );

  const onLayout = React.useCallback(
    (e: LayoutChangeEvent) => {
      width.value = e.nativeEvent.layout.width;
    },
    [width]
  );

  const pan = React.useMemo(
    () =>
      Gesture.Pan()
        .enabled(!disabled)
        .minDistance(0)
        .onBegin((event) => {
          'worklet';
          if (width.value <= 0) return;
          const x = clampBound(event.x / width.value);
          progress.value = withTiming(x, { duration: 90 });
          dragging.value = 1;
          lastEmitted.value = progress.value;
          runOnJS(emit)(progress.value);
        })
        .onUpdate((event) => {
          'worklet';
          if (width.value <= 0) return;
          const next = clampBound(event.x / width.value);
          progress.value = next;
          if (Math.abs(next - lastEmitted.value) > 0.001) {
            lastEmitted.value = next;
            runOnJS(emit)(next);
          }
        })
        .onFinalize(() => {
          'worklet';
          dragging.value = 0;
          progress.value = withTiming(target.value, { duration: 120 });
        }),
    [clampBound, disabled, emit, width, progress, dragging, lastEmitted, target]
  );

  const fillStyle = useAnimatedStyle(() => ({
    width: `${clamp01(progress.value) * 100}%`,
  }));

  const thumbStyle = useAnimatedStyle(() => ({
    left: `${clamp01(progress.value) * 100}%`,
    transform: [
      { translateX: -THUMB_SIZE / 2 },
      { scale: dragging.value ? 1.12 : 1 },
    ],
  }));

  const tone_ = TONES[tone];

  return (
    <View className={className}>
      {label || trailing ? (
        <View className="mb-2 flex-row items-center justify-between">
          {label ? (
            <Text className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
              {label}
            </Text>
          ) : null}
          {trailing}
        </View>
      ) : null}

      <GestureDetector gesture={pan}>
        <View
          className={`h-11 justify-center ${trackClassName ?? ''}`}
          onLayout={onLayout}
        >
          <View className={`h-1.5 w-full overflow-hidden rounded-full ${tone_.rail}`}>
            <Animated.View className={`h-full rounded-full ${tone_.fill}`} style={fillStyle} />
          </View>
          <Animated.View
            style={[
              {
                position: 'absolute',
                width: THUMB_SIZE,
                height: THUMB_SIZE,
                borderRadius: THUMB_SIZE / 2,
                backgroundColor: disabled ? '#525252' : '#e5e5e5',
                borderWidth: 3,
                borderColor: tone_.thumb,
              },
              thumbStyle,
            ]}
          />
        </View>
      </GestureDetector>
    </View>
  );
}
