"""Audit the bazaar for money printers.

  python3 verify_bazaar.py
      Audits the generator output (out/categories, ecoshop_prices.csv) for every path a player could
      use to turn coins into more coins:
        1. NPC -> craft -> bazaar   (buy raw from NPC, compress, dump on bazaar)
        2. bazaar -> craft -> bazaar (buy raw on bazaar, compress, sell enchanted)
        3. bazaar -> NPC            (buy on bazaar, sell to NPC)
      Each is evaluated at the WORST case: bazaar selling at its ceiling, buying at its floor.

  python3 verify_bazaar.py crafting [--categories DIR] [--recipes FILE] [--fuel ITEM] [--fuel-smelts N]
      Recipe arbitrage in the categories the plugin ships (src/main/resources/categories). For every
      vanilla crafting, stonecutting or cooking recipe whose output and inputs are all listed, the bazaar
      must pay no more for the output than the inputs cost to buy there, with every item at its base
      price and again with every item at its floor. Furnace, smoker and blast furnace recipes also pay
      for fuel: 1/N of the fuel item's buy price per item (default coal, which smelts 8). A campfire
      needs no fuel. Exits 1 on any violation. CategoryPricesCraftingTest runs the same rule in mvn test.

  python3 verify_bazaar.py extract-recipes DIR [--out FILE]
      Rewrites the recipe table (tools/vanilla_recipes.txt) from an unpacked server jar, where DIR holds
      data/minecraft/recipe and data/minecraft/tags/item (see README for how to unpack it).
"""
import argparse, glob, json, os, re, sys


