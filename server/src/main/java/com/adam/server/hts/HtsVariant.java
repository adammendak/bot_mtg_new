package com.adam.server.hts;

import com.adam.server.broker.Books;
import com.adam.server.broker.Resolution;

import java.time.Duration;
import java.util.List;

/**
 * The HTS strategy variants run side by side, each on its own Capital.com
 * sub-account:
 *
 * <ul>
 *   <li>{@link #FAST} — ribbon, H1 / M5 → {@code hts} book ("Account m5"), demo</li>
 *   <li>{@link #CORE_LIVE} — ribbon, H4 / M15 → {@code live} book ("bot trading konto"),
 *       <b>real money</b>, 1 % of account risk. <b>Parked / detached</b> — no
 *       strategy trades the live book any more; {@code HTS_LIVE_EXECUTION_ENABLED}
 *       (separate from the demo flag) also defaults to off as a second guard</li>
 *   <li>{@link #CORE_OKX} / {@link #FAST_OKX} — ribbon → {@code okx} book (crypto, SWAP), 24/7</li>
 *   <li>{@link #HA4} — HA-hunt cloud, H4 hunt / M15 entry, "HA flip + stack" trigger
 *       → {@code demo} book ("Account m15"); XAU / XAG / J225 / USDJPY / US100, long only.</li>
 *   <li>{@link #HA12} — HA-hunt cloud, H12 hunt / H1 entry → {@code swing} book
 *       ("Account H1"); same universe as {@link #HA4}. Backtest's strongest
 *       config (PF ~1.8, MaxDD ~6%); ran silent for two days on first deploy,
 *       resumed on the numbers.</li>
 *   <li>{@link #HA1} — H1-HA-hunt cloud, M5 entry, ATR stop / WITH confirm on
 *       M15 (resampled from the M5 feed — no separate broker fetch) →
 *       {@code hts} book ("Account m5"); same universe as {@link #HA4}, long only.
 *       <b>No backtest evidence</b> — FAST's M5 band-edge stop churns on this
 *       book (avg hold 5–9 min on every symbol but BTC), this swaps in an
 *       ATR-based stop on the same book to see if that structurally holds up.</li>
 *   <li>{@link #HA_OKX} — HA-hunt cloud, same H4/M15/H1 shape as {@link #HA4},
 *       on the {@code okx} book: ETH + XRP linear USDT perpetual swaps, long
 *       only, plus a funding-rate crowding skip. OKX HA-hunt backtest dropped
 *       BTC (net loser) for XRP; execution gated by {@code OKX_LIVE_EXECUTION_ENABLED}.</li>
 *   <li>{@link #MMS} — MastermindZX mean-reversion. <b>Parked</b> — no
 *       backtest edge, see {@code docs/MMS-STRATEGY.md}.</li>
 *   <li>{@link #M15_ST_V3} — M15 entry / H1-Supertrend filter+stop, TP1 2:1
 *       half + breakeven + confirmed M15 HA-flip runner exit, on BTC / XAU /
 *       US100 → the {@code mms} book ("Account MMS") {@link #MMS} vacated —
 *       every symbol positive in both a 12-mo in-sample and 12-mo
 *       out-of-sample backtest (PF 1.12-1.37).</li>
 * </ul>
 *
 * <p>{@link #CORE}, {@link #SWING}, {@link #HA4X}, {@link #FAST} and
 * {@link #CORE_LIVE} are {@link #parked() parked} — kept in the enum but not
 * scanned. CORE/SWING (ribbon) gave zero signals through the forward test;
 * FAST churned every non-BTC symbol on M5 and was replaced by {@link #HA1} on
 * the same ("Account m5") book; {@link #HA4X} ("M15 band cross" entry) backtested
 * to PF ~1.2 IS / ~0.8 in the recent regime, MaxDD ~30% — the HA-flip vs
 * band-cross A/B was decided on the numbers, {@link #HA12} took its book.
 * {@link #CORE_LIVE} — the real-money variant — is detached: nothing trades the
 * {@code live} book any more, every strategy is demo/swing/hts/okx only.
 * {@link #MMS} is parked (no backtest edge); {@link #M15_ST_V3} is unparked on
 * its {@code mms} book ("Account MMS") instead; execution still honours
 * {@code HTS_EXECUTION_ENABLED}. {@code live()} is false for both.
 *
 * <p>Ribbon variants ({@link Strategy#RIBBON}) run {@link HtsEngine};
 * HA-hunt variants ({@link Strategy#HA_HUNT}) run {@link HaHuntEngine} with a
 * cloud-hold exit. Within HA-hunt, {@link EntryTrigger} picks how the entry-TF
 * direction is decided (steps 3–7 — hunt gate, RMA-stacked, WITH confirm, daily
 * pivot, universe/side, fill cap — are identical either way).
 * MMS ({@link Strategy#MMS}) runs {@link MmsEngine}: fade a TMA/ATR envelope
 * after a closed-bar band touch + first reactive candle; opposite-band or
 * 1:1 TP; mandatory %-of-price SL (no trail); sequential delever after a
 * full base SL (add-on wick stops do not cut the unit). ST_V3
 * ({@link Strategy#ST_V3}) runs {@link StV3Engine}: see {@link #M15_ST_V3}.
 */
