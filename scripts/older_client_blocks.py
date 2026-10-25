#!/usr/bin/env python3
"""Write the table of vanilla blocks some older client lacks, for OlderClientStandIns.

Usage: python3 -I scripts/older_client_blocks.py [loom-cache] > \
    src/main/resources/polybackwards/older_client_blocks.json

loom-cache defaults to ~/.gradle/caches/fabric-loom and must hold <version>/minecraft-client.jar
for every version in OLDEST, PREVIOUS and NEWEST (a Loom build of that version caches it).

A block is new in a release when its blockstate file is in that release's client jar and in no
older one. Each block gets the release that added it, the kind of stand-in it takes and its 26.3
blockstate, model entries reduced to model, x, y, uvlock and weight. A slab also names the full
block its double variant draws, and a copy the vanilla block an older client gets in its place.
"""

import json
import pathlib
import sys
import zipfile

# Client jars by release: every 26.1.x, then 26.2, then 26.3, whose blockstates the table copies.
OLDEST = ("26.1", "26.1.1", "26.1.2")
PREVIOUS = "26.2"
NEWEST = "26.3"
RELEASE_NAMES = {PREVIOUS: "V26_2", NEWEST: "V26_3"}
BLOCKSTATES = "assets/minecraft/blockstates/"

# Kinds by id suffix, first match wins, so the longer suffix comes first.
SUFFIX_KINDS = (
    ("_trapdoor", "trapdoor"),
    ("_door", "door"),
    ("_fence_gate", "fence_gate"),
    ("_fence", "display"),
    # 26.1 draws a bed stand-in by its block entity, so beds go over snow layers instead.
    ("_bed", "display"),
    ("_leaves", "leaves"),
    ("_sapling", "plant"),
    ("_slab", "slab"),
    ("_stairs", "display"),
    ("_wall", "display"),
    ("_button", "display"),
    ("_pressure_plate", "display"),
    ("_shelf", "shelf"),
    # Signs keep the client's own renderer and editor as the same sign of a stand-in wood.
    ("_sign", "copy"),
)
# Kinds of ids no suffix decides.
ID_KINDS = {
    "red_shrub": "plant",
    "shelf_mushroom": "display",
    "sulfur_spike": "display",
}
# The older wood each new wood's copies go out as, picked by eye.
STAND_IN_WOOD = {"poplar": "pale_oak"}
# Variant keys of a full block: one model, or one per log axis.
FULL_BLOCK_KEYS = ({""}, {"axis=x", "axis=y", "axis=z"})


def blockstate_names(jar):
    """Block ids, without namespace, of every vanilla blockstate file in a client jar."""
    with zipfile.ZipFile(jar) as archive:
        return {name[len(BLOCKSTATES):-len(".json")] for name in archive.namelist()
                if name.startswith(BLOCKSTATES) and name.endswith(".json")}


def kind_of(name, blockstate):
    """The stand-in kind of a block, from its id and its blockstate's variant keys."""
    if name.startswith("potted_"):
        return "display"
    if name in ID_KINDS:
        return ID_KINDS[name]
    for suffix, kind in SUFFIX_KINDS:
        if name.endswith(suffix):
            return kind
    if set(blockstate.get("variants", {})) in FULL_BLOCK_KEYS:
        return "full_block"
    sys.exit(f"no stand-in kind for {name}; extend SUFFIX_KINDS or ID_KINDS")


def model_entry(model):
    """One blockstate model entry, without fields at their default."""
    entry = {"model": model["model"]}
    for field, default in (("x", 0), ("y", 0), ("uvlock", False), ("weight", 1)):
        if model.get(field, default) != default:
            entry[field] = model[field]
    return entry


def alternatives(apply):
    """A variant's or part's models as a list of alternatives."""
    return [model_entry(model) for model in (apply if isinstance(apply, list) else [apply])]


def copy_of(name, every_oldest):
    """The vanilla block every client has that a copy-kind block goes out as."""
    for wood, stand_in in STAND_IN_WOOD.items():
        if name.startswith(wood + "_"):
            copy = stand_in + name[len(wood):]
            if copy not in every_oldest:
                sys.exit(f"{copy}, the copy of {name}, is not in every older client")
            return f"minecraft:{copy}"
    sys.exit(f"no copy for {name}; extend STAND_IN_WOOD")


def table_entry(name, release, blockstate, every_oldest):
    """The table's entry for one block."""
    kind = kind_of(name, blockstate)
    entry = {"release": RELEASE_NAMES[release], "kind": kind}
    if "variants" in blockstate:
        entry["variants"] = {key: alternatives(apply)
                             for key, apply in sorted(blockstate["variants"].items())}
    else:
        entry["multipart"] = [{"when": part["when"], "apply": alternatives(part["apply"])}
                              if "when" in part else {"apply": alternatives(part["apply"])}
                              for part in blockstate["multipart"]]
    if kind == "slab":
        double = entry["variants"]["type=double"][0]["model"]
        entry["full"] = double.replace("block/", "", 1)
    if kind == "copy":
        entry["copy"] = copy_of(name, every_oldest)
    return entry


def main():
    cache = pathlib.Path(sys.argv[1] if len(sys.argv) > 1
                         else pathlib.Path.home() / ".gradle/caches/fabric-loom")
    oldest_jars = [blockstate_names(cache / v / "minecraft-client.jar") for v in OLDEST]
    oldest = set().union(*oldest_jars)
    # A copy goes out to every 26.1.x client, so it must be in each of their jars.
    every_oldest = set.intersection(*oldest_jars)
    previous = blockstate_names(cache / PREVIOUS / "minecraft-client.jar")
    newest_jar = cache / NEWEST / "minecraft-client.jar"
    added = {name: PREVIOUS for name in previous - oldest}
    added.update({name: NEWEST for name in blockstate_names(newest_jar) - previous - oldest})
    blocks = {}
    with zipfile.ZipFile(newest_jar) as archive:
        for name in sorted(added):
            blockstate = json.loads(archive.read(f"{BLOCKSTATES}{name}.json"))
            blocks[f"minecraft:{name}"] = table_entry(name, added[name], blockstate,
                                                      every_oldest)
    # One block per line keeps the file small and its diffs readable.
    lines = [f" {json.dumps(name)}: {json.dumps(entry, sort_keys=True, separators=(',', ':'))}"
             for name, entry in blocks.items()]
    sys.stdout.write('{"blocks": {\n' + ",\n".join(lines) + "\n}}\n")


if __name__ == "__main__":
    main()
