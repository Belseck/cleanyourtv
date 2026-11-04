# Season packs

A season pack changes the launcher's look on certain dates: a background picture, falling particles, an
ornament on the app tiles, an accent colour and, optionally, themed top-bar icons. The launcher ships a built-in
pack. You can replace it with your own: one folder per theme, plus a small index.

An example pack is in [`seasons/`](../seasons/): nine themes with their particles, tile ornaments and top-bar icons,
all drawn in code. It ships without background pictures: add your own `background.jpg`, or a `background/` folder,
to each theme.

## Folder layout

```
my-pack/
  seasons.json              index only: which folders, when, and in what order of priority
  christmas/
    theme.json              optional: names, colours, particles, ornament
    background.png          or background.jpg (not both)
    background/             optional: several images that rotate on Home
      01.jpg
      02.png
      day/                  optional: used from sunrise to sunset instead of the images above
        01.jpg
      night/                optional: used from sunset to sunrise
        01.jpg
    particle.png            single particle sprite, when particles.kind is "image"
    particles/              optional: several sprites, each particle picks one at random
      flake1.png
      flake2.png
    ornament.png            only when tiles.ornament is "image"
    icon-wifi.png           optional themed top-bar icons
    icon-theme.png
    icon-settings.png
  mothers-day/
    theme.json
    background.png
    ...
```

On the TV the pack lives in `/sdcard/TVHome/seasons/`, with the same layout. With no `seasons.json` there, the
launcher uses its built-in pack. A season with a mistake is skipped; a `seasons.json` that cannot be read at all
makes the launcher fall back to the built-in pack.

## seasons.json (the index)

```json
{ "seasons": [
    { "folder": "christmas", "dates": { "from": "12-01", "to": "12-30" }, "priority": 10 },
    { "folder": "winter",    "dates": { "from": "12-21", "to": "03-19" }, "priority": 0 }
] }
```

| Field | Type | Notes |
|---|---|---|
| `folder` | string | Bare name of the theme folder next to `seasons.json`: letters, digits, `.`, `_`, `-`. Also what `season_preview` takes. |
| `dates` | object | One of the four rules below. |
| `priority` | number | Higher wins when dates overlap. Ties: the first one listed. |

At most 64 seasons. Nothing else belongs in the index.

## theme.json

Every field is optional, and so is the file itself. Colours are `#RRGGBB`.

```json
{
  "name": { "es": "Navidad", "en": "Christmas" },
  "background_opacity": 35,
  "background_interval": 120,
  "background_order": "shuffle",
  "accent": "#FF7A1A",
  "particles": { "kind": "image", "count": 80, "seconds": 8 },
  "tiles": { "ornament": "image", "corner": "top" }
}
```

This theme rotates the images in `background/` every 2 minutes in random order, drops particles drawn from the
PNGs in `particles/`, and stretches `ornament.png` across the top edge of each tile.

A theme with the built-in kinds and no rotation:

```json
{
  "background_opacity": 35,
  "particles": { "kind": "snow", "colors": ["#FFFFFF"], "count": 100, "seconds": 7 },
  "tiles": { "ornament": "snow_cap" }
}
```

| Field | Type | Notes |
|---|---|---|
| `name` | object | Display name per language, `{"es": "...", "en": "..."}`. |
| `name_res` | string | Name of a built-in translated name (`season_name_christmas`). |
| `background_opacity` | 0-100 | How strongly the background picture shows. |
| `background_interval` | integer | Seconds between background changes. Default 300; values outside 10-86400 are clamped. Used only with a `background/` folder. |
| `background_order` | string | `sequential` (file-name order, default) or `shuffle`. Used only with a `background/` folder. |
| `accent` | colour | Highlight colour for the season. |
| `skin` | string | Built-in animated icon set. Currently `halloween`. |
| `particles.kind` | string | `snow`, `sakura`, `leaf`, `heart`, `confetti`, `spark`, `bats`, `marigold`, `image` or `none`. `image` uses the PNGs in `particles/` (each particle picks one at random) or, with no `particles/` folder, `particle.png`. |
| `particles.colors` | list of colours | Used by the built-in kinds. |
| `particles.count` | 0-150 | How many particles are on screen. |
| `particles.seconds` | 0-15 | How long one particle takes to cross the screen. |
| `tiles.ornament` | string | `snow_cap`, `frost`, `blossom`, `sun`, `leaves`, `heart`, `hearts`, `sparkles`, `lantern`, `flag_band`, `cobweb`, `marigold`, `candle`, `image` or `none`. `image` uses `ornament.png`. |
| `tiles.colors` | list of colours | For `flag_band`, the stripes from top to bottom, of equal height, e.g. `["#FFFFFF", "#D52B1E"]`. Without colours `flag_band` draws nothing. |
| `tiles.corner` | string | `top-left`, `top-right` or `top`. With `top`, `ornament.png` is stretched across the tile's top edge keeping its aspect ratio (snow caps, frost, flag bands). |
| `photos.queries` | list of text | Up to 8 Unsplash search terms of at most 80 characters, for the theme's internet photos and its share of the screensaver. English terms find the most. Without it, the theme's English name is searched. |