public enum HtsVariant {

    CORE(Resolution.H4, Resolution.M15, Books.DEMO, Duration.ofDays(80), Duration.ofDays(10), 15, false),
    SWING(Resolution.D1, Resolution.H1, Books.SWING, Duration.ofDays(240), Duration.ofDays(28), 60, false),
    FAST(Resolution.H1, Resolution.M5, Books.HTS, Duration.ofDays(28), Duration.ofDays(6), 5, false),
    CORE_LIVE(Resolution.H4, Resolution.M15, Books.LIVE, Duration.ofDays(80), Duration.ofDays(10), 15, true),
    CORE_OKX(Resolution.H4, Resolution.M15, Books.OKX, Duration.ofDays(80), Duration.ofDays(10), 15, false),
    FAST_OKX(Resolution.H1, Resolution.M5, Books.OKX, Duration.ofDays(28), Duration.ofDays(6), 5, false),

    // Shared HA-hunt universe: backtest — XAU/XAG strongest, J225/USDJPY good,
    // US100 weak diversifier, GER40 net-negative (removed). See HaHunt.UNIVERSE.

    /** H4-HA-hunt cloud, M15 execution, ATR stop on H1, slow RMA 100. HA-flip entry. */
    HA4(Books.DEMO, Resolution.M15, 15, 4, 1, 100, HaHunt.UNIVERSE, EntryTrigger.HA_FLIP),
    /** H12-HA-hunt cloud, H1 execution, ATR stop on H4, slow RMA 144. Backtest's strongest config. */
    HA12(Books.SWING, Resolution.H1, 60, 12, 4, 144, HaHunt.UNIVERSE, EntryTrigger.HA_FLIP),
    /** Same H4/M15/H1 shape as {@link #HA4}, band-cross entry. Parked — lost the A/B on the numbers. */
    HA4X(Books.SWING, Resolution.M15, 15, 4, 1, 100, HaHunt.UNIVERSE, EntryTrigger.BAND_CROSS),
    /** H1-HA-hunt cloud, M5 entry, ATR stop/WITH on M15 (resampled from M5). HA-flip entry. Unvalidated — see class javadoc. */
    HA1(Books.HTS, Resolution.M5, 5, 1, 100, List.of("XAU", "US100", "USDJPY"), EntryTrigger.HA_FLIP, 15,
            Duration.ofDays(6)),
    /**
     * Same H4-hunt / M15-entry / H1-ATR-stop / slow-RMA-100 / cloud-hold shape as
     * {@link #HA4}, ported to the OKX {@code okx} book — <b>ETH + XRP</b> linear
     * USDT perpetual swaps, long only. Adds a funding-rate crowding skip (see
     * {@link HaHuntEngine} / {@code HtsScanService}): a long is not executed when
     * OKX funding is extreme-positive (crowded longs). OKX HA-hunt backtest
     * (12&nbsp;mo, no fees): BTC PF ~0.8 (net loser), ETH ~1.2, XRP ~1.6 —
     * dropped BTC, added XRP. Execution stays gated by
     * {@code OKX_LIVE_EXECUTION_ENABLED}.
     */
    HA_OKX(Books.OKX, Resolution.M15, 15, 4, 1, 100, HaHunt.OKX_UNIVERSE, EntryTrigger.HA_FLIP),

