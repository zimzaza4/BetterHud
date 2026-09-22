# for-each

Write one entry, get many. `for-each` is expanded while the configuration is parsed, before any
resource pack is generated, so everything downstream sees ordinary entries.

## Forms

```yaml
"avatar_{i}":            # {i} is available from the range form
  for-each: 1..5
```

```yaml
"row_{i}":
  for-each: [ct, t]      # a list of scalars also binds {i}
```

```yaml
"ce_{team}_slot_{slot}":
  for-each:              # a map of variables is expanded as a cartesian product
    team: [ct, t]
    slot: 1..5
```

```yaml
"avatar_{slot}":
  for-each:              # a list of maps gives one combination per record
    - { slot: 1, x: 77 }
    - { slot: 2, x: 53 }
  x: "{x}"
```

`{name}` is replaced inside keys, inside string values, and recursively inside nested maps and
lists. A value substituted into a string that then looks like a number is emitted as a number -
`x: "{x}"` reaches the loader as an integer, `scale: "{scale}"` as a decimal, so a record can feed
numeric keys too. A literal `"123"` that no variable touched stays the string it was written as.

The record form is what makes one entry cover a whole family that differs in more than its number -
a map's tiles are one element with a different image, different coordinates and different condition
bounds each, which no range can express:

```yaml
"map_{arena}_c{kx}_{ky}":
  for-each:
    - { arena: dust2, kx: 0, ky: 0, x: 150, y: 150, sx: -94,  sy: -73, kx0: -1, kx1: 1, ky0: -1, ky1: 1 }
    - { arena: dust2, kx: 1, ky: 0, x: 150, y: 150, sx:  -9,  sy: -73, kx0:  0, kx1: 2, ky0: -1, ky1: 1 }
  name: "{arena}_tile{kx}_{ky}"
  x: "{x}"
  y: "{y}"
  position: ["dx@(t + {sx})", "dy@(t + {sy})"]
```

Because the expansion happens **in place**, the elements stay where the entry is written - a family
that has to be drawn below something else (a map under its markers) keeps its place in the layout,
which a `repeats` block could not do.

## Limits

* Expansion happens at reload time, so the result is static and cannot depend on run-time data.
  For a count that comes from a placeholder, see `examples/repeats`.
* No arithmetic: `{i}` is a value, not an expression. Use the record form for irregular values.
* `for-each` is removed from the entry it expands, so it never reaches the loader.
