import * as React from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { router } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ArrowLeft, Save } from 'lucide-react-native';
import { Button } from '@/components/ui/button';
import { Field, Label } from '@/components/ui/label';
import { Input } from '@/components/ui/input';
import { Separator } from '@/components/ui/separator';
import { Section } from '@/components/ui/screen';
import {
  FUEL_TYPES,
  fuelCostPerKm,
  fixedCostPerKm,
  totalCostPerKm,
  type FuelId,
} from '@/constants';
import { useApp } from '@/context/app-context';
import { formatBRL, formatInt } from '@/lib/format';
import { cn } from '@/lib/utils';

function toText(value: number) {
  if (!Number.isFinite(value)) return '';
  return String(Number(value.toFixed(2)));
}

function parse(text: string) {
  const cleaned = text.replace(/\s/g, '').replace(',', '.');
  if (!cleaned) return null;
  const parsed = Number(cleaned);
  return Number.isFinite(parsed) ? parsed : null;
}

type CostInputProps = {
  label: string;
  value: number;
  onChange: (value: number) => void;
  prefix?: string;
  suffix?: string;
  step?: number;
};

function CostInput({ label, value, onChange, prefix, suffix }: CostInputProps) {
  const [text, setText] = React.useState(() => toText(value));
  const [lastValue, setLastValue] = React.useState(value);

  if (value !== lastValue) {
    setLastValue(value);
    setText(toText(value));
  }

  return (
    <Field label={label}>
      <Input
        value={text}
        onChangeText={setText}
        onBlur={() => {
          const parsed = parse(text);
          onChange(parsed === null || parsed < 0 ? 0 : parsed);
        }}
        prefix={prefix}
        suffix={suffix}
        keyboardType="decimal-pad"
        placeholder="0"
      />
    </Field>
  );
}