    /**
     * MastermindZX MMS mean-reversion — TMA ± ATR envelope on the entry TF
     * (default M15; prefer M10–M30 for algo, H1 for a manual base; exclude M5
     * as noise; H4 = trend/range context, D1 = bias/sizing — not entry TFs).
     * TP {@link com.adam.server.hts.MmsEngine.TpMode#OPPOSITE_BAND} (site) or
     * {@link com.adam.server.hts.MmsEngine.TpMode#FIXED_1R} (tester clips).
     * Mandatory SL, no trail. Optional one-bar add-on ×1 (default off).
     * Universe: BTC (Capital {@code BTCUSD} / OKX {@code BTC-USDT-SWAP} if
     * remapped), XAU/GOLD, US100/NQ. Both sides. <b>Parked</b> — no backtest
     * edge (PF 0.79-0.94, see {@code docs/MMS-STRATEGY.md}); {@link #M15_ST_V3}
     * took the {@code mms} book instead. Site: https://mastermindzx.pl/
     * (BTCUSDT monthly-optimised backtests; no WR copied into code).
     */
    MMS(Books.MMS, Resolution.M15, 15, Duration.ofDays(15), Mms.UNIVERSE),

    /**
     * M15 entry / H1-Supertrend filter+stop (ATR 10, factor 2.0 — the research
     * scripts' default, not {@link com.adam.server.sdd.Supertrend}'s own 3.0
     * — ATR 12/factor 3.0 backtested to ~breakeven, PF 1.01 IS/1.16 OOS,
     * clearly worse), TP1 2:1 on half then the remaining half's stop jumps
     * once to breakeven and sits there — no trail, no slow-band exit — until
     * a confirmed M15 Heikin-Ashi colour flip against the position flattens
     * the runner (PR #140's locked "v3 bakeoff" config, ported from Pine).
     * H1 structure gate required: H1 close stacked vs its own RMA33/144, OR
     * the M15 close already beyond the closed H1 fast band, in the
     * Supertrend direction. Cap 2 fills per Supertrend regime, no
     * pyramiding (backtested WORSE: PF 1.54/1.61 -> 1.02/1.10 with
     * pyramiding on the M5/M45 pairing, MaxDD 13.5% -> 55.9%). Both sides.
     *
     * <p>The original M5-entry/M45-Supertrend pairing (same idea, one
     * timeframe rung down) backtested markedly worse once a TP1/runner
     * double-counting bug in the research tool was fixed (a completed leg
     * kept being silently re-evaluated on later bars instead of locking its
     * result) — PF 1.09 IS / 1.08 OOS overall, with US100 and US30 flipping
     * negative OOS. This M15/H1 pairing, restricted to BTC/XAU/US100, is the
     * one that held up: 12&nbsp;mo IS 2025-09→2026-09 + 12&nbsp;mo OOS
     * 2024-10→2025-09, no fees — every one of BTC/XAU/US100 positive in BOTH
     * windows (XAU PF 1.31 IS / 1.12 OOS, BTC 1.31/1.22, US100 1.37/1.35;
     * combined PF 1.33 IS / 1.23 OOS). Took the {@code mms} book
     * ("Account MMS") from {@link #MMS} (no edge, parked).
     */
    M15_ST_V3(Resolution.M15, Books.MMS, Duration.ofDays(10), 15, Mms.UNIVERSE);

    /** Entry model: {@link HtsEngine} ribbon, {@link HaHuntEngine} HA-hunt, {@link MmsEngine}, or {@link StV3Engine}. */
    public enum Strategy { RIBBON, HA_HUNT, MMS, ST_V3 }

