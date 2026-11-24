"""Check and edit DMP Remote Link job exports ("DMP Panel Information (*.xml)").

The point is validation: open an export, see everything that differs from the XR-150 checklist
and the Device Cheat Sheet, fix it (one click where the answer is clear), compare it with the job
it was copied from and with the job documents, and save the result as the next version.

File format (Remote Link 2.52):
  - The .xml file is uppercase hex of AES-128-ECB ciphertext. The AES key is MD5 of the
    encryption key typed in the export dialog.
  - The plaintext is <Panels><Panel>...</Panel></Panels>, one child per Remote Link screen
    (Account, CommPathList, ZoneInfoList, OutNamesList, SysOpts, ...). Bytes after </Panels>
    are just padding up to the 16-byte block size.
  - Every value has a DataType attribute: 1 = text, stored as base64 of the text padded with
    NUL bytes to a multiple of 3; 3 = integer; 5 = True/False; 11 = date/time; 14 = record ID.
  - Empty fields and empty sections are left out of exports entirely.

Commands:
  python dmp_job.py check <job.xml | folder>      checklist for one job or every job in a folder
  python dmp_job.py dump  job.xml                 print panel, comm paths, outputs and zones
  python dmp_job.py decrypt job.xml -o x.xml      write the readable XML (for looking around)
  python dmp_job.py encrypt x.xml -o job.xml      encrypt a readable XML back into a job file
  dmp_ui.bat                                      the window: review, fix, zones, compare, save
Encryption key: --key NNNN, else the DMP_EXPORT_KEY environment variable, else
`key = NNNN` under [dmp] in settings.ini next to this script (settings.ini is gitignored,
so the key never goes into the repo).
"""
import argparse
import base64
import configparser
import copy
import csv
import datetime
import glob
import hashlib
import os
import re
import sys
import xml.etree.ElementTree as ET


class JobError(Exception):
    """A problem the user has to fix (bad input, unreadable file, ...)."""


SETTINGS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "settings.ini")
BLANK_JOB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "jobs", "Blank Job.xml")


def default_key():
    """The team's export key, kept out of the repo (env var or gitignored settings.ini)."""
    key = os.environ.get("DMP_EXPORT_KEY", "").strip()
    if not key and os.path.isfile(SETTINGS_FILE):
        cp = configparser.ConfigParser(interpolation=None)
        cp.read(SETTINGS_FILE, encoding="utf-8-sig")
        key = cp.get("dmp", "key", fallback="").strip()
    if not key:
        raise JobError(f"No export encryption key. Create {SETTINGS_FILE} containing:\n"
                       f"  [dmp]\n  key = <the key you type when exporting in Remote Link>\n"
                       f"or set DMP_EXPORT_KEY, or pass --key.")
    return key


# ---------------------------------------------------------------- AES-128 (ECB only)
# Plain-Python AES so the tool needs nothing beyond a standard Python install.

def _xtime(a):
    a <<= 1
    return (a ^ 0x11B) & 0xFF if a & 0x100 else a


def _mul(a, b):
    r = 0
    while b:
        if b & 1:
            r ^= a
        a = _xtime(a)
        b >>= 1
    return r


def _build_sbox():
    sbox = [0] * 256
    p = q = 1
    while True:
        p = p ^ _xtime(p)                      # multiply p by 3
        q ^= q << 1                            # divide q by 3
        q ^= q << 2
        q ^= q << 4
        q &= 0xFF
        if q & 0x80:
            q ^= 0x09
        x = q ^ (q << 1 | q >> 7) ^ (q << 2 | q >> 6) ^ (q << 3 | q >> 5) ^ (q << 4 | q >> 4)
        sbox[p] = (x ^ 0x63) & 0xFF
        if p == 1:
            break
    sbox[0] = 0x63
    return sbox


_SBOX = _build_sbox()
_INV_SBOX = [0] * 256
for _i, _v in enumerate(_SBOX):
    _INV_SBOX[_v] = _i


def _expand_key(key):
    words = [list(key[i:i + 4]) for i in range(0, 16, 4)]
    rcon = 1
    for i in range(4, 44):
        t = list(words[i - 1])
        if i % 4 == 0:
            t = t[1:] + t[:1]
            t = [_SBOX[b] for b in t]
            t[0] ^= rcon
            rcon = _xtime(rcon)
        words.append([a ^ b for a, b in zip(words[i - 4], t)])
    return [sum(words[r * 4:r * 4 + 4], []) for r in range(11)]


_SHIFT = [0, 5, 10, 15, 4, 9, 14, 3, 8, 13, 2, 7, 12, 1, 6, 11]
_INV_SHIFT = [_SHIFT.index(i) for i in range(16)]


def _encrypt_block(s, rk):
    s = [a ^ b for a, b in zip(s, rk[0])]
    for r in range(1, 11):
        s = [_SBOX[s[i]] for i in _SHIFT]
        if r != 10:
            m = []
            for c in range(4):
                a = s[c * 4:c * 4 + 4]
                m += [_mul(a[0], 2) ^ _mul(a[1], 3) ^ a[2] ^ a[3],
                      a[0] ^ _mul(a[1], 2) ^ _mul(a[2], 3) ^ a[3],
                      a[0] ^ a[1] ^ _mul(a[2], 2) ^ _mul(a[3], 3),
                      _mul(a[0], 3) ^ a[1] ^ a[2] ^ _mul(a[3], 2)]
            s = m
        s = [a ^ b for a, b in zip(s, rk[r])]
    return s


def _decrypt_block(s, rk):
    s = [a ^ b for a, b in zip(s, rk[10])]
    for r in range(9, -1, -1):
        s = [_INV_SBOX[s[i]] for i in _INV_SHIFT]
        s = [a ^ b for a, b in zip(s, rk[r])]
        if r != 0:
            m = []
            for c in range(4):
                a = s[c * 4:c * 4 + 4]
                m += [_mul(a[0], 14) ^ _mul(a[1], 11) ^ _mul(a[2], 13) ^ _mul(a[3], 9),
                      _mul(a[0], 9) ^ _mul(a[1], 14) ^ _mul(a[2], 11) ^ _mul(a[3], 13),
                      _mul(a[0], 13) ^ _mul(a[1], 9) ^ _mul(a[2], 14) ^ _mul(a[3], 11),
                      _mul(a[0], 11) ^ _mul(a[1], 13) ^ _mul(a[2], 9) ^ _mul(a[3], 14)]
            s = m
    return s


def _aes_ecb(data, key, decrypt):
    rk = _expand_key(key)
    fn = _decrypt_block if decrypt else _encrypt_block
    out = bytearray()
    for i in range(0, len(data), 16):
        out += bytes(fn(list(data[i:i + 16]), rk))
    return bytes(out)


# ---------------------------------------------------------------- file level

def _aes_key(key):
    return hashlib.md5(str(key).encode("ascii")).digest()


def decrypt_file(path, key):
    """Return the readable XML text of a Remote Link job export."""
    if not os.path.isfile(path):
        raise JobError(f"job file not found: {path}")
    text = open(path, encoding="latin1").read().strip()
    try:
        data = bytes.fromhex(text)
    except ValueError:
        raise JobError(f"{path}: not a Remote Link job export (expected hex text)")
    plain = _aes_ecb(data, _aes_key(key), decrypt=True).decode("latin1")
    end = plain.rfind("</Panels>")
    if not plain.startswith("<Panels>") or end < 0:
        raise JobError(f"{path}: could not decrypt - wrong encryption key?")
    return plain[:end + len("</Panels>")]


def encrypt_text(xml_text, key):
    data = xml_text.encode("latin1")
    data += b"\0" * (-len(data) % 16)
    return _aes_ecb(data, _aes_key(key), decrypt=False).hex().upper()


