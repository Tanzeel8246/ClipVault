import { useState, useEffect } from 'react';
import CryptoJS from 'crypto-js';
import { Lock, Search, Copy, Check, LogOut, RefreshCw, AlertCircle } from 'lucide-react';
import './index.css';

const CLIENT_ID = '591284919475-m2nalmiep2ubg7dd4rrjusjgoo7rcmce.apps.googleusercontent.com';
const SCOPE = 'https://www.googleapis.com/auth/drive.appdata';

interface ClipItem {
  id: string;
  text: string;
  category: string;
  timestamp: string;
  isFavorite: boolean;
  isPinned: boolean;
  isSensitive?: boolean;
}

export default function App() {
  const [accessToken, setAccessToken] = useState<string>('');
  const [syncPassword, setSyncPassword] = useState<string>('');
  const [clips, setClips] = useState<ClipItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [step, setStep] = useState<'LOGIN' | 'PASSWORD' | 'CLIPS'>('LOGIN');
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [search, setSearch] = useState('');

  // 1. Initialize Token Client
  const handleLogin = () => {
    const client = google.accounts.oauth2.initTokenClient({
      client_id: CLIENT_ID,
      scope: SCOPE,
      callback: (tokenResponse) => {
        if (tokenResponse && tokenResponse.access_token) {
          setAccessToken(tokenResponse.access_token);
          setStep('PASSWORD');
        }
      },
    });
    client.requestAccessToken();
  };

  // 2. Fetch from Drive
  const fetchBackup = async () => {
    if (!syncPassword) {
      setError('Please enter your Sync Password');
      return;
    }
    setLoading(true);
    setError('');
    
    try {
      // Find file
      const searchRes = await fetch(`https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=name='clipvault.json'`, {
        headers: { Authorization: `Bearer ${accessToken}` },
      });
      const searchData = await searchRes.json();
      const files = searchData.files;
      if (!files || files.length === 0) {
        throw new Error('No backup found in your Google Drive AppData folder.');
      }
      
      const fileId = files[0].id;
      
      // Download content
      const dlRes = await fetch(`https://www.googleapis.com/drive/v3/files/${fileId}?alt=media`, {
        headers: { Authorization: `Bearer ${accessToken}` },
      });
      let payload = await dlRes.text();
      
      // Decrypt
      if (payload.startsWith('U2FsdGVkX1')) {
        try {
          const bytes = CryptoJS.AES.decrypt(payload, syncPassword);
          payload = bytes.toString(CryptoJS.enc.Utf8);
          if (!payload) throw new Error('Incorrect Sync Password.');
        } catch (e) {
          throw new Error('Incorrect Sync Password or corrupt data.');
        }
      } else {
        // Unencrypted JSON
        if (syncPassword) console.warn('Provided password but backup is unencrypted.');
      }
      
      const backup = JSON.parse(payload);
      if (backup.clips) {
        // Sort by newest
        const sorted = backup.clips.sort((a: ClipItem, b: ClipItem) => 
          new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime()
        );
        setClips(sorted);
        setStep('CLIPS');
      } else {
        throw new Error('Invalid backup format.');
      }
    } catch (err: any) {
      setError(err.message || 'Failed to sync clips.');
    } finally {
      setLoading(false);
    }
  };

  const handleCopy = (id: string, text: string) => {
    navigator.clipboard.writeText(text);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  const filteredClips = clips.filter(c => 
    c.text.toLowerCase().includes(search.toLowerCase()) || 
    c.category.toLowerCase().includes(search.toLowerCase())
  );

  return (
    <div className="min-h-screen bg-[#0f0f13] text-gray-100 font-sans p-6 md:p-12">
      <div className="max-w-4xl mx-auto">
        <header className="flex justify-between items-center mb-10">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-blue-500 rounded-xl flex items-center justify-center">
              <Lock size={20} className="text-white" />
            </div>
            <div>
              <h1 className="text-xl font-bold text-white tracking-wide">ClipVault</h1>
              <p className="text-xs text-blue-400 font-semibold tracking-wider uppercase">Desktop Companion</p>
            </div>
          </div>
          {step === 'CLIPS' && (
            <button onClick={() => { setStep('LOGIN'); setAccessToken(''); setClips([]); }} className="flex items-center gap-2 text-sm text-gray-400 hover:text-white transition-colors">
              <LogOut size={16} /> Disconnect
            </button>
          )}
        </header>

        {step === 'LOGIN' && (
          <div className="max-w-md mx-auto mt-20 text-center">
            <h2 className="text-2xl font-semibold mb-4">Sync Your Clips</h2>
            <p className="text-gray-400 mb-8">Securely access your zero-knowledge encrypted clipboard history from your PC. Data is pulled directly from your Google Drive AppData folder.</p>
            <button 
              onClick={handleLogin}
              className="bg-blue-600 hover:bg-blue-500 text-white font-medium py-3 px-8 rounded-lg transition-colors w-full flex items-center justify-center gap-2"
            >
              Sign in with Google
            </button>
          </div>
        )}

        {step === 'PASSWORD' && (
          <div className="max-w-md mx-auto mt-20 text-center">
            <h2 className="text-2xl font-semibold mb-4">Unlock Backup</h2>
            <p className="text-gray-400 mb-6">Enter the Sync Password you configured in the ClipVault mobile app to decrypt your clips locally.</p>
            
            <div className="flex flex-col gap-4">
              <input 
                type="password" 
                placeholder="Sync Password"
                value={syncPassword}
                onChange={e => setSyncPassword(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && fetchBackup()}
                className="w-full bg-[#1a1a24] border border-[#2a2a36] rounded-lg px-4 py-3 text-white focus:outline-none focus:border-blue-500"
              />
              <button 
                onClick={fetchBackup}
                disabled={loading}
                className="bg-blue-600 hover:bg-blue-500 text-white font-medium py-3 px-8 rounded-lg transition-colors w-full flex items-center justify-center gap-2 disabled:opacity-50"
              >
                {loading ? <RefreshCw className="animate-spin" size={18} /> : 'Decrypt & View Clips'}
              </button>
            </div>

            {error && (
              <div className="mt-6 p-4 bg-red-900/30 border border-red-500/50 rounded-lg flex items-center gap-3 text-red-400 text-sm text-left">
                <AlertCircle size={18} /> {error}
              </div>
            )}
          </div>
        )}

        {step === 'CLIPS' && (
          <div>
            <div className="flex flex-col md:flex-row gap-4 mb-8">
              <div className="relative flex-1">
                <Search size={18} className="absolute left-4 top-1/2 -translate-y-1/2 text-gray-500" />
                <input 
                  type="text" 
                  placeholder="Search clips..." 
                  value={search}
                  onChange={e => setSearch(e.target.value)}
                  className="w-full bg-[#1a1a24] border border-[#2a2a36] rounded-lg pl-11 pr-4 py-3 text-white focus:outline-none focus:border-blue-500"
                />
              </div>
              <button onClick={fetchBackup} disabled={loading} className="px-5 py-3 bg-[#1a1a24] hover:bg-[#2a2a36] border border-[#2a2a36] rounded-lg flex items-center justify-center transition-colors">
                <RefreshCw size={18} className={loading ? 'animate-spin' : ''} />
              </button>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {filteredClips.map(clip => (
                <div key={clip.id} className={`bg-[#1a1a24] border border-[#2a2a36] rounded-xl p-5 relative group transition-all hover:border-[#3a3a46] ${clip.isPinned ? 'ring-1 ring-amber-500/30' : ''}`}>
                  <div className="flex justify-between items-start mb-3">
                    <span className="text-xs font-medium px-2.5 py-1 rounded-md bg-blue-500/10 text-blue-400">
                      {clip.category}
                    </span>
                    <span className="text-xs text-gray-500">
                      {new Date(clip.timestamp).toLocaleDateString()}
                    </span>
                  </div>
                  
                  <p className="text-sm text-gray-300 whitespace-pre-wrap break-words mb-4" style={{ display: '-webkit-box', WebkitLineClamp: 4, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
                    {clip.isSensitive ? '••••••••' : clip.text}
                  </p>

                  <div className="absolute top-4 right-4 md:opacity-0 group-hover:opacity-100 transition-opacity flex gap-2 bg-[#1a1a24] pl-2">
                    <button 
                      onClick={() => handleCopy(clip.id, clip.text)}
                      className="p-2 rounded-lg bg-[#2a2a36] hover:bg-blue-500/20 hover:text-blue-400 transition-colors"
                      title="Copy to clipboard"
                    >
                      {copiedId === clip.id ? <Check size={16} className="text-green-400" /> : <Copy size={16} />}
                    </button>
                  </div>
                </div>
              ))}
            </div>
            
            {filteredClips.length === 0 && (
              <div className="text-center py-20 text-gray-500">
                No clips found.
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