    /**
     * How an HA-hunt variant decides entry-TF direction (the hunt gate, RMA
     * stacked check, WITH confirm, pivot, universe/side and fill cap are the
     * same for both):
     * <ul>
     *   <li>{@link #HA_FLIP} — entry-TF Heikin-Ashi colour flips on the just-closed bar.</li>
     *   <li>{@link #BAND_CROSS} — entry-TF close closes beyond the fast RMA band,
     *       fast band clear of the slow band, on the first bar this becomes true
     *       (not a persisting state).</li>
     * </ul>
     */
    public enum EntryTrigger { HA_FLIP, BAND_CROSS }

    /** Holder so the shared lists can be referenced from the enum constants above. */
    private static final class HaHunt {
        static final java.util.List<String> UNIVERSE = java.util.List.of("XAU", "XAG", "J225", "USDJPY", "US100");
        /** OKX perps — codes match {@link com.adam.server.broker.okx.OkxSymbol}. */
        static final java.util.List<String> OKX_UNIVERSE = java.util.List.of("ETH", "XRP");
    }

    /** MastermindZX MMS: BTC + gold + Nasdaq (Capital epics BTCUSD / GOLD / US100). */
    private static final class Mms {
        static final java.util.List<String> UNIVERSE = java.util.List.of("BTC", "XAU", "US100");
    }

    private final Strategy strategy;
    private final Resolution htf;
    private final Resolution ltf;
    private final String book;
    private final Duration htfLookback;
    private final Duration ltfLookback;
    private final int ltfMinutes;
    private final boolean live;
    // HA-hunt only:
    private final int huntHours;
    private final int atrHours;
    /** ATR/WITH timeframe in minutes instead of hours (resampled from the entry TF, not H1) — 0 = use atrHours. */
    private final int atrMinutes;
    private final int slowLen;
    private final List<String> universe;
    private final boolean longOnly;
    private final EntryTrigger entryTrigger;

    /** Ribbon variant. */
    HtsVariant(Resolution htf, Resolution ltf, String book, Duration htfLookback, Duration ltfLookback,
               int ltfMinutes, boolean live) {
        this(Strategy.RIBBON, htf, ltf, book, htfLookback, ltfLookback, ltfMinutes, live,
                0, 0, 0, 0, List.of(), false, EntryTrigger.HA_FLIP);
    }

    /**
     * HA-hunt cloud variant, ATR/WITH timeframe in whole hours. {@code entryTf}
     * is what the broker is asked for; hunt/stop TFs are resampled from H1.
     */
    HtsVariant(String book, Resolution entryTf, int ltfMinutes, int huntHours, int atrHours,
               int slowLen, List<String> universe, EntryTrigger entryTrigger) {
        this(Strategy.HA_HUNT, null, entryTf, book, Duration.ofDays(60), Duration.ofDays(20), ltfMinutes, false,
                huntHours, atrHours, 0, slowLen, universe, true, entryTrigger);
    }

    /**
     * HA-hunt cloud variant whose ATR/WITH timeframe is finer than an hour
     * (e.g. M15), resampled from the entry-TF feed instead of H1 — for a hunt
     * TF that is itself only H1 (a bare hour), there is no room for a whole-hour
     * "mid" timeframe between the hunt and the entry. Takes its own entry-TF
     * lookback (a fine entry TF like M5 needs far fewer calendar days of
     * history than M15/H1 do, and fetching 20 days of M5 per symbol per scan
     * would be a lot of avoidable Capital API calls).
     */
    HtsVariant(String book, Resolution entryTf, int ltfMinutes, int huntHours,
               int slowLen, List<String> universe, EntryTrigger entryTrigger, int atrMinutes,
               Duration ltfLookback) {
        this(Strategy.HA_HUNT, null, entryTf, book, Duration.ofDays(60), ltfLookback, ltfMinutes, false,
                huntHours, 0, atrMinutes, slowLen, universe, true, entryTrigger);
    }

