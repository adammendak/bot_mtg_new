# HA-Hunt ST v3 — HTF M45 overlap / squeeze filter A/B (~12m)

Research only. **Do not merge as a default-ON change.** Pine switch
`blockHtfBandOverlap` defaults **OFF** (current book unchanged until toggled).

Locked: half+BE+HA, slow 144, M45 structure gate ON, capReg 2, flat, **ST 7/2**,
pyramid OFF. Same simulator and window as PR #144.

Tables (n, WR, sumR, PF, DD) are filled by:

```bash
python3 -m tools.ha_hunt_st_compare.run_htf_overlap_ab
```

Call pending the ~12m A/B.
