# repeats

Write one widget, place it N times. A `repeats` block sits beside `images`, `texts` and `heads`
inside a layout group.

```yaml
ce_team_layout:
  layer: 5
  images:
    panel: { name: ce_game_status_panel, x: -34, y: 0 }
  repeats:
    ct:
      template: ce_member_widget
      source: "ce_team_members:ct"
      max: 12
      pixel: { x: 77, y: 2 }
      offset: { dx: -24, dy: 0 }
```

## How many instances exist

`max` instances are compiled while the configuration is parsed. At run time the repeat reads
`source` (a number placeholder), rounds it, clamps it into `0..max` and renders the first `count`
instances only. The ones past `count` cost nothing per tick, and the ones past `max` do not exist
at all - a larger number is silently truncated.

Everything a template names has to exist for every instance up to `max`. An instance whose `{slot}`
resolves to a missing image, text or head is dropped with a log line of its own while the rest of
the row still loads, so give the families it names a `for-each: slot: 1..max` and the two ranges
cannot drift apart.

## Keys

| key | | meaning |
|---|---|---|
| `source` | required | number placeholder giving `count` |
| `max` | required | how many instances to compile (>= 1) |
| `template` | one of | name of a layout whose root is marked `template: true` |
| `images` / `texts` / `heads` | one of | inline elements instead of a template |
| `offset` | one of | `{dx, dy}`: instance `k` sits at `pixel + k * (dx, dy)` |
| `flow` | one of | `{width, space}`: shorthand for `offset: {dx: width + space}` |
| `grid` | one of | `{per-row, x-space, y-space}`: wrap the instances into rows |
| `pixel` with `x-equation` / `y-equation` | one of | origin driven by an equation |
| `as` | optional | variable name bound to the instance number, default `i` |
| `variables` | optional | constants substituted into the instance |
| `pixel` | optional | the origin of the first instance, default `{0, 0}` |
| `gui` | optional | screen-percentage offset added to every instance |
| `align` | optional | `start` / `center` / `end`, default `start` |
| `conditions` | optional | gates the whole repeat |
| `color-overrides`, `placeholder-option`, `placeholder-string-format` | optional | as elsewhere |

## The instance variable is substituted in two passes

The outer pass is the group's own `for-each`, the inner pass runs once per instance and binds the
`as` variable to `1..max`. A template lives in its own entry, so variables of the outer pass do not
reach it by themselves - forward the ones it needs:

```yaml
  repeats:
    "row_{team}":
      for-each:
        - { team: ct, x: 77,  dx: -24 }
        - { team: t,  x: 155, dx: 24 }
      template: ce_member_widget
      variables: { team: "{team}" }   # outer {team} becomes a constant of the inner pass
      pixel: { x: "{x}" }
      offset: { dx: "{dx}" }
```

## A template is registered, not compiled

```yaml
ce_member_widget:
  template: true
  images: ...
```

Without the flag the group is compiled like any other layout, which fails as soon as it names
`{slot}` elements that do not exist yet. With the flag its raw yaml is kept instead, ready to be
instantiated by `template:`.

## Coordinates

An instance is created with `offset: left`, so an instance's origin is its own origin: element
coordinates are relative to the instance, not to the group's box. `pixel` places the first
instance and the pitch decides the rest - the sign of the pitch is the direction, so `dx: -24`
grows leftwards and `dy: 20` grows downwards.

`align` shifts the whole row at run time by the current `count`: `start` keeps the first instance
on the origin, `center` centres the row on it, `end` puts the last instance there. With `grid` the
shift is computed per row, so a short last row is centred on its own.

## Wrapping into rows

`grid` wraps the instances - the shorthand for what an `x-equation` / `y-equation` pair would
otherwise spell out:

```yaml
  repeats:
    ct:
      template: ce_member_widget
      source: "ce_team_members:ct"
      max: 12
      as: slot
      pixel: { x: 77, y: 2 }
      grid: { per-row: 5, x-space: -24, y-space: 30 }
```

Twelve instances become rows of five, five and two, every row starting at `pixel` again. Instances
past `count` are not rendered, so a short last row is normal.

The horizontal step is applied for you: an instance advances along x by its column, whichever of
`grid`, `flow` and `offset` produced it. The vertical step is not - a rendered instance carries x
only - so a template places its own row by substituting `{y}`, which the repeat binds to that
instance's row offset (`(index / per-row) * y-space`, `index * dy`, or `0`):

```yaml
ce_member_widget:
  template: true
  texts:
    name:
      name: eng_font
      pattern: "member {slot}"
      y: "{y}"
      align: left
```

`{y}` is `0` for a single row, and an entry named `y` in `variables` still wins over it.

## Equations

`pixel` also accepts equations, evaluated once per instance with `t` bound to the instance number
(`1..max`):

```yaml
      pixel:
        x-equation: "77 - 24 * ((t - 1) - 5 * floor((t - 1) / 5))"
        y-equation: "2 + 18 * floor((t - 1) / 5)"
```

Because instances are compiled once, nothing that depends on the run-time `count` can be an
equation, and `align` cannot be derived from one either - with no single pitch its shift stays `0`.
Use `grid` when you want wrapping and alignment together, and keep equations for layouts a grid
cannot express.

## Cost

* Pack size does not grow with `max`: glyphs are shared by `(shader, texture, ascent)` and every
  instance of a template resolves to the same ones. A `render-scale` other than the default breaks
  that sharing, and so does a `grid`: each row is a new ascent, so it adds `rows * elements` glyphs.
* Parse time and resident objects grow with `max * elements * repeats`, but the constant is small:
  on a widget of four images and one text with two repeats, going from 30 to 300 instances per
  repeat - 540 more instances - cost 104 ms of reload (2,324 ms against 2,428 ms), while the
  variance of pack generation hides the difference between 12 and 30.
* The pack is byte-identical across all of them: 12, 30 and 300 instances produced the same
  8,589,072 byte pack, and 300 definitions behind them did not change it either.
* Resident memory keeps the same shape: 12 against 30 stayed within the noise of a running server,
  while 300 instances - with 300 definitions behind them - added roughly 100 MB, because a text
  element builds a scaled glyph table per instance. Keep a large `max` image-only.
* Per-tick bandwidth and CPU grow with `count`, exactly as if the elements had been written out by
  hand - you are rendering that many players.

## Converting an existing block

Replace the `_1 .. _N` element families with a single `template`, move everything that does not
belong to one slot (totals, timers, counts) up into the group itself, and rewrite absolute
coordinates as offsets from the instance origin.