A theme whose internet photos look like the season:

```json
{
  "name": { "en": "Autumn", "es": "Otoño" },
  "photos": { "queries": ["autumn forest red leaves", "fall foliage orange trees", "autumn lake reflection"] }
}
```

Keep each theme's terms specific to it. A term two themes share ("mountains") brings the same photos to both.

## Fixed file names

Images are never named in JSON: the launcher looks for these names inside each theme folder. Any other file is
ignored (and `season.sh` warns about it and does not push it).

| File | Format | Size | Used when |
|---|---|---|---|
| `background.png` or `background.jpg` | PNG or JPG | Any size; 1920x1080 (16:9) fills the screen. Scaled to fit keeping its aspect ratio, anchored top-right, left and bottom edges fade out. Under 4 MB. Only one of the two. | Always, if present |
| `background/*.jpg`, `.jpeg`, `.png` | JPG or PNG | Same rules as `background.png`. At most 30 images, each under 4 MB. | Optional: when the folder holds at least one image, these rotate on Home with a cross-fade (`background_interval`, `background_order`) and `background.png`/`.jpg` is not used |
| `background/day/*`, `background/night/*` | JPG or PNG | Same rules, at most 30 images each | Optional: see [Day and night backgrounds](#day-and-night-backgrounds) |
| `particle.png` | PNG with transparency | 128 px or smaller | `particles.kind` is `image` and there is no `particles/` folder |
| `particles/*.png` | PNG with transparency | Each 128 px or smaller on its longest side | `particles.kind` is `image`: each particle picks one at random. Takes precedence over `particle.png` |
| `ornament.png` | PNG with transparency | 256 px or smaller; with `tiles.corner` `top`, up to 512 px wide | `tiles.ornament` is `image` (required then) |
| `icon-wifi.png` | PNG with transparency | Square, about 66x66 px | Optional: replaces the Wi-Fi icon in the top bar |
| `icon-theme.png` | PNG with transparency | Square, about 66x66 px | Optional: replaces the theme (sparkle) icon |
| `icon-settings.png` | PNG with transparency | Square, about 66x66 px | Optional: replaces the settings icon |

Larger sprites and ornaments are downscaled on the TV. Every file must be under 4 MB.

## Date rules

`dates` takes one of four shapes.

**Fixed range**, `MM-DD` to `MM-DD`, every year. A range that ends before it starts wraps over the new year.

```json
{ "from": "12-01", "to": "12-30" }
{ "from": "12-31", "to": "01-02" }
```

**nth weekday of a month**, with optional days around it. `n` is 1-5, or `-1` for the last one. Thanksgiving in
the United States (fourth Thursday of November, plus the day after):

```json
{ "rule": "nth-weekday", "month": 11, "weekday": "thursday", "n": 4, "days_before": 0, "days_after": 1 }
```

Mother's Day in many countries (second Sunday of May):

```json
{ "rule": "nth-weekday", "month": 5, "weekday": "sunday", "n": 2, "days_before": 0, "days_after": 0 }
```

**Easter**, as day offsets from Easter Sunday. Holy Week is Palm Sunday to Easter Sunday:

```json
{ "rule": "easter", "from": -7, "to": 0 }
```

**Lunar New Year**, starting on the day itself and lasting `days` days:

```json
{ "rule": "lunar-new-year", "days": 3 }
```

## Day and night backgrounds

A theme can have one set of pictures for the day and another for the night. Put them in `background/day/` and
`background/night/`. From sunrise to sunset the launcher shows `background/day/`, and from sunset to sunrise
`background/night/`; the picture changes with a cross-fade when the time comes, while Home is in front.

Sunrise and sunset come from `latitude` and `longitude` in the launcher's `config.xml`; without them the day runs
from 07:00 to 18:30. Both folders are optional. When the folder for the current part of the day is missing or
holds no image, the launcher falls back to the images in `background/`, then to `background.png` or `.jpg`. A
theme with neither folder behaves exactly as before.

## Priority

When several seasons cover the same day, the one with the higher `priority` wins; on a tie the first one in
`seasons.json` wins. Use a low number (0) for broad background seasons such as the four climate seasons, and a
higher one (10-20) for holidays that must override them.

## Composing backgrounds

Backgrounds sit under the whole home screen, so leave room for what is drawn on top:

- Dark tones. The text and tiles are light.
- Keep the bottom 35% quiet: the dock is there.
- Keep the top-left calm: the clock and the date.
- Keep the top-right free: the buttons.
- Put the subject centre-right.
- No text in the picture.

A picture that is not 16:9 is fitted top-right with its left and bottom edges faded, so the plain theme
background shows around it. That is fine, but 16:9 gives you full control.

## Getting the pack onto the TV

Two ways. Both end in `/sdcard/TVHome/seasons/`, and the launcher picks the files up on its own.

**With a file manager on the TV.** The folder is plain shared storage, so no developer options or computer are
needed. Copy `seasons.json` and the theme folders into `/sdcard/TVHome/seasons/` using any TV file manager (for
example X-plore or File Commander), or send them from a phone or computer with an app such as Send Files to TV.
Keep the layout above: `seasons.json` directly in `seasons/`, and each theme folder beside it. Home picks up the
copied files automatically on its next resume (leave Home and come back, or wait until the next time it opens).

**With `tools/season.sh`** from the computer:

```
tools/season.sh --check my-pack                  # validate only, sends nothing
TV_HOST=192.168.1.50 tools/season.sh my-pack          # check, then push
TV_HOST=192.168.1.50 tools/season.sh --reset          # delete the pushed pack
```

Set `TV_ADB_BINARY` as described in `tools/native-adb.md` to push with Google's adb; otherwise `tools/b64push.py`
sends the files.

Before sending anything the script refuses (exit 2) a pack with no `seasons.json`, an index or `theme.json` that
is not valid JSON, an entry whose `folder` is not a bare name or does not exist, an unknown particle kind or
ornament, kind `image` with neither a `.png` in `particles/` nor `particle.png`, ornament `image` without `ornament.png`,
both `background.png` and `background.jpg` in one folder, a `background_interval` that is not an integer, a
`background_order` other than `sequential` or `shuffle`, an image of another type (`.gif`, `.webp`...) in
`background/` or `particles/`, more than 30 images in `background/`, or a file over 4 MB. It only warns about
unknown files or folders (also inside `particles/` and `background/`), a sprite over 128 px, an ornament over
256 px (512 px wide for corner `top`), a `background_interval` outside 10-86400, a background that is not 16:9, icons that are not
about 66x66 px, and values outside the limits above.

A push empties `/sdcard/TVHome/seasons/` first, so it replaces the old pack as a whole, then sends
`seasons.json` and every theme folder (only `theme.json`, the background, `background/*`, `particle.png`, `particles/*`,
`ornament.png` and the icons). Home rebuilds by opening Settings and pressing HOME; the launcher is never force-stopped, because that
switches off its key helpers. `--reset` removes `seasons.json` **and every theme folder**, so the built-in pack
applies again.

Exit status: 0 done, 1 the push failed, 2 refused before sending anything.

## The theme menu on the Home screen

The sparkle button next to Wi-Fi in the top bar opens the theme menu:

- **View background** shows the pictures full screen: Left and Right change the picture, Up shows its details.
- **Background**: Theme, the pack's pictures, or Internet, photos found with `photos.queries`. Internet photos are
  fetched again when the terms change or after three days, and stay on the TV in between.
- **Change photo every**, while the background is Internet.
- **Change theme by date** on/off. On, the theme follows the dates in `seasons.json`. Off, the theme you picked
  stays.
- **A theme list.** Pick any theme from the pack to use it right now, whatever the date.
- **Theme pack problems**, shown only when the pushed pack has mistakes: the row shows how many, and opens the
  list of what the launcher skipped and why (a bad date rule, a missing image, an unknown particle kind). The
  messages are in English.

## Preview one season

To see a season without waiting for its dates, pick it in the theme menu. Alternatively, put its `folder` name in
a file named `season_preview` in `/sdcard/Android/data/com.gathin.tvhome/files/`:

```
adb shell "printf '%s' christmas > /sdcard/Android/data/com.gathin.tvhome/files/season_preview"
```

The file holds only the folder name, and that season is forced. Delete the file to go back to the dates.
