# conditions

`if` states the condition of an element, a layout group or a repeat. A string is an expression, a
map groups expressions, and `conditions:` keeps working exactly as it always did.

```yaml
      if: "item_tag:crosshair in ('null','shotgun','projectile') && !is_aiming"
```

```yaml
      if:
        all:
          - "ce_damage_angle >= -157.5"
          - "ce_damage_angle <= -22.5"
        any:
          - "ce_damaged_all"
```

## Expressions

| form | meaning |
|---|---|
| `a == b`, `a != b` | equality |
| `a > b`, `a < b`, `a >= b`, `a <= b` | order |
| `a in ('x', 'y')` | equal to any of the values |
| `a && b`, `a \|\| b`, `!a` | logic - `!` binds tightest, then `&&`, then `\|\|` |
| `(a \|\| b) && c` | grouping |
| `a` | a bare boolean placeholder is a condition on its own |

Operands resolve exactly like `first` and `second` do: `'quoted'` is a string, a bare number is a
number, `true` and `false` are booleans, `(number)x` forces a container, `[x]` wraps a name that
contains spaces or operators, and `ce_team_ct_exist:{slot}` works inside a template because the
variables are substituted before the expression is parsed.

## Structure

A list under `all` is a conjunction and a list under `any` is a disjunction; either can hold another
map, so nesting states the parentheses:

```yaml
      if:
        all:
          - any:
              - "ce_damage_angle >= 112.5"
              - "ce_damage_angle <= -112.5"
          - "!ce_is_aiming"
```

An empty `if` is always true, and every entry must exist - a missing placeholder is reported with
the expression it came from.

## Compatibility

`conditions:` is unchanged: `first`, `second`, `operation` and `gate` keep their meaning, including
the order they are written in. An element carrying both keys has to satisfy both.

Both forms are compiled into the same condition tree - an expression resolves its operands with
`PlaceholderManagerImpl.find` and its operation with `Operations.find`, exactly as a hand written
one does - so they cost the same per tick and behave identically. Parsing happens once per
expression, while the configuration is read, and the parsed form is cached.