def write_job(path, xml_text, key):
    with open(path, "w", encoding="ascii", newline="") as f:
        f.write(encrypt_text(xml_text, key))


# ---------------------------------------------------------------- field helpers

def get(el, tag):
    """Value of a child field as Python text (DataType 1 decoded)."""
    e = el.find(tag)
    if e is None:
        return None
    if e.get("DataType") == "1":
        return base64.b64decode(e.text or "").split(b"\0")[0].decode("latin1")
    return e.text


# Remote Link leaves empty fields out of its exports, so a field we need to write may be missing.
# Field order as real exports write it, used to put an added field in the usual place.
FIELD_ORDER = {
    "Account": ["ID", "RECVR_NUM", "ACCOUNT_NUM", "SERIAL_NUMBER", "MAC_ADDRESS", "FEATURES_STATUS", "NAME",
                "PARTITIONS", "PANEL_MODEL", "PANEL_VER", "PANEL_DATE", "ADDRESS", "CITY", "STATE", "ZIP",
                "CONNECT_TYPE", "PANEL_PHONE", "PANEL_IP", "PANEL_IP_PRT", "PNL_COM_PRT", "PNL_BAUD", "DIAL",
                "PROG_ON_FILE", "MAIN_ID", "ALLOW_DEFERRAL", "ACCOUNT_TYPE", "LAST_CHANGE", "NEW_PANEL",
                "CUSTOMER", "REGION", "SIM_LEVEL", "BACKUP_CONNECT_TYPE"],
    "CommPath": ["ACCOUNT_ID", "LAST_CHANGE", "LAST_PNL_CMP", "ACCT_NUM", "TRN_DELAY", "NUMBER", "COMM_TYPE",
                 "PATH_TYPE", "TEST_RPT", "FREQ_NUM", "FREQ_UNIT", "TEST_DAY", "TEST_TIME", "USE_CHKIN",
                 "EXT_CHKIN", "FAIL_TIME", "ENCRYPTION", "ENCRSCHEME", "IPV6", "REMOTE_IP", "REMOTEIPV6",
                 "RECVR_PORT", "FIRST_PHON", "SEC_PHONE", "RETRY_TIME", "SUBST_CODE", "APN", "FAIL_FREQ",
                 "PROTOCOL", "DUP_ALARMS", "SEND_FAIL", "SEND_PATH", "USE_893A", "ALRM_SWITC", "ALARM",
                 "SUPV_TRBL", "OPNCLS_USE", "DOOR_ACCES", "PANIC_TEST"],
    "RmtOpts": ["ACCOUNT_ID", "LAST_CHANGE", "LAST_PNL_CMP", "CRYPT_KEY", "ENTRE_ZONE", "APP_KEY", "SEND_LOCAL"],
}
FIELD_TYPES = {  # DataType for fields we may have to add (default 1 = text)
    "3": {"ID", "RECVR_NUM", "ACCOUNT_NUM", "PANEL_IP_PRT", "ACCT_NUM", "TRN_DELAY", "NUMBER", "EXT_CHKIN",
          "FAIL_TIME", "RECVR_PORT", "AS_OUT_NUM"},
    "5": {"SEND_PATH", "USE_893A", "LATCH_SV"},
    "11": {"LAST_CHANGE", "TEST_TIME"},
}


def _add_field(el, tag):
    known = FIELD_ORDER.get(el.tag, [])
    if tag not in known and not any(tag in s for s in FIELD_TYPES.values()) and el.tag not in FIELD_ORDER:
        raise JobError(f"field {tag} missing from <{el.tag}> in the template")
    dtype = next((t for t, tags in FIELD_TYPES.items() if tag in tags), "1")
    e = ET.Element(tag, {"DataType": dtype})
    children = list(el)
    pos = len(children)
    if tag in known:
        for prev in reversed(known[:known.index(tag)]):
            p = el.find(prev)
            if p is not None:
                pos = children.index(p) + 1
                break
    el.insert(pos, e)
    return e


def put(el, tag, value):
    e = el.find(tag)
    if e is None:
        e = _add_field(el, tag)
    if e.get("DataType") == "1":
        raw = str(value).encode("latin1")
        raw += b"\0" * ((-len(raw) % 3) if raw else 3)
        e.text = base64.b64encode(raw).decode("ascii")
    elif e.get("DataType") == "5":
        e.text = "True" if value in (True, "True", "true", "yes", "1") else "False"
    else:
        e.text = str(value)


def now_stamp():
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S.000")


# ---------------------------------------------------------------- zone rules
# From the Device Cheat Sheet (Fire). First match wins, so specific names come first.
# (pattern, TYPE, AO message, AS message, note)
#   TYPE: FI = Fire, SV = Supervisory, A1 = Auxiliary 1    messages: A = Alarm, T = Trouble
ZONE_RULES = [
    (r"RELAY.*BELL|WATER ?FLOW BELL|\bWF BELL", "SV", "T", "A", None),
    (r"\bACME\b.*\b(TRBL|TROUBLE)\b", "SV", "T", "T", None),
    (r"\bACME\b", "FI", "T", "A", None),
    (r"\b(TRBL|TROUBLE)\b", "SV", "T", "T", None),
    # CO: Supervisory, Trouble/Trouble (team preference) + latched supervisory ON
    (r"\bCO\b|\bC/O\b|CARBON", "SV", "T", "T", "co"),
    (r"REPEATER", "A1", "T", "T", None),
    (r"VALVE|TAMPER|\bVT\b|\bPIV\b|OS&Y|\bB4\b|\bBEFORE\b|\bAFTER\b|\bAFT\b|BACKFLOW|PUMP|JOCKEY|PS40|"
     r"PRESSURE|LOW AIR|HIGH AIR|DRY SYS|SUPERVISORY|\bSUPV\b",
     "SV", "T", "T", None),
    (r"WATER ?FLOW|\bWF\b|PS10|FLOW SW", "FI", "T", "A", None),
    (r"SMOKE|HEAT|PULL|\bP/?S\b|DUCT|RECALL|\bELEV|BEAM|FLAME", "FI", "T", "A", None),
]

# Zones that need a correlation (AS output, Steady) and the output they drive.
CORRELATION_RULES = [
    (r"SMOKE.*TOP OF (THE )?STAIR|TOP OF (THE )?STAIR.*SMOKE", "DAMPER TOP OF STAIRS"),
]

TYPE_NAMES = {"FI": "Fire", "SV": "Supervisory", "A1": "Auxiliary 1"}
MSG_NAMES = {"A": "Alarm", "T": "Trouble"}
ZONE_NAME_MAX = 32
OUTPUT_NAME_MAX = 32


def classify(name):
    """Device settings for a zone name: dict(type, ao, as, co) or None if the name isn't recognised."""
    for pattern, ztype, ao, as_, note in ZONE_RULES:
        if re.search(pattern, (name or "").upper()):
            return {"type": ztype, "ao": ao, "as": as_, "co": note == "co"}
    return None


def correlation_for(name):
    for pattern, out_name in CORRELATION_RULES:
        if re.search(pattern, (name or "").upper()):
            return out_name
    return None


def zone_label(ztype, ao, as_, out=0):
    s = (f"{TYPE_NAMES.get(ztype, ztype or '?')}, AO {MSG_NAMES.get(ao, ao or '?')}, "
         f"AS {MSG_NAMES.get(as_, as_ or '?')}")
    return s + (f", AS output {out}" if out else "")


