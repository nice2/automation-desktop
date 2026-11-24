This is for DMP programming via Remote Link. The other files outside of the DMP folder in this repository are irrelevant.


## DMP Job Checker (dmp_job.py + dmp_ui.py)
Purpose: validation. Jobs are mostly copies of earlier jobs, so the tool's job is to catch mistakes and leftovers, not to replace Remote Link's editor.

- Workflow: export the job from Remote Link -> `dmp_ui.bat` -> Open job -> Review tab (fix what's flagged) -> edit zones / outputs if needed -> Save as next version (`v1 - X.xml` -> `v2 - X.xml`, never overwrites) -> import in Remote Link.
- CLI: `python dmp_job.py check <job.xml | folder> [--copied-from old.xml] [--all]`, plus `dump`, `decrypt`, `encrypt`.
- File format: hex of AES-128-ECB, key = MD5(export encryption key). The team key lives in `DMP/settings.ini` (`[dmp] key = ...`, gitignored) or DMP_EXPORT_KEY - never in tracked files. Remote Link leaves empty fields and sections out of exports (APP_KEY, REGION, OutNamesList, cell-path phones); `put()` adds missing fields in export order (`FIELD_ORDER`).
- `validate(panel, baseline, docs)` returns Findings (error / check / note / ok) per checklist step, many with a `fix` callable. Rules agreed with the user:
  - Not checked: account number format or comm account vs panel account (leading zeros, 4-5 digits), serial / MAC, device names (only that devices 1 and 2 exist).
  - Remote key / app key: notes only - the remote key is usually added right before sending.
  - Zones: recognised names must match the Device Cheat Sheet (`ZONE_RULES`); unrecognised names are notes (fine if the type is set). CO (incl. C/O) = Supervisory, AO Trouble, AS Trouble, and turns Latched Supervisory ON. SUPERVISORY / B4 / AFTER / AFT names are Supervisory.
  - Outputs only for correlations (`CORRELATION_RULES`: SMOKE DET ... TOP OF STAIRS -> DAMPER TOP OF STAIRS, AS output Steady); unused outputs are flagged.
  - Comm: test time 10:00 AM, delay 0; radio = Contact ID with receivers 555; phone lines = digital dialer with Rapid numbers and send path info on; network + cell = N primary + L backup with receiver IPs. Hidden/pasted characters in any text field are errors.
  - Profiles: 2 expected; profiles no user code uses can be removed in one click.
  - "Copied from" (baseline): name, customer, address, account, panel IP, comm account or an identical zone list still matching the source job are flagged.
  - "Job documents": pasted zone list (by zone number) and Rapid account / receiver numbers compared with the job.
- Comm setups can be rebuilt (`apply_comm`): radio / phone / network_cell, each path copied from the current path 1. Codes from real exports: COMM_TYPE C Contact ID, D Digital dialer, N Network, L Cell; PATH_TYPE P Primary, B Backup.
- `jobs/Blank Job.xml` is a new, untouched export kept for reference. Job exports are customer data: `DMP/**/*.xml` and `DMP/jobs/*` are gitignored.
- Confirmed codes: zone type FI (Fire), SV (Supervisory); messages A (Alarm), T (Trouble); output action S = Steady. A1 (Auxiliary 1, wireless repeater) is unconfirmed.
