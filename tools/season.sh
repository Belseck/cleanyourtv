#!/usr/bin/env bash
# Push a season pack to the launcher on the TV, or remove the pushed pack. A pack is a folder with an index,
# seasons.json, and one sub-folder per theme (theme.json, fixed-name image files, optional particles/ and background/ sub-folders). The launcher reads the pack
# from shared storage, /sdcard/TVHome/seasons/; with no seasons.json there it uses its built-in pack. See
# docs/seasons.md for the file format.
#
# Usage: TV_HOST=<tv ip> tools/season.sh <pack folder>
#        tools/season.sh --check <pack folder>    # validate only: needs no TV and sends nothing
#        TV_HOST=<tv ip> tools/season.sh --reset  # DELETES the pushed pack from the TV, seasons.json AND every
#                                                 # theme folder; the built-in pack applies again
#
# Exit status: 0 the pack is on the TV (or the reset ran, or --check passed); 1 the push reached the TV and
# failed; 2 the run stopped before anything was sent - the command line is not one folder, --check <folder> or
# --reset, the pack failed validation (the reason is printed), TV_HOST is unset, or the checkout has no tools/venv.
# Home is rebuilt by opening Settings and pressing HOME. The launcher is never force-stopped: that switches off
# its key helpers.
set -euo pipefail
USAGE="usage: season.sh <pack folder> | --check <pack folder> | --reset (deletes the pushed pack and its images)"
MODE=push
case "${1:-}" in
  "") echo "$USAGE" >&2; exit 2 ;;
  --reset)
    MODE=reset
    [ "$#" -eq 1 ] || { echo "--reset takes no other word. Nothing was sent to the TV." >&2; exit 2; } ;;
  --check)
    MODE=check; shift
    [ "$#" -eq 1 ] || { echo "$USAGE" >&2; exit 2; } ;;
  -*) echo "season.sh cannot read $1. Nothing was sent to the TV." >&2; echo "$USAGE" >&2; exit 2 ;;
  *) [ "$#" -eq 1 ] || { echo "season.sh takes one pack folder. Nothing was sent to the TV." >&2
       echo "$USAGE" >&2; exit 2; } ;;
