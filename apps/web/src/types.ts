export interface DeviceItem {
  mac: string;
  serial: string;
  model: string;
  brand: string;
  androidVersion: string;
  sdkInt: number;
  socPlatform: string;
  lanIp: string;
  publicIp: string;
  appVersion: string;
  webPort?: number;
  adbPort?: number;
  tunnelWebPort: number;
  tunnelAdbPort: number;
  tunnelConnected: boolean;
  wsConnected?: boolean;
  stressRunning: boolean;
  socTempC: number | null;
  uptime: string;
  online: boolean;
  lastSeenAgoSec: number;
  adbTunnelCmd: string;
  alias?: string;
  group?: string;
  notes?: string;
}

export type Role = 'admin' | 'operator' | 'viewer';

export interface UserView {
  username: string;
  role: Role;
  createdAt: string;
}

export interface AuditEntry {
  ts: string;
  actor: string;
  role: string;
  action: string;
  target: string;
  detail: string;
  ok: boolean;
}

export interface TelemetryHistoryPoint {
  tsEpoch: number;
  socTempC: number | null;
  stressRunning: boolean;
  online: boolean;
}

export interface DeviceOverview {
  device?: {
    model?: string;
    brand?: string;
    manufacturer?: string;
    androidVersion?: string;
    sdkInt?: number;
    socPlatform?: string;
    abi?: string;
    kernelVersion?: string;
    buildDisplay?: string;
    uidSummary?: string;
    ramSummary?: string;
    storageSummary?: string;
    displaySummary?: string;
    uptime?: string;
    activeIp?: string;
    adbPort?: string;
    sysBootReason?: string;
    isAbnormalReboot?: boolean;
  };
  otg?: {
    usbConfig?: string;
    usbState?: string;
    usbController?: string;
    adbTcpPort?: string;
    nodes?: Array<{
      path: string;
      name: string;
      mode: string;
      writable: boolean;
    }>;
    usbDevices?: Array<{
      name: string;
      classLabel: string;
      vidPid: string;
    }>;
  };
  controls?: {
    adbEnabled?: boolean;
    wifiAdbEnabled?: boolean;
    stayAwakeEnabled?: boolean;
    autoOtgOnBoot?: boolean;
    autoAdbOnBoot?: boolean;
  };
  healthChecks?: Array<{
    title: string;
    detail: string;
    passed: boolean;
  }>;
}

export interface ThermalZone {
  zone?: string;
  name?: string;
  type?: string;
  celsius?: number;
  tempC?: number;
  raw?: number;
}

export interface CpuPolicyInfo {
  name?: string;
  policy?: string;
  affectedCpus?: string;
  curFreq?: string;
  rangeFreq?: string;
  minKHz?: number;
  maxKHz?: number;
  governor?: string;
  availableGovernors?: string[];
  availableFrequenciesKHz?: number[];
}

export interface GpuDevfreqInfo {
  name?: string;
  curFreq?: string;
  rangeFreq?: string;
  minHz?: number;
  maxHz?: number;
  governor?: string;
  availableGovernors?: string[];
  availableFrequenciesHz?: number[];
}

export interface GcTelemetry {
  gcCount?: number;
  gcTimeMs?: number;
  blockingGcCount?: number;
  jvmUsedBytes?: number;
  jvmMaxBytes?: number;
}

export interface TelemetrySnapshot {
  cpuPercent: number;
  loadAvg: string;
  gpuAvailable: boolean;
  gpuPercent: number;
  gpuName?: string;
  gpuFreqMhz?: number;
  memPercent: number;
  memUsedBytes: number;
  memTotalBytes: number;
  zramUsedBytes?: number;
  zramTotalBytes?: number;
  netRxBytesPerSec: number;
  netTxBytesPerSec: number;
  diskReadBytesPerSec: number;
  diskWriteBytesPerSec: number;
  dataFreeBytes: number;
  dataTotalBytes: number;
  dataUsedPercent: number;
  gc?: GcTelemetry;
  thermalZones?: ThermalZone[];
  cpuPolicies?: CpuPolicyInfo[];
  gpuDevfreqs?: GpuDevfreqInfo[];
}

export interface AudioOverview {
  cards?: Array<{
    index: number;
    id: string;
    name: string;
    description?: string;
    active?: boolean;
    streams?: Array<{
      pcmId: string;
      streamName: string;
      active: boolean;
      statusText: string;
      format: string;
      rate: string;
      channels: string;
    }>;
  }>;
  outputDevices?: Array<{
    id?: number;
    typeLabel?: string;
    productName?: string;
    sampleRates?: string;
    encodings?: string;
  }>;
  tone?: {
    isPlaying: boolean;
    waveform: string;
    channelMode: string;
    sampleRateHz: number;
    attenuationDb: number;
  };
}

export interface AotOverview {
  isRunning: boolean;
  currentIndex: number;
  totalCount: number;
  statusMessage: string;
  apps?: Array<{
    packageName: string;
    appLabel: string;
    isSystemApp: boolean;
    status: string;
    reason: string;
    abi: string;
    isSpeedCompiled: boolean;
  }>;
}

export interface ReportEntry {
  fileName: string;
  typeLabel: string;
  isZip?: boolean;
  storageLocationLabel?: string;
  formattedTime: string;
  sizeBytes: number;
  isPass: boolean | null;
  summary: string;
}

export interface CrashSummary {
  bootReason?: {
    sysBootReason?: string;
    roBootReason?: string;
    isAbnormal?: boolean;
    lastKernelExcerpt?: string | null;
  };
  tombstones?: Array<{
    fileName: string;
    timestampText: string;
    processName: string;
    pid: string;
    signalText: string;
  }>;
}

export interface SysPropEntry {
  key: string;
  value: string;
  writable?: boolean;
}

export interface LegacyStatus {
  model?: string;
  android?: string;
  isStressRunning?: boolean;
  stressElapsedSec?: number;
  stressTempCelsius?: number;
  usbAttached?: boolean;
  usbVolume?: string;
  reportCount?: number;
}

export type FilterMode = 'all' | 'online' | 'ws' | 'stress' | 'offline';
export type SortMode = 'default' | 'temp_desc' | 'seen_asc' | 'model_asc';
export type ViewMode = 'table' | 'grid';
export type WorkspaceSection = 'fleet' | 'workbench' | 'batch' | 'admin' | 'settings';
export type WorkbenchTab = 'telemetry' | 'controls' | 'audio' | 'sysprops' | 'diagnostics';
export type ThemeMode = 'system' | 'light' | 'dark';
export type AccentColor = 'blue' | 'green' | 'violet' | 'orange' | 'rose';