def parse_zone_text(text, start=1):
    """Pasted zone list -> list of {number, name}. Accepts lines like
    '5 WATERFLOW 1FL', '5, WATERFLOW 1FL', '5<TAB>WATERFLOW 1FL<TAB>...' (Excel copy),
    or just names (numbered on from the previous zone). Header lines are skipped."""
    header = re.compile(r"(?i)(zone|zone ?#|zone ?no\.?|number|#|name|zone name|description|"
                        r"device|location|output|type)")
    zones, nxt = [], start
    for ln in text.splitlines():
        if not ln.strip():
            continue
        if "\t" in ln:
            cells = ln.split("\t")
        elif "," in ln and re.match(r"\s*\d+\s*,", ln):
            cells = next(csv.reader([ln]))
        else:
            cells = [ln]
        cells = [c.strip() for c in cells]
        cells = [c for c in cells if c] or [""]
        if len(cells) >= 2 and cells[0].isdigit():
            num, name = int(cells[0]), cells[1]
        elif all(header.fullmatch(c) for c in cells):
            continue
        else:
            m = re.match(r"\s*(?:zone\s*#?\s*)?(\d+)[\s.:)\-]+(.+)", cells[0], re.I)
            if m:
                num, name = int(m.group(1)), m.group(2).strip()
            else:
                num, name = nxt, cells[0]
        zones.append({"number": num, "name": " ".join(name.upper().split())})
        nxt = num + 1
    return zones


# ---------------------------------------------------------------- labels

PANEL_FIELDS = [  # (key, Account tag, label)
    ("name", "NAME", "Panel name"), ("customer", "CUSTOMER", "Customer"),
    ("region", "REGION", "Region"), ("account", "ACCOUNT_NUM", "Account #"),
    ("address", "ADDRESS", "Address"), ("city", "CITY", "City"), ("state", "STATE", "State"),
    ("zip", "ZIP", "Zip"), ("ip", "PANEL_IP", "Panel IP"), ("port", "PANEL_IP_PRT", "Panel port"),
    ("receiver", "RECVR_NUM", "Receiver #"), ("phone", "PANEL_PHONE", "Panel phone"),
]
SECRET_FIELDS = {"CRYPT_KEY", "CODE", "APP_KEY", "WLS_PASSPH", "WIFI_KEY"}
FIELD_LABELS = {
    "GMT_ZONE": "Hours from GMT", "PWR_FL_DLY": "Power fail delay", "LATCH_SV": "Latched supervisory",
    "TIMECHG1": "Time change", "TIME_CHG": "Time change", "CRYPT_KEY": "Remote key",
    "APP_KEY": "App key", "CODE": "Code", "PIH_BUPORT": "PIH backup port",
    "ACC_KEYPAD": "Keypad access areas", "CONNECT_TYPE": "Connection type",
    "LOCAL_IP": "Panel local IP", "GATEWAY": "Gateway", "DNS_SERVER": "DNS server",
    "NETMASK2": "Netmask 2", "GATEWAY2": "Gateway 2", "LOCAL_IP2": "Local IP 2",
    "NAME": "Name", "TYPE": "Type", "AO_ACT_MSG": "AO message", "AS_ACT_MSG": "AS message",
    "AS_OUT_NUM": "AS output", "AS_OUT_ACT": "AS output action", "ACCT_NUM": "Account #",
    "FIRST_PHON": "First receiver #", "SEC_PHONE": "Second receiver #", "REMOTE_IP": "Receiver IP",
    "RECVR_PORT": "Receiver port", "TEST_TIME": "Test time", "TRN_DELAY": "Transmit delay",
    "COMM_TYPE": "Comm type", "PATH_TYPE": "Path type", "SEND_PATH": "Send path information",
    "OP_CL_RPT": "Open/close reports", "CARD_PIN": "Card + PIN", "ARM": "Arm",
    "ACCOUNT_NUM": "Account #", "CUSTOMER": "Customer", "REGION": "Region", "ADDRESS": "Address",
    "CITY": "City", "STATE": "State", "ZIP": "Zip", "PANEL_IP": "Panel IP",
}
SECTION_LABELS = {
    "Account": "Panel Information", "RmtOpts": "Remote Options", "SysRpts": "System Reports",
    "SysOpts": "System Options", "OutOpts": "Output Options", "MenuDisp": "Menu Display",
    "StatList": "Status List", "AccessCode": "Access Code", "PartInfoList": "Partitions",
    "HostLogRpts": "PC Log Reports", "NetOpts": "Network Options", "BellOpts": "Bell Options",
    "CommPathList": "Communication Paths", "AreaInfoList": "Area Information",
    "ZoneInfoList": "Zone Information", "ProfilesList": "Profiles", "UsersList": "User Codes",
    "DeviceInfoList": "Device Setup", "OutNamesList": "Output Information",
    "Device734List": "734 Options", "ZWaveBackups": "Z-Wave",
}


def _fmt_time(v):
    try:
        return datetime.datetime.strptime(v[11:16], "%H:%M").strftime("%I:%M %p").lstrip("0")
    except (TypeError, ValueError):
        return v


def _parse_time(text):
    for fmt in ("%I:%M %p", "%I:%M%p", "%H:%M"):
        try:
            t = datetime.datetime.strptime(text.strip().upper(), fmt)
            return f"1899-12-30 {t:%H:%M}:00.000"
        except ValueError:
            pass
    raise JobError(f"test time '{text}' - use e.g. 10:00 AM")


def _clean(v):
    """Text with pasted control characters and outer spaces removed."""
    return "".join(ch for ch in (v or "") if 32 <= ord(ch) <= 126).strip()


def _records(sec):
    """Records of a list section (e.g. ZoneInfoList -> ZoneInfo elements); [sec] for flat sections."""
    recs = [c for c in sec if len(c)]
    return recs if recs else [sec]


# ---------------------------------------------------------------- panel / remote editing

def apply_panel(panel, values):
    """Set Panel Information fields from {key: value}. Returns a list of changes."""
    acct = panel.find("Account")
    changes = []
    for key, tag, label in PANEL_FIELDS:
        if key not in values:
            continue
        v = _clean(str(values[key]))
        if v != (get(acct, tag) or ""):
            changes.append(f"{label}: {get(acct, tag) or '(blank)'} -> {v or '(blank)'}")
            put(acct, tag, v)
    if changes:
        put(acct, "LAST_CHANGE", now_stamp())
    if "account" in values:
        for area in panel.findall("AreaInfoList/AreaInfo"):
            put(area, "ACCT_NUM", _clean(values["account"]) or 0)
    return changes


REMOTE_FIELDS = [("remote_key", "CRYPT_KEY", "Remote key"), ("app_key", "APP_KEY", "App key")]


def apply_remote(panel, values):
    ro = panel.find("RmtOpts")
    changes = []
    for key, tag, label in REMOTE_FIELDS:
        if key in values and _clean(values[key]) != (get(ro, tag) or ""):
            put(ro, tag, _clean(values[key]))      # adds APP_KEY next to ENTRE_ZONE if it was left out
            changes.append(f"Remote Options {label} changed")
    if changes:
        put(ro, "LAST_CHANGE", now_stamp())
    return changes