    /**
     * MMS mean-reversion: entry TF only (no hunt resample), both sides,
     * {@code live=false}. Parked separately — this constructor does not enable
     * execution.
     */
    HtsVariant(String book, Resolution entryTf, int ltfMinutes, Duration ltfLookback, List<String> universe) {
        this(Strategy.MMS, null, entryTf, book, Duration.ofDays(20), ltfLookback, ltfMinutes, false,
                0, 0, 0, 0, universe, false, EntryTrigger.HA_FLIP);
    }

    /**
     * ST_V3 variant: M5 entry, M45-Supertrend filter+SL, TP1 2:1 half + BE +
     * confirmed-HA-flip runner (all fixed in {@link StV3Engine} — this shape
     * only carries the book/lookback/universe, same idea as the MMS
     * constructor above).
     */
    HtsVariant(Resolution entryTf, String book, Duration ltfLookback, int ltfMinutes, List<String> universe) {
        this(Strategy.ST_V3, null, entryTf, book, Duration.ofDays(60), ltfLookback, ltfMinutes, false,
                0, 0, 0, 0, universe, false, EntryTrigger.HA_FLIP);
    }

    HtsVariant(Strategy strategy, Resolution htf, Resolution ltf, String book, Duration htfLookback,
               Duration ltfLookback, int ltfMinutes, boolean live, int huntHours, int atrHours, int atrMinutes,
               int slowLen, List<String> universe, boolean longOnly, EntryTrigger entryTrigger) {
        this.strategy = strategy;
        this.htf = htf;
        this.ltf = ltf;
        this.book = book;
        this.htfLookback = htfLookback;
        this.ltfLookback = ltfLookback;
        this.ltfMinutes = ltfMinutes;
        this.live = live;
        this.huntHours = huntHours;
        this.atrHours = atrHours;
        this.atrMinutes = atrMinutes;
        this.slowLen = slowLen;
        this.universe = universe;
        this.longOnly = longOnly;
        this.entryTrigger = entryTrigger;
    }

    public Strategy strategy() {
        return strategy;
    }

    public EntryTrigger entryTrigger() {
        return entryTrigger;
    }

    /**
     * Whether per-signal e-mail is sent for this variant. Mail is restricted to
     * <b>H1-entry</b> variants only (currently {@link #HA12}) — M15/M5 entries
     * fire too often for an inbox; each still shows up on the dashboard / trades
     * feed. This also silences {@link #MMS} (M15 entry) regardless of
     * {@code app.mms.mail-enabled} — that toggle no longer has an effect while
     * MMS stays on M15.
     */
    public boolean mailsSignals() {
        return (strategy == Strategy.HA_HUNT || strategy == Strategy.MMS) && ltf == Resolution.H1;
    }

    /**
     * Parked: kept in the enum but not scanned or traded. CORE / SWING ribbon
     * gave zero signals; FAST churned every non-BTC M5 symbol (avg hold 5-9 min)
     * on its band-edge stop; HA4X (band-cross entry) lost the HA-flip vs
     * band-cross A/B on the backtest (PF ~1.2 IS / ~0.8 recent, MaxDD ~30%).
     * {@link #CORE_LIVE} (the only real-money variant) is parked too — the
     * live account is detached, every strategy now trades demo/swing/hts books
     * only; {@code HTS_LIVE_EXECUTION_ENABLED} stays as a second guard.
     *
     * <p>{@link #CORE_OKX} / {@link #FAST_OKX} (ribbon on the real-money OKX
     * book) are parked too: once {@code OKX_LIVE_EXECUTION_ENABLED} is armed for
     * {@link #HA_OKX}, FAST_OKX's M5 ribbon would churn LTC/BTC every 5&nbsp;min
     * (the same reason {@link #FAST} is parked) and CORE_OKX ribbon has the same
     * zero-edge history as CORE. Only {@link #HA_OKX} trades the OKX book now.
     * {@link #MMS} is parked too — no backtest edge — and {@link #M15_ST_V3}
     * took its {@code mms} book.
     */
    public boolean parked() {
        return this == CORE || this == SWING || this == HA4X || this == FAST
                || this == CORE_OKX || this == FAST_OKX || this == CORE_LIVE || this == MMS;
    }

