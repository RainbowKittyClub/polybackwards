#!/usr/bin/env python3
"""Write the table of vanilla items some older client lacks, for OlderClientItems.

Usage: python3 -I scripts/older_client_items.py VIAVERSION_JAR VIABACKWARDS_JAR [loom-cache] > \
    src/main/resources/polybackwards/older_client_items.json

VIAVERSION_JAR may be ViaFabric's jar, which nests ViaVersion. loom-cache defaults to
~/.gradle/caches/fabric-loom and must hold <version>/minecraft-client.jar for every version in
OLDEST, PREVIOUS and NEWEST.

An item is new in a release when its item definition is in that release's client jar and in no
older one. Each item gets the release that added it and, per older release, the item ViaBackwards
turns it into for that release's clients, read from Via's own mapping files.
"""

import io
import json
import pathlib
import struct
import sys
import zipfile

OLDEST = ("26.1", "26.1.1", "26.1.2")
PREVIOUS = "26.2"
NEWEST = "26.3"
RELEASE_NAMES = {"26.1": "V26_1", PREVIOUS: "V26_2", NEWEST: "V26_3"}
ITEMS = "assets/minecraft/items/"
VV_DATA = "assets/viaversion/data/"
VB_DATA = "assets/viabackwards/data/"

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, \
    INT_ARRAY, LONG_ARRAY = range(13)
FIXED = {BYTE: ">b", SHORT: ">h", INT: ">i", LONG: ">q", FLOAT: ">f", DOUBLE: ">d"}


def item_names(jar):
    """Item ids, without namespace, of every vanilla item definition in a client jar."""
    with zipfile.ZipFile(jar) as archive:
        return {name[len(ITEMS):-len(".json")] for name in archive.namelist()
                if name.startswith(ITEMS) and name.endswith(".json")}


def read_nbt(data):
    """An uncompressed named NBT root, as plain Python values."""
    view = io.BytesIO(data)

    def num(fmt):
        return struct.unpack(fmt, view.read(struct.calcsize(fmt)))[0]

    def payload(tag):
        if tag in FIXED:
            return num(FIXED[tag])
        if tag == STRING:
            return view.read(num(">H")).decode("utf-8")
        if tag == BYTE_ARRAY:
            return view.read(num(">i"))
        if tag in (INT_ARRAY, LONG_ARRAY):
            fmt = ">i" if tag == INT_ARRAY else ">q"
            return [num(fmt) for _ in range(num(">i"))]
        if tag == LIST:
            item = num(">b")
            return [payload(item) for _ in range(num(">i"))]
        if tag == COMPOUND:
            out = {}
            while (child := num(">b")) != END:
                name = view.read(num(">H")).decode("utf-8")
                out[name] = payload(child)
            return out
        raise ValueError(f"unknown NBT tag {tag}")

    root = num(">b")
    view.read(num(">H"))
    return payload(root)


def var_int(data, at):
    """A varint and the offset after it."""
    value = shift = 0
    while True:
        byte = data[at]
        at += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, at
        shift += 7


def zig_zag(data, at):
    value, at = var_int(data, at)
    return (value >> 1) ^ -(value & 1), at


def pairs(data):
    """The (at, value) pairs of a shifts or changes entry, as Via's MappingDataLoader reads them."""
    out, at, prev_at, prev_value = [], 0, -1, 0
    while at < len(data):
        step, at = var_int(data, at)
        delta, at = zig_zag(data, at)
        prev_at += 1 + step
        prev_value += delta
        out.append((prev_at, prev_value))
    return out


def mappings(tag):
    """One Via mappings entry (version 2) as a list from old id to new id, -1 for none."""
    size, strategy = tag["size"], tag["id"]
    if strategy == 3:
        return list(range(size))
    if strategy == 0:
        values, at, prev = [], 0, 0
        for _ in range(size):
            delta, at = zig_zag(tag["val"], at)
            prev += delta
            values.append(prev)
        return values
    out = [-1] * size
    found = pairs(tag["val"])
    if strategy == 1:
        out[:found[0][0]] = range(found[0][0])
        for i, (start, mapped) in enumerate(found):
            stop = found[i + 1][0] if i + 1 < len(found) else size
            out[start:stop] = range(mapped, mapped + stop - start)
        return out
    if strategy == 2:
        if "nofill" not in tag:
            out = list(range(size))
        for start, mapped in found:
            out[start] = mapped
        return out
    raise ValueError(f"unknown mapping strategy {strategy}")


def open_via(path, data_dir):
    """A reader for Via's data files in a jar, or in the ViaVersion jar a ViaFabric jar nests."""
    archive = zipfile.ZipFile(path)
    if not any(name.startswith(data_dir) for name in archive.namelist()):
        nested = next(name for name in archive.namelist()
                      if name.startswith("META-INF/jars/viaversion-") and name.endswith(".jar"))
        archive = zipfile.ZipFile(io.BytesIO(archive.read(nested)))
    return lambda name: read_nbt(archive.read(data_dir + name))


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    via_version = open_via(sys.argv[1], VV_DATA)
    via_backwards = open_via(sys.argv[2], VB_DATA)
    cache = pathlib.Path(sys.argv[3] if len(sys.argv) > 3
                         else pathlib.Path.home() / ".gradle/caches/fabric-loom")

    oldest = set().union(*(item_names(cache / v / "minecraft-client.jar") for v in OLDEST))
    previous = item_names(cache / PREVIOUS / "minecraft-client.jar")
    newest = item_names(cache / NEWEST / "minecraft-client.jar")
    added = {name: PREVIOUS for name in previous - oldest}
    added.update({name: NEWEST for name in newest - previous - oldest})

    # Each release's item registry by protocol id, from Via's global identifier table.
    table = via_version("identifier-table.nbt")["items"]
    registry = {version: [table[i] for i in mappings(via_version(f"identifiers-{version}.nbt")["items"])]
                for version in ("26.1", PREVIOUS, NEWEST)}
    # What ViaBackwards sends a client one release back, by protocol id.
    down = {NEWEST: mappings(via_backwards(f"mappings-{NEWEST}to{PREVIOUS}.nbt")["items"]),
            PREVIOUS: mappings(via_backwards(f"mappings-{PREVIOUS}to26.1.nbt")["items"])}
    older = {NEWEST: PREVIOUS, PREVIOUS: "26.1"}

    items = {}
    for name in sorted(added):
        release = added[name]
        if name not in registry[release]:
            sys.exit(f"{name} has an item definition but no {release} registry entry")
        entry = {"release": RELEASE_NAMES[release]}
        version, item_id = release, registry[release].index(name)
        while version in down:
            item_id = down[version][item_id]
            version = older[version]
            substitute = registry[version][item_id]
            if substitute not in registry[NEWEST]:
                sys.exit(f"{name}: {version} substitute {substitute} is not a {NEWEST} item")
            entry[RELEASE_NAMES[version]] = f"minecraft:{substitute}"
        items[f"minecraft:{name}"] = entry
    # One item per line keeps the file small and its diffs readable.
    lines = [f" {json.dumps(name)}: {json.dumps(entry, sort_keys=True, separators=(',', ':'))}"
             for name, entry in items.items()]
    sys.stdout.write('{"items": {\n' + ",\n".join(lines) + "\n}}\n")


if __name__ == "__main__":
    main()