# ---------------------------------------------------------------- communication paths
# From real exports:  COMM_TYPE C = Contact ID (radio/AES, receivers 555), D = Digital dialer
# (phone lines), N = Network, L = Cell.   PATH_TYPE P = Primary, B = Backup.
COMM_MODES = {"radio": "Radio (AES)", "phone": "Phone lines", "network_cell": "Network + cell backup"}
COMM_TYPE_NAMES = {"C": "Contact ID", "D": "Digital dialer", "N": "Network", "L": "Cell"}
PATH_TYPE_NAMES = {"P": "Primary", "B": "Backup"}
COMM_FIELDS = [  # (key, label)
    ("account", "Comm account #"),
    ("first_phone", "First receiver phone #"), ("second_phone", "Second receiver phone #"),
    ("receiver_ip", "Network receiver IP"), ("receiver_port", "Network receiver port"),
    ("backup_ip", "Cell receiver IP"), ("backup_port", "Cell receiver port"),
    ("test_time", "Test time"), ("transmit_delay", "Transmit delay"),
]
MODE_FIELDS = {
    "radio": {"account", "test_time", "transmit_delay"},
    "phone": {"account", "first_phone", "second_phone", "test_time", "transmit_delay"},
    "network_cell": {"account", "receiver_ip", "receiver_port", "backup_ip", "backup_port",
                     "test_time", "transmit_delay"},
}
_NO_CHECKIN = {"USE_CHKIN": "N", "EXT_CHKIN": 0, "FAIL_TIME": 0, "REMOTE_IP": "0.0.0.0", "USE_893A": True}
_CHECKIN = {"USE_CHKIN": "Y", "EXT_CHKIN": 5, "FAIL_TIME": 5}


def comm_mode_of(panel):
    types = [get(c, "COMM_TYPE") for c in panel.findall("CommPathList/CommPath")]
    if types == ["C"]:
        return "radio"
    if types == ["D"]:
        return "phone"
    if types[:1] == ["N"] and "L" in types:
        return "network_cell"
    return None


def comm_values(panel):
    """Current comm settings as form values."""
    paths = panel.findall("CommPathList/CommPath")
    v = {}
    if paths:
        c = paths[0]
        v.update(account=get(c, "ACCT_NUM") or "", test_time=_fmt_time(get(c, "TEST_TIME")) or "",
                 transmit_delay=get(c, "TRN_DELAY") or "",
                 send_path="yes" if get(c, "SEND_PATH") == "True" else "no")
    for c in paths:
        ct = get(c, "COMM_TYPE")
        if ct in ("C", "D"):
            v.update(first_phone=get(c, "FIRST_PHON") or "", second_phone=get(c, "SEC_PHONE") or "")
        if ct == "N":
            v.update(receiver_ip=get(c, "REMOTE_IP") or "", receiver_port=get(c, "RECVR_PORT") or "")
        if ct == "L":
            v.update(backup_ip=get(c, "REMOTE_IP") or "", backup_port=get(c, "RECVR_PORT") or "")
    return v


def _bool(v):
    return str(v).strip().lower() in ("yes", "true", "1", "y", "on")


def _check_ip(label, v):
    parts = v.split(".")
    if len(parts) != 4 or not all(p.isdigit() and int(p) < 256 for p in parts):
        raise JobError(f"{label} '{v}' is not an IP address like 216.151.46.112")
    return v


def _check_digits(label, v):
    if not re.fullmatch(r"[0-9]+", v):
        raise JobError(f"{label} '{v}' - digits only, no dashes or spaces")
    return v


def apply_comm(panel, mode, values):
    """Rebuild the communication paths for a setup (radio / phone / network_cell).
    Each path starts as a copy of the current path 1. Returns a list of changes."""
    lst = panel.find("CommPathList")
    paths = lst.findall("CommPath") if lst is not None else []
    if not paths:
        raise JobError("this job has no communication path to copy")
    if mode not in COMM_MODES:
        raise JobError(f"unknown comm setup '{mode}'")
    values = {k: _clean(str(v)) for k, v in values.items() if v is not None}
    defaults = {"test_time": "10:00 AM", "transmit_delay": "0", "receiver_port": "2001",
                "backup_port": "2001", "account": get(panel.find("Account"), "ACCOUNT_NUM") or ""}
    for k, v in defaults.items():
        values[k] = values.get(k) or v
    if not values["account"]:
        raise JobError("Comm account # is blank")
    common = {"ACCT_NUM": _check_digits("Comm account #", values["account"]),
              "TEST_TIME": _parse_time(values["test_time"]),
              "TRN_DELAY": _check_digits("Transmit delay", values["transmit_delay"]),
              "SEND_PATH": _bool(values["send_path"]) if values.get("send_path") else mode == "phone",
              "TEST_RPT": "Y"}
    if mode == "radio":
        plan = [{"COMM_TYPE": "C", "PATH_TYPE": "P", "FIRST_PHON": "555", "SEC_PHONE": "555", **_NO_CHECKIN}]
    elif mode == "phone":
        first = values.get("first_phone")
        if not first or first == "555":
            raise JobError("Phone lines: type the first receiver phone # from Rapid (digits only)")
        second = values.get("second_phone") or first
        plan = [{"COMM_TYPE": "D", "PATH_TYPE": "P", "FIRST_PHON": _check_digits("First receiver phone #", first),
                 "SEC_PHONE": _check_digits("Second receiver phone #", second), **_NO_CHECKIN}]
    else:
        ip1 = values.get("receiver_ip")
        if not ip1 or ip1 == "0.0.0.0":
            raise JobError("Network + cell: fill in the network receiver IP")
        ip2 = values.get("backup_ip") if values.get("backup_ip") not in (None, "", "0.0.0.0") else ip1
        plan = [{"COMM_TYPE": "N", "PATH_TYPE": "P", "FIRST_PHON": "555", "SEC_PHONE": "555", **_CHECKIN,
                 "REMOTE_IP": _check_ip("Network receiver IP", ip1),
                 "RECVR_PORT": _check_digits("Network receiver port", values["receiver_port"]),
                 "USE_893A": True},
                {"COMM_TYPE": "L", "PATH_TYPE": "B", **_CHECKIN, "REMOTE_IP": _check_ip("Cell receiver IP", ip2),
                 "RECVR_PORT": _check_digits("Cell receiver port", values["backup_port"]),
                 "USE_893A": False, "SEND_PATH": False, "_drop": ("FIRST_PHON", "SEC_PHONE")}]
    before = [describe_path(c) for c in paths]
    base = paths[0]
    for c in paths:
        lst.remove(c)
    for num, fields in enumerate(plan, 1):
        e = copy.deepcopy(base)
        for tag, v in {**common, **fields, "NUMBER": num, "LAST_CHANGE": now_stamp()}.items():
            if tag != "_drop":
                put(e, tag, v)
        for tag in fields.get("_drop", ()):
            if e.find(tag) is not None:
                e.remove(e.find(tag))
        lst.append(e)
    after = [describe_path(c) for c in lst.findall("CommPath")]
    return [] if before == after else [f"Communication paths -> {COMM_MODES[mode]}"] + [f"  {d}" for d in after]


def describe_path(c):
    ct = get(c, "COMM_TYPE")
    s = (f"Path {get(c, 'NUMBER')}: {PATH_TYPE_NAMES.get(get(c, 'PATH_TYPE'), get(c, 'PATH_TYPE'))}, "
         f"{COMM_TYPE_NAMES.get(ct, ct)}, account {get(c, 'ACCT_NUM')}")
    if ct in ("C", "D"):
        s += f", receivers {get(c, 'FIRST_PHON')} / {get(c, 'SEC_PHONE')}"
    if ct in ("N", "L"):
        s += f", receiver {get(c, 'REMOTE_IP')}:{get(c, 'RECVR_PORT')}"
    s += (f", check-in {'on' if get(c, 'USE_CHKIN') == 'Y' else 'off'}, test {_fmt_time(get(c, 'TEST_TIME'))}, "
          f"delay {get(c, 'TRN_DELAY')}, send path info {'on' if get(c, 'SEND_PATH') == 'True' else 'off'}")
    return s


# ---------------------------------------------------------------- zones / outputs

