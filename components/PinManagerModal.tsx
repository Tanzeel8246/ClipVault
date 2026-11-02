import React, { useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, Modal, StyleSheet, Alert } from 'react-native';
import CryptoJS from 'crypto-js';
import { ThemeColors } from '../theme/colors';

type PinMode = 'setup' | 'confirm' | 'disable' | 'change_old' | 'change_new' | 'change_confirm';

interface Props {
  visible: boolean;
  mode: PinMode;
  lang: 'en' | 'ur';
  T: ThemeColors;
  currentPinHash: string;
  onSuccess: (newHash: string | null) => void;
  onCancel: () => void;
}

export function PinManagerModal({ visible, mode, lang, T, currentPinHash, onSuccess, onCancel }: Props) {
  const [stepMode, setStepMode] = useState<PinMode>(mode);
  const [pinInput, setPinInput] = useState('');
  const [tempPin, setTempPin] = useState('');
  const [error, setError] = useState('');

  // Reset state when mode changes
  React.useEffect(() => {
    if (visible) {
      setStepMode(mode);
      setPinInput('');
      setTempPin('');
      setError('');
    }
  }, [visible, mode]);

  const hashPin = (pin: string) => CryptoJS.SHA256(pin).toString();

  const handleNext = () => {
    if (pinInput.length < 4) {
      setError(lang === 'en' ? 'PIN must be at least 4 digits' : 'پن کم از کم 4 ہندسوں کا ہونا چاہیے');
      return;
    }

    if (stepMode === 'setup' || stepMode === 'change_new') {
      setTempPin(pinInput);
      setStepMode(stepMode === 'setup' ? 'confirm' : 'change_confirm');
      setPinInput('');
      setError('');
    } else if (stepMode === 'confirm' || stepMode === 'change_confirm') {
      if (pinInput === tempPin) {
        onSuccess(hashPin(pinInput));
      } else {
        setError(lang === 'en' ? 'PINs do not match' : 'پن میچ نہیں ہو رہے');
        setPinInput('');
      }
    } else if (stepMode === 'disable' || stepMode === 'change_old') {
      if (hashPin(pinInput) === currentPinHash) {
        if (stepMode === 'disable') {
          onSuccess(null); // clears PIN
        } else {
          setStepMode('change_new');
          setPinInput('');
          setError('');
        }
      } else {
        setError(lang === 'en' ? 'Incorrect PIN' : 'غلط پن');
        setPinInput('');
      }
    }
  };

  const getTitle = () => {
    if (stepMode === 'setup' || stepMode === 'change_new') return lang === 'en' ? 'Enter New PIN' : 'نیا پن درج کریں';
    if (stepMode === 'confirm' || stepMode === 'change_confirm') return lang === 'en' ? 'Confirm PIN' : 'پن کی تصدیق کریں';
    if (stepMode === 'disable') return lang === 'en' ? 'Enter Current PIN to Disable' : 'غیر فعال کرنے کے لیے موجودہ پن درج کریں';
    if (stepMode === 'change_old') return lang === 'en' ? 'Enter Current PIN' : 'موجودہ پن درج کریں';
    return '';
  };

  return (
    <Modal visible={visible} animationType="slide" transparent>
      <View style={[styles.overlay, { backgroundColor: 'rgba(0,0,0,0.6)' }]}>
        <View style={[styles.dialog, { backgroundColor: T.surface, borderColor: T.border }]}>
          <Text style={[styles.title, { color: T.text }]}>{getTitle()}</Text>
          
          <TextInput
            style={[styles.input, { color: T.text, borderColor: T.border, backgroundColor: T.bg }]}
            value={pinInput}
            onChangeText={(t) => { setPinInput(t.replace(/[^0-9]/g, '').slice(0, 8)); setError(''); }}
            keyboardType="number-pad"
            secureTextEntry
            autoFocus
            placeholder="****"
            placeholderTextColor={T.muted}
          />
          
          {!!error && <Text style={[styles.error, { color: T.red }]}>{error}</Text>}

          <View style={styles.actions}>
            <TouchableOpacity style={styles.btn} onPress={onCancel}>
              <Text style={[styles.btnText, { color: T.muted }]}>{lang === 'en' ? 'Cancel' : 'منسوخ'}</Text>
            </TouchableOpacity>
            <TouchableOpacity style={[styles.btn, { backgroundColor: T.accent }]} onPress={handleNext}>
              <Text style={[styles.btnText, { color: T.bg, fontWeight: '700' }]}>{lang === 'en' ? 'Next' : 'آگے'}</Text>
            </TouchableOpacity>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  overlay: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  dialog: { width: '85%', padding: 24, borderRadius: 16, borderWidth: 1 },
  title: { fontSize: 18, fontWeight: '600', marginBottom: 16, textAlign: 'center' },
  input: { borderWidth: 1, borderRadius: 8, padding: 12, fontSize: 24, textAlign: 'center', letterSpacing: 8 },
  error: { marginTop: 8, fontSize: 13, textAlign: 'center' },
  actions: { flexDirection: 'row', justifyContent: 'flex-end', marginTop: 24, gap: 12 },
  btn: { paddingHorizontal: 16, paddingVertical: 10, borderRadius: 8 },
  btnText: { fontSize: 15 },
});
