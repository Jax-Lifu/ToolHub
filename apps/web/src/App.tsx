import { FormEvent, lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Activity,
  Cpu,
  Download,
  Eye,
  EyeOff,
  Flame,
  Layers,
  LayoutGrid,
  LogOut,
  Monitor,
  RefreshCw,
  Search,
  Server,
  Settings,
  Shield,
  Table2,
  Terminal,
  Thermometer,
  Trash2,
  Wifi,
  X,
} from 'lucide-react';
import { detectInitialLang, LANG_OPTIONS, LangCode, tr } from './i18n';

const AdminAuditView = lazy(() =>
  import('./components/AdminAuditView').then((m) => ({ default: m.AdminAuditView })),
);
const BatchOpsView = lazy(() =>
  import('./components/BatchOpsView').then((m) => ({ default: m.BatchOpsView })),
);
const DeviceDetailDrawer = lazy(() =>
  import('./components/DeviceDetailDrawer').then((m) => ({ default: m.DeviceDetailDrawer })),
);
const DeviceWorkbench = lazy(() =>
  import('./components/DeviceWorkbench').then((m) => ({ default: m.DeviceWorkbench })),
);
const SettingsView = lazy(() =>
  import('./components/SettingsView').then((m) => ({ default: m.SettingsView })),
);
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from './components/ui/alert-dialog';
import { Button } from './components/ui/button';
import { Input } from './components/ui/input';
import {
  AccentColor,
  DeviceItem,
  FilterMode,
  Role,
  SortMode,
  ThemeMode,
  ViewMode,
  WorkspaceSection,
} from './types';
import { copyTextToClipboard, exportDevicesCsv } from './utils/clipboard';

interface ToastItem {
  id: number;
  message: string;
}

