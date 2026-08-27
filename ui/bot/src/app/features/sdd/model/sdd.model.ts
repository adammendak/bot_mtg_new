export interface SetupFlags {
  ha: boolean;
  rma: boolean;
  h1: boolean;
  pp: boolean;
}

export interface SddScan {
  timestamp: string;
  symbol: string;
  epic: string;
  direction: 'BUY' | 'SELL';
  setup: SetupFlags;
  stop: number;
  oneR: number;
  atrH1: number;
  entry: number;
  actionable: boolean;
  reason: string;
  failed: string[];
  newBar: boolean;
  flip: boolean;
  fullStack: boolean;
  h4Note: string;
  h1Supporting: boolean;
}

export interface ScanSnapshot {
  scannedAt: string | null;
  brokerId: string;
  brokerName: string;
  executionEnabled: boolean;
  newsBlackout: boolean;
  halt: string | null;
  symbols: SddScan[];
  error: string | null;
}

export interface BrokerInfo {
  id: string;
  name: string;
  sessionOpen: boolean;
  executionEnabled: boolean;
}

export interface HealthInfo {
  status: string;
  time: string;
  broker: string;
  executionEnabled: boolean;
}

export interface Position {
  dealId: string;
  epic: string;
  direction: string;
  size: number;
  level: number;
  unrealizedPnl: number;
}
