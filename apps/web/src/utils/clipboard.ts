import { DeviceItem } from '../types';

function legacyCopy(text: string): boolean {
  const field = Object.assign(document.createElement('textarea'), { value: text });
  field.setAttribute('readonly', '');
  field.style.cssText = 'position:fixed;inset-block-start:0;opacity:0;pointer-events:none';
  document.body.append(field);
  field.focus();
  field.select();
  field.setSelectionRange(0, text.length);
  try {
    return document.execCommand('copy');
  } catch {
    return false;
  } finally {
    field.remove();
  }
}

export async function copyTextToClipboard(text: string): Promise<boolean> {
  if (!text) return false;
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return legacyCopy(text);
  }
}

export function exportDevicesCsv(devices: DeviceItem[]): void {
  const headers = [
    'MAC',
    'Serial',
    'Model',
    'Brand',
    'SoC',
    'Android',
    'SDK',
    'LAN_IP',
    'Public_IP',
    'Online',
    'WS_Tunnel',
    'SSH_Tunnel',
    'Stress_Running',
    'SoC_Temp_C',
    'Uptime',
    'App_Version',
    'Last_Seen_Ago_Sec',
  ];
  const escape = (v: unknown) => {
    const s = String(v ?? '');
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const rows = devices.map((d) =>
    [
      d.mac,
      d.serial,
      d.model,
      d.brand,
      d.socPlatform,
      d.androidVersion,
      d.sdkInt,
      d.lanIp,
      d.publicIp,
      d.online ? 'online' : 'offline',
      d.wsConnected ? 'yes' : 'no',
      d.tunnelConnected ? 'yes' : 'no',
      d.stressRunning ? 'yes' : 'no',
      d.socTempC !== null ? d.socTempC.toFixed(1) : '',
      d.uptime,
      d.appVersion,
      d.lastSeenAgoSec,
    ]
      .map(escape)
      .join(','),
  );
  const csv = '\uFEFF' + [headers.join(','), ...rows].join('\n');
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `toolhub-fleet-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.csv`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
