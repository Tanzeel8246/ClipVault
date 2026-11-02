import React, { useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, Modal, StyleSheet } from 'react-native';
import { ThemeColors } from '../theme/colors';

type SyncMode = 'setup' | 'disable' | 'change';

interface Props {
  visible: boolean;
  mode: SyncMode;
  lang: 'en' | 'ur';
  T: ThemeColors;
  currentPassword?: string;
  onSuccess: (newPassword: string | null) => void;
  onCancel: () => void;
}

export function SyncPasswordModal({ visible, mode, lang, T, currentPassword, onSuccess, onCancel }: Props) {
  const [stepMode, setStepMode] = useState<SyncMode | 'confirm'>(mode);
  const [passwordInput, setPasswordInput] = useState('');
  const [tempPassword, setTempPassword] = useState('');
  const [error, setError] = useState('');

  React.useEffect(() => {
    if (visible) {
      setStepMode(mode);
      setPasswordInput('');
      setTempPassword('');
      setError('');
    }
  }, [visible, mode]);

  const handleNext = () => {
    if (passwordInput.length < 6) {
      setError(lang === 'en' ? 'Password must be at least 6 characters' : 'پاس ورڈ کم از کم 6 حروف کا ہونا چاہیے');
      return;
    }

    if (stepMode === 'setup' || stepMode === 'change') {
      setTempPassword(passwordInput);
      setStepMode('confirm');
      setPasswordInput('');
      setError('');
    } else if (stepMode === 'confirm') {
      if (passwordInput === tempPassword) {
        onSuccess(passwordInput);
      } else {
        setError(lang === 'en' ? 'Passwords do not match' : 'پاس ورڈ میچ نہیں ہو رہے');
        setPasswordInput('');
      }
    } else if (stepMode === 'disable') {
      if (passwordInput === currentPassword) {
        onSuccess(null);
      } else {
        setError(lang === 'en' ? 'Incorrect Password' : 'غلط پاس ورڈ');
        setPasswordInput('');
      }
    }
  };

  const getTitle = () => {
    if (stepMode === 'setup' || stepMode === 'change') return lang === 'en' ? 'Set Sync Password' : 'سنک پاس ورڈ درج کریں';
    if (stepMode === 'confirm') return lang === 'en' ? 'Confirm Sync Password' : 'پاس ورڈ کی تصدیق کریں';
    if (stepMode === 'disable') return lang === 'en' ? 'Enter Current Password to Disable' : 'غیر فعال کرنے کے لیے موجودہ پاس ورڈ درج کریں';
    return '';
  };

  return (
    <Modal visible={visible} animationType="slide" transparent>
      <View style={[styles.overlay, { backgroundColor: 'rgba(0,0,0,0.6)' }]}>
        <View style={[styles.dialog, { backgroundColor: T.surface, borderColor: T.border }]}>
          <Text style={[styles.title, { color: T.text }]}>{getTitle()}</Text>
          
          <TextInput
            style={[styles.input, { color: T.text, borderColor: T.border, backgroundColor: T.bg }]}
            value={passwordInput}
            onChangeText={(t) => { setPasswordInput(t); setError(''); }}
            secureTextEntry
            autoFocus
            placeholder="******"
            placeholderTextColor={T.muted}
          />
          
          {!!error && <Text style={[styles.error, { color: T.red }]}>{error}</Text>}

          <View style={styles.actions}>
            <TouchableOpacity style={styles.btn} onPress={onCancel}>
              <Text style={[styles.btnText, { color: T.muted }]}>{lang === 'en' ? 'Cancel' : 'منسوخ'}</Text>
            </TouchableOpacity>
            <TouchableOpacity style={[styles.btn, { backgroundColor: T.accent }]} onPress={handleNext}>
              <Text style={[styles.btnText, { color: '#fff' }]}>{lang === 'en' ? 'Confirm' : 'تصدیق'}</Text>
            </TouchableOpacity>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  overlay: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 20 },
  dialog: { width: '100%', maxWidth: 340, borderRadius: 16, padding: 20, borderWidth: 1 },
  title: { fontSize: 18, fontWeight: '700', marginBottom: 16, textAlign: 'center' },
  input: { borderWidth: 1, borderRadius: 8, padding: 12, fontSize: 16, textAlign: 'center', marginBottom: 12 },
  error: { fontSize: 13, textAlign: 'center', marginBottom: 12 },
  actions: { flexDirection: 'row', justifyContent: 'flex-end', gap: 10 },
  btn: { paddingHorizontal: 20, paddingVertical: 10, borderRadius: 8 },
  btnText: { fontSize: 15, fontWeight: '600' },
});
