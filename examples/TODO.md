# TODO

## Where Clause on `with` blocks:
```rust
with allPlayers() where Player.getName().startsWith("zBin") do {

}
```

## Compile `with` blocks to loop
```
select (...)
uuids = selection_uuids
reset selection
foreach uuid in uuids {
    select uuid
    ( code )
    reset selection
}
```

## Use bitwise blocks to calculate AND and OR