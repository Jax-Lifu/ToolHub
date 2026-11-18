import { useCallback, useEffect, useRef, useState } from 'react';
import { Check, Copy, RefreshCw, Terminal, X } from 'lucide-react';
import { DeviceItem, DeviceOverview } from '../types';
import { copyTextToClipboard } from '../utils/clipboard';
import { Button } from './ui/button';

interface DeviceDetailDrawerProps {
  device: DeviceItem | null;
  token: string;
  onClose: () => void;
  onOpenWorkbench: (mac: string) => void;
  onCopyAdb: (cmd: string) => void;
}

function InlineCopyBtn({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false);
  const timerRef = useRef<number | null>(null);

  const handleCopy = async () => {
    const ok = await copyTextToClipboard(value);
    if (!ok) return;
    setCopied(true);
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(() => setCopied(false), 1800);
  };

  return (
    <button
      type="button"
      onClick={handleCopy}
      aria-label={`复制${label}`}
      title={`复制${label}`}
      className="inline-flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground cursor-pointer"
    >
      {copied ? <Check className="size-3.5 text-emerald-500" /> : <Copy className="size-3.5" />}
    </button>
  );
}

export function DeviceDetailDrawer({
  device,
  token,
  onClose,
  onOpenWorkbench,
  onCopyAdb,
}: DeviceDetailDrawerProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [overview, setOverview] = useState<DeviceOverview | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState('');

  const loadOverview = useCallback(async () => {
    if (!device || !device.online) {
      setOverview(null);
      setErr('');
      return;
    }
    setLoading(true);
    setErr('');
    try {
      const res = await fetch(`/proxy/${encodeURIComponent(device.mac)}/api/overview`, {
        headers: { Authorization: `Bearer ${token}` },
        cache: 'no-store',
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setOverview(await res.json());
    } catch (e) {
      setErr(e instanceof Error ? e.message : '拉取设备详情失败');
    } finally {
      setLoading(false);
    }
  }, [device, token]);

  useEffect(() => {
    loadOverview();
  }, [loadOverview]);

  useEffect(() => {
    const dlg = dialogRef.current;
    if (!dlg) return;
    if (device) {
      if (!dlg.open) dlg.showModal();
    } else if (dlg.open) {
      dlg.close();
    }
  }, [device]);

  useEffect(() => {
    const dlg = dialogRef.current;
    if (!dlg) return;
    const handleNativeClose = () => onClose();
    dlg.addEventListener('close', handleNativeClose);

    if (!('closedBy' in HTMLDialogElement.prototype)) {
      const outside = (e: PointerEvent) => {
        const r = dlg.getBoundingClientRect();
        return e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom;
      };
      let pressedOutside = false;
      const onDown = (e: PointerEvent) => {
        pressedOutside = outside(e);
      };
      const onUp = (e: PointerEvent) => {
        if (pressedOutside && outside(e) && dlg.open) dlg.close();
      };
      dlg.addEventListener('pointerdown', onDown);
      dlg.addEventListener('pointerup', onUp);
      return () => {
        dlg.removeEventListener('close', handleNativeClose);
        dlg.removeEventListener('pointerdown', onDown);
        dlg.removeEventListener('pointerup', onUp);
      };
    }

    return () => dlg.removeEventListener('close', handleNativeClose);
  }, [onClose]);

  const canOpenWorkbench = Boolean(
    device && device.online && (device.wsConnected || device.tunnelConnected || device.lanIp),
  );
  const hw = overview?.device;
  const otg = overview?.otg;
  const controls = overview?.controls;
  const checks = overview?.healthChecks ?? [];

  return (
    <dialog
      ref={dialogRef}
      className="drawer-dialog"
      aria-labelledby="drawer-title"
      {...({ closedby: 'any' } as Record<string, string>)}
    >
      {device && (
        <div className="flex h-full w-full flex-col">
          <div className="flex items-start justify-between gap-3 border-b border-border px-5 py-4">
            <div>
              <div className="flex flex-wrap items-center gap-2">
                <h2 id="drawer-title" className="text-base font-semibold">
                  {device.brand} {device.model || '未知设备'}
                </h2>
                <span
                  className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${
                    device.online
                      ? 'bg-emerald-500/15 text-emerald-500'
                      : 'bg-muted text-muted-foreground'
                  }`}
                >
                  {device.online ? '在线' : `离线 (${device.lastSeenAgoSec}s)`}
                </span>
                {device.wsConnected && (
                  <span className="inline-flex items-center rounded-full bg-primary/15 px-2 py-0.5 text-xs font-medium text-primary">
                    WS 隧道
                  </span>
                )}
              </div>
              <div className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground mono">
                <span>{device.mac}</span>
                <InlineCopyBtn value={device.mac} label="MAC 地址" />
                <span>· SN: {device.serial || '-'}</span>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={loadOverview}
                disabled={loading || !device.online}
              >
                <RefreshCw className={`size-3.5 ${loading ? 'animate-spin' : ''}`} />
                <span>刷新</span>
              </Button>
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={() => dialogRef.current?.close()}
                aria-label="关闭设备详情"
                autoFocus
              >
                <X className="size-4" />
              </Button>
            </div>
          </div>

          <div className="flex-1 space-y-4 overflow-y-auto p-5">
            <div className="flex flex-wrap gap-2">
              <Button
                disabled={!canOpenWorkbench}
                onClick={() => onOpenWorkbench(device.mac)}
              >
                进入设备工作台
              </Button>
              <Button
                variant="outline"
                disabled={!device.online || !device.tunnelConnected}
                onClick={() => onCopyAdb(device.adbTunnelCmd)}
              >
                <Terminal className="size-4" />
                <span>复制远程 ADB</span>
              </Button>
            </div>

            <section className="rounded-xl border border-border bg-muted/40 p-4 space-y-3">
              <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                链路与网络
              </div>
              <div className="grid grid-cols-2 gap-3 text-sm">
                <div>
                  <div className="text-xs text-muted-foreground">局域网 IP</div>
                  <div className="mt-0.5 flex items-center gap-1 mono text-xs font-medium">
                    <span>{device.lanIp || '-'}</span>
                    {device.lanIp && <InlineCopyBtn value={device.lanIp} label="局域网 IP" />}
                  </div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">公网出口 IP</div>
                  <div className="mt-0.5 mono text-xs font-medium">{device.publicIp || '-'}</div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">SoC 温度</div>
                  <div className="mt-0.5 mono tabular text-xs font-semibold">
                    {device.socTempC !== null ? `${device.socTempC.toFixed(1)} °C` : '-'}
                  </div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">运行时长</div>
                  <div className="mt-0.5 mono tabular text-xs">{hw?.uptime || device.uptime || '-'}</div>
                </div>
              </div>
              {device.tunnelConnected && (
                <div className="flex items-center justify-between gap-2 rounded-lg border border-border bg-background px-3 py-2 text-xs mono text-primary break-all">
                  <span>{device.adbTunnelCmd}</span>
                  <InlineCopyBtn value={device.adbTunnelCmd} label="远程 ADB 命令" />
                </div>
              )}
            </section>

            {!device.online && (
              <div className="rounded-lg border border-border bg-muted p-3 text-xs text-muted-foreground">
                设备当前离线，无法拉取实时硬件快照。
              </div>
            )}

            {err && (
              <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 p-3 text-xs text-amber-500">
                拉取设备概览失败（{err}），可点击右上角刷新重试。
              </div>
            )}

            {loading && !hw && (
              <section className="rounded-xl border border-border bg-muted/40 p-4 space-y-2.5">
                <div className="h-3.5 w-2/3 animate-pulse rounded bg-muted" />
                <div className="h-3.5 w-full animate-pulse rounded bg-muted" />
                <div className="h-3.5 w-4/5 animate-pulse rounded bg-muted" />
              </section>
            )}

            {hw && (
              <section className="rounded-xl border border-border bg-muted/40 p-4 space-y-3">
                <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  系统与资源快照
                </div>
                <div className="grid grid-cols-2 gap-3 text-xs">
                  <div>
                    <div className="text-muted-foreground">品牌 / SoC</div>
                    <div className="mt-0.5 font-medium">
                      {hw.brand || device.brand} · {hw.socPlatform || device.socPlatform}
                    </div>
                  </div>
                  <div>
                    <div className="text-muted-foreground">Android / ABI</div>
                    <div className="mt-0.5 font-medium">
                      Android {hw.androidVersion || device.androidVersion} · {hw.abi || '-'}
                    </div>
                  </div>
                  <div className="col-span-2">
                    <div className="text-muted-foreground">内存 (RAM)</div>
                    <div className="mt-0.5">{hw.ramSummary || '-'}</div>
                  </div>
                  <div className="col-span-2">
                    <div className="text-muted-foreground">存储 (Storage)</div>
                    <div className="mt-0.5">{hw.storageSummary || '-'}</div>
                  </div>
                  <div className="col-span-2">
                    <div className="text-muted-foreground">显示分辨率</div>
                    <div className="mt-0.5">{hw.displaySummary || '-'}</div>
                  </div>
                  <div className="col-span-2">
                    <div className="text-muted-foreground">构建版本</div>
                    <div className="mt-0.5 mono break-all">{hw.buildDisplay || '-'}</div>
                  </div>
                </div>
              </section>
            )}

            {controls && (
              <section className="rounded-xl border border-border bg-muted/40 p-4 space-y-2.5">
                <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  调试开关
                </div>
                <div className="flex flex-wrap gap-2 text-xs">
                  <span className="rounded-full border border-border bg-background px-2.5 py-0.5">
                    ADB {controls.adbEnabled ? '开启' : '关闭'}
                  </span>
                  <span className="rounded-full border border-border bg-background px-2.5 py-0.5">
                    网络 ADB {controls.wifiAdbEnabled ? '开启' : '关闭'}
                  </span>
                  <span className="rounded-full border border-border bg-background px-2.5 py-0.5">
                    常亮 {controls.stayAwakeEnabled ? '开启' : '关闭'}
                  </span>
                  <span className="rounded-full border border-border bg-background px-2.5 py-0.5">
                    开机 OTG {controls.autoOtgOnBoot ? '是' : '否'}
                  </span>
                </div>
              </section>
            )}

            {otg && (
              <section className="rounded-xl border border-border bg-muted/40 p-4 space-y-2">
                <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  OTG 与 USB 外设 ({otg.usbConfig || '-'})
                </div>
                {otg.nodes?.map((n) => (
                  <div
                    key={n.path}
                    className="flex items-center justify-between rounded-lg border border-border bg-background px-3 py-2 text-xs"
                  >
                    <span className="mono">{n.name}</span>
                    <span className="font-semibold">{n.mode}</span>
                  </div>
                ))}
                {otg.usbDevices && otg.usbDevices.length > 0 ? (
                  otg.usbDevices.map((u) => (
                    <div
                      key={`${u.name}-${u.vidPid}`}
                      className="flex items-center justify-between rounded-lg border border-border bg-background px-3 py-2 text-xs"
                    >
                      <span>{u.classLabel}</span>
                      <span className="mono text-muted-foreground">{u.vidPid}</span>
                    </div>
                  ))
                ) : (
                  <div className="text-xs text-muted-foreground">未枚举到外接 USB 设备</div>
                )}
              </section>
            )}

            {checks.length > 0 && (
              <section className="rounded-xl border border-border bg-muted/40 p-4 space-y-2">
                <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  系统自检
                </div>
                {checks.map((c) => (
                  <div
                    key={c.title}
                    className="flex items-center justify-between gap-3 rounded-lg border border-border bg-background px-3 py-2 text-xs"
                  >
                    <div>
                      <div className="font-medium">{c.title}</div>
                      <div className="mono text-muted-foreground">{c.detail}</div>
                    </div>
                    <span
                      className={`rounded-full px-2 py-0.5 font-medium ${
                        c.passed
                          ? 'bg-emerald-500/15 text-emerald-500'
                          : 'bg-destructive/15 text-destructive'
                      }`}
                    >
                      {c.passed ? '通过' : '异常'}
                    </span>
                  </div>
                ))}
              </section>
            )}
          </div>
        </div>
      )}
    </dialog>
  );
}