export default function Custos() {
  const { costSettings, setCostSettings } = useApp();
  const insets = useSafeAreaInsets();

  const selectDays = (day: number) => patchSettings({ daysWorked: day });
  const patchSettings = (patch: Partial<typeof costSettings>) =>
    setCostSettings((prev) => ({ ...prev, ...patch }));

  const patchCost = (key: keyof typeof costSettings.costs, value: number) =>
    setCostSettings((prev) => ({
      ...prev,
      costs: { ...prev.costs, [key]: value },
    }));

  const patchFuel = (key: 'autonomy' | 'price', value: number) =>
    setCostSettings((prev) => ({
      ...prev,
      fuel: {
        ...prev.fuel,
        [prev.selectedFuel]: { ...prev.fuel[prev.selectedFuel], [key]: value },
      },
    }));

  const fuel = costSettings.fuel[costSettings.selectedFuel];

  return (
    <View className="flex-1 bg-background">
      <ScrollView
        className="flex-1"
        contentContainerStyle={{
          paddingTop: insets.top + 12,
          paddingBottom: 140,
          paddingHorizontal: 16,
        }}
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
      >
        <View className="mb-4 flex-row items-center gap-3">
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Voltar"
            onPress={() => router.back()}
            className="h-10 w-10 items-center justify-center rounded-lg border border-border bg-card active:opacity-70"
          >
            <ArrowLeft size={18} color="#fafafa" />
          </Pressable>
          <View className="flex-1 gap-0.5">
            <Text className="text-lg font-bold text-foreground">Custos</Text>
            <Text className="text-[10px] uppercase tracking-wider text-muted-foreground">
              Informações de custo
            </Text>
          </View>
        </View>

        <View className="gap-5">
          <Section
            title="Dias trabalhados"
            hint={`${costSettings.daysWorked} de 7 dias`}
          >
            <View className="flex-row gap-1.5">
              {[1, 2, 3, 4, 5, 6, 7].map((day) => {
                const active = costSettings.daysWorked === day;
                return (
                  <Pressable
                    key={day}
                    accessibilityRole="radio"
                    accessibilityState={{ selected: active }}
                    accessibilityLabel={`${day} dia${day === 1 ? '' : 's'} trabalhado${day === 1 ? '' : 's'}`}
                    onPress={() => selectDays(day)}
                    className={cn(
                      'h-12 flex-1 items-center justify-center rounded-lg border active:opacity-70',
                      active
                        ? 'border-primary bg-primary'
                        : 'border-border bg-card'
                    )}
                  >
                    <Text
                      className={cn(
                        'text-sm font-bold',
                        active ? 'text-primary-foreground' : 'text-muted-foreground'
                      )}
                    >
                      {day}
                    </Text>
                  </Pressable>
                );
              })}
            </View>

            <View className="flex-row gap-2.5">
              <CostInput
                label="Horas dirigidas/dia"
                value={costSettings.hoursPerDay}
                onChange={(value) => patchSettings({ hoursPerDay: value })}
                suffix="h"
              />
              <CostInput
                label="Km rodado/dia"
                value={costSettings.kmPerDay}
                onChange={(value) => patchSettings({ kmPerDay: value })}
                suffix="km"
              />
            </View>

            <CostInput
              label="Meta semanal"
              value={costSettings.weeklyGoal}
              onChange={(value) => patchSettings({ weeklyGoal: value })}
              prefix="R$"
            />
          </Section>

          <Separator />

          <Section title="Custos" hint="Valores mensais, exceto impostos">
            <View className="gap-2.5">
              <CostInput
                label="Financiamento mensal"
                value={costSettings.costs.financing}
                onChange={(value) => patchCost('financing', value)}
                prefix="R$"
              />
              <CostInput
                label="Manutenção mensal"
                value={costSettings.costs.maintenance}
                onChange={(value) => patchCost('maintenance', value)}
                prefix="R$"
              />
              <CostInput
                label="Seguro mensal"
                value={costSettings.costs.insurance}
                onChange={(value) => patchCost('insurance', value)}
                prefix="R$"
              />
              <CostInput
                label="Outros custos mensais"
                value={costSettings.costs.other}
                onChange={(value) => patchCost('other', value)}
                prefix="R$"
              />
              <CostInput
                label="Impostos e taxas anuais"
                value={costSettings.costs.taxesAnnual}
                onChange={(value) => patchCost('taxesAnnual', value)}
                prefix="R$"
              />
            </View>
          </Section>

          <Separator />

          <Section title="Combustível" hint="Toque para trocar o tipo">
            <View className="flex-row gap-1.5">
              {FUEL_TYPES.map((item) => {
                const active = costSettings.selectedFuel === item.id;
                return (
                  <Pressable
                    key={item.id}
                    onPress={() => patchSettings({ selectedFuel: item.id as FuelId })}
                    className={cn(
                      'flex-1 items-center gap-1 rounded-lg border py-3 active:opacity-70',
                      active
                        ? 'border-primary bg-primary/10'
                        : 'border-border bg-card'
                    )}
                  >
                    <Text
                      className={cn(
                        'text-[10px] font-bold uppercase',
                        active ? 'text-primary' : 'text-muted-foreground'
                      )}
                    >
                      {item.short}
                    </Text>
                    <Text
                      className={cn(
                        'text-[9px]',
                        active ? 'text-primary/70' : 'text-muted-foreground/60'
                      )}
                    >
                      {item.label}
                    </Text>
                  </Pressable>
                );
              })}
            </View>

            <View className="flex-row gap-2.5">
              <CostInput
                label="Autonomia"
                value={fuel.autonomy}
                onChange={(value) => patchFuel('autonomy', value || 1)}
                suffix={costSettings.selectedFuel === 'eletrico' ? 'kWh' : 'km/l'}
              />
              <CostInput
                label="Preço do combustível"
                value={fuel.price}
                onChange={(value) => patchFuel('price', value)}
                prefix="R$"
              />
            </View>
          </Section>
        </View>
      </ScrollView>

      <View
        className="absolute inset-x-0 bottom-0 border-t border-border bg-card px-4 pt-3"
        style={{ paddingBottom: insets.bottom + 12 }}
      >
        <View className="flex-row items-center justify-between gap-3">
          <View className="gap-0.5">
            <Label>Custo por km</Label>
            <Text className="text-xl font-bold text-primary">
              {formatBRL(totalCostPerKm(costSettings))}
            </Text>
            <Text className="text-[10px] text-muted-foreground">
              comb. {formatBRL(fuelCostPerKm(costSettings))} + custo{' '}
              {formatBRL(fixedCostPerKm(costSettings))} ·{' '}
              {formatInt(costSettings.daysWorked)} dias
            </Text>
          </View>
          <Button
            variant="default"
            onPress={() => router.back()}
            className="flex-row gap-2"
          >
            <Save size={16} color="#022c22" />
            <Text className="text-sm font-bold text-primary-foreground">
              Salvar
            </Text>
          </Button>
        </View>
      </View>
    </View>
  );
}
