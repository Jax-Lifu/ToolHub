import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import {
  Activity,
  Cpu,
  FileWarning,
  Flame,
  Network,
  Play,
  RotateCcw,
  Sliders,
  Square,
  Terminal,
  Usb,
  Volume2,
  Zap,
} from 'lucide-react';
import { LangCode, tr } from '../i18n';
import { DeviceItem } from '../types';
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

interface BatchOpsViewProps {
  lang: LangCode;
  devices: DeviceItem[];
  token: string;
  onToast: (msg: string) => void;
  onRefreshFleet: () => void;
}

interface BatchResult {
  mac: string;
  model: string;
  ok: boolean;
  elapsedMs: number;
  detail: string;
}

export function BatchOpsView({ lang, devices, token, onToast, onRefreshFleet }: BatchOpsViewProps) {
  const onlineDevices = useMemo(() => devices.filter((d) => d.online), [devices]);
  const [selectedMacs, setSelectedMacs] = useState<string[]>([]);
  const hasInitializedRef = useRef(false);
  const [busy, setBusy] = useState(false);
  const [lastActionLabel, setLastActionLabel] = useState('');
  const [pingHost, setPingHost] = useState('223.5.5.5');
  const [propKey, setPropKey] = useState('');
  const [propValue, setPropValue] = useState('');
  const [stressType, setStressType] = useState<'SAT' | 'CPU' | 'MEMORY'>('SAT');
  const [stressDuration, setStressDuration] = useState(60);
  const [stressThreads, setStressThreads] = useState(4);
  const [stressRamMb, setStressRamMb] = useState(256);
  const [results, setResults] = useState<BatchResult[]>([]);
  const [rebootOpen, setRebootOpen] = useState(false);

  useEffect(() => {
    if (!hasInitializedRef.current && onlineDevices.length > 0) {
      hasInitializedRef.current = true;
      setSelectedMacs(onlineDevices.map((d) => d.mac));
    }
  }, [onlineDevices]);

  const effectiveSelected = useMemo(() => {
    const onlineSet = new Set(onlineDevices.map((d) => d.mac));
    return selectedMacs.filter((m) => onlineSet.has(m));
  }, [selectedMacs, onlineDevices]);

  const toggleMac = (mac: string) => {
    setSelectedMacs((prev) =>
      prev.includes(mac) ? prev.filter((m) => m !== mac) : [...prev, mac],
    );
  };

  const invertSelection = () => {
    const cur = new Set(effectiveSelected);
    setSelectedMacs(onlineDevices.filter((d) => !cur.has(d.mac)).map((d) => d.mac));
  };

  const runBatch = async (label: string, path: string, body: unknown) => {
    if (effectiveSelected.length === 0) {
      onToast('请先选择至少一台在线设备');
      return;
    }
    setBusy(true);
    setLastActionLabel(label);
    setResults([]);
    const targets = onlineDevices.filter((d) => effectiveSelected.includes(d.mac));
    const out = await Promise.all(
      targets.map(async (d): Promise<BatchResult> => {
        const started = performance.now();
        try {
          const res = await fetch(`/proxy/${encodeURIComponent(d.mac)}${path}`, {
            method: 'POST',
            headers: {
              Authorization: `Bearer ${token}`,
              'Content-Type': 'application/json',
            },
            body: JSON.stringify(body),
          });
          const data = await res.json().catch(() => ({ ok: res.ok, message: `HTTP ${res.status}` }));
          return {
            mac: d.mac,
            model: `${d.brand} ${d.model}`,
            ok: Boolean(data.ok),
            elapsedMs: Math.round(performance.now() - started),
            detail: String(data.output || data.message || (res.ok ? 'OK' : `HTTP ${res.status}`)),
          };
        } catch (e) {
          return {
            mac: d.mac,
            model: `${d.brand} ${d.model}`,
            ok: false,
            elapsedMs: Math.round(performance.now() - started),
            detail: e instanceof Error ? e.message : 'Request failed',
          };
        }
      }),
    );
    setResults(out);
    setBusy(false);
    const okCount = out.filter((r) => r.ok).length;
    onToast(`${label}: ${okCount}/${out.length} OK`);
    onRefreshFleet();
  };

  const handleBatchPing = (e: FormEvent) => {
    e.preventDefault();
    if (!pingHost.trim()) return;
    runBatch(`Ping ${pingHost.trim()}`, '/api/device/ping', { host: pingHost.trim() });
  };

  const handleBatchSetProp = (e: FormEvent) => {
    e.preventDefault();
    if (!propKey.trim()) return;
    runBatch(`setprop ${propKey.trim()}`, '/api/sysprops/set', {
      key: propKey.trim(),
      value: propValue,
    });
  };

  return (
    <div className="space-y-4">
      {/* Device Target Picker */}
      <section className="rounded-xl border border-border bg-card p-5 space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-base font-semibold">{tr(lang, 'batch.selectTitle')}</h2>
            <p className="text-xs text-muted-foreground mt-0.5">
              {effectiveSelected.length} / {onlineDevices.length}
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setSelectedMacs(onlineDevices.map((d) => d.mac))}
            >
              {tr(lang, 'batch.selectAll')}
            </Button>
            <Button variant="outline" size="sm" onClick={invertSelection}>
              {tr(lang, 'batch.invert')}
            </Button>
            <Button variant="outline" size="sm" onClick={() => setSelectedMacs([])}>
              {tr(lang, 'batch.clear')}
            </Button>
          </div>
        </div>

        <div className="flex flex-wrap gap-2">
          {onlineDevices.map((d) => {
            const checked = effectiveSelected.includes(d.mac);
            return (
              <label
                key={d.mac}
                className={`inline-flex items-center gap-2.5 rounded-lg border px-3 py-2 text-xs cursor-pointer transition-colors ${
                  checked
                    ? 'border-primary bg-primary/10 text-foreground'
                    : 'border-border bg-muted/40 text-muted-foreground hover:text-foreground'
                }`}
              >
                <input type="checkbox" checked={checked} onChange={() => toggleMac(d.mac)} />
                <span className="font-semibold">
                  {d.brand} {d.model}
                </span>
                <span className="mono text-muted-foreground">
                  {d.mac} · {d.lanIp}
                </span>
                {d.socTempC !== null && (
                  <span
                    className={`mono tabular font-semibold ${
                      d.socTempC >= 75
                        ? 'text-destructive'
                        : d.socTempC >= 65
                          ? 'text-amber-500'
                          : 'text-emerald-500'
                    }`}
                  >
                    {d.socTempC.toFixed(1)}°C
                  </span>
                )}
                {d.stressRunning && (
                  <span className="rounded-full bg-destructive/15 px-1.5 py-0.5 text-[10px] font-semibold text-destructive">
                    压测中
                  </span>
                )}
              </label>
            );
          })}
          {onlineDevices.length === 0 && (
            <div className="text-xs text-muted-foreground">当前无在线设备</div>
          )}
        </div>
      </section>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {/* Batch Debug & OTG */}
        <section className="rounded-xl border border-border bg-card p-5 space-y-4">
          <div className="flex items-center gap-2">
            <Sliders className="size-4 text-primary" />
            <div>
              <h2 className="text-base font-semibold">{tr(lang, 'batch.opsTitle')}</h2>
              <p className="text-xs text-muted-foreground">{effectiveSelected.length} 台目标设备</p>
            </div>
          </div>

          <div className="space-y-3 text-xs">
            <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border bg-muted/25 px-3 py-2">
              <span className="inline-flex items-center gap-1.5 font-medium">
                <Terminal className="size-3.5 text-primary" /> USB ADB
              </span>
              <div className="flex gap-1.5">
                <Button size="xs" disabled={busy} onClick={() => runBatch('批量开启 ADB', '/api/controls/adb', { enabled: true })}>
                  开启
                </Button>
                <Button variant="outline" size="xs" disabled={busy} onClick={() => runBatch('批量关闭 ADB', '/api/controls/adb', { enabled: false })}>
                  关闭
                </Button>
              </div>
            </div>

            <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border bg-muted/25 px-3 py-2">
              <span className="inline-flex items-center gap-1.5 font-medium">
                <Network className="size-3.5 text-primary" /> 网络 ADB (5555)
              </span>
              <div className="flex gap-1.5">
                <Button size="xs" disabled={busy} onClick={() => runBatch('批量开启网络 ADB', '/api/controls/wifi-adb', { enabled: true })}>
                  开启
                </Button>
                <Button variant="outline" size="xs" disabled={busy} onClick={() => runBatch('批量关闭网络 ADB', '/api/controls/wifi-adb', { enabled: false })}>
                  关闭
                </Button>
              </div>
            </div>

            <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border bg-muted/25 px-3 py-2">
              <span className="inline-flex items-center gap-1.5 font-medium">
                <Zap className="size-3.5 text-amber-500" /> 充电常亮
              </span>
              <div className="flex gap-1.5">
                <Button size="xs" disabled={busy} onClick={() => runBatch('批量开启常亮', '/api/controls/stay-awake', { enabled: true })}>
                  开启
                </Button>
                <Button variant="outline" size="xs" disabled={busy} onClick={() => runBatch('批量关闭常亮', '/api/controls/stay-awake', { enabled: false })}>
                  关闭
                </Button>
              </div>
            </div>

            <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border bg-muted/25 px-3 py-2">
              <span className="inline-flex items-center gap-1.5 font-medium">
                <Usb className="size-3.5 text-emerald-500" /> OTG 模式
              </span>
              <div className="flex gap-1.5">
                {(['OTG', 'HOST', 'PERIPHERAL'] as const).map((m) => (
                  <Button
                    key={m}
                    variant="outline"
                    size="xs"
                    disabled={busy}
                    onClick={() => runBatch(`批量 OTG -> ${m}`, '/api/otg/mode', { mode: m })}
                  >
                    {m}
                  </Button>
                ))}
              </div>
            </div>

            <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border bg-muted/25 px-3 py-2">
              <span className="inline-flex items-center gap-1.5 font-medium">
                <Cpu className="size-3.5 text-violet-500" /> CPU Governor
              </span>
              <div className="flex gap-1.5">
                {(['performance', 'schedutil', 'powersave'] as const).map((g) => (
                  <Button
                    key={g}
                    variant="outline"
                    size="xs"
                    disabled={busy}
                    onClick={() => runBatch(`批量 CPU -> ${g}`, '/api/cpufreq/governor', { mode: g })}
                  >
                    {g}
                  </Button>
                ))}
              </div>
            </div>
          </div>
        </section>

        {/* Batch Stress Test */}
        <section className="rounded-xl border border-border bg-card p-5 space-y-4">
          <div className="flex items-center gap-2">
            <Flame className="size-4 text-destructive" />
            <div>
              <h2 className="text-base font-semibold">{tr(lang, 'batch.stressTitle')}</h2>
              <p className="text-xs text-muted-foreground">SAT / CPU / Memory</p>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3 text-xs">
            <label className="space-y-1 text-muted-foreground">
              <span>压测模式</span>
              <select
                className="w-full"
                value={stressType}
                onChange={(e) => setStressType(e.target.value as typeof stressType)}
              >
                <option value="SAT">Google SAT (DDR+CPU)</option>
                <option value="CPU">CPU 浮点计算</option>
                <option value="MEMORY">纯内存校验</option>
              </select>
            </label>
            <label className="space-y-1 text-muted-foreground">
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
                <option value={0}>持续压测</option>
              </select>
            </label>
            <label className="space-y-1 text-muted-foreground">
              <span>并发核心</span>
              <select
                className="w-full"
                value={stressThreads}
                onChange={(e) => setStressThreads(Number(e.target.value))}
              >
                {[1, 2, 4].map((n) => (
                  <option key={n} value={n}>
                    {n} 核
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-muted-foreground">
              <span>内存容量</span>
              <select
                className="w-full"
                value={stressRamMb}
                onChange={(e) => setStressRamMb(Number(e.target.value))}
              >
                {[64, 128, 256, 512].map((mb) => (
                  <option key={mb} value={mb}>
                    {mb} MB
                  </option>
                ))}
              </select>
            </label>
          </div>

          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              disabled={busy}
              onClick={() =>
                runBatch(tr(lang, 'batch.stressStart'), '/api/stress/start', {
                  type: stressType,
                  threads: stressThreads,
                  ramMB: stressRamMb,
                  duration: stressDuration,
                })
              }
            >
              <Play className="size-3.5" />
              <span>{tr(lang, 'batch.stressStart')}</span>
            </Button>
            <Button
              variant="destructive"
              size="sm"
              disabled={busy}
              onClick={() => runBatch(tr(lang, 'batch.stressStop'), '/api/stress/stop', {})}
            >
              <Square className="size-3.5" />
              <span>{tr(lang, 'batch.stressStop')}</span>
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={busy}
              onClick={() => runBatch(tr(lang, 'wb.stressExport'), '/api/stress/export', {})}
            >
              {tr(lang, 'wb.stressExport')}
            </Button>
            <Button
              variant="destructive"
              size="sm"
              disabled={busy || effectiveSelected.length === 0}
              onClick={() => setRebootOpen(true)}
            >
              <RotateCcw className="size-3.5" />
              <span>{tr(lang, 'batch.reboot')}</span>
            </Button>
          </div>
        </section>

        {/* Batch Diagnostics, AOT & Audio Tone */}
        <section className="rounded-xl border border-border bg-card p-5 space-y-3">
          <div className="flex items-center gap-2">
            <FileWarning className="size-4 text-primary" />
            <div>
              <h2 className="text-base font-semibold">{tr(lang, 'batch.diagTitle')}</h2>
              <p className="text-xs text-muted-foreground">Bugreport · Crash · AOT · 1kHz</p>
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={busy}
              onClick={() => runBatch(tr(lang, 'batch.bugreport'), '/api/bugreport/start', {})}
            >
              {tr(lang, 'batch.bugreport')}
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={busy}
              onClick={() => runBatch(tr(lang, 'batch.crash'), '/api/crash/export', {})}
            >
              {tr(lang, 'batch.crash')}
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={busy}
              onClick={() => runBatch(tr(lang, 'batch.aot'), '/api/aot/compile', { mode: 'BATCH_SPEED' })}
            >
              <Activity className="size-3.5" />
              <span>{tr(lang, 'batch.aot')}</span>
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={busy}
              onClick={() =>
                runBatch(tr(lang, 'batch.toneOn'), '/api/audio/tone', {
                  action: 'start',
                  waveform: 'SINE_1K',
                  channelMode: 'STEREO',
                  sampleRateHz: 48000,
                  attenuationDb: -6,
                })
              }
            >
              <Volume2 className="size-3.5" />
              <span>{tr(lang, 'batch.toneOn')}</span>
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={busy}
              onClick={() => runBatch(tr(lang, 'batch.toneOff'), '/api/audio/tone', { action: 'stop' })}
            >
              {tr(lang, 'batch.toneOff')}
            </Button>
          </div>
        </section>

        {/* Batch Ping & Batch setprop */}
        <section className="rounded-xl border border-border bg-card p-5 space-y-4">
          <div className="space-y-2">
            <h2 className="text-base font-semibold">{tr(lang, 'batch.pingTitle')}</h2>
            <form onSubmit={handleBatchPing} className="flex flex-wrap gap-2">
              <Input
                className="min-w-44 flex-1"
                value={pingHost}
                onChange={(e) => setPingHost(e.target.value)}
                placeholder="223.5.5.5"
              />
              <Button type="submit" size="sm" disabled={busy}>
                {busy ? '...' : tr(lang, 'wb.pingRun')}
              </Button>
            </form>
          </div>

          <div className="border-t border-border pt-3 space-y-2">
            <div className="text-xs font-semibold text-muted-foreground">{tr(lang, 'batch.setprop')}</div>
            <form onSubmit={handleBatchSetProp} className="flex flex-wrap gap-2">
              <Input
                className="min-w-36 flex-1 mono text-xs"
                value={propKey}
                onChange={(e) => setPropKey(e.target.value)}
                placeholder="Key (e.g. persist.sys.foo)"
              />
              <Input
                className="min-w-28 flex-1 mono text-xs"
                value={propValue}
                onChange={(e) => setPropValue(e.target.value)}
                placeholder="Value"
              />
              <Button type="submit" size="sm" variant="outline" disabled={busy || !propKey.trim()}>
                {tr(lang, 'batch.apply')}
              </Button>
            </form>
          </div>
        </section>
      </div>

      {/* Results */}
      {results.length > 0 && (
        <section className="rounded-xl border border-border bg-card p-5 space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <h2 className="text-base font-semibold">
                {tr(lang, 'batch.resultsTitle')} {lastActionLabel ? `· ${lastActionLabel}` : ''}
              </h2>
              <p className="text-xs text-muted-foreground">
                成功 {results.filter((r) => r.ok).length} / {results.length} 台
              </p>
            </div>
            <Button variant="ghost" size="xs" onClick={() => setResults([])}>
              清空记录
            </Button>
          </div>
          <div className="space-y-2">
            {results.map((r) => (
              <div
                key={r.mac}
                className="flex items-start justify-between gap-3 rounded-lg border border-border bg-muted/30 p-3 text-xs"
              >
                <div className="min-w-0 flex-1">
                  <div className="font-semibold">
                    {r.model}{' '}
                    <span className="mono text-muted-foreground">
                      ({r.mac}) · {r.elapsedMs}ms
                    </span>
                  </div>
                  <pre className="mono mt-1.5 whitespace-pre-wrap text-muted-foreground">{r.detail}</pre>
                </div>
                <span
                  className={`rounded-full px-2.5 py-0.5 font-medium ${
                    r.ok ? 'bg-emerald-500/15 text-emerald-500' : 'bg-destructive/15 text-destructive'
                  }`}
                >
                  {r.ok ? 'OK' : 'FAIL'}
                </span>
              </div>
            ))}
          </div>
        </section>
      )}

      <AlertDialog open={rebootOpen} onOpenChange={setRebootOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认批量重启设备</AlertDialogTitle>
            <AlertDialogDescription>
              确定同时重启已选中的 {effectiveSelected.length} 台在线设备？重启完成前设备将短暂离线。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{tr(lang, 'action.cancel')}</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => runBatch('批量重启', '/api/device/reboot', {})}
            >
              {tr(lang, 'action.confirm')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
