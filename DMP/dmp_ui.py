"""DMP Job Checker: open a Remote Link job export (.xml), review it against the XR-150 checklist and
the Device Cheat Sheet, fix what's wrong, edit zones/outputs, compare with the job it was copied
from and the job documents, and save it as the next version. All rules live in dmp_job.py."""
import configparser
import os
import tkinter as tk
import xml.etree.ElementTree as ET
from tkinter import filedialog, messagebox, simpledialog, ttk

import dmp_job as dj

HERE = os.path.dirname(os.path.abspath(__file__))

TYPE_CHOICES = ["Fire (FI)", "Supervisory (SV)", "Auxiliary 1 (A1)"]
MSG_CHOICES = ["Alarm (A)", "Trouble (T)"]
SEV_TEXT = {dj.ERROR: "ERROR", dj.CHECK: "CHECK", dj.NOTE: "note", dj.OK: "OK"}
SEV_COLORS = {dj.ERROR: "#ffd2d2", dj.CHECK: "#ffe7bf", dj.NOTE: "#e6f0ff", dj.OK: ""}


def code_of(choice):
    """'Fire (FI)' -> 'FI'; anything else returned as typed (upper case)."""
    choice = (choice or "").strip()
    return choice[choice.rfind("(") + 1:-1] if choice.endswith(")") else choice.upper()


def choice_of(code, choices):
    return next((c for c in choices if c.endswith(f"({code})")), code or "")


# ---------------------------------------------------------------- settings.ini (gitignored)

def read_settings():
    cp = configparser.ConfigParser(interpolation=None)
    if os.path.isfile(dj.SETTINGS_FILE):
        cp.read(dj.SETTINGS_FILE, encoding="utf-8-sig")
    return cp


def save_setting(section, key, value):
    cp = read_settings()
    if not cp.has_section(section):
        cp.add_section(section)
    cp.set(section, key, value)
    with open(dj.SETTINGS_FILE, "w", encoding="utf-8") as f:
        f.write("# Local settings for dmp_job.py / dmp_ui.py. Gitignored - do not commit.\n")
        cp.write(f)


# ---------------------------------------------------------------- paste dialog

class PasteDialog(tk.Toplevel):
    def __init__(self, master, initial_text, next_number):
        super().__init__(master)
        self.title("Paste zone list")
        self.transient(master)
        self.result = None
        self.next_number = next_number
        ttk.Label(self, justify="left", text=(
            "One zone per line. Any of these work:\n"
            "   5 WATERFLOW 1FL SPRKLR RM        5, WATERFLOW 1FL        5<Tab>WATERFLOW (copied from Excel)\n"
            "   WATERFLOW 1FL SPRKLR RM            (no number: continues from the previous zone)\n"
            "Settings come from the Device Cheat Sheet by name. Header rows are skipped.")).pack(
            anchor="w", padx=10, pady=(10, 4))
        self.text = tk.Text(self, width=90, height=16, font=("Consolas", 10), undo=True)
        self.text.pack(fill="both", expand=True, padx=10)
        self.text.insert("1.0", initial_text)
        self.text.bind("<<Modified>>", self._changed)
        opts = ttk.Frame(self)
        opts.pack(fill="x", padx=10, pady=(8, 0))
        ttk.Label(opts, text="Type for names the cheat sheet doesn't know:").pack(side="left")
        self.unknown_type = tk.StringVar(value="Supervisory (SV)")
        ttk.Combobox(opts, textvariable=self.unknown_type, values=TYPE_CHOICES, width=18,
                     state="readonly").pack(side="left", padx=6)
        bottom = ttk.Frame(self)
        bottom.pack(fill="x", padx=10, pady=10)
        self.mode = tk.StringVar(value="add")
        ttk.Radiobutton(bottom, text="Add / update zones", variable=self.mode, value="add",
                        command=self._changed).pack(side="left")
        ttk.Radiobutton(bottom, text="Replace the whole zone list", variable=self.mode, value="replace",
                        command=self._changed).pack(side="left", padx=10)
        self.preview = ttk.Label(bottom, text="")
        self.preview.pack(side="left", padx=10)
        ttk.Button(bottom, text="Cancel", command=self.destroy).pack(side="right")
        ttk.Button(bottom, text="OK", command=self._ok).pack(side="right", padx=6)
        self._changed()
        self.text.focus_set()
        self.grab_set()

    def _zones(self):
        start = 1 if self.mode.get() == "replace" else self.next_number
        return dj.parse_zone_text(self.text.get("1.0", "end"), start)

    def _changed(self, _event=None):
        self.text.edit_modified(False)
        zones = self._zones()
        unknown = [z["name"] for z in zones if dj.classify(z["name"]) is None]
        msg = f"{len(zones)} zone(s)"
        if unknown:
            msg += f", {len(unknown)} not recognised: " + ", ".join(unknown[:3]) + ("…" if len(unknown) > 3 else "")
        self.preview.config(text=msg)

    def _ok(self):
        self.result = (self.mode.get(), self._zones(), code_of(self.unknown_type.get()))
        self.destroy()


# ---------------------------------------------------------------- main window

