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

export interface BookScan {
  id: string;
  broker: string;
  halt: string | null;
  error: string | null;
}

export interface ScanSnapshot {
  scannedAt: string | null;
  brokerId: string;
  brokerName: string;
  executionEnabled: boolean;
  newsBlackout: boolean;
  symbols: SddScan[];
  error: string | null;
  books: BookScan[];
}

export interface BrokerBookInfo {
  id: string;
  broker: string;
  name: string;
  sessionOpen: boolean;
  configured: boolean;
}

export interface BrokerInfo {
  executionEnabled: boolean;
  books: BrokerBookInfo[];
}

export interface HealthInfo {
  status: string;
  time: string;
  broker: string;
  executionEnabled: boolean;
  demoConfigured: boolean;
  liveConfigured: boolean;
}

export interface Position {
  dealId: string;
  epic: string;
  direction: string;
  size: number;
  level: number;
  unrealizedPnl: number;
}

export interface PositionsByBook {
  demo: Position[];
  live: Position[];
}

export interface AccountView {
  id: 'demo' | 'live' | string;
  broker: string;
  accountName: string | null;
  equity: number | null;
  available: number | null;
  dayPnl: number | null;
  currency: string | null;
  connected: boolean;
  error: string | null;
}

export interface DailyEquityPoint {
  date: string;
  equity: number | null;
  dayPnl: number | null;
  pctChange: number | null;
}

export interface HistoryResponse {
  book: string;
  currency: string | null;
  connected: boolean;
  points: DailyEquityPoint[];
}