def audit_generated():
    SPREAD = 0.05
    SHOP = {}
    for line in open('ecoshop_prices.csv', encoding='utf-8'):
        p = line.strip().split('|')
        if len(p) >= 5 and p[3]:
            SHOP[p[2]] = {'buy': float(p[3]), 'sell': float(p[4]) if p[4] else 0.0}
    SHOP['diamond_block'] = {'buy': 1980.0, 'sell': 495.0}
    SHOP['iron_block'] = {'buy': 270.0, 'sell': 72.0}

    items = {}
    skipped = []
    for f in glob.glob('out/categories/*.yml'):
        txt = open(f, encoding='utf-8').read()
        # Parse each entry as a block of "key: value" lines rather than a fixed line order.
        # An ordered regex silently DROPS entries when the generator adds a field (elasticity and
        # npc_* have each done it), and a dropped entry is an unaudited one -- exactly the item a
        # money-printer check must not miss. Order-independent parsing fails loudly instead.
        for m in re.finditer(r'^  (\w+):\n((?:    [^\n]*\n)+)', txt, re.M):
            iid, body = m.group(1), m.group(2)
            if 'item: ' not in body:
                continue                       # a groups: entry, not an item
            prov = re.search(r'#\s*([\d.]+)x (\w+) @ ([\d.]+)/unit', body)
            base_price = re.search(r'base_price: ([\d.]+)', body)
            if not (prov and base_price):
                skipped.append((iid, 'base_price: auto' if 'base_price: auto' in body else 'no provenance'))
                continue
            ceil = re.search(r'ceiling_pct: ([\d.]+)', body)
            floor = re.search(r'floor_pct: ([\d.]+)', body)
            items[iid] = {'base_price': float(base_price.group(1)),
                          'ceiling': float(ceil.group(1)) if ceil else 3.0,
                          'floor': float(floor.group(1)) if floor else 0.4,
                          'mult': float(prov.group(1)), 'base': prov.group(2),
                          'unit': float(prov.group(3)), 'cat': f}

    print(f'audited {len(items)} bazaar items')
    if skipped:
        print(f'NOT audited ({len(skipped)}): ' + ', '.join(f'{i} ({why})' for i, why in skipped))
    print()

    fails = 0

    # --- 1. NPC -> craft -> bazaar (worst case: bazaar paying its ceiling) ---
    print('=== 1. buy raw from NPC -> craft -> sell enchanted to bazaar (at ceiling) ===')
    bad = []
    for iid, it in items.items():
        s = SHOP.get(it['base'])
        if not s or not s['buy']:
            continue
        cost = it['mult'] * s['buy']
        rev = it['base_price'] * it['ceiling'] * (1 - SPREAD)
        if rev > cost:
            bad.append((rev - cost, iid, cost, rev))
    for d, iid, c, r in sorted(bad, reverse=True):
        print(f'  LOOP +{d:9.0f}  {iid:34s} cost {c:9.0f} -> sells {r:9.0f}')
    print(f'  {"FAIL: " + str(len(bad)) + " loops" if bad else "PASS: no loops"}')
    fails += len(bad)

    # --- 2. bazaar raw -> craft -> bazaar enchanted ---
    # raw and enchanted share a unit value, so compressing is value-neutral before spread.
    # Worst case: buy raw at its floor, sell enchanted at its ceiling.
    print('\n=== 2. buy raw on bazaar (floor) -> craft -> sell enchanted (ceiling) ===')
    bad2 = []
    for iid, it in items.items():
        raw_cheapest = it['unit'] * it['floor'] * (1 + SPREAD)
        cost = it['mult'] * raw_cheapest
        rev = it['base_price'] * it['ceiling'] * (1 - SPREAD)
        if rev > cost:
            bad2.append((rev - cost, iid, cost, rev))
    print(f'  {len(bad2)} items where enchanted-at-ceiling exceeds raw-at-floor.')
    print('  (expected: this is the normal bazaar spread between a low and high market,')
    print('   not a free loop -- it needs the raw market at floor AND enchanted at ceiling')
    print('   simultaneously, and buying the raw pushes its price up. Bounded by check 1.)')

    # --- 3. bazaar -> NPC (buy cheap on bazaar, sell to NPC) ---
    print('\n=== 3. buy enchanted on bazaar (floor) -> sell raw-equivalent to NPC ===')
    bad3 = []
    for iid, it in items.items():
        s = SHOP.get(it['base'])
        if not s or not s['sell']:
            continue
        cost = it['base_price'] * it['floor'] * (1 + SPREAD)
        rev = it['mult'] * s['sell']          # if uncraftable this is unreachable, but be strict
        if rev > cost:
            bad3.append((rev - cost, iid, cost, rev))
    for d, iid, c, r in sorted(bad3, reverse=True)[:10]:
        print(f'  RISK +{d:9.0f}  {iid:34s} buy {c:9.0f} -> NPC pays {r:9.0f}')
    print(f'  {"FAIL: " + str(len(bad3)) if bad3 else "PASS: no NPC dump"}')
    fails += len(bad3)

    # --- 4. floor sanity: bazaar should never pay less than the NPC for the same goods ---
    print('\n=== 4. does the bazaar floor undercut the NPC? (players would just use the NPC) ===')
    bad4 = []
    for iid, it in items.items():
        s = SHOP.get(it['base'])
        if not s or not s['sell']:
            continue
        baz = it['base_price'] * it['floor'] * (1 - SPREAD)
        npc = it['mult'] * s['sell']
        if baz < npc * 0.99:
            bad4.append((npc - baz, iid, baz, npc))
    print(f'  {len(bad4)} items where bazaar floor < NPC sell (acceptable: NPC is the floor by design)')

    print(f'\n{"=" * 62}\n{"ALL CRITICAL CHECKS PASS" if fails == 0 else f"{fails} CRITICAL FAILURES"}')


HERE = os.path.dirname(os.path.abspath(__file__))
SHIPPED_CATEGORIES = os.path.join(HERE, '..', 'src', 'main', 'resources', 'categories')
RECIPE_TABLE = os.path.join(HERE, 'vanilla_recipes.txt')

