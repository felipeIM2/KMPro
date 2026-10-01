import * as React from 'react';
import { type LayoutChangeEvent, Text, View } from 'react-native';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import Animated, {
  type SharedValue,
  makeMutable,
  runOnJS,
  useAnimatedStyle,
  useSharedValue,
  withSpring,
} from 'react-native-reanimated';
import { METRICS, type MetricId } from '@/constants';

const GAP = 8;
const SPRING = { damping: 20, stiffness: 260, mass: 0.6 } as const;

type MetricOrderProps = {
  order: MetricId[];
  onReorder: (order: MetricId[]) => void;
};

type CardSlot = {
  x: SharedValue<number>;
  lift: SharedValue<number>;
};

export function MetricOrder({ order, onReorder }: MetricOrderProps) {
  const pitch = useSharedValue(0);
  const orderSV = useSharedValue<MetricId[]>(order);
  const layoutSV = useSharedValue<MetricId[]>(order);
  const startSlot = useSharedValue(0);

  const [slots] = React.useState<Record<string, CardSlot>>(() => {
    const map: Record<string, CardSlot> = {};
    for (const metric of METRICS) {
      map[metric.id] = { x: makeMutable(0), lift: makeMutable(0) };
    }
    return map;
  });

  const commit = React.useCallback(
    (next: MetricId[]) => onReorder(next),
    [onReorder]
  );

  // After the new order is rendered the layout slots already match the visual
  // order, so every offset can be dropped without a visible jump.
  React.useLayoutEffect(() => {
    for (const key in slots) {
      slots[key].x.value = 0;
    }
  }, [order, slots]);

  const onLayout = React.useCallback(
    (event: LayoutChangeEvent) => {
      const width = event.nativeEvent.layout.width;
      const cardWidth = (width - GAP * (order.length - 1)) / order.length;
      pitch.value = cardWidth + GAP;
    },
    [order.length, pitch]
  );

  const makeGesture = React.useCallback(
    (id: MetricId) => {
      const slot = slots[id];

      return (
        Gesture.Pan()
          .activeOffsetX([-4, 4])
          .failOffsetY([-14, 14])
          .onBegin(() => {
            'worklet';
            startSlot.value = orderSV.value.indexOf(id);
            slot.lift.value = withSpring(1, SPRING);
          })
          .onUpdate((event) => {
            'worklet';
            const step = pitch.value;
            if (step <= 0) return;

            const current = orderSV.value;
            const index = current.indexOf(id);
            const last = current.length - 1;

            slot.x.value = event.translationX;

            const target = Math.max(
              0,
              Math.min(last, startSlot.value + Math.round(event.translationX / step))
            );

            if (target !== index) {
              const displaced = current[target];
              const next = [...current];
              next.splice(target, 0, next.splice(index, 1)[0]);
              orderSV.value = next;

              const layoutIndex = layoutSV.value.indexOf(displaced);
              slots[displaced].x.value = withSpring(
                (index - layoutIndex) * step,
                SPRING
              );
            }
          })
          .onFinalize(() => {
            'worklet';
            slot.lift.value = withSpring(0, SPRING);
            runOnJS(commit)([...orderSV.value]);
          })
      );
    },
    [commit, layoutSV, orderSV, pitch, slots, startSlot]
  );

  return (
    <View className="flex-row" style={{ gap: GAP }} onLayout={onLayout}>
      {order.map((id) => (
        <OrderCard
          key={id}
          id={id}
          label={METRICS.find((m) => m.id === id)?.short ?? id}
          slot={slots[id]}
          makeGesture={makeGesture}
        />
      ))}
    </View>
  );
}

type OrderCardProps = {
  id: MetricId;
  label: string;
  slot: CardSlot;
  makeGesture: (id: MetricId) => ReturnType<typeof Gesture.Pan>;
};

function OrderCard({ id, label, slot, makeGesture }: OrderCardProps) {
  const gesture = React.useMemo(() => makeGesture(id), [makeGesture, id]);

  const style = useAnimatedStyle(() => ({
    transform: [
      { translateX: slot.x.value },
      { scale: 1 + slot.lift.value * 0.07 },
    ],
    zIndex: slot.lift.value > 0.5 ? 20 : 1,
  }));

  return (
    <GestureDetector gesture={gesture}>
      <Animated.View
        style={[
          style,
          {
            shadowColor: '#10b981',
            shadowOffset: { width: 0, height: 6 },
            shadowOpacity: 0.4,
            shadowRadius: 12,
          },
        ]}
        className="flex-1"
      >
        <Animated.View className="h-[68px] items-center justify-center gap-0.5 rounded-lg border border-border bg-card">
          <Text
            numberOfLines={1}
            className="px-1 text-center text-[9px] font-bold uppercase text-foreground"
          >
            {label}
          </Text>
          <Text className="text-[8px] text-muted-foreground">arraste</Text>
        </Animated.View>
      </Animated.View>
    </GestureDetector>
  );
}
