import { Tabs } from 'expo-router';
import { History, Radar, Settings, Target, Wallet } from 'lucide-react-native';
import { Platform } from 'react-native';

export default function TabsLayout() {
  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: '#10b981',
        tabBarInactiveTintColor: '#525252',
        tabBarStyle: {
          backgroundColor: '#0a0a0a',
          borderTopColor: '#1f1f1f',
          borderTopWidth: 1,
        },
        tabBarLabelStyle: {
          fontSize: 10,
          fontWeight: '600',
          marginTop: Platform.OS === 'ios' ? 2 : 0,
        },
        sceneStyle: { backgroundColor: '#000000' },
      }}
    >
      <Tabs.Screen
        name="ganhos/ganhos"
        options={{
          title: 'Ganhos',
          tabBarIcon: ({ color }) => <Wallet size={22} color={color} />,
        }}
      />
      <Tabs.Screen
        name="historico/historico"
        options={{
          title: 'Histórico',
          tabBarIcon: ({ color }) => <History size={22} color={color} />,
        }}
      />
      <Tabs.Screen
        name="copiloto/copiloto"
        options={{
          title: 'Copiloto',
          tabBarIcon: ({ color }) => <Radar size={22} color={color} />,
        }}
      />
      <Tabs.Screen
        name="metas/metas"
        options={{
          title: 'Metas',
          tabBarIcon: ({ color }) => <Target size={22} color={color} />,
        }}
      />
      <Tabs.Screen
        name="ajustes/ajustes"
        options={{
          title: 'Ajustes',
          tabBarIcon: ({ color }) => <Settings size={22} color={color} />,
        }}
      />
    </Tabs>
  );
}
