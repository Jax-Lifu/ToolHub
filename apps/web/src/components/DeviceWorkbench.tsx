import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  Clock,
  Cpu,
  Database,
  Download,
  FileCode2,
  FileWarning,
  Fingerprint,
  Flame,
  Gauge,
  Globe,
  HardDrive,
  MemoryStick,
  Monitor,
  Network,
  Play,
  RefreshCw,
  RotateCcw,
  ShieldCheck,
  Sliders,
  Smartphone,
  Square,
  Sun,
  Terminal,
  Usb,
  Volume2,
  Wifi,
  X,
  Zap,
} from 'lucide-react';
import { LangCode, tr } from '../i18n';
import {
  AotOverview,
  AudioOverview,
  CrashSummary,
  DeviceItem,
  DeviceOverview,
  LegacyStatus,
  ReportEntry,
  SysPropEntry,
  TelemetryHistoryPoint,
  TelemetrySnapshot,
  WorkbenchTab,
} from '../types';
import { TelemetrySparkline } from './TelemetrySparkline';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from './ui/alert-dialog';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { Switch } from './ui/switch';

interface DeviceWorkbenchProps {
  lang: LangCode;
  devices: DeviceItem[];
  activeMac: string | null;
  token: string;
  onSelectMac: (mac: string) => void;
  onCopyAdb: (cmd: string) => void;
  onToast: (msg: string) => void;
  onRefreshFleet: () => void;
}