def job_zones(panel):
    return [{"number": int(get(z, "NUMBER")), "name": get(z, "NAME") or "", "type": get(z, "TYPE") or "",
             "ao": get(z, "AO_ACT_MSG") or "", "as": get(z, "AS_ACT_MSG") or "",
             "out": int(get(z, "AS_OUT_NUM") or 0)} for z in panel.findall("ZoneInfoList/ZoneInfo")]


def job_outputs(panel):
    return {int(get(o, "NUMBER")): get(o, "NAME") or "" for o in panel.findall("OutNamesList/OutNames")}


# An output record, used when the job has none to copy from (shape taken from a real export).
_OUTPUT_PROTOTYPE = (
    '<OutNames><ACCOUNT_ID DataType="3">0</ACCOUNT_ID>'
    '<LAST_CHANGE DataType="11">2026-01-01 00:00:00.000</LAST_CHANGE>'
    '<NUMBER DataType="3">1</NUMBER><NAME DataType="1">AAAA</NAME>'
    '<OUT_SUPV DataType="1">MwAA</OUT_SUPV><RT_STAT DataType="5">False</RT_STAT>'
    '<TRIPWBELL DataType="5">True</TRIPWBELL></OutNames>')


def set_outputs(panel, outputs):
    """Make Output Information hold exactly {number: name}, keeping existing records' other settings."""
    lst = panel.find("OutNamesList")
    if lst is None:
        if not outputs:
            return
        lst = ET.Element("OutNamesList")         # empty sections are left out of exports
        anchor = panel.find("OutOpts")
        panel.insert(list(panel).index(anchor) + 1 if anchor is not None else len(panel), lst)
    existing = {int(get(o, "NUMBER")): o for o in lst.findall("OutNames")}
    if existing:
        base = next(iter(existing.values()))
    else:
        base = ET.fromstring(_OUTPUT_PROTOTYPE)
        put(base, "ACCOUNT_ID", get(panel.find("Account"), "ID") or 0)
    for o in list(lst):
        lst.remove(o)
    for num, name in sorted(outputs.items()):
        o = existing.get(num)
        if o is None:
            o = copy.deepcopy(base)
            put(o, "NUMBER", num)
        if get(o, "NAME") != name or num not in existing:
            put(o, "NAME", _clean(name).upper())
            put(o, "LAST_CHANGE", now_stamp())
        lst.append(o)


def set_zones(panel, zones):
    """Make Zone Information hold exactly these zones (dicts: number, name, type, ao, as, out).
    Existing zones keep all their other settings; new zones copy a zone of the same type."""
    lst = panel.find("ZoneInfoList")
    old = lst.findall("ZoneInfo") if lst is not None else []
    if not old:
        if zones:
            raise JobError("this job has no zones to copy settings from - open a programmed job")
        return
    by_num = {get(z, "NUMBER"): z for z in old}
    by_type = {}
    for z in old:
        by_type.setdefault(get(z, "TYPE"), z)
    for z in old:
        lst.remove(z)
    for z in sorted(zones, key=lambda d: int(d["number"])):
        e = by_num.get(str(z["number"]))
        new = e is None
        if new:
            e = copy.deepcopy(by_type.get(z.get("type"), old[0]))
        before = ET.tostring(e)
        put(e, "NUMBER", int(z["number"]))
        put(e, "NAME", _clean(z["name"]).upper())
        for key, tag in (("type", "TYPE"), ("ao", "AO_ACT_MSG"), ("as", "AS_ACT_MSG")):
            if z.get(key):
                put(e, tag, z[key])
        out = int(z.get("out") or 0)
        put(e, "AS_OUT_NUM", out)
        if not out:
            put(e, "AS_OUT_ACT", "-")
        elif (get(e, "AS_OUT_ACT") or "-") == "-":
            put(e, "AS_OUT_ACT", "S")
        if new or ET.tostring(e) != before:
            put(e, "LAST_CHANGE", now_stamp())
        lst.append(e)


# ---------------------------------------------------------------- validation

ERROR, CHECK, NOTE, OK = "error", "check", "note", "ok"
SECTION_ORDER = ["Panel Information", "Communication Paths", "Device Setup", "System Options",
                 "Output Information", "Area Information", "Zone Information", "Profiles", "User Codes",
                 "Remote Options", "Pasted characters", "Copied from", "Job documents"]


class Finding:
    def __init__(self, severity, section, text, fix=None, fix_label=None, zone=None):
        self.severity, self.section, self.text = severity, section, text
        self.fix, self.fix_label, self.zone = fix, fix_label, zone

    def __repr__(self):
        return f"<{self.severity} {self.section}: {self.text}>"


def _norm(s):
    return " ".join((s or "").upper().split())


