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
lists. A value that becomes a plain integer is emitted as an integer, so `x: "{x}"` reaches the
loader as a number.

## Limits

* Expansion happens at reload time, so the result is static and cannot depend on run-time data.
  For a count that comes from a placeholder, see `examples/repeats`.
* No arithmetic: `{i}` is a value, not an expression. Use the record form for irregular values.
* `for-each` is removed from the entry it expands, so it never reaches the loader.