esac
PACK="${1:-}"
# Resolve the folder against the caller's directory before moving into tools/ (pwd -W: the Windows Python cannot
# open /c/Users/... paths, as in theme.sh). A folder that does not exist keeps the path as typed.
case "$PACK" in
  "") ;;
  /*|[A-Za-z]:[\\/]*) PACK="$(cd "$PACK" 2>/dev/null && { pwd -W 2>/dev/null || pwd; } || printf '%s' "$PACK")" ;;
  *) PACK="$(pwd -W 2>/dev/null || pwd)/$PACK" ;;
esac
cd "$(dirname "$0")"
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt." \
  "Nothing was sent to the TV." >&2; exit 2; }
if [ "$MODE" != check ] && [ -z "${TV_HOST:-}" ]; then
  echo "set TV_HOST to the TV IP address, e.g. TV_HOST=<tv ip> tools/season.sh <pack folder>." \
    "Nothing was sent to the TV." >&2
  exit 2
fi
export MSYS_NO_PATHCONV=1
PKG=com.gathin.tvhome
DIR=/sdcard/TVHome/seasons   # shared storage: a file manager on the TV can edit it too
TMP=/data/local/tmp/cyt-season
tv() { "$PY" -I tv.py "$@"; }

# Home rebuilds by going to Settings and back. Never am force-stop: it switches off the launcher's key helpers.
restart_home() {
  tv "am start -a android.settings.SETTINGS >/dev/null 2>&1; sleep 2; input keyevent HOME" >/dev/null
}

if [ "$MODE" = reset ]; then
  tv "rm -rf $DIR; mkdir -p $DIR; chmod 777 $DIR 2>/dev/null; true" >/dev/null
  restart_home
  echo "reset: the pushed pack and its theme folders are deleted, the built-in pack applies"
  exit 0
fi

# Validation. Prints "ERR ...", "WARN ..." and "FILE <folder>/<name>" lines (the pushable files); image sizes are
# read from the PNG/JPEG headers with plain Python.
CHECK_OUT="$("$PY" -I - "$PACK" <<'PYEOF'
import json, os, re, struct, sys

root = sys.argv[1]
MAX_BYTES = 4 * 1024 * 1024
NAME_RE = re.compile(r"^[A-Za-z0-9_.-]+$")
SOF = (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)
PARTICLES = "snow sakura leaf heart confetti spark bats marigold image none".split()
ORNAMENTS = ("snow_cap frost blossom sun leaves heart hearts sparkles lantern flag_band cobweb marigold candle "
             "image none").split()
ICONS = ("icon-wifi.png", "icon-theme.png", "icon-settings.png")
KNOWN = {"theme.json", "background.png", "background.jpg", "particle.png", "ornament.png"} | set(ICONS)
SUBDIRS = {"particles": (".png",), "background": (".jpg", ".jpeg", ".png")}
MAX_BG = 30
errs, warns, files = [], [], []

def size_of(path):
    with open(path, "rb") as f:
        head = f.read(26)
        if head[:8] == b"\x89PNG\r\n\x1a\n" and head[12:16] == b"IHDR":
            return struct.unpack(">II", head[16:24])
        if head[:2] == b"\xff\xd8":
            f.seek(2)
            while True:
                b = f.read(1)
                while b and b != b"\xff":
                    b = f.read(1)
                m = f.read(1)
                while m == b"\xff":
                    m = f.read(1)
                if not m:
                    return None
                if m[0] in (0xD8, 0x01) or 0xD0 <= m[0] <= 0xD7:
                    continue
                n = struct.unpack(">H", f.read(2))[0]
                if m[0] in SOF:
                    h, w = struct.unpack(">HH", f.read(5)[1:5])
                    return (w, h)
                f.seek(n - 2, 1)
    return None

def load(path, label):
    try:
        with open(path, encoding="utf-8-sig") as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        errs.append("%s is not valid JSON (%s)" % (label, e))
        return None

def image(tag, folder, name, limit):
    """Checks one image file; returns True when it may be pushed."""
    path = os.path.join(root, folder, name)
    what = "%s %s" % (tag, name)
    if os.path.getsize(path) > MAX_BYTES:
        errs.append("%s: file is over 4 MB" % what)
        return False
    dim = size_of(path)
    if dim is None:
        warns.append("%s: could not read its size (not a real PNG/JPEG?)" % what)
    elif limit == "bg":
        if abs(dim[0] * 9 - dim[1] * 16) > dim[0] * 9 * 0.01:
            warns.append("%s is %dx%d, not 16:9: it will be fitted top-right with its left and bottom edges faded, leaving background around it" % (what, dim[0], dim[1]))
    elif limit == "icon":
        if dim[0] != dim[1] or max(dim) > 132:
            warns.append("%s is %dx%d: top-bar icons should be square, about 66x66 px" % (what, dim[0], dim[1]))
    elif max(dim) > limit:
        warns.append("%s is %dx%d: images over %d px are downscaled on the TV" % (what, dim[0], dim[1], limit))
    return True

def subfolder(tag, folder, sub):
    """Checks particles/, background/ or background/day|night/; returns the pushable paths below the theme folder."""
    kind = sub.split("/")[0]
    exts, ok = SUBDIRS[kind], []
    names = sorted(os.listdir(os.path.join(root, folder, sub)))
    for name in names:
        full = os.path.join(root, folder, sub, name)
        ext = os.path.splitext(name)[1].lower()
        if os.path.isfile(full) and ext in exts:
            if image(tag, folder + "/" + sub, name, "bg" if kind == "background" else 128):
                ok.append("%s/%s/%s" % (folder, sub, name))
        elif sub == "background" and name in ("day", "night") and os.path.isdir(full):
            ok.extend(subfolder(tag, folder, sub + "/" + name))
        elif os.path.isfile(full) and ext in (".gif", ".bmp", ".webp", ".svg", ".tif", ".tiff", ".heic", ".avif", ".jpg", ".jpeg"):
            errs.append("%s: %s/%s has an unsupported image type: %s/ takes only %s" % (tag, sub, name, sub, " ".join(exts)))
        else:
            warns.append("%s: unknown %s '%s/%s' is ignored and not pushed"
                         % (tag, "folder" if os.path.isdir(full) else "file", sub, name))
    own = [f for f in ok if f.count("/") == sub.count("/") + 1]   # day/ and night/ have their own limit
    if kind == "background" and len(own) > MAX_BG:
        errs.append("%s: %s/ holds %d images, the maximum is %d" % (tag, sub, len(own), MAX_BG))
    return ok

data = None
if not os.path.isdir(root):
    errs.append("folder not found: " + root)
elif not os.path.isfile(os.path.join(root, "seasons.json")):
    errs.append("no seasons.json in " + root)
else:
    data = load(os.path.join(root, "seasons.json"), "seasons.json")
if data is not None:
    seasons = data.get("seasons") if isinstance(data, dict) else None
    if not isinstance(seasons, list):
        errs.append('seasons.json must be an object with a "seasons" list')
        seasons = []
    if len(seasons) > 64:
        warns.append("%d seasons: the launcher reads at most 64" % len(seasons))
    listed = set()
    for i, s in enumerate(seasons):
        if not isinstance(s, dict):
            errs.append("season #%d is not an object" % (i + 1))
            continue
        folder = s.get("folder")
        tag = "season %s" % (folder if isinstance(folder, str) and folder else "#%d" % (i + 1))
        if not isinstance(folder, str) or not NAME_RE.match(folder) or folder.strip(".") == "":
            errs.append('%s: "folder" must be a bare folder name (letters, digits, . _ -, no / \\ or ..)' % tag)
            continue
        if folder in listed:
            errs.append("%s is listed twice" % tag)
            continue
        listed.add(folder)
        if "dates" not in s:
            warns.append("%s has no dates: the launcher skips it" % tag)
        d = os.path.join(root, folder)
        if not os.path.isdir(d):
            errs.append("%s: folder is missing" % tag)
            continue
        theme = {}
        tpath = os.path.join(d, "theme.json")
        if os.path.isfile(tpath):
            theme = load(tpath, "%s/theme.json" % folder)
            if not isinstance(theme, dict):
                if theme is not None:
                    errs.append("%s/theme.json must be an object" % folder)
                theme = {}
            else:
                files.append(folder + "/theme.json")
        part = theme.get("particles") if isinstance(theme.get("particles"), dict) else {}
        tiles = theme.get("tiles") if isinstance(theme.get("tiles"), dict) else {}
        for key, hi in (("count", 150), ("seconds", 15)):
            v = part.get(key)
            if v is not None and not (isinstance(v, (int, float)) and 0 <= v <= hi):
                warns.append("%s: particles.%s must be 0-%d" % (tag, key, hi))
        v = theme.get("background_opacity")
        if v is not None and not (isinstance(v, (int, float)) and 0 <= v <= 100):
            warns.append("%s: background_opacity must be 0-100" % tag)
        v = theme.get("accent")
        if v is not None and not (isinstance(v, str) and re.match(r"^#[0-9A-Fa-f]{6}$", v)):
            warns.append("%s: accent must be #RRGGBB" % tag)
        pk, ok = part.get("kind"), tiles.get("ornament")
        if pk is not None and pk not in PARTICLES:
            errs.append("%s: particles.kind '%s' is not one of: %s" % (tag, pk, " ".join(PARTICLES)))
        if ok is not None and ok not in ORNAMENTS:
            errs.append("%s: tiles.ornament '%s' is not one of: %s" % (tag, ok, " ".join(ORNAMENTS)))
        corner = tiles.get("corner")
        if corner not in (None, "top-left", "top-right", "top"):
            errs.append("%s: tiles.corner must be top-left, top-right or top" % tag)
        v = theme.get("background_interval")
        if v is not None:
            if isinstance(v, bool) or not isinstance(v, int):
                errs.append("%s: background_interval must be an integer number of seconds" % tag)
            elif not 10 <= v <= 86400:
                warns.append("%s: background_interval %d is clamped to 10-86400" % (tag, v))
        v = theme.get("background_order")
        if v is not None and v not in ("sequential", "shuffle"):
            errs.append("%s: background_order must be sequential or shuffle" % tag)
        present = set(os.listdir(d))
        for name in sorted(present - KNOWN - {n for n in SUBDIRS if os.path.isdir(os.path.join(d, n))}):
            warns.append("%s: unknown %s '%s' is ignored and not pushed"
                         % (tag, "folder" if os.path.isdir(os.path.join(d, name)) else "file", name))
        if "background.png" in present and "background.jpg" in present:
            errs.append("%s: has both background.png and background.jpg: keep one" % tag)
        for name in ("background.png", "background.jpg"):
            if name in present and image(tag, folder, name, "bg"):
                files.append(folder + "/" + name)
        multi = {sub: subfolder(tag, folder, sub) for sub in SUBDIRS if os.path.isdir(os.path.join(d, sub))}
        for sub in multi:
            files += multi[sub]
        if "background" in multi:
            if not multi["background"]:
                warns.append("%s: background/ holds no image: background.png/.jpg is used instead" % tag)
        elif "background_interval" in theme or "background_order" in theme:
            warns.append("%s: background_interval/background_order only apply with a background/ folder" % tag)
        if pk == "image":
            if "particles" in multi:
                if not multi["particles"] and "particle.png" not in present:
                    errs.append('%s: particles kind "image" needs at least one .png in particles/ or particle.png' % tag)
                elif multi["particles"] and "particle.png" in present:
                    warns.append("%s: particle.png is ignored while particles/ holds images" % tag)
            elif "particle.png" not in present:
                errs.append('%s: particles kind "image" needs particles/ with a .png or particle.png' % tag)
        for name, limit in (("particle.png", 128), ("ornament.png", 512 if corner == "top" else 256)):
            if name in present and image(tag, folder, name, limit):
                files.append(folder + "/" + name)
        if ok == "image" and "ornament.png" not in present:
            errs.append('%s: tiles ornament "image" needs ornament.png' % tag)
        for name in ICONS:
            if name in present and image(tag, folder, name, "icon"):
                files.append(folder + "/" + name)
    for name in sorted(os.listdir(root)) if os.path.isdir(root) else []:
        if os.path.isdir(os.path.join(root, name)) and name not in listed:
            warns.append("folder '%s' is not listed in seasons.json: it is not pushed" % name)
for e in errs:
    print("ERR " + e)
for w in warns:
    print("WARN " + w)
for n in files:
    print("FILE " + n)
PYEOF
)" || { echo "validation could not run. Nothing was sent to the TV." >&2; exit 2; }

FILES=""
NFILES=0
FAILED=0
while IFS= read -r LINE; do
  LINE=${LINE%$'\r'}   # Python on Windows prints CRLF; a stray \r would end up in every file name
  case "$LINE" in
    ERR\ *) echo "refused: ${LINE#ERR }" >&2; FAILED=1 ;;
    WARN\ *) echo "warning: ${LINE#WARN }" >&2 ;;
    FILE\ *) FILES="$FILES ${LINE#FILE }"; NFILES=$((NFILES + 1)) ;;
  esac
done <<< "$CHECK_OUT"
if [ "$FAILED" = 1 ]; then
  echo "Nothing was sent to the TV." >&2
  exit 2
fi
if [ "$MODE" = check ]; then
  echo "ok: seasons.json and $NFILES theme file(s) pass the checks"
  exit 0
fi

# Some firmware locks the shell out of Android/data, so files are staged in /data/local/tmp and copied in.
# The folder is emptied first so a file of an older pack cannot linger.
tv "mkdir -p $DIR && rm -rf $DIR/* && chmod 777 $DIR 2>/dev/null; true" >/dev/null \
  || { echo "could not clear $DIR on the TV" >&2; exit 1; }
send() { # <local file> <path below $DIR>
  local flat="${2//\//_}"
  if [ -n "${TV_ADB_BINARY:-}" ]; then
    "$TV_ADB_BINARY" -s "$TV_HOST" push "$1" "$TMP-$flat" >/dev/null || return 1
  else
    "$PY" -I b64push.py "$1" "$TMP-$flat" >/dev/null || return 1
  fi
  # tv.py exits 0 whatever the TV's command answered, so success is read from a marker in its output.
  tv "mkdir -p $DIR/$(dirname "$2") && chmod 777 $DIR/$(dirname "$2") 2>/dev/null; cp $TMP-$flat $DIR/$2 && chmod 666 $DIR/$2 && echo SEASON_SENT; rm -f $TMP-$flat" | grep -q SEASON_SENT
}
for NAME in seasons.json $FILES; do
  send "$PACK/$NAME" "$NAME" || { echo "push of $NAME failed; the TV may hold a partial pack: run --reset" >&2; exit 1; }
done
restart_home || { echo "the pack is on the TV but Home did not restart; press HOME on the remote" >&2; exit 1; }
echo "Pack $(basename "$PACK") is on the TV. Undo with: tools/season.sh --reset"