function parseHashRoute(): { section: WorkspaceSection; mac: string | null } {
  const hash = window.location.hash.replace(/^#\/?/, '');
  if (hash.startsWith('workbench')) {
    const [, rawMac] = hash.split('/');
    return { section: 'workbench', mac: rawMac ? decodeURIComponent(rawMac) : null };
  }
  if (hash === 'batch') return { section: 'batch', mac: null };
  if (hash === 'admin') return { section: 'admin', mac: null };
  if (hash === 'settings') return { section: 'settings', mac: null };
  return { section: 'fleet', mac: null };
}

function applyAppearance(theme: ThemeMode, accent: AccentColor) {
  const root = document.documentElement;
  const isDark =
    theme === 'dark' ||
    (theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  root.dataset.theme = isDark ? 'dark' : 'light';
  root.dataset.accent = accent;
}

function renderTemp(temp: number | null) {
  if (temp === null || Number.isNaN(temp)) {
    return <span className="text-muted-foreground">-</span>;
  }
  const textCls =
    temp >= 75 ? 'text-destructive' : temp >= 65 ? 'text-amber-500' : 'text-emerald-500';
  const barCls =
    temp >= 75 ? 'bg-destructive' : temp >= 65 ? 'bg-amber-500' : 'bg-emerald-500';
  const pct = Math.max(8, Math.min(100, ((temp - 25) / 70) * 100));
  return (
    <div className="flex items-center gap-2 tabular">
      <span className={`min-w-[46px] font-semibold ${textCls}`}>{temp.toFixed(1)}°C</span>
      <div className="h-1.5 w-12 overflow-hidden rounded-full bg-muted" title={`SoC ${temp.toFixed(1)}°C`}>
        <div className={`h-full rounded-full ${barCls}`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

export default function App() {
  const initialRoute = useMemo(() => parseHashRoute(), []);
  const [token, setToken] = useState<string>(() => localStorage.getItem('fleet_token') || '');
  const [username, setUsername] = useState<string>(() => localStorage.getItem('fleet_user') || 'admin');
  const [currentRole, setCurrentRole] = useState<Role>(
    () => (localStorage.getItem('fleet_role') as Role) || 'admin',
  );
  const [loginUser, setLoginUser] = useState('admin');
  const [lang, setLang] = useState<LangCode>(() => detectInitialLang());
  const [theme, setTheme] = useState<ThemeMode>(
    () => (localStorage.getItem('fleet_theme') as ThemeMode) || 'dark',
  );
  const [accent, setAccent] = useState<AccentColor>(
    () => (localStorage.getItem('fleet_accent') as AccentColor) || 'blue',
  );
  const [password, setPassword] = useState('');
  const [showPass, setShowPass] = useState(false);
  const [loginErr, setLoginErr] = useState('');
  const [devices, setDevices] = useState<DeviceItem[]>([]);
  const [section, setSection] = useState<WorkspaceSection>(initialRoute.section);
  const [workbenchMac, setWorkbenchMac] = useState<string | null>(initialRoute.mac);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<FilterMode>('all');
  const [sortBy, setSortBy] = useState<SortMode>('default');
  const [viewMode, setViewMode] = useState<ViewMode>('table');
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [lastSyncAt, setLastSyncAt] = useState('');
  const [syncError, setSyncError] = useState(false);
  const [selectedMac, setSelectedMac] = useState<string | null>(null);
  const [removeTarget, setRemoveTarget] = useState<DeviceItem | null>(null);
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const toastSeqRef = useRef(1);
  const toastPausedRef = useRef(false);
  const searchInputRef = useRef<HTMLInputElement>(null);

  const navigate = useCallback((nextSection: WorkspaceSection, mac: string | null = null) => {
    setSection(nextSection);
    if (nextSection === 'workbench') {
      setWorkbenchMac(mac);
      window.location.hash = mac ? `#/workbench/${encodeURIComponent(mac)}` : '#/workbench';
    } else {
      window.location.hash = `#/${nextSection}`;
    }
  }, []);

  useEffect(() => {
    const onHashChange = () => {
      const parsed = parseHashRoute();
      setSection(parsed.section);
      if (parsed.mac) setWorkbenchMac(parsed.mac);
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && !e.altKey && !e.shiftKey && e.key === ',') {
        e.preventDefault();
        navigate('settings');
      } else if (
        e.key === '/' &&
        !e.metaKey &&
        !e.ctrlKey &&
        !e.altKey &&
        !(document.activeElement instanceof HTMLInputElement) &&
        !(document.activeElement instanceof HTMLTextAreaElement) &&
        !(document.activeElement instanceof HTMLSelectElement)
      ) {
        e.preventDefault();
        if (section !== 'fleet') navigate('fleet');
        window.setTimeout(() => searchInputRef.current?.focus(), 0);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [navigate, section]);

  useEffect(() => {
    localStorage.setItem('fleet_lang', lang);
    document.documentElement.lang = lang;
  }, [lang]);

  useEffect(() => {
    applyAppearance(theme, accent);
    localStorage.setItem('fleet_theme', theme);
    localStorage.setItem('fleet_accent', accent);
    if (theme !== 'system') return;
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => applyAppearance('system', accent);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, [theme, accent]);

  const dismissToast = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const showToast = useCallback(
    (message: string) => {
      const id = toastSeqRef.current++;
      setToasts((prev) => [...prev.slice(-2), { id, message }]);
      window.setTimeout(() => {
        if (!toastPausedRef.current) {
          dismissToast(id);
        }
      }, 3600);
    },
    [dismissToast],
  );

  const handleCopyAdb = useCallback(
    async (cmd: string) => {
      const ok = await copyTextToClipboard(cmd);
      if (ok) {
        showToast(`已复制远程 ADB 命令: ${cmd}`);
      } else {
        window.prompt('当前环境无法自动写入剪贴板，请手动复制远程 ADB 命令:', cmd);
      }
    },
    [showToast],
  );

  const fetchDevices = useCallback(async (tk: string) => {
    try {
      const res = await fetch('/api/devices', {
        headers: { Authorization: `Bearer ${tk}` },
        credentials: 'include',
        cache: 'no-store',
      });
      if (res.status === 401) {
        localStorage.removeItem('fleet_token');
        setToken('');
        return;
      }
      if (res.ok) {
        setDevices(await res.json());
        setLastSyncAt(new Date().toLocaleTimeString('zh-CN', { hour12: false }));
        setSyncError(false);
      } else {
        setSyncError(true);
      }
    } catch {
      setSyncError(true);
    }
  }, []);

  useEffect(() => {
    if (!token) return;
    fetch('/api/auth/me', {
      headers: { Authorization: `Bearer ${token}` },
      cache: 'no-store',
    })
      .then((r) => (r.ok ? r.json() : null))
      .then((me) => {
        if (me?.username) {
          setUsername(me.username);
          localStorage.setItem('fleet_user', me.username);
        }
        if (me?.role) {
          setCurrentRole(me.role);
          localStorage.setItem('fleet_role', me.role);
        }
      })
      .catch(() => {});
  }, [token]);

  useEffect(() => {
    if (!token) return;
    fetchDevices(token);
    if (!autoRefresh) return;
    const id = window.setInterval(() => fetchDevices(token), 5000);
    return () => window.clearInterval(id);
  }, [token, autoRefresh, fetchDevices]);

  const openWorkbench = useCallback(
    (mac: string) => {
      setSelectedMac(null);
      navigate('workbench', mac);
    },
    [navigate],
  );

  const handleLogin = async (e: FormEvent) => {
    e.preventDefault();
    setLoginErr('');
    try {
      const res = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ username: loginUser.trim() || 'admin', password }),
      });
      if (!res.ok) {
        setLoginErr(tr(lang, 'login.err'));
        return;
      }
      const data = await res.json();
      localStorage.setItem('fleet_token', data.token);
      if (data.username) {
        localStorage.setItem('fleet_user', data.username);
        setUsername(data.username);
      }
      if (data.role) {
        localStorage.setItem('fleet_role', data.role);
        setCurrentRole(data.role);
      }
      setToken(data.token);
      setPassword('');
    } catch {
      setLoginErr('无法连接到管理服务器');
    }
  };

  const confirmRemoveDevice = async () => {
    if (!removeTarget) return;
    const mac = removeTarget.mac;
    setRemoveTarget(null);
    await fetch(`/api/devices/${encodeURIComponent(mac)}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` },
    });
    if (selectedMac === mac) setSelectedMac(null);
    fetchDevices(token);
    showToast(`已移除离线设备 ${mac}`);
  };

  const counts = useMemo(() => {
    const online = devices.filter((d) => d.online).length;
    const ws = devices.filter((d) => d.online && d.wsConnected).length;
    const stress = devices.filter((d) => d.online && d.stressRunning).length;
    const offline = devices.length - online;
    const temps = devices
      .filter((d) => d.online && d.socTempC !== null)
      .map((d) => d.socTempC as number);
    const maxTemp = temps.length > 0 ? Math.max(...temps) : null;
    const avgTemp = temps.length > 0 ? temps.reduce((a, b) => a + b, 0) / temps.length : null;
    return { all: devices.length, online, ws, stress, offline, maxTemp, avgTemp };
  }, [devices]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    const list = devices.filter((d) => {
      if (filter === 'online' && !d.online) return false;
      if (filter === 'ws' && !(d.online && d.wsConnected)) return false;
      if (filter === 'stress' && !(d.online && d.stressRunning)) return false;
      if (filter === 'offline' && d.online) return false;
      if (!q) return true;
      return (
        d.mac.toLowerCase().includes(q) ||
        d.serial.toLowerCase().includes(q) ||
        d.model.toLowerCase().includes(q) ||
        d.brand.toLowerCase().includes(q) ||
        d.lanIp.toLowerCase().includes(q) ||
        d.publicIp.toLowerCase().includes(q) ||
        d.socPlatform.toLowerCase().includes(q) ||
        d.androidVersion.toLowerCase().includes(q) ||
        (d.alias || '').toLowerCase().includes(q) ||
        (d.group || '').toLowerCase().includes(q) ||
        (d.notes || '').toLowerCase().includes(q)
      );
    });

    const sorted = [...list];
    if (sortBy === 'temp_desc') {
      sorted.sort((a, b) => (b.socTempC ?? -999) - (a.socTempC ?? -999));
    } else if (sortBy === 'seen_asc') {
      sorted.sort((a, b) => a.lastSeenAgoSec - b.lastSeenAgoSec);
    } else if (sortBy === 'model_asc') {
      sorted.sort((a, b) => a.model.localeCompare(b.model));
    } else {
      sorted.sort((a, b) => Number(b.online) - Number(a.online) || a.model.localeCompare(b.model));
    }
    return sorted;
  }, [devices, query, filter, sortBy]);

  const selectedDevice = useMemo(
    () => devices.find((d) => d.mac === selectedMac) ?? null,
    [devices, selectedMac],
  );

  if (!token) {
    return (
      <main className="flex min-h-dvh items-center justify-center bg-canvas p-5">
        <form
          onSubmit={handleLogin}
          className="w-full max-w-sm space-y-4 rounded-xl border border-border bg-background p-6 shadow-lg"
        >
          <div className="flex items-center gap-3">
            <span className="brand-mark" aria-hidden="true">
              T.
            </span>
            <div>
              <h1 className="text-lg font-semibold tracking-tight">{tr(lang, 'app.title')}</h1>
              <p className="text-xs text-muted-foreground">{tr(lang, 'login.subtitle')}</p>
            </div>
          </div>

          <Input
            type="text"
            aria-label="Username"
            autoComplete="username"
            placeholder="admin"
            value={loginUser}
            onChange={(e) => setLoginUser(e.target.value)}
          />

          <div className="relative">
            <Input
              type={showPass ? 'text' : 'password'}
              aria-label="Password"
              autoComplete="current-password"
              placeholder={tr(lang, 'login.placeholder')}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="pr-10"
              autoFocus
            />
            <button
              type="button"
              onClick={() => setShowPass((v) => !v)}
              aria-label="Toggle password"
              className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-1 text-muted-foreground hover:text-foreground cursor-pointer"
            >
              {showPass ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
            </button>
          </div>

          {loginErr && (
            <div role="alert" className="text-xs font-medium text-destructive">
              {loginErr}
            </div>
          )}

          <Button type="submit" className="w-full">
            {tr(lang, 'login.submit')}
          </Button>
        </form>
      </main>
    );
  }

  const tabs: Array<{ id: FilterMode; label: string; count: number }> = [
    { id: 'all', label: tr(lang, 'filter.all'), count: counts.all },
    { id: 'online', label: tr(lang, 'filter.online'), count: counts.online },
    { id: 'ws', label: tr(lang, 'filter.ws'), count: counts.ws },
    { id: 'stress', label: tr(lang, 'filter.stress'), count: counts.stress },
    { id: 'offline', label: tr(lang, 'filter.offline'), count: counts.offline },
  ];

  const sectionTitle = tr(lang, `nav.${section}`);

  return (
    <div className="app-shell">
      <aside className="sidebar" aria-label="工作台导航">
        <div>
          <div className="brand">
            <span className="brand-mark" aria-hidden="true">
              T.
            </span>
            <span>ToolHub Fleet</span>
          </div>
          <div className="sidebar-label">{tr(lang, 'app.workspace')}</div>
          <nav className="app-nav">
            <button
              type="button"
              aria-current={section === 'fleet' ? 'page' : undefined}
              onClick={() => navigate('fleet')}
            >
              <Monitor className="size-4" />
              <span>{tr(lang, 'nav.fleet')}</span>
              {section === 'fleet' && <span className="nav-dot" aria-hidden="true" />}
            </button>
            <button
              type="button"
              aria-current={section === 'workbench' ? 'page' : undefined}
              onClick={() => navigate('workbench', workbenchMac)}
            >
              <Cpu className="size-4" />
              <span>{tr(lang, 'nav.workbench')}</span>
              {section === 'workbench' && <span className="nav-dot" aria-hidden="true" />}
            </button>
            <button
              type="button"
              aria-current={section === 'batch' ? 'page' : undefined}
              onClick={() => navigate('batch')}
            >
              <Layers className="size-4" />
              <span>{tr(lang, 'nav.batch')}</span>
              {section === 'batch' && <span className="nav-dot" aria-hidden="true" />}
            </button>
            <button
              type="button"
              aria-current={section === 'admin' ? 'page' : undefined}
              onClick={() => navigate('admin')}
            >
              <Shield className="size-4" />
              <span>{tr(lang, 'nav.admin')}</span>
              {section === 'admin' && <span className="nav-dot" aria-hidden="true" />}
            </button>
            <button
              type="button"
              aria-current={section === 'settings' ? 'page' : undefined}
              onClick={() => navigate('settings')}
            >
              <Settings className="size-4" />
              <span>{tr(lang, 'nav.settings')}</span>
              {section === 'settings' && <span className="nav-dot" aria-hidden="true" />}
            </button>
          </nav>
        </div>

        <div className="sidebar-footer">
          <div className="flex items-center justify-between px-1 text-xs">
            <span className="mono font-semibold">{username}</span>
            <span className="rounded bg-primary/15 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-primary">
              {currentRole}
            </span>
          </div>
          <div className="px-1 text-[11px] text-muted-foreground">
            {tr(lang, 'app.shortcut')}: <kbd className="inline">Ctrl/⌘ ,</kbd> · <kbd className="inline">/</kbd>
          </div>
          <button
            type="button"
            onClick={() => setAutoRefresh((v) => !v)}
            className="flex w-full items-center justify-between rounded-lg border border-border bg-background px-3 py-2 text-xs text-muted-foreground hover:text-foreground cursor-pointer"
          >
            <span className="inline-flex items-center gap-2">
              <span
                className={`size-2 rounded-full ${
                  syncError
                    ? 'bg-destructive'
                    : autoRefresh
                      ? 'bg-emerald-500 animate-pulse'
                      : 'bg-amber-500'
                }`}
              />
              <span>
                {syncError
                  ? tr(lang, 'status.connErr')
                  : autoRefresh
                    ? tr(lang, 'status.pollOn')
                    : tr(lang, 'status.pollOff')}
              </span>
            </span>
            {lastSyncAt && <span className="mono">{lastSyncAt}</span>}
          </button>

          <Button
            variant="outline"
            size="sm"
            className="w-full justify-start text-destructive hover:text-destructive"
            onClick={() => {
              localStorage.removeItem('fleet_token');
              setToken('');
            }}
          >
            <LogOut className="size-3.5" />
            <span>{tr(lang, 'action.logout')}</span>
          </Button>
        </div>
      </aside>

      <main className="workspace">
        <div className="workspace-bar">
          <div className="flex flex-wrap items-center gap-2">
            <span>{tr(lang, 'app.workspace')}</span>
            <span aria-hidden="true">/</span>
            <strong className="font-semibold text-foreground">{sectionTitle}</strong>
            <span className="ml-1 rounded-full bg-muted px-2 py-0.5 text-xs tabular">
              {tr(lang, 'status.online')} {counts.online} / {counts.all}
            </span>
            {syncError && (
              <span className="rounded-full bg-destructive/15 px-2 py-0.5 text-xs text-destructive">
                {tr(lang, 'status.connErr')}
              </span>
            )}
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <select
              aria-label={tr(lang, 'settings.lang')}
              value={lang}
              onChange={(e) => setLang(e.target.value as LangCode)}
              className="h-8 min-h-0 py-1 text-xs"
            >
              {LANG_OPTIONS.map((opt) => (
                <option key={opt.code} value={opt.code}>
                  {opt.label}
                </option>
              ))}
            </select>
            <Button variant="outline" size="sm" onClick={() => fetchDevices(token)}>
              <RefreshCw className="size-3.5" />
              <span>{tr(lang, 'action.refresh')}</span>
            </Button>
            {section === 'fleet' && (
              <Button
                variant="outline"
                size="sm"
                disabled={filtered.length === 0}
                onClick={() => {
                  exportDevicesCsv(filtered);
                  showToast(`CSV (${filtered.length})`);
                }}
              >
                <Download className="size-3.5" />
                <span>{tr(lang, 'action.exportCsv')}</span>
              </Button>
            )}
            <Button
              variant="outline"
              size="sm"
              className="md:hidden text-destructive"
              onClick={() => {
                localStorage.removeItem('fleet_token');
                setToken('');
              }}
            >
              <LogOut className="size-3.5" />
            </Button>
          </div>
        </div>

        <div className="workspace-content">
          {section === 'fleet' && (
            <>
              <section className="grid grid-cols-2 gap-3 lg:grid-cols-5 tabular" aria-label="设备统计概览">
                <div className="rounded-xl border border-border bg-card p-4">
                  <div className="flex items-center justify-between text-xs text-muted-foreground">
                    <span>{tr(lang, 'stat.total')}</span>
                    <Server className="size-4 text-primary" />
                  </div>
                  <div className="mt-1.5 text-2xl font-bold">{counts.all}</div>
                </div>
                <div className="rounded-xl border border-border bg-card p-4">
                  <div className="flex items-center justify-between text-xs text-muted-foreground">
                    <span>{tr(lang, 'stat.onlineWs')}</span>
                    <Wifi className="size-4 text-emerald-500" />
                  </div>
                  <div className="mt-1.5 text-2xl font-bold text-emerald-500">
                    {counts.online}{' '}
                    <span className="text-sm font-normal text-muted-foreground">/ {counts.ws} WS</span>
                  </div>
                </div>
                <div className="rounded-xl border border-border bg-card p-4">
                  <div className="flex items-center justify-between text-xs text-muted-foreground">
                    <span>{tr(lang, 'stat.peakTemp')}</span>
                    <Flame className="size-4 text-amber-500" />
                  </div>
                  <div
                    className={`mt-1.5 text-2xl font-bold ${
                      counts.maxTemp !== null && counts.maxTemp >= 75
                        ? 'text-destructive'
                        : counts.maxTemp !== null && counts.maxTemp >= 65
                          ? 'text-amber-500'
                          : ''
                    }`}
                  >
                    {counts.maxTemp !== null ? `${counts.maxTemp.toFixed(1)}°C` : '-'}
                  </div>
                </div>
                <div className="rounded-xl border border-border bg-card p-4">
                  <div className="flex items-center justify-between text-xs text-muted-foreground">
                    <span>{tr(lang, 'stat.avgTemp')}</span>
                    <Thermometer className="size-4 text-sky-500" />
                  </div>
                  <div className="mt-1.5 text-2xl font-bold">
                    {counts.avgTemp !== null ? `${counts.avgTemp.toFixed(1)}°C` : '-'}
                  </div>
                </div>
                <div className="rounded-xl border border-border bg-card p-4">
                  <div className="flex items-center justify-between text-xs text-muted-foreground">
                    <span>{tr(lang, 'stat.stressOffline')}</span>
                    <Activity className="size-4 text-rose-500" />
                  </div>
                  <div className="mt-1.5 text-2xl font-bold">
                    <span className={counts.stress > 0 ? 'text-destructive' : ''}>{counts.stress}</span>
                    <span className="text-sm font-normal text-muted-foreground"> / {counts.offline}</span>
                  </div>
                </div>
              </section>

              <section className="flex flex-wrap items-center justify-between gap-3">
                <div className="inline-flex flex-wrap gap-1 rounded-lg border border-border bg-canvas p-1" role="tablist">
                  {tabs.map((t) => (
                    <button
                      key={t.id}
                      type="button"
                      role="tab"
                      aria-selected={filter === t.id}
                      onClick={() => setFilter(t.id)}
                      className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-semibold cursor-pointer transition-colors ${
                        filter === t.id
                          ? 'bg-primary text-primary-foreground shadow-sm'
                          : 'text-muted-foreground hover:bg-muted hover:text-foreground'
                      }`}
                    >
                      <span>{t.label}</span>
                      <span
                        className={`rounded-full px-1.5 py-0.5 text-[11px] tabular ${
                          filter === t.id ? 'bg-white/20 text-primary-foreground' : 'bg-muted'
                        }`}
                      >
                        {t.count}
                      </span>
                    </button>
                  ))}
                </div>

                <div className="flex flex-1 flex-wrap items-center justify-end gap-2">
                  <div className="relative min-w-[220px] flex-1 max-w-sm">
                    <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                    <Input
                      ref={searchInputRef}
                      type="search"
                      aria-label={tr(lang, 'filter.search')}
                      placeholder={tr(lang, 'filter.search')}
                      value={query}
                      onChange={(e) => setQuery(e.target.value)}
                      className="pl-8 pr-8"
                    />
                    {query && (
                      <button
                        type="button"
                        onClick={() => setQuery('')}
                        aria-label="Clear"
                        className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground cursor-pointer"
                      >
                        <X className="size-3.5" />
                      </button>
                    )}
                  </div>

                  <select
                    aria-label="Sort"
                    value={sortBy}
                    onChange={(e) => setSortBy(e.target.value as SortMode)}
                  >
                    <option value="default">{tr(lang, 'sort.default')}</option>
                    <option value="temp_desc">{tr(lang, 'sort.temp')}</option>
                    <option value="seen_asc">{tr(lang, 'sort.seen')}</option>
                    <option value="model_asc">{tr(lang, 'sort.model')}</option>
                  </select>

                  <div className="inline-flex gap-1 rounded-lg border border-border bg-canvas p-1" role="group">
                    <button
                      type="button"
                      onClick={() => setViewMode('table')}
                      className={`inline-flex items-center gap-1 rounded-md px-2.5 py-1 text-xs font-semibold cursor-pointer ${
                        viewMode === 'table'
                          ? 'bg-primary text-primary-foreground shadow-sm'
                          : 'text-muted-foreground hover:bg-muted hover:text-foreground'
                      }`}
                    >
                      <Table2 className="size-3.5" />
                      <span>{tr(lang, 'view.table')}</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => setViewMode('grid')}
                      className={`inline-flex items-center gap-1 rounded-md px-2.5 py-1 text-xs font-semibold cursor-pointer ${
                        viewMode === 'grid'
                          ? 'bg-primary text-primary-foreground shadow-sm'
                          : 'text-muted-foreground hover:bg-muted hover:text-foreground'
                      }`}
                    >
                      <LayoutGrid className="size-3.5" />
                      <span>{tr(lang, 'view.grid')}</span>
                    </button>
                  </div>
                </div>
              </section>

              {viewMode === 'table' ? (
                <div tabIndex={0} role="region" aria-labelledby="fleet-table-caption" className="table-box">
                  <table className="fleet-table">
                    <caption id="fleet-table-caption" className="sr-only">
                      ToolHub Fleet 已接入设备列表
                    </caption>
                    <thead>
                      <tr>
                        <th>{tr(lang, 'tbl.status')}</th>
                        <th>{tr(lang, 'tbl.mac')}</th>
                        <th>{tr(lang, 'tbl.model')}</th>
                        <th>{tr(lang, 'tbl.ip')}</th>
                        <th>{tr(lang, 'tbl.telemetry')}</th>
                        <th>{tr(lang, 'tbl.ports')}</th>
                        <th>{tr(lang, 'tbl.actions')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {filtered.map((d) => (
                        <tr
                          key={d.mac}
                          className={selectedMac === d.mac ? 'selected-row' : ''}
                          onClick={() => setSelectedMac(d.mac)}
                        >
                          <td>
                            <div className="flex flex-col items-start gap-1">
                              <span
                                className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${
                                  d.online
                                    ? 'bg-emerald-500/15 text-emerald-500'
                                    : 'bg-muted text-muted-foreground'
                                }`}
                              >
                                {d.online ? `在线 · ${d.lastSeenAgoSec}s` : `离线 (${d.lastSeenAgoSec}s)`}
                              </span>
                              <div className="flex flex-wrap gap-1">
                                {d.online && d.wsConnected && (
                                  <span className="rounded-full bg-primary/15 px-2 py-0.5 text-[11px] font-medium text-primary">
                                    WS 隧道
                                  </span>
                                )}
                                {d.online && d.tunnelConnected && (
                                  <span className="rounded-full bg-emerald-500/15 px-2 py-0.5 text-[11px] font-medium text-emerald-500">
                                    SSH
                                  </span>
                                )}
                                {d.stressRunning && (
                                  <span className="rounded-full bg-destructive/15 px-2 py-0.5 text-[11px] font-medium text-destructive">
                                    压测中
                                  </span>
                                )}
                              </div>
                            </div>
                          </td>
                          <td>
                            <div className="mono font-semibold">{d.mac}</div>
                            <div className="mono text-xs text-muted-foreground">SN: {d.serial || '-'}</div>
                          </td>
                          <td>
                            <div className="font-medium">
                              {d.alias ? `${d.alias} · ` : ''}
                              {d.brand} {d.model}
                              {d.group && (
                                <span className="ml-1.5 rounded bg-primary/15 px-1.5 py-0.5 text-[10px] font-semibold text-primary">
                                  {d.group}
                                </span>
                              )}
                            </div>
                            <div className="text-xs text-muted-foreground">
                              Android {d.androidVersion} (SDK {d.sdkInt}) · {d.socPlatform}
                            </div>
                          </td>
                          <td className="mono text-xs">
                            <div>LAN: {d.lanIp || '-'}</div>
                            <div className="text-muted-foreground">WAN: {d.publicIp || '-'}</div>
                          </td>
                          <td className="text-xs">
                            {renderTemp(d.socTempC)}
                            <div className="mt-1 tabular text-muted-foreground">
                              Uptime: {d.uptime || '-'} · v{d.appVersion || '-'}
                            </div>
                          </td>
                          <td className="mono tabular text-xs text-muted-foreground">
                            <div>Web: {d.tunnelWebPort || '-'}</div>
                            <div>ADB: {d.tunnelAdbPort || '-'}</div>
                          </td>
                          <td onClick={(e) => e.stopPropagation()}>
                            <div className="flex flex-wrap gap-1.5">
                              <Button
                                size="xs"
                                disabled={!d.online}
                                onClick={() => openWorkbench(d.mac)}
                              >
                                进入工作台
                              </Button>
                              <Button
                                variant="outline"
                                size="xs"
                                disabled={!d.online || !d.tunnelConnected}
                                onClick={() => handleCopyAdb(d.adbTunnelCmd)}
                              >
                                <Terminal className="size-3" />
                                <span>远程 ADB</span>
                              </Button>
                              {!d.online && (
                                <Button
                                  variant="destructive"
                                  size="xs"
                                  onClick={() => setRemoveTarget(d)}
                                >
                                  <Trash2 className="size-3" />
                                  <span>移除</span>
                                </Button>
                              )}
                            </div>
                          </td>
                        </tr>
                      ))}
                      {filtered.length === 0 && (
                        <tr>
                          <td colSpan={7} className="py-10 text-center text-sm text-muted-foreground">
                            {devices.length === 0
                              ? '暂无已接入设备，设备端启动 ToolHub 后将自动通过 WebSocket 反向隧道注册上线'
                              : '当前筛选或搜索条件下没有匹配的设备'}
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
                  {filtered.map((d) => (
                    <div
                      key={d.mac}
                      onClick={() => setSelectedMac(d.mac)}
                      className={`flex flex-col gap-3 rounded-xl border bg-card p-4 cursor-pointer transition-colors hover:bg-muted/30 ${
                        selectedMac === d.mac ? 'border-primary ring-1 ring-primary' : 'border-border'
                      }`}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <div>
                          <div className="font-semibold">
                            {d.brand} {d.model}
                          </div>
                          <div className="mono text-xs text-muted-foreground">
                            {d.mac} · {d.serial || '-'}
                          </div>
                        </div>
                        <span
                          className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                            d.online
                              ? 'bg-emerald-500/15 text-emerald-500'
                              : 'bg-muted text-muted-foreground'
                          }`}
                        >
                          {d.online ? '在线' : `离线 ${d.lastSeenAgoSec}s`}
                        </span>
                      </div>

                      <div className="grid grid-cols-2 gap-2.5 rounded-lg bg-muted/50 p-3 text-xs">
                        <div>
                          <div className="text-muted-foreground">系统 / SoC</div>
                          <div className="mt-0.5 font-medium">
                            Android {d.androidVersion} · {d.socPlatform}
                          </div>
                        </div>
                        <div>
                          <div className="text-muted-foreground">SoC 温度</div>
                          <div className="mt-0.5">{renderTemp(d.socTempC)}</div>
                        </div>
                        <div>
                          <div className="text-muted-foreground">局域网 / 出口 IP</div>
                          <div className="mono mt-0.5">
                            {d.lanIp || '-'} / {d.publicIp || '-'}
                          </div>
                        </div>
                        <div>
                          <div className="text-muted-foreground">运行时长 / 版本</div>
                          <div className="tabular mt-0.5">
                            {d.uptime || '-'} · v{d.appVersion || '-'}
                          </div>
                        </div>
                      </div>

                      <div className="flex gap-2" onClick={(e) => e.stopPropagation()}>
                        <Button
                          size="sm"
                          className="flex-1"
                          disabled={!d.online}
                          onClick={() => openWorkbench(d.mac)}
                        >
                          进入工作台
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={!d.online || !d.tunnelConnected}
                          onClick={() => handleCopyAdb(d.adbTunnelCmd)}
                        >
                          <Terminal className="size-3.5" />
                          <span>远程 ADB</span>
                        </Button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}

          <Suspense
            fallback={
              <div className="rounded-xl border border-border bg-card p-8 text-sm text-muted-foreground">
                Loading...
              </div>
            }
          >
            {section === 'workbench' && (
              <DeviceWorkbench
                lang={lang}
                devices={devices}
                activeMac={workbenchMac}
                token={token}
                onSelectMac={(mac) => navigate('workbench', mac)}
                onCopyAdb={handleCopyAdb}
                onToast={showToast}
                onRefreshFleet={() => fetchDevices(token)}
              />
            )}

            {section === 'batch' && (
              <BatchOpsView
                lang={lang}
                devices={devices}
                token={token}
                onToast={showToast}
                onRefreshFleet={() => fetchDevices(token)}
              />
            )}

            {section === 'admin' && (
              <AdminAuditView
                token={token}
                currentUsername={username}
                currentRole={currentRole}
                onToast={showToast}
              />
            )}

            {section === 'settings' && (
              <SettingsView
                lang={lang}
                theme={theme}
                accent={accent}
                autoRefresh={autoRefresh}
                onLangChange={setLang}
                onThemeChange={setTheme}
                onAccentChange={setAccent}
                onAutoRefreshChange={setAutoRefresh}
              />
            )}
          </Suspense>
        </div>
      </main>

      <Suspense fallback={null}>
        <DeviceDetailDrawer
          device={selectedDevice}
          token={token}
          onClose={() => setSelectedMac(null)}
          onOpenWorkbench={openWorkbench}
          onCopyAdb={handleCopyAdb}
        />
      </Suspense>

      <AlertDialog open={Boolean(removeTarget)} onOpenChange={(open) => !open && setRemoveTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>移除离线设备</AlertDialogTitle>
            <AlertDialogDescription>
              确认从列表中移除设备{' '}
              <span className="mono font-semibold text-foreground">
                {removeTarget?.brand} {removeTarget?.model} ({removeTarget?.mac})
              </span>
              ？设备下次上线时会自动重新注册。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={confirmRemoveDevice}>
              确认移除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <div
        role="region"
        aria-label="系统通知"
        className="fixed right-5 bottom-5 z-60 flex max-w-md flex-col gap-2"
        onMouseEnter={() => {
          toastPausedRef.current = true;
        }}
        onMouseLeave={() => {
          toastPausedRef.current = false;
        }}
        onFocus={() => {
          toastPausedRef.current = true;
        }}
        onBlur={() => {
          toastPausedRef.current = false;
        }}
      >
        {toasts.map((item) => (
          <div
            key={item.id}
            role="status"
            aria-live="polite"
            className="flex items-center gap-2.5 rounded-xl border border-primary bg-background px-4 py-2.5 text-xs text-foreground shadow-xl break-all"
          >
            <span className="flex-1">{item.message}</span>
            <button
              type="button"
              onClick={() => dismissToast(item.id)}
              aria-label="关闭通知"
              className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground cursor-pointer"
            >
              <X className="size-3.5" />
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