class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.geometry("1220x760")
        self.minsize(1000, 620)
        style = ttk.Style(self)
        if "vista" in style.theme_names():
            style.theme_use("vista")
        style.configure("Treeview", rowheight=22)
        style.configure("Hint.TLabel", foreground="#666666")

        self.key = None
        self.path = None            # file the job was opened from / last saved to
        self.root_el = None         # <Panels> element being edited
        self.panel = None
        self.saved_panel = None     # job as it was opened / last saved (for "changes since opened")
        self.baseline_path = None   # job this one was copied from
        self.baseline_panel = None
        self.docs = {}
        self.findings = []
        self.dirty = False

        self._build_top()
        self.nb = ttk.Notebook(self)
        self.nb.pack(fill="both", expand=True, padx=8, pady=(0, 4))
        self._build_review_tab()
        self._build_zones_tab()
        self._build_outputs_tab()
        self._build_panel_tab()
        self._build_compare_tab()
        self._build_settings_tab()
        self.status = ttk.Label(self, text="", anchor="w")
        self.status.pack(fill="x", padx=10, pady=(0, 6))
        self.nb.bind("<<NotebookTabChanged>>", self._tab_changed)
        self.protocol("WM_DELETE_WINDOW", self._close)
        self._set_title()
        self.after(50, self._startup)

    # ------------------------------------------------------------ top bar
    def _build_top(self):
        top = ttk.Frame(self)
        top.pack(fill="x", padx=8, pady=(8, 4))
        ttk.Button(top, text="Open job…", command=self.open_job).pack(side="left")
        ttk.Button(top, text="Save as next version", command=self.save_next).pack(side="left", padx=4)
        ttk.Button(top, text="Save as…", command=self.save_as).pack(side="left")
        ttk.Separator(top, orient="vertical").pack(side="left", fill="y", padx=10)
        ttk.Label(top, text="Copied from:").pack(side="left")
        self.baseline_label = ttk.Label(top, text="(none)", style="Hint.TLabel", width=38)
        self.baseline_label.pack(side="left", padx=4)
        ttk.Button(top, text="Choose…", command=self.choose_baseline).pack(side="left")
        ttk.Button(top, text="Clear", command=self.clear_baseline).pack(side="left", padx=4)
        ttk.Button(top, text="Check a folder…", command=self.check_folder).pack(side="right")
        self.banner = tk.Label(self, text="Open a job exported from Remote Link to check it.",
                               anchor="w", padx=10, pady=6, font=("Segoe UI", 10, "bold"), bg="#eeeeee")
        self.banner.pack(fill="x", padx=8, pady=(0, 6))

    def _set_title(self):
        name = os.path.basename(self.path) if self.path else "no job open"
        self.title(f"DMP Job Checker - {name}{' *' if self.dirty else ''}")

    # ------------------------------------------------------------ review tab
    def _build_review_tab(self):
        tab = ttk.Frame(self.nb, padding=10)
        self.nb.add(tab, text="Review")
        row = ttk.Frame(tab)
        row.pack(fill="x")
        ttk.Button(row, text="Fix selected", command=self.fix_selected).pack(side="left")
        self.fix_all_btn = ttk.Button(row, text="Fix all", command=self.fix_all)
        self.fix_all_btn.pack(side="left", padx=4)
        self.show_ok = tk.BooleanVar(value=False)
        ttk.Checkbutton(row, text="Show passed checks", variable=self.show_ok,
                        command=self.refresh_review).pack(side="left", padx=12)
        ttk.Label(row, style="Hint.TLabel", text="Double-click a zone item to edit that zone.").pack(side="right")
        frame = ttk.Frame(tab)
        frame.pack(fill="both", expand=True, pady=(8, 0))
        self.rv = ttk.Treeview(frame, columns=("sev", "text", "fix"), show="tree headings")
        self.rv.heading("#0", text="Checklist step")
        self.rv.heading("sev", text="")
        self.rv.heading("text", text="Detail")
        self.rv.heading("fix", text="Fix")
        self.rv.column("#0", width=190, stretch=False)
        self.rv.column("sev", width=60, anchor="center", stretch=False)
        self.rv.column("text", width=640)
        self.rv.column("fix", width=240, stretch=False)
        for sev, color in SEV_COLORS.items():
            self.rv.tag_configure(sev, background=color)
        self.rv.tag_configure("ok", foreground="#1b6e20")
        self.rv.tag_configure("section", font=("Segoe UI", 9, "bold"))
        sb = ttk.Scrollbar(frame, orient="vertical", command=self.rv.yview)
        self.rv.configure(yscrollcommand=sb.set)
        self.rv.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        self.rv.bind("<Double-1>", self._review_double)
        self.rv_items = {}

    def refresh_review(self):
        self.rv.delete(*self.rv.get_children())
        self.rv_items = {}
        if self.panel is None:
            return
        groups = {}
        for i, f in enumerate(self.findings):
            if f.severity == dj.OK and not self.show_ok.get():
                continue
            if f.section not in groups:
                groups[f.section] = self.rv.insert("", "end", text=f.section, open=True, tags=("section",))
            iid = self.rv.insert(groups[f.section], "end", text="", tags=(f.severity,), values=(
                SEV_TEXT[f.severity], f.text, f.fix_label or ""))
            self.rv_items[iid] = f
        for sec, g in groups.items():
            worst = [f.severity for f in self.findings if f.section == sec]
            if dj.ERROR in worst:
                self.rv.item(g, tags=("section", dj.ERROR))
            elif dj.CHECK in worst:
                self.rv.item(g, tags=("section", dj.CHECK))
        c = dj.summary(self.findings)
        n_fix = sum(1 for f in self.findings if f.fix)
        self.fix_all_btn.config(text=f"Fix all ({n_fix})", state="normal" if n_fix else "disabled")
        text = f"{c[dj.ERROR]} error(s)   {c[dj.CHECK]} to check   {c[dj.NOTE]} note(s)   {c[dj.OK]} passed"
        if n_fix:
            text += f"   -   {n_fix} can be fixed with one click"
        color = "#ffd2d2" if c[dj.ERROR] else "#ffe7bf" if c[dj.CHECK] else "#d6f5d6"
        if not c[dj.ERROR] and not c[dj.CHECK]:
            text = "Everything on the checklist passed.   " + text
        self.banner.config(text=text, bg=color)
        self.nb.tab(0, text=f"Review ({c[dj.ERROR] + c[dj.CHECK]})")

    def _review_double(self, _event=None):
        sel = self.rv.selection()
        f = self.rv_items.get(sel[0]) if sel else None
        if f is None:
            return
        if f.zone is not None:
            self.nb.select(1)
            if self.zt.exists(str(f.zone)):
                self.zt.selection_set(str(f.zone))
                self.zt.see(str(f.zone))
        elif f.section in ("Panel Information", "Communication Paths", "Remote Options"):
            self.nb.select(3)
        elif f.section == "Output Information":
            self.nb.select(2)

    def fix_selected(self):
        fixes = [self.rv_items[i] for i in self.rv.selection() if i in self.rv_items and self.rv_items[i].fix]
        if not fixes:
            messagebox.showinfo("Fix", "Select items that have a fix (Fix column).", parent=self)
            return
        for f in fixes:
            f.fix()
        self.changed(f"Fixed {len(fixes)} item(s)")

    def fix_all(self):
        fixes = [f for f in self.findings if f.fix]
        if not fixes:
            return
        if not messagebox.askyesno("Fix all", "Apply these fixes?\n\n" + "\n".join(
                f"- {f.text}  ->  {f.fix_label}" for f in fixes[:25]) + ("\n…" if len(fixes) > 25 else ""),
                parent=self):
            return
        total = 0
        for _ in range(5):                      # a fix can reveal another (e.g. a new output)
            fixes = [f for f in dj.validate(self.panel) if f.fix]
            if not fixes:
                break
            for f in fixes:
                f.fix()
            total += len(fixes)
        self.changed(f"Applied {total} fix(es)")

    # ------------------------------------------------------------ zones tab
    def _build_zones_tab(self):
        tab = ttk.Frame(self.nb, padding=10)
        self.nb.add(tab, text="Zones")
        tab.columnconfigure(0, weight=1)
        tab.rowconfigure(1, weight=1)
        add = ttk.Frame(tab)
        add.grid(row=0, column=0, columnspan=3, sticky="ew", pady=(0, 6))
        ttk.Label(add, text="Zone #").pack(side="left")
        self.new_num = tk.StringVar()
        e_num = ttk.Entry(add, textvariable=self.new_num, width=6)
        e_num.pack(side="left", padx=4)
        ttk.Label(add, text="Name").pack(side="left", padx=(8, 0))
        self.new_name = tk.StringVar()
        self.new_name.trace_add("write", lambda *_: self._preview_new())
        self.e_name = ttk.Entry(add, textvariable=self.new_name, width=36)
        self.e_name.pack(side="left", padx=4)
        for w in (e_num, self.e_name):
            w.bind("<Return>", lambda _e: self.add_zone())
        ttk.Button(add, text="Add", command=self.add_zone).pack(side="left")
        self.new_preview = ttk.Label(add, text="", style="Hint.TLabel")
        self.new_preview.pack(side="left", padx=10)

        cols = ("num", "name", "type", "ao", "as", "out", "note")
        self.zt = ttk.Treeview(tab, columns=cols, show="headings", selectmode="extended")
        for c, text, w in (("num", "Zone", 48), ("name", "Name", 230), ("type", "Type", 90),
                           ("ao", "AO msg", 62), ("as", "AS msg", 62), ("out", "AS output", 190),
                           ("note", "Review", 200)):
            self.zt.heading(c, text=text)
            self.zt.column(c, width=w, minwidth=40, anchor="w", stretch=c == "note")
        self.zt.tag_configure("error", background=SEV_COLORS[dj.ERROR])
        self.zt.tag_configure("check", background=SEV_COLORS[dj.CHECK])
        self.zt.tag_configure("note", background=SEV_COLORS[dj.NOTE])
        sb = ttk.Scrollbar(tab, orient="vertical", command=self.zt.yview)
        self.zt.configure(yscrollcommand=sb.set)
        self.zt.grid(row=1, column=0, sticky="nsew")
        sb.grid(row=1, column=1, sticky="ns")
        self.zt.bind("<<TreeviewSelect>>", lambda _e: self._load_editor())
        self.zt.bind("<Delete>", lambda _e: self.delete_zones())
        self.zt.bind("<Control-v>", lambda _e: self.paste_zones(clipboard=True))

        legend = ttk.Frame(tab)
        legend.grid(row=2, column=0, columnspan=2, sticky="ew", pady=(4, 0))
        for color, text in ((SEV_COLORS[dj.ERROR], "error"), (SEV_COLORS[dj.CHECK], "differs from cheat sheet"),
                            (SEV_COLORS[dj.NOTE], "name not recognised")):
            tk.Label(legend, text="  ", bg=color).pack(side="left")
            ttk.Label(legend, text=text).pack(side="left", padx=(3, 12))
        ttk.Button(legend, text="Delete selected", command=self.delete_zones).pack(side="right")
        ttk.Button(legend, text="Paste list… (Ctrl+V)", command=self.paste_zones).pack(side="right", padx=4)

        ed = ttk.LabelFrame(tab, text="Selected zone", padding=10)
        ed.grid(row=1, column=2, rowspan=2, sticky="ns", padx=(10, 0))
        self.ed = {}
        r = 0
        for key, label, width in (("number", "Zone #", 8), ("name", "Name", 34)):
            ttk.Label(ed, text=label).grid(row=r, column=0, sticky="w", pady=3)
            v = tk.StringVar()
            ttk.Entry(ed, textvariable=v, width=width).grid(row=r, column=1, sticky="w")
            self.ed[key] = v
            r += 1
        for key, label, choices in (("type", "Type", TYPE_CHOICES), ("ao", "AO message", MSG_CHOICES),
                                    ("as", "AS message", MSG_CHOICES)):
            ttk.Label(ed, text=label).grid(row=r, column=0, sticky="w", pady=3)
            v = tk.StringVar()
            ttk.Combobox(ed, textvariable=v, values=choices, width=20).grid(row=r, column=1, sticky="w")
            self.ed[key] = v
            r += 1
        ttk.Label(ed, text="AS output").grid(row=r, column=0, sticky="w", pady=3)
        self.ed["out"] = tk.StringVar(value="None")
        self.out_combo = ttk.Combobox(ed, textvariable=self.ed["out"], state="readonly", width=31)
        self.out_combo.grid(row=r, column=1, sticky="w")
        self.out_combo.bind("<<ComboboxSelected>>", self._out_selected)
        r += 1
        self.ed_info = ttk.Label(ed, text="", style="Hint.TLabel", wraplength=300, justify="left")
        self.ed_info.grid(row=r, column=0, columnspan=2, sticky="w", pady=(8, 8))
        r += 1
        btns = ttk.Frame(ed)
        btns.grid(row=r, column=0, columnspan=2, sticky="w")
        ttk.Button(btns, text="Apply", command=self.apply_editor).pack(side="left")
        ttk.Button(btns, text="Use cheat sheet settings", command=self.rules_editor).pack(side="left", padx=4)
        r += 1
        ttk.Label(ed, style="Hint.TLabel", wraplength=300, justify="left", text=(
            "New zones get the Device Cheat Sheet settings for their name, and a correlation if the name "
            "needs one (SMOKE DET … TOP OF STAIRS -> DAMPER TOP OF STAIRS). All other zone settings are "
            "copied from a zone of the same type in this job.")).grid(
            row=r, column=0, columnspan=2, sticky="w", pady=(16, 0))

    def _zone_findings(self):
        by_zone = {}
        for f in self.findings:
            if f.zone is not None and f.section == "Zone Information":
                by_zone.setdefault(f.zone, []).append(f)
        return by_zone

    def refresh_zones(self, select=None):
        sel = select if select is not None else (self.zt.selection()[0] if self.zt.selection() else None)
        self.zt.delete(*self.zt.get_children())
        if self.panel is None:
            return
        outs = dj.job_outputs(self.panel)
        fz = self._zone_findings()
        zones = dj.job_zones(self.panel)
        for z in zones:
            fs = fz.get(z["number"], [])
            sevs = [f.severity for f in fs]
            tag = next((s for s in (dj.ERROR, dj.CHECK, dj.NOTE) if s in sevs), "")
            note = "; ".join(f.text.split(": ", 1)[-1] if ": " in f.text else f.text for f in fs)
            out = f"{z['out']} - {outs.get(z['out'], '?')}" if z["out"] else ""
            self.zt.insert("", "end", iid=str(z["number"]), tags=(tag,), values=(
                z["number"], z["name"], dj.TYPE_NAMES.get(z["type"], z["type"]),
                dj.MSG_NAMES.get(z["ao"], z["ao"]), dj.MSG_NAMES.get(z["as"], z["as"]), out, note))
        if sel is not None and self.zt.exists(str(sel)):
            self.zt.selection_set(str(sel))
            self.zt.see(str(sel))
        self.nb.tab(1, text=f"Zones ({len(zones)})")

    def _preview_new(self):
        name = self.new_name.get().strip().upper()
        rule = dj.classify(name) if name else None
        if not name:
            text = ""
        elif rule is None:
            text = "not recognised - you'll pick the type"
        else:
            text = "-> " + dj.zone_label(rule["type"], rule["ao"], rule["as"])
            if dj.correlation_for(name):
                text += f", correlates to {dj.correlation_for(name)}"
        self.new_preview.config(text=text)

    def _new_zone(self, number, name, unknown_type=None):
        rule = dj.classify(name)
        if rule is None:
            if unknown_type is None:
                choice = simpledialog.askstring(
                    "Zone type", f"'{name}' isn't in the cheat sheet.\nType: FI (Fire), SV (Supervisory) or A1",
                    initialvalue="SV", parent=self)
                if not choice:
                    return None
                unknown_type = choice.strip().upper()
            rule = {"type": unknown_type, "ao": "T", "as": "A" if unknown_type == "FI" else "T"}
        z = {"number": number, "name": name, "type": rule["type"], "ao": rule["ao"], "as": rule["as"], "out": 0}
        want = dj.correlation_for(name)
        if want:
            outs = dj.job_outputs(self.panel)
            num = next((k for k, v in sorted(outs.items()) if v == want), None)
            if num is None:
                num = next(i for i in range(1, 1000) if i not in outs)
                outs[num] = want
                dj.set_outputs(self.panel, outs)
            z["out"] = num
        return z

    def add_zone(self):
        if not self._need_job():
            return
        name = " ".join(self.new_name.get().upper().split())
        if not name:
            return
        zones = dj.job_zones(self.panel)
        num = self.new_num.get().strip() or str(max((z["number"] for z in zones), default=0) + 1)
        if not num.isdigit():
            messagebox.showerror("Add zone", f"Zone # '{num}' is not a number", parent=self)
            return
        num = int(num)
        if any(z["number"] == num for z in zones):
            if not messagebox.askyesno("Add zone", f"Zone {num} already exists. Replace its name and "
                                       f"settings?", parent=self):
                return
            zones = [z for z in zones if z["number"] != num]
        z = self._new_zone(num, name)
        if z is None:
            return
        self._set_zones(zones + [z])
        self.new_num.set("")
        self.new_name.set("")
        self.changed(f"Added zone {num}", select=num)
        self.e_name.focus_set()

    def paste_zones(self, clipboard=False):
        if not self._need_job():
            return
        text = ""
        if clipboard:
            try:
                text = self.clipboard_get()
            except tk.TclError:
                text = ""
        zones = dj.job_zones(self.panel)
        dlg = PasteDialog(self, text, max((z["number"] for z in zones), default=0) + 1)
        self.wait_window(dlg)
        if not dlg.result:
            return "break"
        mode, pasted, unknown_type = dlg.result
        current = {} if mode == "replace" else {z["number"]: z for z in zones}
        for p in pasted:
            old = current.get(p["number"])
            if old and dj.classify(p["name"]) is None and old["name"] == p["name"]:
                continue                         # same zone, keep its settings
            current[p["number"]] = self._new_zone(p["number"], p["name"], unknown_type)
        self._set_zones(list(current.values()))
        self.changed(f"Pasted {len(pasted)} zone(s)")
        return "break"

    def delete_zones(self):
        nums = {int(i) for i in self.zt.selection()}
        if nums and messagebox.askyesno("Delete zones", f"Delete {len(nums)} zone(s)?", parent=self):
            self._set_zones([z for z in dj.job_zones(self.panel) if z["number"] not in nums])
            self.changed(f"Deleted {len(nums)} zone(s)")

    def _set_zones(self, zones):
        try:
            dj.set_zones(self.panel, zones)
        except dj.JobError as e:
            messagebox.showerror("Zones", str(e), parent=self)

    def _out_choices(self):
        return ["None"] + [f"{n} - {nm}" for n, nm in sorted(dj.job_outputs(self.panel).items())] + ["New output…"]

    def _selected_zone(self):
        sel = self.zt.selection()
        if len(sel) != 1 or self.panel is None:
            return None
        return next((z for z in dj.job_zones(self.panel) if z["number"] == int(sel[0])), None)

    def _load_editor(self):
        z = self._selected_zone()
        if self.panel is not None:
            self.out_combo.config(values=self._out_choices())
        if z is None:
            for k in self.ed:
                self.ed[k].set("")
            self.ed_info.config(text="")
            return
        self.ed["number"].set(str(z["number"]))
        self.ed["name"].set(z["name"])
        self.ed["type"].set(choice_of(z["type"], TYPE_CHOICES))
        self.ed["ao"].set(choice_of(z["ao"], MSG_CHOICES))
        self.ed["as"].set(choice_of(z["as"], MSG_CHOICES))
        self.ed["out"].set(next((c for c in self._out_choices() if c.startswith(f"{z['out']} - ")), "None")
                           if z["out"] else "None")
        rule = dj.classify(z["name"])
        info = ("Cheat sheet: " + dj.zone_label(rule["type"], rule["ao"], rule["as"])) if rule else \
            "Cheat sheet: name not recognised (settings kept as they are)"
        if dj.correlation_for(z["name"]):
            info += f"\nCorrelation: output '{dj.correlation_for(z['name'])}'"
        for f in self._zone_findings().get(z["number"], []):
            if f.severity in (dj.ERROR, dj.CHECK):
                info += f"\n! {f.text}"
        self.ed_info.config(text=info)

    def _out_selected(self, _event=None):
        if self.ed["out"].get() != "New output…":
            return
        name = simpledialog.askstring("New output", "Output name (e.g. BOILER SHUTDOWN):", parent=self)
        if not name:
            self.ed["out"].set("None")
            return
        outs = dj.job_outputs(self.panel)
        num = next(i for i in range(1, 1000) if i not in outs)
        outs[num] = " ".join(name.upper().split())[:dj.OUTPUT_NAME_MAX]
        dj.set_outputs(self.panel, outs)
        self.out_combo.config(values=self._out_choices())
        self.ed["out"].set(f"{num} - {outs[num]}")
        self.dirty = True

    def apply_editor(self):
        z = self._selected_zone()
        if z is None:
            return
        num = self.ed["number"].get().strip()
        if not num.isdigit():
            messagebox.showerror("Zone", f"Zone # '{num}' is not a number", parent=self)
            return
        zones = [o for o in dj.job_zones(self.panel) if o["number"] != z["number"]]
        if any(o["number"] == int(num) for o in zones):
            messagebox.showerror("Zone", f"Zone {num} already exists", parent=self)
            return
        out = self.ed["out"].get()
        z.update(number=int(num), name=" ".join(self.ed["name"].get().upper().split()),
                 type=code_of(self.ed["type"].get()), ao=code_of(self.ed["ao"].get()),
                 out=0 if out in ("None", "", "New output…") else int(out.split(" - ")[0]))
        z["as"] = code_of(self.ed["as"].get())
        self._set_zones(zones + [z])
        self.changed(f"Updated zone {num}", select=int(num))

    def rules_editor(self):
        rule = dj.classify(self.ed["name"].get())
        if rule is None:
            messagebox.showinfo("Cheat sheet", "The cheat sheet doesn't know this name - set the type by hand.",
                                parent=self)
            return
        self.ed["type"].set(choice_of(rule["type"], TYPE_CHOICES))
        self.ed["ao"].set(choice_of(rule["ao"], MSG_CHOICES))
        self.ed["as"].set(choice_of(rule["as"], MSG_CHOICES))
        self.apply_editor()

    # ------------------------------------------------------------ outputs tab
    def _build_outputs_tab(self):
        tab = ttk.Frame(self.nb, padding=10)
        self.nb.add(tab, text="Outputs")
        ttk.Label(tab, wraplength=900, justify="left", text=(
            "Outputs are only needed for correlations (a zone's AS output, Steady). The Review tab flags "
            "outputs no zone uses, zones that need a correlation, and zones pointing at a missing output.")
        ).pack(anchor="w")
        self.ot = ttk.Treeview(tab, columns=("num", "name", "zones"), show="headings", height=10)
        for c, text, w in (("num", "Output", 70), ("name", "Name", 300), ("zones", "Used by zones", 400)):
            self.ot.heading(c, text=text)
            self.ot.column(c, width=w, anchor="w")
        self.ot.pack(fill="both", expand=True, pady=8)
        row = ttk.Frame(tab)
        row.pack(fill="x")
        ttk.Button(row, text="Add output…", command=self.add_output).pack(side="left")
        ttk.Button(row, text="Rename…", command=self.rename_output).pack(side="left", padx=4)
        ttk.Button(row, text="Remove", command=self.remove_output).pack(side="left")

    def refresh_outputs(self):
        self.ot.delete(*self.ot.get_children())
        if self.panel is None:
            return
        users = {}
        for z in dj.job_zones(self.panel):
            if z["out"]:
                users.setdefault(z["out"], []).append(str(z["number"]))
        outs = dj.job_outputs(self.panel)
        for num, name in sorted(outs.items()):
            self.ot.insert("", "end", iid=str(num), values=(num, name, ", ".join(users.get(num, [])) or "(not used)"))
        self.nb.tab(2, text=f"Outputs ({len(outs)})")

    def add_output(self):
        if not self._need_job():
            return
        name = simpledialog.askstring("Add output", "Output name:", parent=self)
        if not name:
            return
        outs = dj.job_outputs(self.panel)
        num = simpledialog.askinteger("Add output", "Output number:", parent=self, minvalue=1, maxvalue=999,
                                      initialvalue=next(i for i in range(1, 1000) if i not in outs))
        if num:
            outs[num] = " ".join(name.upper().split())[:dj.OUTPUT_NAME_MAX]
            dj.set_outputs(self.panel, outs)
            self.changed(f"Added output {num}")

    def rename_output(self):
        sel = self.ot.selection()
        if not sel:
            return
        num = int(sel[0])
        outs = dj.job_outputs(self.panel)
        name = simpledialog.askstring("Rename output", f"Name for output {num}:", initialvalue=outs[num], parent=self)
        if name:
            outs[num] = " ".join(name.upper().split())[:dj.OUTPUT_NAME_MAX]
            dj.set_outputs(self.panel, outs)
            self.changed(f"Renamed output {num}")

    def remove_output(self):
        sel = self.ot.selection()
        if not sel:
            return
        num = int(sel[0])
        zones = dj.job_zones(self.panel)
        using = [z for z in zones if z["out"] == num]
        if using and not messagebox.askyesno("Remove output", f"Zone(s) {', '.join(str(z['number']) for z in using)} "
                                             f"use output {num}. Remove the correlation from them too?", parent=self):
            return
        for z in using:
            z["out"] = 0
        self._set_zones(zones)
        outs = dj.job_outputs(self.panel)
        outs.pop(num, None)
        dj.set_outputs(self.panel, outs)
        self.changed(f"Removed output {num}")

    # ------------------------------------------------------------ panel & comm tab
    def _build_panel_tab(self):
        tab = ttk.Frame(self.nb, padding=10)
        self.nb.add(tab, text="Panel & Comm")
        self.panel_vars, self.comm_vars, self.remote_vars = {}, {}, {}
        self.comm_widgets = {}
        left = ttk.LabelFrame(tab, text="Panel Information", padding=10)
        left.grid(row=0, column=0, rowspan=2, sticky="nsew", padx=(0, 10))
        right = ttk.LabelFrame(tab, text="Communication Paths", padding=10)
        right.grid(row=0, column=1, sticky="nsew")
        remote = ttk.LabelFrame(tab, text="Remote Options", padding=10)
        remote.grid(row=1, column=1, sticky="nsew", pady=(10, 0))
        tab.columnconfigure(0, weight=1)
        tab.columnconfigure(1, weight=1)

        def row(frame, r, key, label, store, width=30):
            ttk.Label(frame, text=label).grid(row=r, column=0, sticky="w", pady=3)
            v = tk.StringVar()
            e = ttk.Entry(frame, textvariable=v, width=width)
            e.grid(row=r, column=1, sticky="w", padx=6)
            store[key] = v
            return e

        for i, (key, _tag, label) in enumerate(dj.PANEL_FIELDS):
            row(left, i, key, label, self.panel_vars)
        ttk.Button(left, text="Apply panel information", command=self.apply_panel_form).grid(
            row=len(dj.PANEL_FIELDS), column=0, columnspan=2, sticky="w", pady=(10, 0))

        ttk.Label(right, text="Setup").grid(row=0, column=0, sticky="w", pady=3)
        self.comm_mode = tk.StringVar()
        cb = ttk.Combobox(right, textvariable=self.comm_mode, values=list(dj.COMM_MODES.values()),
                          state="readonly", width=28)
        cb.grid(row=0, column=1, sticky="w", padx=6)
        cb.bind("<<ComboboxSelected>>", lambda _e: self._comm_mode_changed(user=True))
        r = 1
        for key, label in dj.COMM_FIELDS:
            self.comm_widgets[key] = row(right, r, key, label, self.comm_vars, width=22)
            r += 1
        ttk.Label(right, text="Send path information").grid(row=r, column=0, sticky="w", pady=3)
        self.send_path = tk.StringVar(value="")
        ttk.Combobox(right, textvariable=self.send_path, values=["", "yes", "no"], state="readonly",
                     width=8).grid(row=r, column=1, sticky="w", padx=6)
        r += 1
        ttk.Button(right, text="Rebuild communication paths", command=self.apply_comm_form).grid(
            row=r, column=0, columnspan=2, sticky="w", pady=(10, 0))
        r += 1
        self.comm_paths_label = ttk.Label(right, text="", style="Hint.TLabel", justify="left", wraplength=560)
        self.comm_paths_label.grid(row=r, column=0, columnspan=3, sticky="w", pady=(8, 0))
        r += 1
        ttk.Label(right, style="Hint.TLabel", justify="left", wraplength=560, text=(
            "Radio (AES): 1 path, Contact ID, receivers 555.  Phone lines: 1 path, digital dialer, Rapid "
            "numbers typed digits only, send path info on.  Network + cell: path 1 Network (primary) + path 2 "
            "Cell (backup), check-in on.  Blank: test 10:00 AM, delay 0, ports 2001.")).grid(
            row=r, column=0, columnspan=3, sticky="w", pady=(6, 0))

        for i, (key, _tag, label) in enumerate(dj.REMOTE_FIELDS):
            row(remote, i, key, label, self.remote_vars, width=22)
        ttk.Button(remote, text="Apply remote options", command=self.apply_remote_form).grid(
            row=len(dj.REMOTE_FIELDS), column=0, columnspan=2, sticky="w", pady=(10, 0))
        ttk.Label(remote, style="Hint.TLabel", wraplength=560, justify="left", text=(
            "The remote key is usually added right before sending to the panel, so a job file without one is "
            "normal.")).grid(row=len(dj.REMOTE_FIELDS) + 1, column=0, columnspan=3, sticky="w", pady=(6, 0))

    def _mode_key(self):
        return next((k for k, v in dj.COMM_MODES.items() if v == self.comm_mode.get()), None)

    def _comm_mode_changed(self, user=False):
        mode = self._mode_key()
        for key, w in self.comm_widgets.items():
            w.configure(state="normal" if mode and key in dj.MODE_FIELDS[mode] else "disabled")
        if user and mode == "network_cell":       # last receiver IPs used on this PC
            st = read_settings()
            for key in ("receiver_ip", "receiver_port", "backup_ip", "backup_port"):
                if not self.comm_vars[key].get() or self.comm_vars[key].get() == "0.0.0.0":
                    self.comm_vars[key].set(st.get("ui", "last_" + key, fallback=""))

    def load_panel_form(self):
        if self.panel is None:
            return
        a = self.panel.find("Account")
        for key, tag, _l in dj.PANEL_FIELDS:
            self.panel_vars[key].set(dj.get(a, tag) or "")
        cv = dj.comm_values(self.panel)
        for key, v in self.comm_vars.items():
            v.set(cv.get(key, ""))
        self.send_path.set(cv.get("send_path", ""))
        mode = dj.comm_mode_of(self.panel)
        self.comm_mode.set(dj.COMM_MODES.get(mode, ""))
        self._comm_mode_changed()
        self.comm_paths_label.config(text="Current paths:\n" + "\n".join(
            dj.describe_path(c) for c in self.panel.findall("CommPathList/CommPath")))
        ro = self.panel.find("RmtOpts")
        for key, tag, _l in dj.REMOTE_FIELDS:
            self.remote_vars[key].set(dj.get(ro, tag) or "")

    def apply_panel_form(self):
        if not self._need_job():
            return
        changes = dj.apply_panel(self.panel, {k: v.get() for k, v in self.panel_vars.items()})
        self.changed("; ".join(changes) if changes else "No panel changes")

    def apply_comm_form(self):
        if not self._need_job():
            return
        mode = self._mode_key()
        if not mode:
            messagebox.showinfo("Communication paths", "Pick a setup first.", parent=self)
            return
        values = {k: v.get() for k, v in self.comm_vars.items() if k in dj.MODE_FIELDS[mode]}
        if self.send_path.get():
            values["send_path"] = self.send_path.get()
        try:
            changes = dj.apply_comm(self.panel, mode, values)
        except dj.JobError as e:
            messagebox.showerror("Communication paths", str(e), parent=self)
            return
        if mode == "network_cell":
            for key in ("receiver_ip", "receiver_port", "backup_ip", "backup_port"):
                if values.get(key, "").strip():
                    save_setting("ui", "last_" + key, values[key].strip())
        self.changed(changes[0] if changes else "Communication paths unchanged")

    def apply_remote_form(self):
        if not self._need_job():
            return
        changes = dj.apply_remote(self.panel, {k: v.get() for k, v in self.remote_vars.items()})
        self.changed("; ".join(changes) if changes else "No remote option changes")

    # ------------------------------------------------------------ compare tab
    def _build_compare_tab(self):
        tab = ttk.Frame(self.nb, padding=10)
        self.nb.add(tab, text="Compare")
        docs = ttk.LabelFrame(tab, text="Job documents - paste what the job should have; differences show on "
                                        "the Review tab", padding=8)
        docs.pack(fill="x")
        ttk.Label(docs, text="Zone list (from the drawings / device schedule):").grid(row=0, column=0, sticky="w")
        self.docs_text = tk.Text(docs, width=60, height=7, font=("Consolas", 9))
        self.docs_text.grid(row=1, column=0, rowspan=4, sticky="nsew")
        docs.columnconfigure(0, weight=1)
        self.docs_vars = {}
        for i, (key, label) in enumerate((("account", "Rapid account #"), ("first_phone", "Rapid receiver 1"),
                                          ("second_phone", "Rapid receiver 2"))):
            ttk.Label(docs, text=label).grid(row=i + 1, column=1, sticky="e", padx=(10, 4))
            v = tk.StringVar()
            ttk.Entry(docs, textvariable=v, width=18).grid(row=i + 1, column=2, sticky="w")
            self.docs_vars[key] = v
        ttk.Button(docs, text="Check against the job", command=self.apply_docs).grid(row=4, column=1, columnspan=2,
                                                                                      sticky="e", pady=(6, 0))

        row = ttk.Frame(tab)
        row.pack(fill="x", pady=(10, 4))
        ttk.Label(row, text="Differences:").pack(side="left")
        self.diff_mode = tk.StringVar(value="opened")
        ttk.Radiobutton(row, text="what I changed since opening / saving", variable=self.diff_mode,
                        value="opened", command=self.refresh_diff).pack(side="left", padx=8)
        ttk.Radiobutton(row, text="this job vs. the job it was copied from", variable=self.diff_mode,
                        value="baseline", command=self.refresh_diff).pack(side="left")
        frame = ttk.Frame(tab)
        frame.pack(fill="both", expand=True)
        self.dt = ttk.Treeview(frame, columns=("rec", "field", "old", "new"), show="tree headings")
        self.dt.heading("#0", text="Section")
        for c, text, w in (("rec", "Record", 240), ("field", "Setting", 200), ("old", "Before", 220),
                           ("new", "Now", 220)):
            self.dt.heading(c, text=text)
            self.dt.column(c, width=w)
        self.dt.column("#0", width=170)
        sb = ttk.Scrollbar(frame, orient="vertical", command=self.dt.yview)
        self.dt.configure(yscrollcommand=sb.set)
        self.dt.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")

    def apply_docs(self):
        self.docs = {k: v.get().strip() for k, v in self.docs_vars.items()}
        self.docs["zones"] = self.docs_text.get("1.0", "end")
        self.revalidate()
        self.nb.select(0)

    def refresh_diff(self):
        self.dt.delete(*self.dt.get_children())
        if self.panel is None:
            return
        other = self.saved_panel if self.diff_mode.get() == "opened" else self.baseline_panel
        if other is None:
            self.dt.insert("", "end", text="(choose the job it was copied from at the top)")
            return
        groups = {}
        rows = dj.diff_panels(other, self.panel)
        names = {"Type": dj.TYPE_NAMES, "AO message": dj.MSG_NAMES, "AS message": dj.MSG_NAMES,
                 "Comm type": dj.COMM_TYPE_NAMES, "Path type": dj.PATH_TYPE_NAMES}
        for sec, rec, field, old, new in rows:
            if sec not in groups:
                groups[sec] = self.dt.insert("", "end", text=sec, open=True)
            m = names.get(field, {})
            self.dt.insert(groups[sec], "end", values=(rec, field, "" if old is None else m.get(old, old),
                                                      "" if new is None else m.get(new, new)))
        if not rows:
            self.dt.insert("", "end", text="(no differences)")

    # ------------------------------------------------------------ all settings tab
    def _build_settings_tab(self):
        tab = ttk.Frame(self.nb, padding=10)
        self.nb.add(tab, text="All settings")
        row = ttk.Frame(tab)
        row.pack(fill="x")
        ttk.Label(row, text="Search:").pack(side="left")
        self.settings_filter = tk.StringVar()
        e = ttk.Entry(row, textvariable=self.settings_filter, width=28)
        e.pack(side="left", padx=6)
        e.bind("<KeyRelease>", lambda _e: self.refresh_settings())
        self.show_secrets = tk.BooleanVar(value=False)
        ttk.Checkbutton(row, text="Show keys and codes", variable=self.show_secrets,
                        command=self.refresh_settings).pack(side="left", padx=10)
        frame = ttk.Frame(tab)
        frame.pack(fill="both", expand=True, pady=(6, 0))
        self.st = ttk.Treeview(frame, columns=("value",), show="tree headings")
        self.st.heading("#0", text="Section / field")
        self.st.heading("value", text="Value")
        self.st.column("#0", width=460)
        self.st.column("value", width=500)
        sb = ttk.Scrollbar(frame, orient="vertical", command=self.st.yview)
        self.st.configure(yscrollcommand=sb.set)
        self.st.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")

    def refresh_settings(self):
        st = self.st
        open_now = {st.item(i, "text") for i in st.get_children() if st.item(i, "open")}
        st.delete(*st.get_children())
        if self.panel is None:
            return
        needle = self.settings_filter.get().strip().lower()

        def shown(rec, tag):
            v = dj.get(rec, tag)
            if tag in dj.SECRET_FIELDS and not self.show_secrets.get():
                return "(hidden)" if v else "(blank)"
            return "" if v is None else v

        def add_fields(parent, rec):
            n = 0
            for e in rec:
                if len(e) or e.tag in ("ACCOUNT_ID", "LAST_PNL_CMP"):
                    continue
                label = dj.FIELD_LABELS.get(e.tag)
                text = f"{label}  ({e.tag})" if label else e.tag
                v = shown(rec, e.tag)
                if needle and needle not in f"{text} {v}".lower():
                    continue
                st.insert(parent, "end", text=text, values=(v,))
                n += 1
            return n

        for sec in self.panel:
            title = dj.SECTION_LABELS.get(sec.tag, sec.tag)
            node = st.insert("", "end", text=title, open=bool(needle) or title in open_now)
            records = [c for c in sec if len(c)]
            count = 0
            if records:
                for i, rec in enumerate(records):
                    k = dj._rec_key(rec, i)
                    rnode = st.insert(node, "end", text=dj._rec_label(rec, k), open=bool(needle))
                    c = add_fields(rnode, rec)
                    if not c:
                        st.delete(rnode)
                    count += c
            else:
                count = add_fields(node, sec)
            if needle and not count:
                st.delete(node)

    # ------------------------------------------------------------ files
    def _startup(self):
        try:
            self.key = dj.default_key()
        except dj.JobError:
            key = simpledialog.askstring(
                "Export encryption key",
                "Enter the encryption key you type when exporting from Remote Link.\n"
                "It is saved in DMP\\settings.ini on this PC (not committed to git).", parent=self, show="*")
            if not key:
                self.destroy()
                return
            save_setting("dmp", "key", key.strip())
            self.key = key.strip()
        self.set_status("Open a job exported from Remote Link.")

    def _last_dir(self):
        d = read_settings().get("ui", "last_dir", fallback="")
        return d if d and os.path.isdir(d) else os.path.join(HERE, "jobs")

    def _need_job(self):
        if self.panel is None:
            messagebox.showinfo("No job", "Open a job first.", parent=self)
            return False
        return True

    def _confirm_discard(self):
        return not self.dirty or messagebox.askyesno(
            "Unsaved changes", "This job has changes that aren't saved. Discard them?", parent=self)

    def open_job(self, path=None):
        if not self._confirm_discard():
            return
        path = path or filedialog.askopenfilename(
            parent=self, title="Open job", initialdir=self._last_dir(),
            filetypes=[("DMP Panel Information", "*.xml"), ("All files", "*.*")])
        if not path:
            return
        try:
            root = dj.load_job(path, self.key)
        except dj.JobError as e:
            messagebox.showerror("Open job", str(e), parent=self)
            return
        self.path = os.path.normpath(path)
        self.root_el = root
        self.panel = root.find("Panel")
        self.saved_panel = ET.fromstring(ET.tostring(self.panel))
        self.docs = {}
        self.dirty = False
        save_setting("ui", "last_dir", os.path.dirname(self.path))
        self.load_panel_form()
        self.revalidate()
        self.nb.select(0)
        self.set_status(f"Opened {self.path}")

    def _write(self, path):
        if os.path.normcase(os.path.abspath(path)) == os.path.normcase(os.path.abspath(self.path or "")) and \
                not messagebox.askyesno("Overwrite", "Overwrite the file you opened? (Save as next version keeps "
                                        "it.)", parent=self):
            return False
        try:
            dj.save_job(path, self.root_el, self.key)
            dj.load_job(path, self.key)          # read it back before trusting it
        except (dj.JobError, OSError) as e:
            messagebox.showerror("Save", f"Couldn't save: {e}", parent=self)
            return False
        self.path = os.path.normpath(path)
        self.saved_panel = ET.fromstring(ET.tostring(self.panel))
        self.dirty = False
        self._set_title()
        self.refresh_diff()
        c = dj.summary(self.findings)
        msg = f"Saved {self.path}"
        if c[dj.ERROR] or c[dj.CHECK]:
            msg += f"  -  still {c[dj.ERROR]} error(s), {c[dj.CHECK]} to check"
        self.set_status(msg)
        messagebox.showinfo("Saved", msg + "\n\nImport it in Remote Link, then send to the panel.", parent=self)
        return True

    def save_next(self):
        if self._need_job():
            path = dj.next_version_path(self.path)
            if messagebox.askyesno("Save as next version", f"Save as\n{os.path.basename(path)}\nin "
                                   f"{os.path.dirname(path)}?", parent=self):
                self._write(path)

    def save_as(self):
        if not self._need_job():
            return
        nxt = dj.next_version_path(self.path)
        path = filedialog.asksaveasfilename(
            parent=self, title="Save job", initialdir=os.path.dirname(nxt), initialfile=os.path.basename(nxt),
            defaultextension=".xml", filetypes=[("DMP Panel Information", "*.xml")], confirmoverwrite=False)
        if path:
            self._write(path)

    def choose_baseline(self):
        path = filedialog.askopenfilename(
            parent=self, title="Job this one was copied from", initialdir=self._last_dir(),
            filetypes=[("DMP Panel Information", "*.xml"), ("All files", "*.*")])
        if not path:
            return
        try:
            self.baseline_panel = dj.load_panel(path, self.key)
        except dj.JobError as e:
            messagebox.showerror("Copied from", str(e), parent=self)
            return
        self.baseline_path = path
        self.baseline_label.config(text=os.path.basename(path))
        self.revalidate()

    def clear_baseline(self):
        self.baseline_path = self.baseline_panel = None
        self.baseline_label.config(text="(none)")
        self.revalidate()

    def check_folder(self):
        folder = filedialog.askdirectory(parent=self, title="Check every job in a folder",
                                         initialdir=self._last_dir())
        if not folder:
            return
        win = tk.Toplevel(self)
        win.title(f"Folder check - {folder}")
        win.geometry("1000x600")
        ttk.Label(win, text="Double-click a job to open it.", style="Hint.TLabel").pack(anchor="w", padx=10, pady=6)
        tree = ttk.Treeview(win, columns=("sev", "text"), show="tree headings")
        tree.heading("#0", text="Job")
        tree.heading("sev", text="")
        tree.heading("text", text="Result")
        tree.column("#0", width=380)
        tree.column("sev", width=60, anchor="center")
        tree.column("text", width=520)
        for sev, color in SEV_COLORS.items():
            tree.tag_configure(sev, background=color)
        tree.pack(fill="both", expand=True, padx=10, pady=(0, 10))
        paths = {}
        for p in dj.find_jobs(folder):
            try:
                fs = dj.validate(dj.load_panel(p, self.key))
            except dj.JobError:
                continue
            c = dj.summary(fs)
            tag = dj.ERROR if c[dj.ERROR] else dj.CHECK if c[dj.CHECK] else ""
            node = tree.insert("", "end", text=os.path.basename(p), tags=(tag,),
                               values=("", f"{c[dj.ERROR]} error(s), {c[dj.CHECK]} to check, "
                                           f"{sum(1 for f in fs if f.fix)} fixable"))
            paths[node] = p
            for f in fs:
                if f.severity in (dj.ERROR, dj.CHECK):
                    tree.insert(node, "end", text=f.section, tags=(f.severity,), values=(SEV_TEXT[f.severity], f.text))
        if not paths:
            tree.insert("", "end", text="(no job exports made with this key in that folder)")

        def opener(_e):
            sel = tree.selection()
            node = sel[0] if sel else None
            while node and node not in paths:
                node = tree.parent(node)
            if node:
                self.open_job(paths[node])
                self.lift()
        tree.bind("<Double-1>", opener)

    # ------------------------------------------------------------ refresh
    def revalidate(self):
        self.findings = dj.validate(self.panel, self.baseline_panel, self.docs) if self.panel is not None else []
        self.refresh_review()
        self.refresh_zones()
        self.refresh_outputs()
        self._load_editor()
        self.refresh_diff()
        if self.nb.index("current") == 5:
            self.refresh_settings()
        self._set_title()

    def changed(self, message, select=None):
        self.dirty = True
        self.revalidate()
        if select is not None:
            self.refresh_zones(select=select)
        self.load_panel_form()
        self.set_status(message)

    def _tab_changed(self, _event=None):
        idx = self.nb.index("current")
        if idx == 5:
            self.refresh_settings()
        elif idx == 4:
            self.refresh_diff()

    def set_status(self, text):
        self.status.config(text=text)

    def _close(self):
        if self._confirm_discard():
            self.destroy()


if __name__ == "__main__":
    App().mainloop()