def validate(panel, baseline=None, docs=None):
    """Everything worth knowing about a job, as a list of Findings (see SECTION_ORDER).
    baseline: the job this one was copied from (Panel element) - flags leftovers.
    docs: {"zones": text, "account": str, "first_phone": str, "second_phone": str} from the job documents."""
    out = []

    def add(sev, sec, text, fix=None, fix_label=None, zone=None):
        out.append(Finding(sev, sec, text, fix, fix_label, zone))

    def check(ok, sec, good, bad, sev=CHECK, fix=None, fix_label=None, zone=None):
        if ok:
            add(OK, sec, good, zone=zone)
        else:
            add(sev, sec, bad, fix, fix_label, zone)

    a = panel.find("Account")
    S = "Panel Information"
    for key, tag, label in PANEL_FIELDS[:8]:
        check(bool(get(a, tag)), S, f"{label}: {get(a, tag)}", f"{label} is blank")

    # ---- communication paths
    S = "Communication Paths"
    cps = panel.findall("CommPathList/CommPath")
    mode = comm_mode_of(panel)
    check(mode is not None, S, f"Setup: {COMM_MODES.get(mode)} ({len(cps)} path(s))",
          f"Setup not recognised ({len(cps)} path(s): "
          f"{', '.join(COMM_TYPE_NAMES.get(get(c, 'COMM_TYPE'), '?') for c in cps) or 'none'})")
    for c in cps:
        n, ct = get(c, "NUMBER"), get(c, "COMM_TYPE")
        add(NOTE, S, describe_path(c))
        tt = get(c, "TEST_TIME")
        if not tt:
            add(ERROR, S, f"Path {n}: no test time", lambda c=c: put(c, "TEST_TIME", _parse_time("10:00 AM")),
                "Set test time 10:00 AM")
        elif tt[11:16] != "10:00":
            add(CHECK, S, f"Path {n}: test time {_fmt_time(tt)} (10:00 AM expected)",
                lambda c=c: put(c, "TEST_TIME", _parse_time("10:00 AM")), "Set test time 10:00 AM")
        check(get(c, "TRN_DELAY") == "0", S, f"Path {n}: transmit delay 0",
              f"Path {n}: transmit delay {get(c, 'TRN_DELAY')} (0 expected)",
              fix=lambda c=c: put(c, "TRN_DELAY", 0), fix_label="Set transmit delay 0")
        if ct == "C":
            check(get(c, "FIRST_PHON") == "555" and get(c, "SEC_PHONE") == "555", S,
                  f"Path {n}: radio receivers 555",
                  f"Path {n}: radio path with receivers {get(c, 'FIRST_PHON')} / {get(c, 'SEC_PHONE')} (555 expected)",
                  fix=lambda c=c: (put(c, "FIRST_PHON", "555"), put(c, "SEC_PHONE", "555")),
                  fix_label="Set receivers 555")
        if ct == "D":
            check(get(c, "FIRST_PHON") not in (None, "", "555"), S, f"Path {n}: dialer receiver numbers set",
                  f"Path {n}: phone-line path has receiver number '{get(c, 'FIRST_PHON') or ''}' - "
                  f"type the Rapid numbers", sev=ERROR)
            check(get(c, "SEND_PATH") == "True", S, f"Path {n}: send path information on",
                  f"Path {n}: send path information off (on for phone lines)",
                  fix=lambda c=c: put(c, "SEND_PATH", True), fix_label="Turn send path information on")
        if ct in ("N", "L"):
            check(get(c, "REMOTE_IP") not in (None, "", "0.0.0.0"), S, f"Path {n}: receiver IP set",
                  f"Path {n}: receiver IP is {get(c, 'REMOTE_IP') or 'blank'}", sev=ERROR)
    if mode == "network_cell":
        add(NOTE, S, "Network path: check Network Options (panel IP / gateway / DHCP) for this site")

    # ---- device setup
    S = "Device Setup"
    devs = {get(d, "NUMBER"): get(d, "NAME") for d in panel.findall("DeviceInfoList/DeviceInfo")}
    for num in ("1", "2"):
        check(num in devs, S, f"Device {num}: {devs.get(num)}", f"Device {num} is missing")
    extra = [f"{n} {nm}" for n, nm in devs.items() if n not in ("1", "2")]
    if extra:
        add(NOTE, S, "Other devices: " + ", ".join(extra))

    # ---- system options
    S = "System Options"
    so = panel.find("SysOpts")
    zones = panel.findall("ZoneInfoList/ZoneInfo")
    co_zones = [get(z, "NUMBER") for z in zones if (classify(get(z, "NAME")) or {}).get("co")]
    check(get(so, "GMT_ZONE") == "5", S, "Hours from GMT 5", f"Hours from GMT {get(so, 'GMT_ZONE')} (5 expected)",
          fix=lambda: put(so, "GMT_ZONE", 5), fix_label="Set hours from GMT 5")
    check(get(so, "PWR_FL_DLY") == "0", S, "Power fail delay 0",
          f"Power fail delay {get(so, 'PWR_FL_DLY')} (0 expected)",
          fix=lambda: put(so, "PWR_FL_DLY", 0), fix_label="Set power fail delay 0")
    latched = get(so, "LATCH_SV") == "True"
    want = bool(co_zones)
    check(latched == want, S,
          f"Latched supervisory {'ON (CO detector: zone ' + ', '.join(co_zones) + ')' if latched else 'OFF (no CO detector)'}",
          f"Latched supervisory {'ON' if latched else 'OFF'} but there is "
          f"{'a CO detector (zone ' + ', '.join(co_zones) + ')' if want else 'no CO detector'}",
          fix=lambda: put(so, "LATCH_SV", want), fix_label=f"Turn latched supervisory {'ON' if want else 'OFF'}")

    # ---- outputs / correlations
    S = "Output Information"
    outs = job_outputs(panel)
    used = {}
    for z in zones:
        o = int(get(z, "AS_OUT_NUM") or 0)
        if o:
            used.setdefault(o, []).append(get(z, "NUMBER"))
    if not outs and not used:
        add(OK, S, "No outputs (no correlations)")
    for num, nm in sorted(outs.items()):
        def drop(num=num):
            o = job_outputs(panel)
            o.pop(num, None)
            set_outputs(panel, o)
        check(num in used, S, f"Output {num} {nm} <- zone(s) {', '.join(used.get(num, []))}",
              f"Output {num} {nm} isn't used by any zone (outputs are only for correlations)",
              fix=drop, fix_label=f"Remove output {num}")
    for num in sorted(set(used) - set(outs)):
        add(ERROR, S, f"Zone(s) {', '.join(used[num])} use AS output {num}, but there is no output {num}")

    # ---- areas
    S = "Area Information"
    for ar in panel.findall("AreaInfoList/AreaInfo"):
        n = f"Area {get(ar, 'AREA_NUM')} {get(ar, 'NAME')}"
        check(get(ar, "OP_CL_RPT") != "True", S, f"{n}: open/close reports off", f"{n}: open/close reports ON",
              fix=lambda ar=ar: put(ar, "OP_CL_RPT", False), fix_label="Turn open/close reports off")
        check(get(ar, "CARD_PIN") != "True", S, f"{n}: card + PIN off", f"{n}: card + PIN ON",
              fix=lambda ar=ar: put(ar, "CARD_PIN", False), fix_label="Turn card + PIN off")

    # ---- zones
    S = "Zone Information"
    names = {}
    for z in zones:
        names.setdefault(_norm(get(z, "NAME")), []).append(get(z, "NUMBER"))
    for z in zones:
        n, nm = get(z, "NUMBER"), get(z, "NAME") or ""
        ztype, ao, as_ = get(z, "TYPE"), get(z, "AO_ACT_MSG"), get(z, "AS_ACT_MSG")
        zn = int(n)
        if not nm.strip():
            add(ERROR, S, f"Zone {n} has no name", zone=zn)
        if len(nm) > ZONE_NAME_MAX:
            add(ERROR, S, f"Zone {n} name is {len(nm)} characters (max {ZONE_NAME_MAX})", zone=zn)
        if not ztype or ztype == "--":
            add(ERROR, S, f"Zone {n} '{nm}' has no type", zone=zn)
        if len(names.get(_norm(nm), [])) > 1:
            add(CHECK, S, f"Zone {n} '{nm}': same name as zone(s) "
                          f"{', '.join(x for x in names[_norm(nm)] if x != n)}", zone=zn)
        rule = classify(nm)
        if rule is None:
            add(NOTE, S, f"Zone {n} '{nm}': name not recognised - {zone_label(ztype, ao, as_)} kept as is",
                zone=zn)
        elif (ztype, ao, as_) != (rule["type"], rule["ao"], rule["as"]):
            def fix_rule(z=z, r=rule):
                put(z, "TYPE", r["type"]), put(z, "AO_ACT_MSG", r["ao"]), put(z, "AS_ACT_MSG", r["as"])
                put(z, "LAST_CHANGE", now_stamp())
            add(CHECK, S, f"Zone {n} '{nm}': {zone_label(ztype, ao, as_)} - cheat sheet says "
                          f"{zone_label(rule['type'], rule['ao'], rule['as'])}",
                fix_rule, "Apply cheat sheet settings", zone=zn)
        want_out = correlation_for(nm)
        if want_out and get(z, "AS_OUT_NUM") in (None, "0"):
            def fix_corr(z=z, want=want_out):
                o = job_outputs(panel)
                num = next((k for k, v in sorted(o.items()) if v == want), None)
                if num is None:
                    num = next(i for i in range(1, 1000) if i not in o)
                    o[num] = want
                    set_outputs(panel, o)
                put(z, "AS_OUT_NUM", num), put(z, "AS_OUT_ACT", "S"), put(z, "LAST_CHANGE", now_stamp())
            add(CHECK, S, f"Zone {n} '{nm}' needs a correlation ({want_out}) but has no AS output",
                fix_corr, f"Add correlation to '{want_out}'", zone=zn)
    add(OK if zones else CHECK, S, f"{len(zones)} zone(s) checked" if zones else "No zones")

    # ---- profiles / users
    S = "Profiles"
    profs = panel.findall("ProfilesList/Profiles")
    users = panel.findall("UsersList/Users")
    used_profiles = {get(u, e.tag) for u in users for e in u if e.tag.startswith("PROFILE")} - {None, "0"}
    unused = [p for p in profs if get(p, "PROF_NUM") not in used_profiles]

    def drop_unused():
        lst = panel.find("ProfilesList")
        for p in list(lst.findall("Profiles")):
            if get(p, "PROF_NUM") not in used_profiles:
                lst.remove(p)
    check(len(profs) == 2, S, "2 profiles: " + ", ".join(f"{get(p, 'PROF_NUM')} {get(p, 'NAME')}" for p in profs),
          f"{len(profs)} profiles (2 expected)" + (f" - {len(unused)} not used by any user code" if unused else ""),
          fix=drop_unused if unused and len(profs) - len(unused) >= 1 else None,
          fix_label=f"Remove the {len(unused)} profiles no user code uses")
    for p in profs:
        if p in unused:
            continue                 # covered by "remove the profiles no user code uses"
        check(get(p, "ARM") != "True", S, f"Profile {get(p, 'PROF_NUM')}: arm off",
              f"Profile {get(p, 'PROF_NUM')} {get(p, 'NAME')}: arm ON (not needed)",
              fix=lambda p=p: put(p, "ARM", False), fix_label="Turn arm off")
    check(len(users) == 2, "User Codes", "2 user codes: " + ", ".join(
        f"{get(u, 'USER_NUM')} {get(u, 'NAME')} (profile {get(u, 'PROFILE1')})" for u in users),
        f"{len(users)} user codes (2 expected)")

    # ---- remote options (added before sending; not required in the file)
    S = "Remote Options"
    ro = panel.find("RmtOpts")
    add(OK if get(ro, "CRYPT_KEY") else NOTE, S,
        "Remote key set" if get(ro, "CRYPT_KEY") else "Remote key not set - add it before sending to the panel")
    add(NOTE, S, "App key set" if get(ro, "APP_KEY") else "App key not set")
    add(OK if get(panel.find("AccessCode"), "CODE") else NOTE, S,
        "Access code set" if get(panel.find("AccessCode"), "CODE") else "Access code not set")

    # ---- pasted characters (notes: pasted numbers make Send fail without warning)
    for sec in panel:
        for rec in _records(sec):
            for e in rec:
                if e.get("DataType") != "1" or len(e):
                    continue
                v = get(rec, e.tag) or ""
                if v != _clean(v):
                    shown = "(hidden)" if e.tag in SECRET_FIELDS else repr(v)
                    add(ERROR, "Pasted characters",
                        f"{SECTION_LABELS.get(sec.tag, sec.tag)} - {FIELD_LABELS.get(e.tag, e.tag)}: "
                        f"hidden/space character in {shown}",
                        lambda rec=rec, tag=e.tag: put(rec, tag, _clean(get(rec, tag))), "Remove the character")

    if baseline is not None:
        out += compare_baseline(panel, baseline)
    if docs:
        out += compare_docs(panel, docs)
    order = {s: i for i, s in enumerate(SECTION_ORDER)}
    out.sort(key=lambda f: order.get(f.section, 99))
    return out