    /** Real-money account (the {@code live} book) — extra guards + separate enable flag. */
    public boolean live() {
        return live;
    }

    /**
     * True in the 5-minute scan slot right after this model's LTF bar closes, so
     * the every-5-min scheduler only hits Capital for a variant when there is a
     * fresh bar: FAST every pass, CORE/HA4/HA4X on the M15 boundary, SWING/HA12 on the hour.
     */
    public boolean dueAtMinute(int minuteOfHour) {
        return minuteOfHour % ltfMinutes < 5;
    }

    public Resolution htf() {
        return htf;
    }

    /**
     * Non-null higher-timeframe label for persistence / display. HA-hunt variants
     * have no {@link Resolution} htf (the hunt runs on a resampled H4/H12 series),
     * so this returns {@code "H4"} / {@code "H12"} for them.
     */
    public String htfLabel() {
        if (strategy == Strategy.MMS) {
            return ltf != null ? ltf.name() : "M15";
        }
        if (strategy == Strategy.ST_V3) {
            return "H1";
        }
        return htf != null ? htf.name() : "H" + huntHours;
    }

    public Resolution ltf() {
        return ltf;
    }

    /** Capital.com book this variant scans and (if enabled) trades. */
    public String book() {
        return book;
    }

    public Duration htfLookback() {
        return htfLookback;
    }

    public Duration ltfLookback() {
        return ltfLookback;
    }

    public String label() {
        if (strategy == Strategy.MMS) {
            return name() + " " + ltf + " TMA-ATR";
        }
        if (strategy == Strategy.ST_V3) {
            return name() + " H1-ST/" + ltf;
        }
        if (strategy != Strategy.HA_HUNT) {
            return name() + " " + htf + "/" + ltf;
        }
        String trigger = entryTrigger == EntryTrigger.BAND_CROSS ? " band-cross" : "";
        return name() + " H" + huntHours + "-hunt/" + ltf + trigger;
    }

    /** Entry-TF bar length in minutes (M15 = 15, H1 = 60). Used to drop a forming bar. */
    public int ltfMinutes() {
        return ltfMinutes;
    }

    // ---- HA-hunt accessors ----
    public int huntHours() {
        return huntHours;
    }

    public int atrHours() {
        return atrHours;
    }

    /** ATR/WITH timeframe in minutes, resampled from the entry TF — 0 means "use atrHours instead". */
    public int atrMinutes() {
        return atrMinutes;
    }

    public int slowLen() {
        return slowLen;
    }

    public List<String> universe() {
        return universe;
    }

    public boolean longOnly() {
        return longOnly;
    }

    /**
     * Whether this model trades {@code symbolCode}.
     *
     * <ul>
     *   <li>{@link #FAST} skips BTC / EURUSD — the M5 fast-band stop there is a
     *       tiny fraction of price (~0.15&nbsp;% BTC, ~0.04&nbsp;% EURUSD), so it
     *       scalps and churns the m5 sub-account. Both stay on the HTF models.</li>
     *   <li>{@link #CORE_LIVE} skips GER40 — on the <b>real-money</b> book it
     *       stacked and re-entered GER40 every M15 bar (ids 85–102, ~10&nbsp;min
     *       each) and GER40 is net-negative in the HA-hunt backtest across every
     *       variant. The other CORE_LIVE names are unaffected.</li>
     * </ul>
     */
    public boolean tradesSymbol(String symbolCode) {
        if (this == FAST) {
            return !("BTC".equalsIgnoreCase(symbolCode) || "EURUSD".equalsIgnoreCase(symbolCode));
        }
        if (this == CORE_LIVE) {
            return !"GER40".equalsIgnoreCase(symbolCode);
        }
        if (strategy == Strategy.MMS || strategy == Strategy.ST_V3) {
            if (symbolCode == null) {
                return false;
            }
            for (String code : universe) {
                if (code.equalsIgnoreCase(symbolCode)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }
}
