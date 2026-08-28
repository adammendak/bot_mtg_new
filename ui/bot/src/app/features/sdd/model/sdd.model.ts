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
  lastWebhookAt?: string | null;
  lastWebhookError?: string | null;
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
  webhookConfigured?: boolean;
  lastWebhook?: string;
  lastWebhookAt?: string | null;
}

export interface Position {
  dealId: string;
  epic: string;
  direction: string;
  size: number;
  level: number;
  stopLevel: number | null;
  unrealizedPnl: number;
}

export interface PositionsByBook {
  demo: Position[];
  live: Position[];
  glowne: Position[];
}

export interface AccountView {
  id: 'demo' | 'live' | 'glowne' | string;
  broker: string;
  accountName: string | null;
  equity: number | null;
  available: number | null;
  dayPnl: number | null;
  currency: string | null;
  connected: boolean;
  error: string | null;
}

export interface OverviewView {
  id: string;
  broker: string;
  kind: 'DEMO' | 'LIVE' | 'MAIN' | string;
  displayName: string;
  accountName: string | null;
  strategy: string;
  executionEnabled: boolean;
  equity: number | null;
  available: number | null;
  dayPnl: number | null;
  currency: string | null;
  connected: boolean;
  error: string | null;
  positionsCount: number;
  positionsPnl: number | null;
  maxLossPln: number | null;
  positionsWithoutStop: number;
  riskCurrency: string | null;
  correlatedPln: number | null;
  effectiveRiskPln: number | null;
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