def compare_baseline(panel, base):
    """Leftovers from the job this one was copied from."""
    S = "Copied from"
    out = []
    a, b = panel.find("Account"), base.find("Account")
    for tag, label in (("NAME", "Panel name"), ("CUSTOMER", "Customer"), ("ADDRESS", "Address"),
                       ("ACCOUNT_NUM", "Account #"), ("PANEL_IP", "Panel IP")):
        v, bv = get(a, tag), get(b, tag)
        if v and v == bv:
            out.append(Finding(CHECK, S, f"{label} is still the copied job's: {v}"))
        else:
            out.append(Finding(OK, S, f"{label} changed ({bv or 'blank'} -> {v or 'blank'})"))
    cp, bcp = panel.find("CommPathList/CommPath"), base.find("CommPathList/CommPath")
    if cp is not None and bcp is not None:
        if get(cp, "ACCT_NUM") == get(bcp, "ACCT_NUM"):
            out.append(Finding(CHECK, S, f"Comm account # is still the copied job's: {get(cp, 'ACCT_NUM')}"))
        if get(cp, "COMM_TYPE") == "D" and get(bcp, "COMM_TYPE") == "D" and \
                get(cp, "FIRST_PHON") == get(bcp, "FIRST_PHON"):
            out.append(Finding(NOTE, S, "Receiver phone numbers are the same as the copied job's "
                                        "(fine if it's the same central station)"))
    zn = [(get(z, "NUMBER"), _norm(get(z, "NAME"))) for z in panel.findall("ZoneInfoList/ZoneInfo")]
    bzn = [(get(z, "NUMBER"), _norm(get(z, "NAME"))) for z in base.findall("ZoneInfoList/ZoneInfo")]
    if zn and zn == bzn:
        out.append(Finding(CHECK, S, f"Zone list is identical to the copied job's ({len(zn)} zones)"))
    else:
        same = len(set(zn) & set(bzn))
        out.append(Finding(NOTE, S, f"{same} of {len(zn)} zones have the same number and name as the copied job"))
    n1, n2 = panel.find("NetOpts"), base.find("NetOpts")
    if n1 is not None and n2 is not None and get(n1, "LOCAL_IP") == get(n2, "LOCAL_IP"):
        out.append(Finding(NOTE, S, f"Network Options panel local IP is the copied job's: {get(n1, 'LOCAL_IP')}"))
    return out


def _digits_equal(a, b):
    a, b = re.sub(r"\D", "", a or ""), re.sub(r"\D", "", b or "")
    return a.lstrip("0") == b.lstrip("0")


def compare_docs(panel, docs):
    """Compare the job with what the job documents say (zone list, Rapid account / receivers)."""
    S = "Job documents"
    out = []
    expected = parse_zone_text(docs.get("zones") or "")
    if expected:
        job = {int(get(z, "NUMBER")): _norm(get(z, "NAME")) for z in panel.findall("ZoneInfoList/ZoneInfo")}
        exp = {}
        for z in expected:
            exp[z["number"]] = _norm(z["name"])
        problems = 0
        for num in sorted(set(exp) | set(job)):
            if num not in job:
                out.append(Finding(ERROR, S, f"Zone {num} '{exp[num]}' is on the zone list but not in the job",
                                   zone=num))
            elif num not in exp:
                out.append(Finding(CHECK, S, f"Zone {num} '{job[num]}' is in the job but not on the zone list",
                                   zone=num))
            elif exp[num] != job[num]:
                out.append(Finding(CHECK, S, f"Zone {num}: job says '{job[num]}', zone list says '{exp[num]}'",
                                   zone=num))
            else:
                continue
            problems += 1
        if not problems:
            out.append(Finding(OK, S, f"All {len(exp)} zones match the zone list"))
    acct = (docs.get("account") or "").strip()
    if acct:
        a = get(panel.find("Account"), "ACCOUNT_NUM")
        out.append(Finding(OK, S, f"Panel account # matches Rapid ({acct})") if _digits_equal(a, acct) else
                   Finding(CHECK, S, f"Panel account # {a} - Rapid says {acct}"))
        for c in panel.findall("CommPathList/CommPath"):
            if not _digits_equal(get(c, "ACCT_NUM"), acct) and not acct.endswith(get(c, "ACCT_NUM") or "x"):
                out.append(Finding(CHECK, S, f"Comm path {get(c, 'NUMBER')} account # {get(c, 'ACCT_NUM')} - "
                                             f"Rapid says {acct}"))
    for key, tag, label in (("first_phone", "FIRST_PHON", "First receiver #"),
                            ("second_phone", "SEC_PHONE", "Second receiver #")):
        want = (docs.get(key) or "").strip()
        if not want:
            continue
        for c in panel.findall("CommPathList/CommPath"):
            if get(c, "COMM_TYPE") != "D":
                continue
            have = get(c, tag)
            out.append(Finding(OK, S, f"{label} matches Rapid") if _digits_equal(have, want) else
                       Finding(CHECK, S, f"{label} {have} - Rapid says {want}"))
    return out


def summary(findings):
    c = {ERROR: 0, CHECK: 0, NOTE: 0, OK: 0}
    for f in findings:
        c[f.severity] += 1
    return c


