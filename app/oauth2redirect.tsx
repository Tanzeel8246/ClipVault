import React, { useEffect } from 'react';
import { View } from 'react-native';
import { useRouter } from 'expo-router';

export default function BlankRedirect() {
  const router = useRouter();
  useEffect(() => {
    router.replace('/');
  }, [router]);
  return <View style={{ flex: 1, backgroundColor: '#080810' }} />;
}