function formatBytes(bytes: number | undefined): string {
  if (!bytes || bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const idx = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
  const val = bytes / Math.pow(1024, idx);
  return `${val.toFixed(idx >= 2 ? 2 : 1)} ${units[idx]}`;
}

export function DeviceWorkbench({
  lang,
  devices,
  activeMac,
  token,
  onSelectMac,
  onCopyAdb,
  onToast,
  onRefreshFleet,
}: DeviceWorkbenchProps) {
  const onlineDevices = useMemo(() => devices.filter((d) => d.online), [devices]);
  const device = useMemo(
    () => devices.find((d) => d.mac === activeMac) ?? onlineDevices[0] ?? devices[0] ?? null,
    [devices, onlineDevices, activeMac],
  );

  const [tab, setTab] = useState<WorkbenchTab>('telemetry');
  const [overview, setOverview] = useState<DeviceOverview | null>(null);
  const [telemetry, setTelemetry] = useState<TelemetrySnapshot | null>(null);
  const [legacyStatus, setLegacyStatus] = useState<LegacyStatus | null>(null);
  const [history, setHistory] = useState<{ cpu: number[]; mem: number[]; temp: number[] }>({
    cpu: [],
    mem: [],
    temp: [],
  });
  const [sysfsPath, setSysfsPath] = useState('/sys/');
  const [sysfsValue, setSysfsValue] = useState('');
  const [editingMeta, setEditingMeta] = useState(false);
  const [metaAlias, setMetaAlias] = useState('');
  const [metaGroup, setMetaGroup] = useState('');
  const [metaNotes, setMetaNotes] = useState('');
  const [audio, setAudio] = useState<AudioOverview | null>(null);
  const [aot, setAot] = useState<AotOverview | null>(null);
  const [reports, setReports] = useState<ReportEntry[]>([]);
  const [crash, setCrash] = useState<CrashSummary | null>(null);
  const [sysProps, setSysProps] = useState<SysPropEntry[]>([]);
  const [propQuery, setPropQuery] = useState('');
  const [editingPropKey, setEditingPropKey] = useState<string | null>(null);
  const [editingPropVal, setEditingPropVal] = useState('');
  const [pingHost, setPingHost] = useState('223.5.5.5');
  const [pingOutput, setPingOutput] = useState('');
  const [pingBusy, setPingBusy] = useState(false);
  const [stressType, setStressType] = useState<'SAT' | 'CPU' | 'MEMORY'>('SAT');
  const [stressDuration, setStressDuration] = useState(60);
  const [stressThreads, setStressThreads] = useState(4);
  const [stressRamMb, setStressRamMb] = useState(256);
  const [toneWaveform, setToneWaveform] = useState('SINE_1K');
  const [toneChannel, setToneChannel] = useState('STEREO');
  const [toneRate, setToneRate] = useState(48000);
  const [toneDb, setToneDb] = useState(-6);
  const [actionBusy, setActionBusy] = useState(false);
  const [tabLoading, setTabLoading] = useState(false);
  const [viewerTitle, setViewerTitle] = useState('');
  const [viewerText, setViewerText] = useState('');
  const [rebootConfirmOpen, setRebootConfirmOpen] = useState(false);

  const viewerDialogRef = useRef<HTMLDialogElement>(null);

  const proxyGet = useCallback(
    async <T,>(mac: string, path: string): Promise<T> => {
      const res = await fetch(`/proxy/${encodeURIComponent(mac)}${path}`, {
        headers: { Authorization: `Bearer ${token}` },
        cache: 'no-store',
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return res.json();
    },
    [token],
  );

  const proxyPost = useCallback(
    async (mac: string, path: string, body: unknown = {}) => {
      const res = await fetch(`/proxy/${encodeURIComponent(mac)}${path}`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
      });
      const data = await res.json().catch(() => ({ ok: res.ok, message: `HTTP ${res.status}` }));
      return data as { ok?: boolean; message?: string; output?: string };
    },
    [token],
  );

  const refreshTelemetryAndOverview = useCallback(async () => {
    if (!device || !device.online) return;
    try {
      const [ov, tel, st] = await Promise.all([
        proxyGet<DeviceOverview>(device.mac, '/api/overview'),
        proxyGet<TelemetrySnapshot>(device.mac, '/api/telemetry'),
        proxyGet<LegacyStatus>(device.mac, '/api/status').catch(() => null),
      ]);
      setOverview(ov);
      setTelemetry(tel);
      if (st) setLegacyStatus(st);
      setHistory((prev) => ({
        cpu: [...prev.cpu.slice(-29), tel.cpuPercent],
        mem: [...prev.mem.slice(-29), tel.memPercent],
        temp:
          device.socTempC !== null
            ? [...prev.temp.slice(-29), device.socTempC]
            : prev.temp,
      }));
    } catch {
      // Tolerate transient polling error
    }
  }, [device, proxyGet]);

  const loadAudio = useCallback(async () => {
    if (!device || !device.online) return;
    try {
      const data = await proxyGet<AudioOverview>(device.mac, '/api/audio');
      setAudio(data);
      if (data.tone) {
        setToneWaveform(data.tone.waveform || 'SINE_1K');
        setToneChannel(data.tone.channelMode || 'STEREO');
        setToneRate(data.tone.sampleRateHz || 48000);
        setToneDb(data.tone.attenuationDb ?? -6);
      }
    } catch (e) {
      onToast(`Audio fetch failed: ${e instanceof Error ? e.message : 'unknown'}`);
    }
  }, [device, proxyGet, onToast]);

  const loadSysPropsAndAot = useCallback(async () => {
    if (!device || !device.online) return;
    setTabLoading(true);
    try {
      const [propsRes, aotRes] = await Promise.all([
        proxyGet<{ props?: SysPropEntry[] }>(device.mac, '/api/sysprops'),
        proxyGet<AotOverview>(device.mac, '/api/aot'),
      ]);
      setSysProps(propsRes.props ?? []);
      setAot(aotRes);
    } catch (e) {
      onToast(`SysProps/AOT failed: ${e instanceof Error ? e.message : 'unknown'}`);
    } finally {
      setTabLoading(false);
    }
  }, [device, proxyGet, onToast]);

  const loadDiagnostics = useCallback(async () => {
    if (!device || !device.online) return;
    setTabLoading(true);
    try {
      const [rep, cr] = await Promise.all([
        proxyGet<ReportEntry[]>(device.mac, '/api/reports'),
        proxyGet<CrashSummary>(device.mac, '/api/crash'),
      ]);
      setReports(rep);
      setCrash(cr);
    } catch (e) {
      onToast(`Diagnostics failed: ${e instanceof Error ? e.message : 'unknown'}`);
    } finally {
      setTabLoading(false);
    }
  }, [device, proxyGet, onToast]);

  useEffect(() => {
    setOverview(null);
    setTelemetry(null);
    setLegacyStatus(null);
    setHistory({ cpu: [], mem: [], temp: [] });
    setAudio(null);
    setAot(null);
    setReports([]);
    setCrash(null);
    setSysProps([]);
    setPingOutput('');
    setEditingMeta(false);
    setMetaAlias(device?.alias || '');
    setMetaGroup(device?.group || '');
    setMetaNotes(device?.notes || '');
    if (!device) return;
    fetch(`/api/devices/${encodeURIComponent(device.mac)}/history?limit=60`, {
      headers: { Authorization: `Bearer ${token}` },
      cache: 'no-store',
    })
      .then((r) => (r.ok ? r.json() : []))
      .then((pts: TelemetryHistoryPoint[]) => {
        const temps = pts.map((p) => p.socTempC).filter((v): v is number => v !== null);
        if (temps.length > 0) {
          setHistory((prev) => ({ ...prev, temp: temps.slice(-30) }));
        }
      })
      .catch(() => {});
  }, [device?.mac, token]);

  useEffect(() => {
    if (!device || !device.online) return;
    refreshTelemetryAndOverview();
    if (tab === 'telemetry' || tab === 'controls') {
      const id = window.setInterval(refreshTelemetryAndOverview, 3000);
      return () => window.clearInterval(id);
    }
    if (tab === 'audio') loadAudio();
    if (tab === 'sysprops' && sysProps.length === 0) loadSysPropsAndAot();
    if (tab === 'diagnostics') loadDiagnostics();
  }, [
    device,
    tab,
    refreshTelemetryAndOverview,
    loadAudio,
    loadSysPropsAndAot,
    loadDiagnostics,
    sysProps.length,
  ]);

  useEffect(() => {
    const dlg = viewerDialogRef.current;
    if (!dlg) return;
    if (viewerTitle) {
      if (!dlg.open) dlg.showModal();
    } else if (dlg.open) {
      dlg.close();
    }
  }, [viewerTitle]);

  const runAction = async (
    label: string,
    path: string,
    body: unknown = {},
    after?: () => Promise<void> | void,
  ) => {
    if (!device) return;
    setActionBusy(true);
    try {
      const res = await proxyPost(device.mac, path, body);
      onToast(res.message ? `${label}: ${res.message}` : `${label} OK`);
      await refreshTelemetryAndOverview();
      if (after) await after();
      onRefreshFleet();
    } catch (e) {
      onToast(`${label} failed: ${e instanceof Error ? e.message : 'error'}`);
    } finally {
      setActionBusy(false);
    }
  };

  const handlePing = async (e: FormEvent) => {
    e.preventDefault();
    if (!device || !pingHost.trim()) return;
    setPingBusy(true);
    try {
      const res = await proxyPost(device.mac, '/api/device/ping', { host: pingHost.trim() });
      setPingOutput(res.output || res.message || 'No output');
    } catch (e) {
      setPingOutput(`Ping error: ${e instanceof Error ? e.message : 'unknown'}`);
    } finally {
      setPingBusy(false);
    }
  };

  const handleSaveProp = async (key: string) => {
    await runAction('Set Property', '/api/sysprops/set', { key, value: editingPropVal }, loadSysPropsAndAot);
    setEditingPropKey(null);
  };

  const openReportContent = async (fileName: string, isTombstone = false) => {
    if (!device) return;
    try {
      const endpoint = isTombstone
        ? `/api/tombstone-content?file=${encodeURIComponent(fileName)}`
        : `/api/report-content?file=${encodeURIComponent(fileName)}`;
      const res = await fetch(`/proxy/${encodeURIComponent(device.mac)}${endpoint}`, {
        headers: { Authorization: `Bearer ${token}` },
        cache: 'no-store',
      });
      setViewerTitle(fileName);
      setViewerText((await res.text()) || '(Empty)');
    } catch (e) {
      onToast(`Read failed: ${e instanceof Error ? e.message : 'unknown'}`);
    }
  };

  const realCpuCores = useMemo(() => {
    const policies = telemetry?.cpuPolicies ?? [];
    const cpuIds = new Set<number>();
    for (const p of policies) {
      if (!p.affectedCpus) continue;
      for (const tok of p.affectedCpus.trim().split(/\s+/)) {
        const n = Number(tok);
        if (Number.isInteger(n) && n >= 0) cpuIds.add(n);
      }
    }
    if (cpuIds.size > 0) return cpuIds.size;
    return 4;
  }, [telemetry?.cpuPolicies]);

  const cpuThreadOptions = useMemo(() => {
    const base = [1, 2, 4, 6, 8, 12, 16].filter((n) => n <= realCpuCores);
    if (!base.includes(realCpuCores)) base.push(realCpuCores);
    return base.sort((a, b) => a - b);
  }, [realCpuCores]);

  const safeMaxRamMb = useMemo(() => {
    if (!telemetry?.memTotalBytes) return 512;
    const totalMb = telemetry.memTotalBytes / (1024 * 1024);
    const freeMb = Math.max(0, (telemetry.memTotalBytes - telemetry.memUsedBytes) / (1024 * 1024));
    // Reserve >=30% free headroom and never allocate more than 60% of total physical RAM
    const cap = Math.floor(Math.min(freeMb * 0.7, totalMb * 0.6));
    return Math.max(64, cap);
  }, [telemetry?.memTotalBytes, telemetry?.memUsedBytes]);

  const ramOptions = useMemo(() => {
    const presets = [64, 128, 256, 512, 1024, 1536, 2048, 3072, 4096].filter(
      (mb) => mb <= safeMaxRamMb,
    );
    return presets.length > 0 ? presets : [64];
  }, [safeMaxRamMb]);

  useEffect(() => {
    setStressThreads((prev) => (prev > realCpuCores ? realCpuCores : prev));
  }, [realCpuCores]);

  useEffect(() => {
    setStressRamMb((prev) => {
      if (ramOptions.includes(prev)) return prev;
      return ramOptions[ramOptions.length - 1] ?? 64;
    });
  }, [ramOptions]);

  const filteredProps = useMemo(() => {
    const q = propQuery.trim().toLowerCase();
    if (!q) return sysProps.slice(0, 150);
    return sysProps
      .filter((p) => p.key.toLowerCase().includes(q) || p.value.toLowerCase().includes(q))
      .slice(0, 150);
  }, [sysProps, propQuery]);

  if (!device) {
    return (
      <div className="rounded-xl border border-border bg-card p-10 text-center">
        <div className="text-base font-semibold">No Devices Available</div>
      </div>
    );
  }

  const hw = overview?.device;
  const controls = overview?.controls;
  const otgNodes = overview?.otg?.nodes ?? [];
  const usbDevices = overview?.otg?.usbDevices ?? [];
  const healthChecks = overview?.healthChecks ?? [];

  return (
    <div className="space-y-4">
      {/* Top Picker + High-Contrast Tabs */}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border bg-card px-4 py-3">
        <div className="flex flex-wrap items-center gap-2.5">
          <label htmlFor="wb-device-select" className="text-xs font-medium text-muted-foreground">
            {tr(lang, 'wb.currentDevice')}
          </label>
          <select
            id="wb-device-select"
            value={device.mac}
            onChange={(e) => onSelectMac(e.target.value)}
          >
            {devices.map((d) => (
              <option key={d.mac} value={d.mac}>
                {d.online ? '● ' : '○ '}
                {d.brand} {d.model} ({d.mac}) · {d.lanIp || '-'}
              </option>
            ))}
          </select>
        </div>

        <div className="inline-flex flex-wrap gap-1.5 rounded-lg border border-border bg-canvas p-1" role="tablist">
          {(
            [
              { id: 'telemetry', label: tr(lang, 'wb.tab.telemetry'), Icon: Activity },
              { id: 'controls', label: tr(lang, 'wb.tab.controls'), Icon: Sliders },
              { id: 'audio', label: tr(lang, 'wb.tab.audio'), Icon: Volume2 },
              { id: 'sysprops', label: tr(lang, 'wb.tab.sysprops'), Icon: FileCode2 },
              { id: 'diagnostics', label: tr(lang, 'wb.tab.diagnostics'), Icon: FileWarning },
            ] as const
          ).map(({ id, label, Icon }) => (
            <button
              key={id}
              type="button"
              role="tab"
              aria-selected={tab === id}
              onClick={() => setTab(id)}
              className={`inline-flex items-center gap-1.5 rounded-md px-3.5 py-2 text-xs font-semibold transition-colors cursor-pointer ${
                tab === id
                  ? 'bg-primary text-primary-foreground shadow-sm'
                  : 'text-muted-foreground hover:bg-muted hover:text-foreground'
              }`}
            >
              <Icon className="size-3.5" />
              <span>{label}</span>
            </button>
          ))}
        </div>

        <Button
          variant="outline"
          size="sm"
          disabled={!device.online || !device.tunnelConnected}
          onClick={() => onCopyAdb(device.adbTunnelCmd)}
        >
          <Terminal className="size-3.5" />
          <span>{tr(lang, 'action.remoteAdb')}</span>
        </Button>
      </div>

      {/* Spacious Persistent Single-Device Hardware & System Card (No Truncation) */}
      <section className="rounded-xl border border-border bg-card p-5 space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className="grid size-10 place-items-center rounded-xl bg-primary/12 text-primary">
              <Smartphone className="size-5" />
            </div>
            <div>
              <div className="flex flex-wrap items-center gap-2">
                <h2 className="text-base font-semibold">
                  {device.brand} {device.model}
                </h2>
                <span className="rounded-full bg-muted px-2.5 py-0.5 text-xs mono">
                  {hw?.socPlatform || device.socPlatform} · {hw?.abi || '-'}
                </span>
                <span
                  className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${
                    device.online
                      ? 'bg-emerald-500/15 text-emerald-500'
                      : 'bg-muted text-muted-foreground'
                  }`}
                >
                  {device.online ? tr(lang, 'status.online') : tr(lang, 'status.offline')}
                </span>
              </div>
              <div className="mt-0.5 text-xs text-muted-foreground mono break-all">
                Build: {hw?.buildDisplay || '-'}
              </div>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span className="inline-flex items-center gap-1.5 rounded-lg border border-border bg-muted/40 px-2.5 py-1">
              <Clock className="size-3.5 text-primary" />
              <span>Uptime: {hw?.uptime || device.uptime || '-'}</span>
            </span>
            <span className="inline-flex items-center gap-1.5 rounded-lg border border-border bg-muted/40 px-2.5 py-1">
              <ShieldCheck className="size-3.5 text-emerald-500" />
              <span>{hw?.uidSummary || '-'}</span>
            </span>
            <Button
              variant="outline"
              size="xs"
              onClick={() => {
                setMetaAlias(device.alias || '');
                setMetaGroup(device.group || '');
                setMetaNotes(device.notes || '');
                setEditingMeta((v) => !v);
              }}
            >
              {editingMeta ? '收起标签' : '别名/分组'}
            </Button>
          </div>
        </div>

        {editingMeta && (
          <div className="flex flex-wrap items-center gap-2 rounded-lg border border-border bg-muted/30 p-3">
            <Input
              className="h-8 w-40 text-xs"
              value={metaAlias}
              onChange={(e) => setMetaAlias(e.target.value)}
              placeholder="设备别名 (如 老化架-A1)"
            />
            <Input
              className="h-8 w-36 text-xs"
              value={metaGroup}
              onChange={(e) => setMetaGroup(e.target.value)}
              placeholder="分组 (如 产线/研发)"
            />
            <Input
              className="h-8 min-w-44 flex-1 text-xs"
              value={metaNotes}
              onChange={(e) => setMetaNotes(e.target.value)}
              placeholder="备注信息"
            />
            <Button
              size="xs"
              onClick={async () => {
                const res = await fetch(`/api/devices/${encodeURIComponent(device.mac)}`, {
                  method: 'PATCH',
                  headers: {
                    Authorization: `Bearer ${token}`,
                    'Content-Type': 'application/json',
                  },
                  body: JSON.stringify({
                    alias: metaAlias,
                    group: metaGroup,
                    notes: metaNotes,
                  }),
                });
                if (!res.ok) {
                  const err = await res.json().catch(() => ({}));
                  onToast(err.error || '保存失败（只读账号无权修改）');
                  return;
                }
                setEditingMeta(false);
                onToast('已更新设备别名/分组');
                onRefreshFleet();
              }}
            >
              保存
            </Button>
          </div>
        )}

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 text-xs">
          <div className="flex items-start gap-2.5 rounded-lg border border-border bg-muted/25 p-3">
            <Fingerprint className="mt-0.5 size-4 shrink-0 text-primary" />
            <div className="min-w-0">
              <div className="text-muted-foreground">MAC / 序列号 (SN)</div>
              <div className="mt-0.5 mono font-medium break-all">
                {device.mac} · {device.serial || '-'}
              </div>
            </div>
          </div>

          <div className="flex items-start gap-2.5 rounded-lg border border-border bg-muted/25 p-3">
            <Globe className="mt-0.5 size-4 shrink-0 text-primary" />
            <div className="min-w-0">
              <div className="text-muted-foreground">局域网 IP / 公网出口 IP</div>
              <div className="mt-0.5 mono font-medium break-all">
                {device.lanIp || '-'} / {device.publicIp || '-'}
              </div>
            </div>
          </div>

          <div className="flex items-start gap-2.5 rounded-lg border border-border bg-muted/25 p-3">
            <Cpu className="mt-0.5 size-4 shrink-0 text-primary" />
            <div className="min-w-0">
              <div className="text-muted-foreground">系统版本 / Linux 内核</div>
              <div className="mt-0.5 mono font-medium break-all">
                Android {hw?.androidVersion || device.androidVersion} (SDK {hw?.sdkInt || device.sdkInt}) · Kernel {hw?.kernelVersion || '-'}
              </div>
            </div>
          </div>

          <div className="flex items-start gap-2.5 rounded-lg border border-border bg-muted/25 p-3">
            <MemoryStick className="mt-0.5 size-4 shrink-0 text-emerald-500" />
            <div className="min-w-0">
              <div className="text-muted-foreground">运行内存 (RAM)</div>
              <div className="mt-0.5 font-medium break-words">{hw?.ramSummary || '-'}</div>
            </div>
          </div>

          <div className="flex items-start gap-2.5 rounded-lg border border-border bg-muted/25 p-3">
            <HardDrive className="mt-0.5 size-4 shrink-0 text-violet-500" />
            <div className="min-w-0">
              <div className="text-muted-foreground">内部存储 (Storage)</div>
              <div className="mt-0.5 font-medium break-words">{hw?.storageSummary || '-'}</div>
            </div>
          </div>

          <div className="flex items-start gap-2.5 rounded-lg border border-border bg-muted/25 p-3">
            <Monitor className="mt-0.5 size-4 shrink-0 text-amber-500" />
            <div className="min-w-0">
              <div className="text-muted-foreground">显示分辨率 · 启动原因</div>
              <div className="mt-0.5 font-medium break-words">
                {hw?.displaySummary || '-'} · Boot: {hw?.sysBootReason || '-'}
              </div>
            </div>
          </div>
        </div>
      </section>

      {!device.online && (
        <div className="rounded-xl border border-amber-500/40 bg-amber-500/10 px-4 py-3 text-sm text-amber-500">
          {device.model} ({device.mac}) is currently offline.
        </div>
      )}

      {/* TAB 1: Telemetry & CPU/GPU Freq */}
      {device.online && tab === 'telemetry' && (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <section className="rounded-xl border border-border bg-card p-5 lg:col-span-2 space-y-4">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <h2 className="flex items-center gap-2 text-base font-semibold">
                  <Gauge className="size-4 text-primary" />
                  <span>实时硬件遥测与历史趋势（3s 采样）</span>
                </h2>
                <p className="text-xs text-muted-foreground mt-0.5">
                  CPU · 内存 · SoC 温度 · 网络/磁盘吞吐 · JVM GC
                </p>
              </div>
              <Button variant="outline" size="sm" onClick={refreshTelemetryAndOverview}>
                <RefreshCw className="size-3.5" />
                <span>{tr(lang, 'action.refresh')}</span>
              </Button>
            </div>

            {telemetry ? (
              <>
                <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                  <TelemetrySparkline
                    label="CPU 使用率趋势"
                    values={history.cpu}
                    unit="%"
                    strokeColor="var(--primary)"
                  />
                  <TelemetrySparkline
                    label="内存占用趋势"
                    values={history.mem}
                    unit="%"
                    strokeColor="var(--ok)"
                  />
                  <TelemetrySparkline
                    label="SoC 温度趋势"
                    values={history.temp}
                    unit="°C"
                    min={25}
                    max={95}
                    strokeColor="var(--warn)"
                  />
                </div>

                <div className="grid grid-cols-2 gap-3 lg:grid-cols-5 text-xs tabular">
                  <div className="rounded-lg border border-border bg-muted/30 p-3.5">
                    <div className="flex items-center gap-1.5 text-muted-foreground">
                      <Cpu className="size-3.5 text-primary" />
                      <span>Load Avg / GPU</span>
                    </div>
                    <div className="mt-1 mono font-semibold">{telemetry.loadAvg}</div>
                    <div className="mt-0.5 text-muted-foreground">
                      GPU: {telemetry.gpuAvailable ? `${telemetry.gpuPercent.toFixed(0)}% @ ${telemetry.gpuFreqMhz || '-'} MHz` : 'N/A'}
                    </div>
                  </div>
                  <div className="rounded-lg border border-border bg-muted/30 p-3.5">
                    <div className="flex items-center gap-1.5 text-muted-foreground">
                      <MemoryStick className="size-3.5 text-emerald-500" />
                      <span>内存 / ZRAM</span>
                    </div>
                    <div className="mt-1 font-semibold">
                      {formatBytes(telemetry.memUsedBytes)} / {formatBytes(telemetry.memTotalBytes)}
                    </div>
                    <div className="mt-0.5 text-muted-foreground">
                      ZRAM: {formatBytes(telemetry.zramUsedBytes)} / {formatBytes(telemetry.zramTotalBytes)}
                    </div>
                  </div>
                  <div className="rounded-lg border border-border bg-muted/30 p-3.5">
                    <div className="flex items-center gap-1.5 text-muted-foreground">
                      <Network className="size-3.5 text-sky-500" />
                      <span>网络吞吐 (RX / TX)</span>
                    </div>
                    <div className="mt-1 font-semibold">↓ {formatBytes(telemetry.netRxBytesPerSec)}/s</div>
                    <div className="mt-0.5 text-muted-foreground">↑ {formatBytes(telemetry.netTxBytesPerSec)}/s</div>
                  </div>
                  <div className="rounded-lg border border-border bg-muted/30 p-3.5">
                    <div className="flex items-center gap-1.5 text-muted-foreground">
                      <Database className="size-3.5 text-violet-500" />
                      <span>磁盘 I/O · /data</span>
                    </div>
                    <div className="mt-1 font-semibold">
                      R {formatBytes(telemetry.diskReadBytesPerSec)}/s · W {formatBytes(telemetry.diskWriteBytesPerSec)}/s
                    </div>
                    <div className="mt-0.5 text-muted-foreground">
                      可用 {formatBytes(telemetry.dataFreeBytes)} ({(100 - telemetry.dataUsedPercent).toFixed(1)}%)
                    </div>
                  </div>
                  <div className="rounded-lg border border-border bg-muted/30 p-3.5">
                    <div className="flex items-center gap-1.5 text-muted-foreground">
                      <Zap className="size-3.5 text-amber-500" />
                      <span>JVM / GC 状态</span>
                    </div>
                    <div className="mt-1 font-semibold">
                      {formatBytes(telemetry.gc?.jvmUsedBytes)} / {formatBytes(telemetry.gc?.jvmMaxBytes)}
                    </div>
                    <div className="mt-0.5 text-muted-foreground">
                      GC: {telemetry.gc?.gcCount ?? 0} 次 ({telemetry.gc?.gcTimeMs ?? 0} ms)
                    </div>
                  </div>
                </div>
              </>
            ) : (
              <div className="space-y-2.5">
                <div className="h-4 w-5/6 animate-pulse rounded bg-muted" />
                <div className="h-4 w-2/3 animate-pulse rounded bg-muted" />
              </div>
            )}
          </section>

          {/* CPU / GPU Governor & Frequency */}
          <section className="rounded-xl border border-border bg-card p-5 space-y-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <h2 className="text-base font-semibold">{tr(lang, 'wb.dvfsTitle')}</h2>
                <p className="text-xs text-muted-foreground mt-0.5">Governor · Min/Max</p>
              </div>
              <div className="flex gap-1.5">
                <Button
                  variant="outline"
                  size="xs"
                  disabled={actionBusy}
                  onClick={() => runAction('全局设为 performance', '/api/cpufreq/governor', { mode: 'performance' })}
                >
                  全部 Performance
                </Button>
                <Button
                  variant="outline"
                  size="xs"
                  disabled={actionBusy}
                  onClick={() => runAction('全局设为 schedutil', '/api/cpufreq/governor', { mode: 'schedutil' })}
                >
                  全部 Schedutil
                </Button>
              </div>
            </div>
            <div className="space-y-2.5">
              {(telemetry?.cpuPolicies ?? []).map((p, idx) => {
                const policyName = p.name || p.policy || `policy${idx}`;
                return (
                  <div
                    key={policyName}
                    className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border bg-muted/30 px-3 py-2.5 text-xs"
                  >
                    <div>
                      <div className="mono font-semibold">
                        {policyName}{' '}
                        {p.affectedCpus && (
                          <span className="font-normal text-muted-foreground">(CPU {p.affectedCpus})</span>
                        )}
                      </div>
                      <div className="mono tabular text-muted-foreground">
                        当前 {p.curFreq || '-'} · 范围 {p.rangeFreq || '-'}
                      </div>
                    </div>
                    <div className="flex flex-wrap items-center gap-1.5">
                      {p.availableGovernors && p.availableGovernors.length > 0 && (
                        <select
                          aria-label={`${policyName} governor`}
                          disabled={actionBusy}
                          value={p.governor || ''}
                          onChange={(e) =>
                            runAction(`切换 ${policyName} 调度器`, '/api/cpufreq/policy-gov', {
                              policy: policyName,
                              governor: e.target.value,
                            })
                          }
                        >
                          {p.availableGovernors.map((g) => (
                            <option key={g} value={g}>
                              {g}
                            </option>
                          ))}
                        </select>
                      )}
                      {p.availableFrequenciesKHz && p.availableFrequenciesKHz.length > 0 && (
                        <>
                          <select
                            aria-label={`${policyName} min freq`}
                            disabled={actionBusy}
                            value={p.minKHz ?? p.availableFrequenciesKHz[0]}
                            onChange={(e) => {
                              const nextMin = Number(e.target.value);
                              const curMax = p.maxKHz ?? p.availableFrequenciesKHz![p.availableFrequenciesKHz!.length - 1];
                              runAction(`${policyName} Min`, '/api/cpufreq/policy-freq', {
                                policy: policyName,
                                minFreq: nextMin,
                                maxFreq: Math.max(nextMin, curMax),
                              });
                            }}
                          >
                            {p.availableFrequenciesKHz.map((khz) => (
                              <option key={khz} value={khz}>
                                Min {(khz / 1000).toFixed(0)}M
                              </option>
                            ))}
                          </select>
                          <select
                            aria-label={`${policyName} max freq`}
                            disabled={actionBusy}
                            value={p.maxKHz ?? p.availableFrequenciesKHz[p.availableFrequenciesKHz.length - 1]}
                            onChange={(e) => {
                              const nextMax = Number(e.target.value);
                              const curMin = p.minKHz ?? p.availableFrequenciesKHz![0];
                              runAction(`${policyName} Max`, '/api/cpufreq/policy-freq', {
                                policy: policyName,
                                minFreq: Math.min(curMin, nextMax),
                                maxFreq: nextMax,
                              });
                            }}
                          >
                            {p.availableFrequenciesKHz.map((khz) => (
                              <option key={khz} value={khz}>
                                Max {(khz / 1000).toFixed(0)}M
                              </option>
                            ))}
                          </select>
                        </>
                      )}
                    </div>
                  </div>
                );
              })}
              {(telemetry?.gpuDevfreqs ?? []).map((g, idx) => {
                const gpuName = g.name || `gpu${idx}`;
                return (
                  <div
                    key={gpuName}
                    className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border bg-muted/30 px-3 py-2.5 text-xs"
                  >
                    <div>
                      <div className="mono font-semibold">GPU: {gpuName}</div>
                      <div className="mono tabular text-muted-foreground">
                        当前 {g.curFreq || '-'} · 范围 {g.rangeFreq || '-'}
                      </div>
                    </div>
                    <div className="flex flex-wrap items-center gap-1.5">
                      {g.availableGovernors && g.availableGovernors.length > 0 && (
                        <select
                          aria-label={`${gpuName} governor`}
                          disabled={actionBusy}
                          value={g.governor || ''}
                          onChange={(e) =>
                            runAction(`切换 GPU 调度器`, '/api/gpufreq/gov', {
                              policy: gpuName,
                              governor: e.target.value,
                            })
                          }
                        >
                          {g.availableGovernors.map((gv) => (
                            <option key={gv} value={gv}>
                              {gv}
                            </option>
                          ))}
                        </select>
                      )}
                      {g.availableFrequenciesHz && g.availableFrequenciesHz.length > 0 && (
                        <>
                          <select
                            aria-label={`${gpuName} min freq`}
                            disabled={actionBusy}
                            value={g.minHz ?? g.availableFrequenciesHz[0]}
                            onChange={(e) => {
                              const nextMin = Number(e.target.value);
                              const curMax = g.maxHz ?? g.availableFrequenciesHz![g.availableFrequenciesHz!.length - 1];
                              runAction(`GPU Min`, '/api/gpufreq/freq', {
                                policy: gpuName,
                                minFreq: nextMin,
                                maxFreq: Math.max(nextMin, curMax),
                              });
                            }}
                          >
                            {g.availableFrequenciesHz.map((hz) => (
                              <option key={hz} value={hz}>
                                Min {(hz / 1_000_000).toFixed(0)}M
                              </option>
                            ))}
                          </select>
                          <select
                            aria-label={`${gpuName} max freq`}
                            disabled={actionBusy}
                            value={g.maxHz ?? g.availableFrequenciesHz[g.availableFrequenciesHz.length - 1]}
                            onChange={(e) => {
                              const nextMax = Number(e.target.value);
                              const curMin = g.minHz ?? g.availableFrequenciesHz![0];
                              runAction(`GPU Max`, '/api/gpufreq/freq', {
                                policy: gpuName,
                                minFreq: Math.min(curMin, nextMax),
                                maxFreq: nextMax,
                              });
                            }}
                          >
                            {g.availableFrequenciesHz.map((hz) => (
                              <option key={hz} value={hz}>
                                Max {(hz / 1_000_000).toFixed(0)}M
                              </option>
                            ))}
                          </select>
                        </>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </section>

          {/* Thermal Zones */}
          <section className="rounded-xl border border-border bg-card p-5 space-y-3">
            <div>
              <h2 className="flex items-center gap-2 text-base font-semibold">
                <Flame className="size-4 text-amber-500" />
                <span>温区传感器明细 (Thermal Zones)</span>
              </h2>
              <p className="text-xs text-muted-foreground mt-0.5">
                共 {(telemetry?.thermalZones ?? []).length} 个温度采样点
              </p>
            </div>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 max-h-72 overflow-y-auto">
              {(telemetry?.thermalZones ?? []).map((z, idx) => {
                const val = z.celsius ?? z.tempC;
                const t = val ?? 0;
                const cls =
                  val === undefined
                    ? 'text-muted-foreground'
                    : t >= 75
                      ? 'text-destructive'
                      : t >= 65
                        ? 'text-amber-500'
                        : 'text-emerald-500';
                return (
                  <div
                    key={`${z.zone || z.type}-${idx}`}
                    className="flex items-center justify-between rounded-lg border border-border bg-muted/30 px-3 py-2 text-xs"
                  >
                    <span className="mono truncate pr-2" title={`${z.zone || ''} (${z.type || ''})`}>
                      {z.type || z.zone || z.name || `zone${idx}`}
                    </span>
                    <span className={`mono tabular font-semibold ${cls}`}>
                      {val !== undefined ? `${val.toFixed(1)}°C` : '-'}
                    </span>
                  </div>
                );
              })}
            </div>
          </section>
        </div>
      )}

      {/* TAB 2: OTG, Debug Switches, USB, Health Checks, Stress Test, Ping & Reboot */}
      {device.online && tab === 'controls' && (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <section className="rounded-xl border border-border bg-card p-5 space-y-4">
            <div>
              <h2 className="text-base font-semibold">远程调试、开机策略与 OTG</h2>
              <p className="text-xs text-muted-foreground mt-0.5">
                USB Config: <span className="mono">{overview?.otg?.usbConfig || '-'}</span> · Controller:{' '}
                <span className="mono">{overview?.otg?.usbController || '-'}</span>
              </p>
            </div>

            <div className="space-y-2.5">
              <div className="flex items-center justify-between gap-3 rounded-lg border border-border bg-muted/30 px-3.5 py-2.5">
                <div className="flex items-center gap-2.5">
                  <Terminal className="size-4 text-primary" />
                  <div>
                    <div className="text-sm font-medium">USB ADB 调试</div>
                    <div className="text-xs text-muted-foreground">persist.sys.usb.config / adb</div>
                  </div>
                </div>
                <Switch
                  checked={Boolean(controls?.adbEnabled)}
                  disabled={actionBusy}
                  onCheckedChange={(checked) =>
                    runAction('ADB', '/api/controls/adb', { enabled: checked })
                  }
                />
              </div>

              <div className="flex items-center justify-between gap-3 rounded-lg border border-border bg-muted/30 px-3.5 py-2.5">
                <div className="flex items-center gap-2.5">
                  <Wifi className="size-4 text-primary" />
                  <div>
                    <div className="text-sm font-medium">网络 ADB (5555)</div>
                    <div className="text-xs text-muted-foreground">service.adb.tcp.port</div>
                  </div>
                </div>
                <Switch
                  checked={Boolean(controls?.wifiAdbEnabled)}
                  disabled={actionBusy}
                  onCheckedChange={(checked) =>
                    runAction('Wi-Fi ADB', '/api/controls/wifi-adb', { enabled: checked })
                  }
                />
              </div>

              <div className="flex items-center justify-between gap-3 rounded-lg border border-border bg-muted/30 px-3.5 py-2.5">
                <div className="flex items-center gap-2.5">
                  <Sun className="size-4 text-amber-500" />
                  <div>
                    <div className="text-sm font-medium">充电时保持亮屏</div>
                    <div className="text-xs text-muted-foreground">stay_on_while_plugged_in</div>
                  </div>
                </div>
                <Switch
                  checked={Boolean(controls?.stayAwakeEnabled)}
                  disabled={actionBusy}
                  onCheckedChange={(checked) =>
                    runAction('Stay Awake', '/api/controls/stay-awake', { enabled: checked })
                  }
                />
              </div>

              <div className="flex items-center justify-between gap-3 rounded-lg border border-border bg-muted/30 px-3.5 py-2.5">
                <div className="flex items-center gap-2.5">
                  <Usb className="size-4 text-emerald-500" />
                  <div>
                    <div className="text-sm font-medium">开机自动恢复 OTG</div>
                    <div className="text-xs text-muted-foreground">BootCompletedReceiver</div>
                  </div>
                </div>
                <Switch
                  checked={Boolean(controls?.autoOtgOnBoot)}
                  disabled={actionBusy}
                  onCheckedChange={(checked) =>
                    runAction('Boot Policy', '/api/controls/boot-policy', {
                      autoOtg: checked,
                      autoAdb: Boolean(controls?.autoAdbOnBoot),
                    })
                  }
                />
              </div>
            </div>

            <div className="space-y-2 pt-1">
              <div className="text-xs font-medium text-muted-foreground">OTG 节点控制</div>
              {otgNodes.map((n) => (
                <div
                  key={n.path}
                  className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border bg-muted/20 px-3 py-2 text-xs"
                >
                  <div>
                    <div className="mono font-medium">{n.name}</div>
                    <div className="mono text-[11px] text-muted-foreground">当前: {n.mode}</div>
                  </div>
                  {n.writable ? (
                    <div className="flex gap-1">
                      {(['OTG', 'HOST', 'PERIPHERAL'] as const).map((m) => (
                        <Button
                          key={m}
                          variant={n.mode === m ? 'default' : 'outline'}
                          size="xs"
                          disabled={actionBusy}
                          onClick={() => runAction(`${n.name} -> ${m}`, '/api/otg/node', { path: n.path, mode: m })}
                        >
                          {m}
                        </Button>
                      ))}
                    </div>
                  ) : (
                    <span className="text-muted-foreground">只读</span>
                  )}
                </div>
              ))}
            </div>
          </section>

          <section className="rounded-xl border border-border bg-card p-5 space-y-4">
            <div className="flex items-start justify-between gap-2">
              <div>
                <h2 className="text-base font-semibold">{tr(lang, 'wb.stressTitle')}</h2>
                <p className="text-xs text-muted-foreground mt-0.5">
                  {realCpuCores} Cores · Max {safeMaxRamMb} MB (≥30% Headroom)
                </p>
              </div>
              <span
                className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${
                  device.stressRunning || legacyStatus?.isStressRunning
                    ? 'bg-destructive/15 text-destructive'
                    : 'bg-emerald-500/15 text-emerald-500'
                }`}
              >
                {device.stressRunning || legacyStatus?.isStressRunning
                  ? tr(lang, 'status.running')
                  : tr(lang, 'status.idle')}
              </span>
            </div>

            {(device.stressRunning || legacyStatus?.isStressRunning) && (
              <div className="rounded-lg border border-destructive/30 bg-destructive/10 p-3 text-xs space-y-1.5">
                <div className="flex items-center justify-between font-semibold text-destructive">
                  <span>
                    Elapsed:{' '}
                    {Math.floor((legacyStatus?.stressElapsedSec ?? 0) / 60)}m{' '}
                    {(legacyStatus?.stressElapsedSec ?? 0) % 60}s
                  </span>
                  <span className="mono tabular">
                    SoC:{' '}
                    {(legacyStatus?.stressTempCelsius ?? device.socTempC ?? 0).toFixed(1)}°C
                  </span>
                </div>
                {stressDuration > 0 && (
                  <div className="h-1.5 overflow-hidden rounded-full bg-background/60">
                    <div
                      className="h-full rounded-full bg-destructive transition-all"
                      style={{
                        width: `${Math.min(
                          100,
                          ((legacyStatus?.stressElapsedSec ?? 0) / stressDuration) * 100,
                        )}%`,
                      }}
                    />
                  </div>
                )}
              </div>
            )}

            <div className="grid grid-cols-2 gap-3.5">
              <label className="space-y-1.5 text-xs text-muted-foreground">
                <span>压测模式</span>
                <select
                  className="w-full"
                  value={stressType}
                  onChange={(e) => setStressType(e.target.value as typeof stressType)}
                >
                  <option value="SAT">Google SAT 混合满载 (DDR+CPU)</option>
                  <option value="CPU">CPU 浮点计算</option>
                  <option value="MEMORY">纯内存校验</option>
                </select>
              </label>
              <label className="space-y-1.5 text-xs text-muted-foreground">
                <span>持续时间</span>
                <select
                  className="w-full"
                  value={stressDuration}
                  onChange={(e) => setStressDuration(Number(e.target.value))}
                >
                  <option value={20}>20 秒</option>
                  <option value={60}>1 分钟 (60s)</option>
                  <option value={180}>3 分钟 (180s)</option>
                  <option value={600}>10 分钟 (600s)</option>
                  <option value={1800}>30 分钟 (1800s)</option>
                  <option value={3600}>1 小时 (3600s)</option>
                  <option value={0}>持续压测 (手动停止)</option>
                </select>
              </label>
              <label className="space-y-1.5 text-xs text-muted-foreground">
                <span>并发核心（上限 {realCpuCores} 核）</span>
                <select
                  className="w-full"
                  value={stressThreads}
                  onChange={(e) => setStressThreads(Number(e.target.value))}
                >
                  {cpuThreadOptions.map((n) => (
                    <option key={n} value={n}>
                      {n} 核{n === realCpuCores ? '（全核）' : ''}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1.5 text-xs text-muted-foreground">
                <span>内存容量（上限 {safeMaxRamMb} MB）</span>
                <select
                  className="w-full"
                  value={stressRamMb}
                  onChange={(e) => setStressRamMb(Number(e.target.value))}
                >
                  {ramOptions.map((mb) => (
                    <option key={mb} value={mb}>
                      {mb >= 1024 ? `${(mb / 1024).toFixed(mb % 1024 === 0 ? 0 : 1)} GB (${mb} MB)` : `${mb} MB`}
                    </option>
                  ))}
                </select>
              </label>
            </div>

            <div className="flex flex-wrap gap-2">
              {!device.stressRunning ? (
                <Button
                  disabled={actionBusy}
                  onClick={() =>
                    runAction('Start Stress', '/api/stress/start', {
                      type: stressType,
                      threads: stressThreads,
                      ramMB: stressRamMb,
                      duration: stressDuration,
                    })
                  }
                >
                  启动老化压测
                </Button>
              ) : (
                <Button
                  variant="destructive"
                  disabled={actionBusy}
                  onClick={() => runAction('Stop Stress', '/api/stress/stop')}
                >
                  立即停止压测
                </Button>
              )}
              <Button
                variant="outline"
                disabled={actionBusy}
                onClick={() => runAction('Export Aging Report', '/api/stress/export')}
              >
                导出老化报告
              </Button>
              <Button
                variant="destructive"
                disabled={actionBusy}
                onClick={() => setRebootConfirmOpen(true)}
              >
                <RotateCcw className="size-3.5" />
                <span>远程重启</span>
              </Button>
            </div>

            {/* USB Peripherals & Health Checks */}
            <div className="border-t border-border pt-3 space-y-2 text-xs">
              <div className="font-medium text-muted-foreground">
                USB 外设 ({usbDevices.length}) · 系统自检 ({healthChecks.filter((h) => h.passed).length}/{healthChecks.length})
              </div>
              <div className="flex flex-wrap gap-1.5">
                {usbDevices.map((u) => (
                  <span
                    key={`${u.name}-${u.vidPid}`}
                    className="rounded-md border border-border bg-muted/40 px-2 py-1 mono"
                  >
                    {u.classLabel} ({u.vidPid})
                  </span>
                ))}
                {healthChecks.map((h) => (
                  <span
                    key={h.title}
                    className={`inline-flex items-center gap-1 rounded-md px-2 py-1 ${
                      h.passed
                        ? 'bg-emerald-500/10 text-emerald-500'
                        : 'bg-destructive/15 text-destructive'
                    }`}
                    title={h.detail}
                  >
                    {h.passed ? (
                      <CheckCircle2 className="size-3.5" />
                    ) : (
                      <AlertTriangle className="size-3.5" />
                    )}
                    <span>{h.title}</span>
                  </span>
                ))}
              </div>
            </div>
          </section>

          <section className="rounded-xl border border-border bg-card p-5 space-y-3">
            <div>
              <h2 className="text-base font-semibold">{tr(lang, 'wb.pingTitle')}</h2>
              <p className="text-xs text-muted-foreground mt-0.5">ICMP Ping (x4)</p>
            </div>
            <form onSubmit={handlePing} className="flex flex-wrap gap-2">
              <Input
                className="min-w-44 flex-1"
                value={pingHost}
                onChange={(e) => setPingHost(e.target.value)}
                placeholder="223.5.5.5"
              />
              <Button type="submit" disabled={pingBusy}>
                {pingBusy ? '...' : tr(lang, 'wb.pingRun')}
              </Button>
            </form>
            {pingOutput && (
              <pre className="mono overflow-x-auto whitespace-pre-wrap rounded-lg border border-border bg-canvas p-3 text-xs">
                {pingOutput}
              </pre>
            )}
          </section>

          <section className="rounded-xl border border-border bg-card p-5 space-y-3">
            <div>
              <h2 className="text-base font-semibold">{tr(lang, 'wb.sysfsTitle')}</h2>
              <p className="text-xs text-muted-foreground mt-0.5">
                /api/sysfs/custom-add · /api/sysfs/write
              </p>
            </div>
            <div className="space-y-2.5">
              <Input
                value={sysfsPath}
                onChange={(e) => setSysfsPath(e.target.value)}
                placeholder="/sys/class/... or /proc/..."
                className="mono text-xs"
              />
              <div className="flex flex-wrap gap-2">
                <Input
                  value={sysfsValue}
                  onChange={(e) => setSysfsValue(e.target.value)}
                  placeholder="Value (for write)"
                  className="mono flex-1 text-xs"
                />
                <Button
                  variant="outline"
                  size="sm"
                  disabled={actionBusy || !sysfsPath.trim()}
                  onClick={() => runAction('Add Sysfs', '/api/sysfs/custom-add', { path: sysfsPath.trim() })}
                >
                  监控
                </Button>
                <Button
                  size="sm"
                  disabled={actionBusy || !sysfsPath.trim()}
                  onClick={() =>
                    runAction('Write Sysfs', '/api/sysfs/write', {
                      path: sysfsPath.trim(),
                      value: sysfsValue,
                    })
                  }
                >
                  写入
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={actionBusy || !sysfsPath.trim()}
                  onClick={() => runAction('Remove Sysfs', '/api/sysfs/custom-remove', { path: sysfsPath.trim() })}
                >
                  移除
                </Button>
              </div>
            </div>
          </section>
        </div>
      )}

      {/* TAB 3: Audio & Signal Generator */}
      {device.online && tab === 'audio' && (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <section className="rounded-xl border border-border bg-card p-5 space-y-4">
            <div className="flex items-start justify-between gap-2">
              <div>
                <h2 className="flex items-center gap-2 text-base font-semibold">
                  <Volume2 className="size-4 text-primary" />
                  <span>音频信号发生器</span>
                </h2>
                <p className="text-xs text-muted-foreground mt-0.5">
                  远程控制设备扬声器/声卡输出测试音源
                </p>
              </div>
              <span
                className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${
                  audio?.tone?.isPlaying
                    ? 'bg-emerald-500/15 text-emerald-500'
                    : 'bg-muted text-muted-foreground'
                }`}
              >
                {audio?.tone?.isPlaying ? '播放中' : '已停止'}
              </span>
            </div>

            <div className="grid grid-cols-2 gap-3 text-xs">
              <label className="space-y-1 text-muted-foreground">
                <span>波形</span>
                <select
                  className="w-full"
                  value={toneWaveform}
                  onChange={(e) => setToneWaveform(e.target.value)}
                >
                  <option value="SINE_1K">1 kHz 正弦波</option>
                  <option value="SINE_440">440 Hz 正弦波</option>
                  <option value="SWEEP">扫频信号 (Sweep)</option>
                  <option value="WHITE_NOISE">白噪声</option>
                </select>
              </label>
              <label className="space-y-1 text-muted-foreground">
                <span>声道</span>
                <select
                  className="w-full"
                  value={toneChannel}
                  onChange={(e) => setToneChannel(e.target.value)}
                >
                  <option value="STEREO">立体声 (L+R)</option>
                  <option value="LEFT">仅左声道 (L)</option>
                  <option value="RIGHT">仅右声道 (R)</option>
                </select>
              </label>
              <label className="space-y-1 text-muted-foreground">
                <span>采样率</span>
                <select
                  className="w-full"
                  value={toneRate}
                  onChange={(e) => setToneRate(Number(e.target.value))}
                >
                  <option value={44100}>44.1 kHz</option>
                  <option value={48000}>48 kHz</option>
                  <option value={96000}>96 kHz</option>
                </select>
              </label>
              <label className="space-y-1 text-muted-foreground">
                <span>增益衰减 (dBFS)</span>
                <select
                  className="w-full"
                  value={toneDb}
                  onChange={(e) => setToneDb(Number(e.target.value))}
                >
                  {[0, -3, -6, -12, -18, -24].map((db) => (
                    <option key={db} value={db}>
                      {db} dBFS
                    </option>
                  ))}
                </select>
              </label>
            </div>

            <div className="flex gap-2">
              <Button
                disabled={actionBusy}
                onClick={() =>
                  runAction(
                    'Start Tone',
                    '/api/audio/tone',
                    {
                      action: 'start',
                      waveform: toneWaveform,
                      channelMode: toneChannel,
                      sampleRateHz: toneRate,
                      attenuationDb: toneDb,
                    },
                    loadAudio,
                  )
                }
              >
                <Play className="size-3.5" />
                <span>开始输出</span>
              </Button>
              <Button
                variant="outline"
                disabled={actionBusy}
                onClick={() =>
                  runAction('Stop Tone', '/api/audio/tone', { action: 'stop' }, loadAudio)
                }
              >
                <Square className="size-3.5" />
                <span>停止</span>
              </Button>
            </div>
          </section>

          <section className="rounded-xl border border-border bg-card p-5 space-y-3">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="text-base font-semibold">Android 音频输出设备</h2>
                <p className="text-xs text-muted-foreground mt-0.5">
                  已检测到 {(audio?.outputDevices ?? []).length} 个音频路由设备
                </p>
              </div>
              <Button variant="outline" size="icon-sm" onClick={loadAudio}>
                <RefreshCw className="size-3.5" />
              </Button>
            </div>
            <div className="space-y-2 text-xs">
              {(audio?.outputDevices ?? []).map((od) => (
                <div
                  key={`${od.id}-${od.typeLabel}`}
                  className="flex items-center justify-between rounded-lg border border-border bg-muted/30 px-3 py-2.5"
                >
                  <div>
                    <div className="font-semibold">{od.typeLabel || 'Audio Device'}</div>
                    <div className="mono text-muted-foreground">{od.productName || '-'}</div>
                  </div>
                  <div className="text-right mono text-muted-foreground">
                    <div>{od.sampleRates || '-'}</div>
                    <div>{od.encodings || '-'}</div>
                  </div>
                </div>
              ))}
              {(audio?.outputDevices ?? []).length === 0 && (
                <div className="text-muted-foreground">暂无输出设备信息</div>
              )}
            </div>
          </section>

          <section className="rounded-xl border border-border bg-card p-5 lg:col-span-2 space-y-3">
            <div>
              <h2 className="text-base font-semibold">ALSA 声卡与 PCM 流节点 (/proc/asound)</h2>
              <p className="text-xs text-muted-foreground mt-0.5">
                共 {(audio?.cards ?? []).length} 张声卡，实时状态与采样参数
              </p>
            </div>
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3 text-xs">
              {(audio?.cards ?? []).map((c) => (
                <div key={c.index} className="rounded-lg border border-border bg-muted/30 p-3.5 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="font-semibold">
                      Card {c.index}: {c.name || c.id}
                    </span>
                    <span
                      className={`rounded-full px-2 py-0.5 text-[11px] ${
                        c.active
                          ? 'bg-emerald-500/15 text-emerald-500'
                          : 'bg-muted text-muted-foreground'
                      }`}
                    >
                      {c.active ? '活跃' : '空闲'}
                    </span>
                  </div>
                  <div className="space-y-1">
                    {c.streams?.map((s) => (
                      <div key={s.pcmId} className="flex justify-between mono text-[11px] text-muted-foreground">
                        <span>
                          {s.pcmId} · {s.streamName}
                        </span>
                        <span>
                          {s.statusText} {s.rate !== '-' ? `(${s.rate} ${s.format})` : ''}
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </section>
        </div>
      )}

      {/* TAB 4: SysProps Editor & AOT */}
      {device.online && tab === 'sysprops' && (
        <div className="space-y-4">
          {aot && (
            <section className="rounded-xl border border-border bg-card p-5 flex flex-wrap items-center justify-between gap-3">
              <div>
                <h2 className="text-base font-semibold">ART AOT 编译优化状态</h2>
                <p className="text-xs text-muted-foreground mt-0.5">
                  状态：{aot.statusMessage}
                  {aot.apps ? ` · 已扫描 ${aot.apps.length} 个应用` : ''}
                </p>
              </div>
              <Button
                variant="outline"
                size="sm"
                disabled={actionBusy || aot.isRunning}
                onClick={() =>
                  runAction('AOT Batch Speed', '/api/aot/compile', { mode: 'BATCH_SPEED' }, loadSysPropsAndAot)
                }
              >
                {aot.isRunning ? '编译进行中...' : '一键优化未编译应用 (speed)'}
              </Button>
            </section>
          )}

          <section className="rounded-xl border border-border bg-card p-5 space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h2 className="text-base font-semibold">系统属性浏览器与修改 (getprop / setprop)</h2>
                <p className="text-xs text-muted-foreground mt-0.5">
                  共 {sysProps.length} 条属性（展示前 {filteredProps.length} 条匹配）
                </p>
              </div>
              <div className="flex items-center gap-2">
                <Input
                  type="search"
                  className="w-64"
                  placeholder="过滤 key 或 value..."
                  value={propQuery}
                  onChange={(e) => setPropQuery(e.target.value)}
                />
                <Button variant="outline" size="sm" onClick={loadSysPropsAndAot}>
                  <RefreshCw className="size-3.5" />
                  <span>{tr(lang, 'action.refresh')}</span>
                </Button>
              </div>
            </div>
            <div className="table-box" style={{ maxBlockSize: '54vh' }}>
              <table className="fleet-table">
                <thead>
                  <tr>
                    <th>Key</th>
                    <th>Value</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredProps.map((p) => (
                    <tr key={p.key}>
                      <td className="mono font-medium">{p.key}</td>
                      <td className="mono text-primary break-all">
                        {editingPropKey === p.key ? (
                          <Input
                            value={editingPropVal}
                            onChange={(e) => setEditingPropVal(e.target.value)}
                            className="h-8 text-xs"
                          />
                        ) : (
                          p.value || '-'
                        )}
                      </td>
                      <td>
                        {p.writable ? (
                          editingPropKey === p.key ? (
                            <div className="flex gap-1.5">
                              <Button size="xs" onClick={() => handleSaveProp(p.key)}>
                                保存
                              </Button>
                              <Button variant="ghost" size="xs" onClick={() => setEditingPropKey(null)}>
                                取消
                              </Button>
                            </div>
                          ) : (
                            <Button
                              variant="outline"
                              size="xs"
                              onClick={() => {
                                setEditingPropKey(p.key);
                                setEditingPropVal(p.value);
                              }}
                            >
                              编辑
                            </Button>
                          )
                        ) : (
                          <span className="text-xs text-muted-foreground">只读</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </div>
      )}

      {/* TAB 5: Reports, Snapshot Download & Crash Diagnostics */}
      {device.online && tab === 'diagnostics' && (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <section className="rounded-xl border border-border bg-card p-5 space-y-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <h2 className="text-base font-semibold">老化报告、Bugreport 与快照</h2>
                <p className="text-xs text-muted-foreground mt-0.5">共 {reports.length} 份报告</p>
              </div>
              <div className="flex flex-wrap gap-2">
                <Button variant="outline" size="sm" asChild>
                  <a
                    href={`/proxy/${encodeURIComponent(device.mac)}/api/snapshot?token=${encodeURIComponent(token)}`}
                    download
                  >
                    <Download className="size-3.5" />
                    <span>下载诊断快照</span>
                  </a>
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={actionBusy}
                  onClick={() => runAction('Bugreport', '/api/bugreport/start')}
                >
                  生成 Bugreport
                </Button>
                <Button variant="outline" size="icon-sm" onClick={loadDiagnostics}>
                  <RefreshCw className="size-3.5" />
                </Button>
              </div>
            </div>
            <div className="space-y-2">
              {reports.map((r) => (
                <div
                  key={r.fileName}
                  className="flex items-center justify-between gap-3 rounded-lg border border-border bg-muted/30 px-3 py-2.5 text-xs"
                >
                  <div>
                    <div className="mono font-medium">{r.fileName}</div>
                    <div className="text-muted-foreground">
                      {r.summary} · {r.formattedTime}
                    </div>
                  </div>
                  {!r.isZip ? (
                    <Button variant="outline" size="xs" onClick={() => openReportContent(r.fileName, false)}>
                      查看
                    </Button>
                  ) : (
                    <span className="rounded bg-muted px-2 py-0.5 text-[11px] text-muted-foreground">ZIP</span>
                  )}
                </div>
              ))}
              {tabLoading && reports.length === 0 ? (
                <div className="space-y-2">
                  <div className="h-10 animate-pulse rounded-lg bg-muted" />
                  <div className="h-10 animate-pulse rounded-lg bg-muted" />
                </div>
              ) : (
                reports.length === 0 && (
                  <div className="text-xs text-muted-foreground">暂无报告文件</div>
                )
              )}
            </div>
          </section>

          <section className="rounded-xl border border-border bg-card p-5 space-y-3">
            <div className="flex items-center justify-between gap-2">
              <div>
                <h2 className="text-base font-semibold">启动原因与 Tombstone 崩溃日志</h2>
                <p className="text-xs text-muted-foreground mt-0.5">
                  最近启动：{crash?.bootReason?.sysBootReason || '-'}
                  {crash?.bootReason?.isAbnormal ? '（异常重启）' : ''}
                </p>
              </div>
              <Button
                variant="outline"
                size="sm"
                disabled={actionBusy}
                onClick={() => runAction('Export Crash', '/api/crash/export')}
              >
                导出诊断
              </Button>
            </div>
            <div className="space-y-2">
              {(crash?.tombstones ?? []).map((t) => (
                <div
                  key={t.fileName}
                  className="flex items-center justify-between gap-3 rounded-lg border border-border bg-muted/30 px-3 py-2.5 text-xs"
                >
                  <div>
                    <div className="mono font-medium">
                      {t.fileName} · {t.processName}
                    </div>
                    <div className="text-muted-foreground">
                      {t.signalText} · {t.timestampText}
                    </div>
                  </div>
                  <Button variant="outline" size="xs" onClick={() => openReportContent(t.fileName, true)}>
                    日志
                  </Button>
                </div>
              ))}
              {(crash?.tombstones ?? []).length === 0 && (
                <div className="text-xs text-muted-foreground">未检测到 Tombstone 记录</div>
              )}
            </div>
          </section>
        </div>
      )}

      <dialog
        ref={viewerDialogRef}
        className="viewer-dialog"
        aria-labelledby="viewer-title"
        onClose={() => {
          setViewerTitle('');
          setViewerText('');
        }}
      >
        <div className="flex items-center justify-between border-b border-border px-5 py-3.5">
          <h2 id="viewer-title" className="mono text-sm font-semibold">
            {viewerTitle}
          </h2>
          <Button
            variant="ghost"
            size="icon-sm"
            autoFocus
            onClick={() => setViewerTitle('')}
            aria-label="Close"
          >
            <X className="size-4" />
          </Button>
        </div>
        <pre className="mono flex-1 overflow-auto bg-canvas p-5 text-xs leading-relaxed whitespace-pre-wrap break-words">
          {viewerText}
        </pre>
      </dialog>

      <AlertDialog open={rebootConfirmOpen} onOpenChange={setRebootConfirmOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认远程重启设备</AlertDialogTitle>
            <AlertDialogDescription>
              确定要重启{' '}
              <span className="mono font-semibold text-foreground">
                {device.brand} {device.model} ({device.mac})
              </span>
              ？
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{tr(lang, 'action.cancel')}</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => runAction('Reboot', '/api/device/reboot')}
            >
              {tr(lang, 'action.confirm')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