# ---------------------------------------------------------------- differences between two jobs

_DIFF_IGNORE = {"ACCOUNT_ID", "LAST_CHANGE", "LAST_PNL_CMP", "ID"}
_RECORD_KEYS = ("NUMBER", "PROF_NUM", "USER_NUM", "AREA_NUM")


def _rec_key(rec, i):
    for k in _RECORD_KEYS:
        if rec.find(k) is not None:
            return get(rec, k)
    return f"#{i + 1}"


def _rec_label(rec, key):
    nm = get(rec, "NAME")
    return f"{rec.tag} {key}" + (f" {nm}" if nm else "")


def diff_panels(old, new, show_secrets=False):
    """[(section, record, field, old value, new value)] for everything that differs."""
    rows = []

    def val(rec, tag):
        v = get(rec, tag) if rec is not None and rec.find(tag) is not None else None
        if tag in SECRET_FIELDS and not show_secrets and v is not None:
            return "(hidden)"
        return v

    def fields(r1, r2, sec_label, rec_label):
        tags = []
        for r in (r1, r2):
            if r is not None:
                tags += [e.tag for e in r if not len(e) and e.tag not in tags]
        for tag in tags:
            if tag in _DIFF_IGNORE:
                continue
            g1 = get(r1, tag) if r1 is not None and r1.find(tag) is not None else None
            g2 = get(r2, tag) if r2 is not None and r2.find(tag) is not None else None
            if (g1 or "") != (g2 or ""):
                rows.append((sec_label, rec_label, FIELD_LABELS.get(tag, tag), val(r1, tag), val(r2, tag)))

    secs = [s.tag for s in old] + [s.tag for s in new if old.find(s.tag) is None]
    for tag in secs:
        s1, s2 = old.find(tag), new.find(tag)
        label = SECTION_LABELS.get(tag, tag)
        recs1 = [c for c in s1 if len(c)] if s1 is not None else []
        recs2 = [c for c in s2 if len(c)] if s2 is not None else []
        if not recs1 and not recs2:
            fields(s1, s2, label, "")
            continue
        m1 = {_rec_key(r, i): r for i, r in enumerate(recs1)}
        m2 = {_rec_key(r, i): r for i, r in enumerate(recs2)}
        for k in list(m1) + [k for k in m2 if k not in m1]:
            r1, r2 = m1.get(k), m2.get(k)
            if r1 is None:
                rows.append((label, _rec_label(r2, k), "(added)", None, get(r2, "NAME") or ""))
            elif r2 is None:
                rows.append((label, _rec_label(r1, k), "(removed)", get(r1, "NAME") or "", None))
            else:
                fields(r1, r2, label, _rec_label(r2, k))
    return rows


# ---------------------------------------------------------------- files

def load_job(path, key):
    return ET.fromstring(decrypt_file(path, key))


def load_panel(path, key):
    return load_job(path, key).find("Panel")


def save_job(path, root, key):
    write_job(path, ET.tostring(root, encoding="unicode"), key)


def next_version_path(path):
    """'v1 - 917 Home St.xml' -> 'v2 - 917 Home St.xml'; 'Job.xml' -> 'v1 - Job.xml' (never an existing file)."""
    folder, base = os.path.split(path)
    stem, ext = os.path.splitext(base)
    m = re.match(r"(?i)v(\d+)(\s*-\s*)(.*)$", stem)
    num, sep, rest = (int(m.group(1)), m.group(2), m.group(3)) if m else (0, " - ", stem)
    while True:
        num += 1
        cand = os.path.join(folder, f"v{num}{sep}{rest}{ext or '.xml'}")
        if not os.path.exists(cand):
            return cand


def find_jobs(folder):
    return sorted(p for p in glob.glob(os.path.join(folder, "*")) if p.lower().endswith(".xml"))


# ---------------------------------------------------------------- dump / CLI

def dump(path, key):
    panel = load_panel(path, key)
    a = panel.find("Account")
    print(f"Panel: {get(a, 'NAME')}   account {get(a, 'ACCOUNT_NUM')}   receiver {get(a, 'RECVR_NUM')}")
    print(f"  Customer {get(a, 'CUSTOMER')}   Region {get(a, 'REGION')}")
    print(f"  {get(a, 'ADDRESS')}, {get(a, 'CITY')}, {get(a, 'STATE')} {get(a, 'ZIP')}")
    for c in panel.findall("CommPathList/CommPath"):
        print(f"  {describe_path(c)}")
    print(f"Latched supervisory: {get(panel.find('SysOpts'), 'LATCH_SV')}")
    for num, name in job_outputs(panel).items():
        print(f"Output {num}: {name}")
    print("Zones:")
    for z in job_zones(panel):
        print(f"  {z['number']:>3} {z['name']:<32} {zone_label(z['type'], z['ao'], z['as'], z['out'])}")


MARKS = {ERROR: "ERROR", CHECK: "CHECK", NOTE: "note ", OK: "  ok "}


def print_findings(findings, show_ok=True):
    section = None
    for f in findings:
        if f.severity == OK and not show_ok:
            continue
        if f.section != section:
            print(f"\n{f.section}")
            section = f.section
        print(f"  {MARKS[f.severity]}  {f.text}" + ("   [fix available]" if f.fix else ""))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("check", help="checklist for a job file, or every job file in a folder")
    c.add_argument("path")
    c.add_argument("--copied-from", help="the job this one was copied from (flags leftovers)")
    c.add_argument("--all", action="store_true", help="also list the checks that passed")
    d = sub.add_parser("dump")
    d.add_argument("job")
    d = sub.add_parser("decrypt")
    d.add_argument("job")
    d.add_argument("-o", "--output", required=True)
    e = sub.add_parser("encrypt")
    e.add_argument("xml")
    e.add_argument("-o", "--output", required=True)
    for p in sub.choices.values():
        p.add_argument("--key", help="export encryption key (default: settings.ini or DMP_EXPORT_KEY)")
    args = ap.parse_args(argv)
    try:
        sys.exit(run(args))
    except JobError as err:
        print(f"ERROR: {err}")
        sys.exit(2)


def run(args):
    key = args.key or default_key()
    if args.cmd == "check":
        base = load_panel(args.copied_from, key) if args.copied_from else None
        if os.path.isdir(args.path):
            worst = 0
            for p in find_jobs(args.path):
                try:
                    fs = validate(load_panel(p, key), base)
                except JobError:
                    print(f"\n=== {os.path.basename(p)}: skipped - not a job export made with this key")
                    continue
                c = summary(fs)
                print(f"\n=== {os.path.basename(p)}: {c[ERROR]} error(s), {c[CHECK]} to check")
                print_findings([f for f in fs if f.severity in (ERROR, CHECK)], show_ok=False)
                worst = max(worst, 1 if c[ERROR] else 0)
            return worst
        fs = validate(load_panel(args.path, key), base)
        print_findings(fs, show_ok=args.all)
        c = summary(fs)
        print(f"\n{c[ERROR]} error(s), {c[CHECK]} to check, {c[NOTE]} note(s), {c[OK]} ok")
        return 1 if c[ERROR] else 0
    if args.cmd == "dump":
        dump(args.job, key)
    elif args.cmd == "decrypt":
        with open(args.output, "w", encoding="latin1", newline="") as f:
            f.write(decrypt_file(args.job, key))
        print(f"Wrote {args.output}")
    elif args.cmd == "encrypt":
        text = open(args.xml, encoding="latin1").read()
        ET.fromstring(text)                     # refuse to write broken XML
        write_job(args.output, text, key)
        print(f"Wrote {args.output}")
    return 0


if __name__ == "__main__":
    main()