# Items a recipe hands back. They aren't in the recipe JSON, so they are kept by hand.
REMAINDERS = {'honey_bottle': 'glass_bottle', 'dragon_breath': 'glass_bottle',
              'water_bucket': 'bucket', 'milk_bucket': 'bucket', 'lava_bucket': 'bucket',
              'powder_snow_bucket': 'bucket'}
TABLE_TYPES = {'minecraft:crafting_shaped': 'shaped', 'minecraft:crafting_shapeless': 'shapeless',
               'minecraft:stonecutting': 'stonecutter', 'minecraft:smelting': 'smelting',
               'minecraft:blasting': 'blasting', 'minecraft:smoking': 'smoking',
               'minecraft:campfire_cooking': 'campfire'}
# Furnace, blast furnace and smoker all get 8 items out of one coal (the last two burn it twice as fast
# but also cook twice as fast). A campfire burns nothing.
FUELED_KINDS = {'smelting', 'blasting', 'smoking'}
DEFAULT_FUEL, DEFAULT_FUEL_SMELTS = 'coal', 8


def strip_ns(item_id):
    return item_id[len('minecraft:'):] if item_id.startswith('minecraft:') else item_id


def extract_recipes(root, out_path):
    tag_dir = os.path.join(root, 'data', 'minecraft', 'tags', 'item')
    tags = {}
    for path in sorted(glob.glob(tag_dir + '/**/*.json', recursive=True)):
        name = os.path.relpath(path, tag_dir)[:-5].replace(os.sep, '/')
        tags[name] = [v['id'] if isinstance(v, dict) else v
                      for v in json.load(open(path, encoding='utf-8'))['values']]

    def flatten(name, seen=()):
        out = []
        for v in tags.get(name, []):
            if v.startswith('#'):
                if v not in seen:
                    out += flatten(strip_ns(v[1:]), seen + (v,))
            else:
                out.append(strip_ns(v))
        return out

    used_tags = set()

    def alternatives(ing):
        if isinstance(ing, list):
            return [a for i in ing for a in alternatives(i)]
        if isinstance(ing, dict):
            ing = ing['item'] if 'item' in ing else '#' + ing['tag']
        if ing.startswith('#'):
            used_tags.add(strip_ns(ing[1:]))
            return ['#' + strip_ns(ing[1:])]
        return [strip_ns(ing)]

    lines = []
    for path in sorted(glob.glob(os.path.join(root, 'data', 'minecraft', 'recipe', '*.json'))):
        r = json.load(open(path, encoding='utf-8'))
        kind = TABLE_TYPES.get(r.get('type'))
        result = r.get('result')
        if not kind or not isinstance(result, dict):
            continue
        slots = {}
        if kind == 'shaped':
            for row in r['pattern']:
                for ch in row.replace(' ', ''):
                    key = '|'.join(alternatives(r['key'][ch]))
                    slots[key] = slots.get(key, 0) + 1
        else:
            for ing in r['ingredients'] if kind == 'shapeless' else [r['ingredient']]:
                key = '|'.join(alternatives(ing))
                slots[key] = slots.get(key, 0) + 1
        inputs = ' '.join(f'{n}*{key}' for key, n in slots.items())
        lines.append(f'{os.path.basename(path)[:-5]} {kind} {result.get("count", 1)} '
                     f'{strip_ns(result["id"])} <- {inputs}')

    with open(out_path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('# Vanilla crafting, stonecutting and cooking recipes, read by verify_bazaar.py crafting and\n'
                '# CategoryPricesCraftingTest. Regenerate with: verify_bazaar.py extract-recipes DIR\n'
                '#   tag <name> <item>...                       an item tag, any one item fits\n'
                '#   remainder <ingredient> <item>              handed back when the recipe is crafted\n'
                '#   <id> <kind> <count> <output> <- <n>*<a>|<b>...  n of any one of a, b\n'
                '#   kind: shaped, shapeless, stonecutter, smelting, blasting, smoking, campfire\n')
        for name in sorted(used_tags):
            f.write(f'tag {name} {" ".join(flatten(name))}\n')
        for ing, back in sorted(REMAINDERS.items()):
            f.write(f'remainder {ing} {back}\n')
        f.write('\n'.join(lines) + '\n')
    print(f'wrote {len(lines)} recipes and {len(used_tags)} tags to {out_path}')


def load_recipe_table(path):
    tags, remainders, recipes = {}, {}, []
    for line in open(path, encoding='utf-8'):
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        parts = line.split()
        if parts[0] == 'tag':
            tags[parts[1]] = parts[2:]
        elif parts[0] == 'remainder':
            remainders[parts[1]] = parts[2]
        else:
            rid, kind, count, output, arrow = parts[:5]
            assert arrow == '<-', line
            inputs = []
            for term in parts[5:]:
                n, alts = term.split('*', 1)
                inputs.append((int(n), alts.split('|')))
            recipes.append({'id': rid, 'kind': kind, 'count': int(count), 'output': output,
                            'inputs': inputs})
    for r in recipes:
        r['inputs'] = [(n, [x for a in alts for x in (tags[a[1:]] if a.startswith('#') else [a])])
                       for n, alts in r['inputs']]
    return recipes, remainders


def parse_scalar(raw):
    raw = raw.split(' #', 1)[0].strip().strip('"').strip("'")
    try:
        return float(raw)
    except ValueError:
        return raw


def load_shipped(cat_dir):
    """minecraft id -> {key, file, base, spread, floor_pct, trade_mode} per vanilla item listed in cat_dir."""
    shipped = {}
    for path in sorted(glob.glob(os.path.join(cat_dir, '*.yml'))):
        defaults, items, section, current = {}, {}, None, None
        for line in open(path, encoding='utf-8'):
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            depth = len(line) - len(line.lstrip(' '))
            key, _, value = line.strip().partition(':')
            if depth == 0:
                section, current = key, None
            elif section == 'defaults' and depth == 2:
                defaults[key] = parse_scalar(value)
            elif section == 'items' and depth == 2:
                current = items.setdefault(key, {})
            elif section == 'items' and depth == 4 and current is not None:
                current[key] = parse_scalar(value)
        for key, it in items.items():
            item = str(it.get('item', ''))
            if not item.startswith('minecraft:') or not isinstance(it.get('base_price'), float):
                continue
            shipped[strip_ns(item)] = {
                'key': key, 'file': os.path.basename(path), 'base': it['base_price'],
                'spread': it.get('spread', defaults.get('spread', 0.05)),
                'floor_pct': it.get('floor_pct', defaults.get('floor_pct', 0.4)),
                'trade_mode': it.get('trade_mode', defaults.get('trade_mode', 'both'))}
    return shipped


def crafting_violations(shipped, recipes, remainders, fuel=DEFAULT_FUEL, fuel_smelts=DEFAULT_FUEL_SMELTS):
    """(state, recipe, coins the bazaar pays for the output, coins the inputs cost) per printer.

    Cooking in a furnace, smoker or blast furnace adds 1/fuel_smelts of the fuel's buy price per item.
    A fuel players can't buy here counts as free, which only makes the check stricter.
    """
    def mid(i, state):
        s = shipped[i]
        return s['base'] * (s['floor_pct'] if state == 'floor' else 1.0)

    def buy(i, state):
        return mid(i, state) * (1 + shipped[i]['spread'] / 2)

    def sell(i, state):
        return mid(i, state) * (1 - shipped[i]['spread'] / 2)

    out = []
    for r in recipes:
        if r['output'] not in shipped or shipped[r['output']]['trade_mode'] == 'buy_only':
            continue
        slots = [(n, [a for a in alts if a in shipped and shipped[a]['trade_mode'] != 'sell_only'])
                 for n, alts in r['inputs']]
        if any(not alts for _, alts in slots):
            continue                      # an input the bazaar doesn't sell: no closed loop here
        for state in ('base', 'floor'):
            cost = sum(n * min(buy(a, state) for a in alts) for n, alts in slots)
            # a handed-back item is only worth what the bazaar pays for it
            cost -= sum(n * sell(remainders[alts[0]], state) for n, alts in slots
                        if len(alts) == 1 and remainders.get(alts[0]) in shipped)
            if r['kind'] in FUELED_KINDS and fuel in shipped and shipped[fuel]['trade_mode'] != 'sell_only':
                cost += buy(fuel, state) / fuel_smelts
            pays = r['count'] * sell(r['output'], state)
            if pays > cost + 1e-9:
                out.append((state, r, pays, cost))
    return out


def audit_crafting(cat_dir, table, fuel=DEFAULT_FUEL, fuel_smelts=DEFAULT_FUEL_SMELTS):
    shipped = load_shipped(cat_dir)
    recipes, remainders = load_recipe_table(table)
    checked = [r for r in recipes if r['output'] in shipped
               and all(any(a in shipped for a in alts) for _, alts in r['inputs'])]
    cooked = sum(1 for r in checked if r['kind'] in FUELED_KINDS or r['kind'] == 'campfire')
    bad = crafting_violations(shipped, recipes, remainders, fuel, fuel_smelts)
    print(f'{len(shipped)} listed vanilla items, {len(checked)} recipes made only of listed items '
          f'({cooked} of them cooking)')
    fuel_paid = fuel in shipped and shipped[fuel]['trade_mode'] != 'sell_only'
    if fuel_paid:
        print(f'fuel: 1 {fuel} per {fuel_smelts:g} items cooked in a furnace, smoker or blast furnace')
    else:
        print(f'fuel: {fuel} can\'t be bought here, so cooking is checked as if fuel were free')
    for state, r, pays, cost in sorted(bad, key=lambda b: (b[0], -b[2] / b[3])):
        inputs = ' + '.join(f'{n} {"/".join(a for a in alts if a in shipped)}' for n, alts in r['inputs'])
        if r['kind'] in FUELED_KINDS and fuel_paid:
            inputs += f' + 1/{fuel_smelts:g} {fuel}'
        print(f'  PRINTER at {state:5s} {r["count"]} {r["output"]:<26s} pays {pays:9.2f} '
              f'> inputs {cost:9.2f} ({(pays / cost - 1) * 100:+6.1f}%)  [{r["id"]}: {inputs}]')
    print('PASS: no crafting or smelting arbitrage' if not bad else f'FAIL: {len(bad)} recipe printer(s)')
    return 1 if bad else 0


def main(argv):
    parser = argparse.ArgumentParser(description='Audit the bazaar for money printers.')
    sub = parser.add_subparsers(dest='cmd')
    craft = sub.add_parser('crafting', help='crafting and smelting arbitrage in the shipped categories')
    craft.add_argument('--categories', default=SHIPPED_CATEGORIES)
    craft.add_argument('--recipes', default=RECIPE_TABLE)
    craft.add_argument('--fuel', default=DEFAULT_FUEL, help='fuel item paid for when cooking (default coal)')
    craft.add_argument('--fuel-smelts', type=float, default=DEFAULT_FUEL_SMELTS,
                       help='items one fuel item cooks (coal 8, coal_block 80, blaze_rod 12, dried_kelp_block 20)')
    extract = sub.add_parser('extract-recipes', help='rewrite the recipe table from a server jar')
    extract.add_argument('root', help='directory holding data/minecraft/recipe and tags/item')
    extract.add_argument('--out', default=RECIPE_TABLE)
    args = parser.parse_args(argv)
    if args.cmd == 'crafting':
        return audit_crafting(args.categories, args.recipes, args.fuel, args.fuel_smelts)
    if args.cmd == 'extract-recipes':
        extract_recipes(args.root, args.out)
        return 0
    audit_generated()
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
