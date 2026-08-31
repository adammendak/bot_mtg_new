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
  swingConfigured?: boolean;
  htsConfigured?: boolean;
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
  riskPln: number | null;
}

/**
 * Broker book identifiers. `swing` = separate demo account for the SDD-SWING (H1)
 * strategy; `hts` = separate demo account for the HTS ("wstęgi") strategy;
 * `okx` = OKX crypto exchange account (HTS CORE_OKX / FAST_OKX).
 */
export type BookId = 'demo' | 'live' | 'glowne' | 'swing' | 'hts' | 'okx';

/** All book tabs in dashboard order — filter by AuthService.canSeeBook() before rendering. */
export const BOOK_TABS: { id: BookId; label: string }[] = [
  { id: 'glowne', label: 'Główne (live)' },
  { id: 'live', label: 'Live · bot konto' },
  { id: 'demo', label: 'Demo · m15 / CORE' },
  { id: 'swing', label: 'Demo · H1 / SWING' },
  { id: 'hts', label: 'Demo · m5 / FAST' },
  { id: 'okx', label: 'OKX · crypto' },
];

export interface PositionsByBook {
  demo: Position[];
  live: Position[];
  glowne: Position[];
  swing: Position[];
  hts: Position[];
  okx: Position[];
}

export interface AccountView {
  id: BookId | string;
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
  haltPln: number | null;
  hardHaltPln: number | null;
  remainingToHaltPln: number | null;
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
  maxDrawdownPct: number | null;
  currentDrawdownPct: number | null;
  recoveryDays: number | null;
}

export interface SymbolStats {
  symbol: string;
  epic: string;
  trades: number;
  wins: number;
  losses: number;
  winRate: number;
  avgWin: number;
  avgLoss: number;
  expectancy: number;
  profitFactor: number;
  enabled: boolean;
}

export interface BacktestResult {
  symbol: string;
  epic: string;
  signals: number;
  wins: number;
  losses: number;
  winRate: number;
  avgR: number;
  expectancy: number;
  profitFactor: number;
}

/** One SDD-SWING (H1) scan row — HA flip on H1 in the direction of the H4 context. */
export interface SwingScan {
  timestamp: string;
  symbol: string;
  epic: string;
  direction: 'BUY' | 'SELL';
  entry: number;
  stopLevel: number;
  targetLevel: number;
  h4Trend: 'UP' | 'DOWN' | 'FLAT';
}

export interface SwingLastResponse {
  scannedAt: string | null;
  error: string;
  signals: SwingScan[];
}

/** One persisted HTS ("wstęgi") signal — `variant` is the timeframe model. */
export interface HtsSignal {
  id: number;
  scannedAt: string;
  variant: 'CORE' | 'SWING' | 'FAST' | 'CORE_LIVE' | string | null;
  symbol: string;
  epic: string | null;
  direction: 'BUY' | 'SELL' | null;
  entry: number | null;
  stopLevel: number | null;
  targetLevel: number | null;
  htfUp: boolean | null;
}

/**
 * One persisted HTS trade (E-1 lifecycle). `status` OPEN → the position monitor
 * owns it: `tp1At` set means half is off and the rest trails at `runnerStop`.
 * `status` CLOSED → outcome fields (`exitPrice`, `rMultiple`, `pnl`, `closeReason`).
 */
export interface HtsTrade {
  id: number;
  variant: string | null;
  htf: string | null;
  ltf: string | null;
  book: string | null;
  accountName: string | null;
  symbol: string;
  epic: string | null;
  direction: 'BUY' | 'SELL' | null;
  entry: number | null;
  stopLevel: number | null;
  targetLevel: number | null;
  size: number | null;
  remainingSize: number | null;
  tp1At: string | null;
  tp1Pnl: number | null;
  runnerStop: number | null;
  openedAt: string | null;
  status: string;
  exitPrice: number | null;
  exitAt: string | null;
  rMultiple: number | null;
  pnl: number | null;
  pnlCcy: string | null;
  closeReason: string | null;
  barTime: string | null;
}

/** HTS trade journal (E-8). */
export interface HtsJournal {
  trades: number;
  wins: number;
  winRate: number;
  avgR: number;
  sumR: number;
  byDay: { date: string; r: number; pnl: number | null; trades: number }[];
  rHistogram: { label: string; count: number }[];
  byReason: HtsJournalGroup[];
  bySymbol: HtsJournalGroup[];
}

export interface HtsJournalGroup {
  key: string;
  trades: number;
  wins: number;
  winRate: number;
  avgR: number;
  sumR: number;
}

/** One cell of the E-9 parameter sweep. */
export interface HtsSweepRow {
  rr: number;
  stopBuf: number;
  runnerLock: number;
  adxPermit: boolean;
  n: number;
  winRate: number;
  avgR: number;
  sumR: number;
  maxDdR: number;
}

/** E-10 walk-forward split result. */
export interface HtsOosResult {
  splitPct: number;
  splitAt: string | null;
  inSample: HtsOosHalf;
  outOfSample: HtsOosHalf;
}

export interface HtsOosHalf {
  n: number;
  winRate: number;
  avgR: number;
  sumR: number;
  maxDdR: number;
}

/** One row of the HTS forward-test scorecard (E-4) — per timeframe model. */
export interface HtsScorecardRow {
  variant: string;
  htf: string | null;
  ltf: string | null;
  book: string | null;
  openTrades: number;
  closedTrades: number;
  wins: number;
  losses: number;
  winRate: number;
  avgR: number;
  sumR: number;
  expectancyR: number;
  maxDrawdownR: number;
  realisedPnl: number | null;
  pnlCcy: string | null;
  lastTradeAt: string | null;
}

export interface AuditEvent {
  at: string;
  book: string;
  symbol: string;
  action: string;
  detail: string;
}

/** One scheduler liveness probe (E-5). `stale` = no successful cycle within its window. */
export interface SchedulerHeartbeatView {
  name: string;
  lastOkAt: string | null;
  ageSeconds: number;
  maxSilenceSeconds: number;
  stale: boolean;
}

export interface OpsHealth {
  time: string;
  staleCount: number;
  schedulers: SchedulerHeartbeatView[];
}

/** One durable failure row (E-5) from /api/ops/errors. */
export interface ErrorEvent {
  id: number;
  at: string;
  source: string;
  scope: string | null;
  detail: string | null;
  exception: string | null;
  message: string | null;
}

export interface PositionMonitorView {
  dealId: string;
  epic: string;
  direction: string;
  size: number;
  level: number;
  stopLevel: number | null;
  unrealizedPnl: number;
  currency: string | null;
  riskPln: number | null;
  openMinutes: number;
  openedAt: string | null;
  stopDrifted: boolean;
  sleeping: boolean;
}
